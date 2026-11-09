// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: a relay deployed without typing. The phone makes the owner key and
// never shows it. Vercel's deploy link pre-fills its SHA-256
// (LATCH_OWNER_KEY_SHA256); Cloudflare's button cannot pre-fill, so a
// Cloudflare relay ships with LATCH_CLAIM=first and the first phone to claim
// it within CLAIM_WINDOW_MS of its first request owns it. Either way the
// phone sends the key once (`POST /v1/claim`) and then pairs as the owner.
//
// A claim stores a fresh 256-bit relay secret, used where LATCH_ADMIN_TOKEN
// would be (sealing, link keys), and records the phone's key through the
// rotation record of ADR-038, so every owner check works unchanged.
// LATCH_ADMIN_TOKEN, when set, wins and turns claiming off.

import { configFromEnv, Gateway, type GatewayConfig, type Platform, PRIVATE_HEADERS } from "./gateway.js";
import { newToken, secretsEqual, sha256 } from "./secret.js";
import type { Store } from "./store.js";
import { countingStore, limitsFromEnv, Usage } from "./usage.js";

/** The claim record: unsealed, since it holds the key the seal is derived from. */
export const CLAIM_KEY = "latch:claim";
const FIRST_SEEN_KEY = "latch:claim:first";
const FAILS_KEY = "latch:claim:fails";
/** The rotated owner key record of ADR-038 (gateway.ts reads the same key). */
const OWNER_KEY = "latch:ownerkey";

/** How long a first-claim relay waits for its owner's phone after it first answers. */
export const CLAIM_WINDOW_MS = 24 * 60 * 60 * 1000;
const MAX_CLAIM_FAILURES_PER_MINUTE = 20;
const MAX_CLAIM_BODY = 4096;

/** The body as text, or undefined once it passes [max] bytes (never buffered further). */
async function readCapped(request: Request, max: number): Promise<string | undefined> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > max) {
      await reader.cancel();
      return undefined;
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

interface ClaimRecord {
  secret: string;
  owner_sha256: string;
  claimed_at_ms: number;
  mode: ClaimMode;
}

/** How this relay may be claimed, or null when it cannot be (it has LATCH_ADMIN_TOKEN, or nothing to check a claim against). */
export type ClaimMode = "hash" | "first";

export function claimModeFromEnv(env: Record<string, string | undefined>): ClaimMode | null {
  const hash = env.LATCH_OWNER_KEY_SHA256?.trim().toLowerCase();
  if (hash && /^[0-9a-f]{64}$/.test(hash)) return "hash";
  if (env.LATCH_CLAIM?.trim().toLowerCase() === "first") return "first";
  return null;
}

const json = (status: number, body: unknown) => {
  const response = new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
  for (const [k, v] of Object.entries(PRIVATE_HEADERS)) response.headers.set(k, v);
  return response;
};

/** A key the phone made: printable ASCII, 32 to 256 characters. */
const keyOk = (key: unknown): key is string => typeof key === "string" && key.length >= 32 && key.length <= 256 && /^[\x21-\x7e]+$/.test(key);

/**
 * The deployed relay: the gateway once it has an owner (from the environment
 * or a claim), and until then the setup gateway plus `POST /v1/claim`.
 */
export class Relay {
  private claimed?: Gateway;
  private readonly fixed?: Gateway;
  private readonly unclaimed: Gateway;
  private readonly mode: ClaimMode | null;
  private readonly config: GatewayConfig;

  /** ADR-075: this relay's use of its platform's free allowance. */
  readonly usage: Usage;
  private readonly store: Store;

  constructor(raw: Store, env: Record<string, string | undefined>, platform: Platform) {
    this.usage = new Usage(raw, platform, limitsFromEnv(platform, env.LATCH_USAGE_LIMITS));
    // Upstash bills per command; a Durable Object's SQLite per row written (the row and its expiry index).
    this.store = platform === "vercel"
      ? countingStore(raw, (n) => this.usage.count("store_commands", n))
      : countingStore(raw, (n) => this.usage.count("store_rows", n), { writesOnly: true });
    const store = this.store;
    this.config = { ...configFromEnv(env), platform, usage: this.usage };
    const hasToken = this.config.adminToken !== undefined && this.config.adminToken.length >= 32;
    this.mode = hasToken ? null : claimModeFromEnv(env);
    this.expectedHash = env.LATCH_OWNER_KEY_SHA256?.trim().toLowerCase();
    if (hasToken) this.fixed = new Gateway(store, this.config);
    this.unclaimed = this.fixed ?? new Gateway(store, { ...this.config, adminToken: undefined, claim: this.mode ?? undefined });
  }

  private readonly expectedHash?: string;

  /** The gateway for this request; reads the claim record until there is one. */
  private async current(): Promise<Gateway> {
    if (this.fixed) return this.fixed;
    if (this.claimed) return this.claimed;
    if (this.mode === null) return this.unclaimed;
    const raw = await this.store.get(CLAIM_KEY);
    if (raw === null) {
      if (this.mode === "first") await this.store.set(FIRST_SEEN_KEY, String(Date.now()), { nx: true });
      return this.unclaimed;
    }
    return this.adopt(JSON.parse(raw) as ClaimRecord);
  }

  private async adopt(record: ClaimRecord): Promise<Gateway> {
    // The owner key record of ADR-038; written once, so a later rotation stays.
    await this.store.set(OWNER_KEY, JSON.stringify({ key_sha256: record.owner_sha256, admin_token_sha256: sha256(record.secret), rotated_at_ms: record.claimed_at_ms }), { nx: true });
    this.claimed = new Gateway(this.store, { ...this.config, adminToken: record.secret });
    return this.claimed;
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    this.usage.count("requests");
    const flush = this.usage.tick();
    if (flush) await flush;
    const gateway = await this.current();
    if (path === "/v1/claim" && request.method === "POST") return this.claim(request, gateway);
    return gateway.fetch(request);
  }

  private async claim(request: Request, gateway: Gateway): Promise<Response> {
    if (gateway !== this.unclaimed || this.fixed) return json(409, { error: "this relay already has an owner" });
    if (this.mode === null) return json(503, { error: "this relay cannot be claimed: set LATCH_ADMIN_TOKEN in its settings" });
    if ((await this.store.incr(FAILS_KEY, 60_000)) > MAX_CLAIM_FAILURES_PER_MINUTE) {
      return json(429, { error: "too many claims; wait a minute" });
    }
    let key: unknown;
    try {
      // Refuse a big body before reading it, and stop reading past the cap.
      if (Number(request.headers.get("content-length") ?? "0") > MAX_CLAIM_BODY) return json(413, { error: "body too large" });
      const text = await readCapped(request, MAX_CLAIM_BODY);
      if (text === undefined) return json(413, { error: "body too large" });
      key = (JSON.parse(text) as { owner_key?: unknown }).owner_key;
    } catch {
      return json(400, { error: "malformed JSON" });
    }
    if (!keyOk(key)) return json(400, { error: "owner_key must be 32 to 256 printable characters" });
    if (this.mode === "hash") {
      if (!secretsEqual(sha256(key), this.expectedHash!)) return json(403, { error: "this relay was made for another phone's key" });
    } else {
      const first = Number((await this.store.get(FIRST_SEEN_KEY)) ?? Date.now());
      if (Date.now() - first > CLAIM_WINDOW_MS) {
        return json(403, { error: "this relay waited more than 24 hours for its owner; set LATCH_ADMIN_TOKEN in its settings, or delete it and deploy again" });
      }
    }
    const record: ClaimRecord = { secret: newToken("lrs"), owner_sha256: sha256(key), claimed_at_ms: Date.now(), mode: this.mode };
    if (!(await this.store.set(CLAIM_KEY, JSON.stringify(record), { nx: true }))) {
      return json(409, { error: "this relay already has an owner" });
    }
    await this.store.del(FAILS_KEY);
    console.info(`latch: relay claimed (${this.mode})`);
    await this.adopt(record);
    return json(200, { ok: true, claimed_at_ms: record.claimed_at_ms });
  }
}
