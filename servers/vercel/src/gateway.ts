// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// HTTP routes of the Vercel gateway. Same paths, status codes, and JSON shapes
// as the Rust gateway (servers/mcp/src/http.rs and device_http.rs), so the
// phone app works against either. The page at "/" is static (public/).

import { type DeviceRecord, Devices, type Timing } from "./devices.js";
import { MAX_TRANSFER_BYTES, Transfers, sizeText } from "./files.js";
import { GATEWAY_LINK_BYTES, blobLinksFromEnv, type BlobLinks, Links } from "./links.js";
import { type Elicit, type ElicitResult, askOwnerVia, handle as handleMcp, SUPPORTED_VERSIONS } from "./mcp.js";
import { type ApprovalRequest, type Hello, PROTOCOL_VERSION, ProtocolError, LIMITS, type Outcome, isValidMime, parseApprovalRequest, validateHello } from "./protocol.js";
import { OAuth } from "./oauth.js";
import { bearer, newId, newToken, normalizeCode, pairingCode, secretsEqual, sha256 } from "./secret.js";
import type { Store } from "./store.js";

export interface GatewayConfig {
  /** Owner secret (LATCH_ADMIN_TOKEN). Undefined or too short puts the gateway in setup mode. */
  adminToken?: string;
  /** Optional static MCP token (LATCH_MCP_TOKEN). */
  mcpToken?: string;
  publicUrl?: string;
  allowedOrigins: string[];
  timing: Timing;
  settleMs: number;
  maxPollWaitMs: number;
  /** Vercel Blob for encrypted file links of any size (protocol 1.7); without it links use Redis, up to 4 MB. */
  blob?: BlobLinks;
}

export function configFromEnv(env: NodeJS.ProcessEnv): GatewayConfig {
  const clean = (v?: string) => (v && v.trim() !== "" ? v.trim() : undefined);
  return {
    adminToken: clean(env.LATCH_ADMIN_TOKEN),
    mcpToken: clean(env.LATCH_MCP_TOKEN),
    publicUrl: clean(env.LATCH_PUBLIC_URL)?.replace(/\/+$/, ""),
    allowedOrigins: (env.LATCH_ALLOWED_ORIGINS ?? "").split(",").map((o) => o.trim().replace(/\/+$/, "")).filter(Boolean),
    timing: {
      pollIntervalMs: Number(env.LATCH_POLL_INTERVAL_MS ?? 500),
      hotPollIntervalMs: Number(env.LATCH_HOT_POLL_INTERVAL_MS ?? 100),
      resultIntervalMs: Number(env.LATCH_RESULT_INTERVAL_MS ?? 100),
    },
    settleMs: Number(env.LATCH_SETTLE_MS ?? 500),
    maxPollWaitMs: 25_000,
    blob: blobLinksFromEnv(env),
  };
}

/** Which commit this deployment runs (Vercel sets these), so an owner can tell whether it is up to date. */
const BUILD = process.env.VERCEL_GIT_COMMIT_SHA
  ? { commit: process.env.VERCEL_GIT_COMMIT_SHA.slice(0, 7), repository: `${process.env.VERCEL_GIT_REPO_OWNER ?? "?"}/${process.env.VERCEL_GIT_REPO_SLUG ?? "?"}` }
  : {};

const PAIR_TTL_MS = 10 * 60 * 1000;
/** `last_used_ms` of an MCP client is a hint for the owner; writing it on every call costs a round trip. */
const LAST_USED_WRITE_MS = 60_000;
const MAX_PAIR_FAILURES_PER_MINUTE = 20;
/** How long an approval question stays open in the AI app (the phone's own deadline). */
const ELICIT_MS = 120_000;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * On every answer, pages and APIs alike (vercel.json repeats them for public/):
 * a gateway is private, so search engines must not index it, browsers must not
 * frame it, and HTTPS sticks. Same set as the Rust gateway's `private_headers`.
 */
export const PRIVATE_HEADERS: Readonly<Record<string, string>> = {
  "x-robots-tag": "noindex, nofollow, noarchive, nosnippet, noimageindex",
  "x-frame-options": "DENY",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "strict-transport-security": "max-age=63072000; includeSubDomains",
  "permissions-policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
};

function withPrivateHeaders(response: Response): Response {
  for (const [k, v] of Object.entries(PRIVATE_HEADERS)) response.headers.set(k, v);
  return response;
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...headers } });
const error = (status: number, message: string) => json(status, { error: message });
const noContent = () => new Response(null, { status: 204 });
const unauthorized = () => json(401, { error: "missing or wrong bearer token" }, { "www-authenticate": "Bearer" });

function bounded(value: unknown, max: number): value is string {
  // eslint-disable-next-line no-control-regex
  return typeof value === "string" && value.trim() !== "" && [...value].length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
}

async function readJson(request: Request, limit: number): Promise<unknown> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > limit) throw new ProtocolError("invalid_request", "body too large");
  const text = await request.text();
  if (text.length > limit) throw new ProtocolError("invalid_request", "body too large");
  return JSON.parse(text);
}

export class Gateway {
  readonly devices: Devices;
  /** File links (protocol 1.6). */
  readonly transfers: Transfers;
  readonly links: Links;
  readonly oauth: OAuth;
  /** When this instance last wrote `last_used_ms` per MCP client. */
  private readonly lastUsedWrites = new Map<string, number>();

  constructor(private readonly store: Store, private readonly config: GatewayConfig) {
    this.devices = new Devices(store, config.timing);
    this.transfers = new Transfers(store);
    this.links = new Links(store, config.adminToken ?? "", config.blob);
    const setup = () => this.setupMode;
    this.oauth = new OAuth(store, {
      get adminToken() {
        return setup() ? undefined : config.adminToken;
      },
      addApp: async (name, oauthClientId) => {
        const record = { id: newId("m"), name, kind: "oauth", oauth_client_id: oauthClientId, created_at_ms: Date.now() };
        await store.hset("latch:clients", record.id, JSON.stringify(record));
        return record.id;
      },
      appExists: async (id) => (await store.hget("latch:clients", id)) !== null,
    });
  }

  private get setupMode() {
    return this.config.adminToken === undefined || this.config.adminToken.length < 32;
  }

  private isAdmin(request: Request): boolean {
    const token = bearer(request.headers.get("authorization"));
    return !this.setupMode && token !== undefined && secretsEqual(token, this.config.adminToken!);
  }

  private baseUrl(request: Request): string {
    if (this.config.publicUrl) return this.config.publicUrl;
    const url = new URL(request.url);
    const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? url.host;
    const proto = request.headers.get("x-forwarded-proto") ?? url.protocol.replace(":", "");
    return `${proto}://${host}`;
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    return withPrivateHeaders(await this.routeSafely(request, path));
  }

  private async routeSafely(request: Request, path: string): Promise<Response> {
    try {
      return await this.route(request, path);
    } catch (e) {
      if (e instanceof ProtocolError) return error(400, e.message);
      if (e instanceof SyntaxError) return error(400, "malformed JSON");
      // Never echo internals: they could contain screen content.
      console.error("latch gateway error", e instanceof Error ? e.name : "unknown");
      return error(500, "internal gateway error");
    }
  }

  private async route(request: Request, path: string): Promise<Response> {
    const m = request.method;
    if (path === "/healthz" && m === "GET") return this.health();
    if (path.startsWith("/.well-known/") || path.startsWith("/oauth/")) {
      const oauth = await this.oauthRoute(request, path, m);
      if (oauth) return oauth;
    }
    if (path === "/v1/info" && m === "GET") return this.info();
    if (path === "/mcp" || path.startsWith("/mcp/")) {
      if (m !== "POST") return json(405, { error: "this server does not offer a server-to-client stream; use POST" }, { allow: "POST" });
      // `/mcp/<client token>` is the secret-link form for clients that accept only a URL.
      const linkToken = path.startsWith("/mcp/") ? decodeURIComponent(path.slice("/mcp/".length)) : undefined;
      return this.mcp(request, linkToken ?? bearer(request.headers.get("authorization")));
    }
    if (path === "/v1/pair" && m === "POST") return this.pair(request);
    // File links (protocol 1.6): the 256-bit token in the path is the credential.
    if (path.startsWith("/v1/files/") && m === "GET") return this.fileDownload(path.slice("/v1/files/".length));
    if (path.startsWith("/v1/uploads/") && (m === "PUT" || m === "POST")) return this.fileUpload(request, path.slice("/v1/uploads/".length));
    // Encrypted links (protocol 1.7) when no Blob store is connected; the token is the credential.
    if (path.startsWith("/v1/blobs/") && m === "GET") return this.linkDownload(path.slice("/v1/blobs/".length));
    if (path.startsWith("/v1/blobs/") && m === "PUT") return this.linkUpload(request, path.slice("/v1/blobs/".length));
    if (path === "/v1/device/hello" && m === "POST") return this.hello(request);
    if (path === "/v1/device/poll" && m === "GET") return this.poll(request);
    if (path === "/v1/device/messages" && m === "POST") return this.messages(request);
    if (path === "/v1/device") return error(426, "this gateway offers the HTTP poll transport only; see /v1/info");
    if (path.startsWith("/v1/admin/")) {
      if (this.setupMode) return error(503, "set LATCH_ADMIN_TOKEN (at least 32 characters) in the Vercel project settings, then redeploy");
      if (!this.isAdmin(request)) return unauthorized();
      return this.admin(request, path.slice("/v1/admin/".length), m);
    }
    return error(404, "not found");
  }

  // ---- File links (protocol 1.6) ----

  private async fileDownload(token: string): Promise<Response> {
    const file = await this.transfers.download(decodeURIComponent(token));
    if (!file) return error(404, "this link has expired or never existed");
    // The name came from the phone: keep only safe characters in the header.
    const safe = [...file.name].map((c) => (/[A-Za-z0-9 ._()-]/.test(c) ? c : "_")).join("");
    return new Response(new Uint8Array(file.bytes), {
      status: 200,
      headers: {
        "content-type": isValidMime(file.mime) ? file.mime : "application/octet-stream",
        "content-disposition": `attachment; filename="${safe}"`,
        "cache-control": "no-store",
        // A phone file is never a page of this gateway.
        "x-content-type-options": "nosniff",
        "content-security-policy": "sandbox; default-src 'none'",
      },
    });
  }

  private async fileUpload(request: Request, token: string): Promise<Response> {
    const declared = Number(request.headers.get("content-length") ?? "0");
    if (declared > MAX_TRANSFER_BYTES) return error(413, "the file is larger than 4 MB");
    const bytes = Buffer.from(await request.arrayBuffer());
    const result = await this.transfers.putUpload(decodeURIComponent(token), bytes);
    if (result === "too_large") return error(413, "the file is larger than 4 MB");
    if (result === "used") return error(409, "this upload link was already used; ask for a new one");
    if (result === "unknown") return error(404, "this link has expired or never existed");
    return json(200, { ok: true, upload_id: token, size: bytes.length });
  }

  private async linkDownload(token: string): Promise<Response> {
    const bytes = await this.links.getBytes(decodeURIComponent(token));
    if (!bytes) return error(404, "this link has expired, never existed, or nothing was uploaded to it yet");
    // Ciphertext only; never a page of this gateway.
    return new Response(new Uint8Array(bytes), {
      status: 200,
      headers: {
        "content-type": "application/octet-stream",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
        "content-security-policy": "sandbox; default-src 'none'",
      },
    });
  }

  private async linkUpload(request: Request, token: string): Promise<Response> {
    const declared = Number(request.headers.get("content-length") ?? "0");
    if (declared > GATEWAY_LINK_BYTES) return error(413, `the file is larger than ${sizeText(GATEWAY_LINK_BYTES)}; connect a Vercel Blob store for bigger files`);
    const result = await this.links.putBytes(decodeURIComponent(token), Buffer.from(await request.arrayBuffer()));
    if (result === "too_large") return error(413, `the file is larger than ${sizeText(GATEWAY_LINK_BYTES)}; connect a Vercel Blob store for bigger files`);
    if (result === "used") return error(409, "this link was already used; ask for a new one");
    if (result === "unknown") return error(404, "this link has expired or never existed");
    return json(200, { ok: true });
  }

  // ---- Public ----

  /** Public, so it tells nothing about the owner: not even whether a phone is online. */
  private health() {
    return json(200, {
      status: this.setupMode ? "setup_required" : "ok",
      version: "0.1.0",
      protocol: PROTOCOL_VERSION,
      ...BUILD,
    });
  }

  private info() {
    return json(200, {
      product: "latch-gateway",
      implementation: "vercel",
      version: "0.1.0",
      protocol: PROTOCOL_VERSION,
      ...BUILD,
      transports: ["poll"],
      mcp_path: "/mcp",
      mcp_auth: ["oauth", "bearer", "secret_link"],
      setup_required: this.setupMode,
    });
  }

  // ---- OAuth for MCP clients ----

  private async oauthRoute(request: Request, path: string, m: string): Promise<Response | undefined> {
    const base = this.baseUrl(request);
    if (m === "OPTIONS") return this.oauth.preflight();
    if (m === "GET" && (path === "/.well-known/oauth-protected-resource" || path === "/.well-known/oauth-protected-resource/mcp")) {
      return this.oauth.protectedResource(base);
    }
    if (m === "GET" && (path === "/.well-known/oauth-authorization-server" || path === "/.well-known/openid-configuration")) {
      return this.oauth.authorizationServer(base);
    }
    if (path === "/oauth/register" && m === "POST") return this.oauth.register(request);
    if (path === "/oauth/authorize" && m === "GET") return this.oauth.authorize(request, base);
    if (path === "/oauth/token" && m === "POST") return this.oauth.token(request);
    if (path === "/oauth/revoke" && m === "POST") return this.oauth.revoke(request);
    if (path.startsWith("/oauth/requests/")) {
      const id = decodeURIComponent(path.slice("/oauth/requests/".length));
      if (m === "GET") return this.oauth.requestStatus(id);
      if (m === "POST") return this.oauth.decideInBrowser(request, id);
    }
    return undefined;
  }

  /** 401 for MCP that tells clients where to sign in (MCP authorization, RFC 9728). */
  private mcpUnauthorized(request: Request, hadToken: boolean) {
    const metadata = `${this.baseUrl(request)}/.well-known/oauth-protected-resource`;
    const challenge = `Bearer resource_metadata="${metadata}", scope="phone"${hadToken ? ', error="invalid_token"' : ""}`;
    return json(401, {
      error: "unauthorized",
      error_description: "Sign in: add this server by its URL in your AI app and approve it in the Latch app, or use a key from the Latch app.",
    }, { "www-authenticate": challenge, "access-control-expose-headers": "www-authenticate" });
  }

  // ---- MCP ----

  /** Checks an MCP credential on every request (revocation is immediate). Two round trips. */
  private async isMcpClient(token: string | undefined): Promise<boolean> {
    if (token === undefined || token === "") return false;
    if (this.config.mcpToken && secretsEqual(token, this.config.mcpToken)) return true;
    const id = token.startsWith("lat_") ? await this.oauth.accessTokenApp(token) : await this.store.get(`latch:clienttoken:${sha256(token)}`);
    if (!id) return false;
    const raw = await this.store.hget("latch:clients", id);
    if (!raw) return false;
    const now = Date.now();
    if (now - (this.lastUsedWrites.get(id) ?? 0) > LAST_USED_WRITE_MS) {
      this.lastUsedWrites.set(id, now);
      await this.store.hset("latch:clients", id, JSON.stringify({ ...JSON.parse(raw), last_used_ms: now }));
    }
    return true;
  }

  private async mcp(request: Request, token: string | undefined): Promise<Response> {
    const origin = request.headers.get("origin");
    if (origin !== null && !this.config.allowedOrigins.includes(origin.replace(/\/+$/, ""))) {
      return error(403, "origin not allowed; see LATCH_ALLOWED_ORIGINS");
    }
    if (this.setupMode) return error(503, "this gateway is not set up yet");
    if (!(await this.isMcpClient(token))) return this.mcpUnauthorized(request, token !== undefined);
    let message: unknown;
    try {
      message = await readJson(request, 256 * 1024);
    } catch {
      return json(400, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
    }
    // The version is negotiated by initialize; only later requests must carry a supported one.
    const version = request.headers.get("mcp-protocol-version");
    const isInitialize = typeof message === "object" && message !== null && (message as { method?: unknown }).method === "initialize";
    if (version !== null && !isInitialize && !SUPPORTED_VERSIONS.includes(version)) return error(400, "unsupported MCP-Protocol-Version");
    const client = sha256(token ?? "");
    const m = (typeof message === "object" && message !== null ? message : {}) as Record<string, unknown>;
    // A client's answer to one of our elicitation requests (ADR-023).
    if (!("method" in m) && "id" in m && ("result" in m || "error" in m)) {
      await this.elicitAnswer(client, m);
      return new Response(null, { status: 202 });
    }
    if (isInitialize) await this.rememberClient(client, m.params);
    // Clients that can show questions get the tool call as an event stream, so an
    // approval the phone waits on can be asked in the AI app as well.
    if (m.method === "tools/call" && (request.headers.get("accept") ?? "").includes("text/event-stream") && (await this.canElicit(client))) {
      return this.streamToolCall(client, message, this.baseUrl(request));
    }
    // Without elicitation, a question the phone asks pauses the call so the AI can ask in the chat.
    const reply = await handleMcp(
      { devices: this.devices, settleMs: this.config.settleMs, deferWhenAsked: true, transfers: this.transfers, links: this.links, baseUrl: this.baseUrl(request), skills: this.store },
      message,
    );
    return reply === undefined ? new Response(null, { status: 202 }) : json(200, reply);
  }

  // ---- Approvals in the AI app (MCP elicitation, ADR-023) ----

  private readonly elicitCapable = new Map<string, boolean>();

  /** Remembers whether this MCP key's client can show questions (its `initialize` capabilities). */
  private async rememberClient(client: string, params: unknown) {
    const caps = (params as { capabilities?: { elicitation?: unknown } } | null)?.capabilities;
    const capable = typeof caps?.elicitation === "object" && caps.elicitation !== null;
    this.elicitCapable.set(client, capable);
    await this.store.set(`latch:mcpclient:${client}`, capable ? "elicitation" : "none", { px: 30 * 24 * 3600_000 });
  }

  private async canElicit(client: string): Promise<boolean> {
    const known = this.elicitCapable.get(client);
    if (known !== undefined) return known;
    const capable = (await this.store.get(`latch:mcpclient:${client}`)) === "elicitation";
    this.elicitCapable.set(client, capable);
    return capable;
  }

  /** Stores the answer for the waiting tool call, only from the client that was asked. */
  private async elicitAnswer(client: string, m: Record<string, unknown>) {
    if (typeof m.id !== "string" || !/^e_[A-Za-z0-9_-]{1,64}$/.test(m.id)) return;
    if ((await this.store.getdel(`latch:elicitwait:${m.id}`)) !== client) return;
    const result = typeof m.result === "object" && m.result !== null ? m.result : { action: "cancel" };
    await this.store.set(`latch:elicit:${m.id}`, JSON.stringify(result).slice(0, 4096), { px: 60_000 });
  }

  private streamToolCall(client: string, message: unknown, baseUrl: string): Response {
    const encoder = new TextEncoder();
    const store = this.store;
    const ctx = { devices: this.devices, settleMs: this.config.settleMs, transfers: this.transfers, links: this.links, baseUrl, skills: this.store };
    const stream = new ReadableStream<Uint8Array>({
      start: async (controller) => {
        let open = true;
        const write = (chunk: string) => { if (open) controller.enqueue(encoder.encode(chunk)); };
        const send = (obj: unknown) => write(`event: message\ndata: ${JSON.stringify(obj)}\n\n`);
        // Keeps proxies from closing a stream that waits on a person.
        const keepalive = setInterval(() => write(": keepalive\n\n"), 15_000);
        const asked: string[] = [];
        const elicit: Elicit = async (text, requestedSchema): Promise<ElicitResult> => {
          const rid = newId("e");
          await store.set(`latch:elicitwait:${rid}`, client, { px: ELICIT_MS + 10_000 });
          asked.push(rid);
          send({ jsonrpc: "2.0", id: rid, method: "elicitation/create", params: { message: text, requestedSchema } });
          const until = Date.now() + ELICIT_MS;
          while (open && Date.now() < until) {
            const answer = await store.getdel(`latch:elicit:${rid}`);
            if (answer) return JSON.parse(answer) as ElicitResult;
            await sleep(400);
          }
          return undefined;
        };
        try {
          const reply = await handleMcp({ ...ctx, askOwner: askOwnerVia(elicit) }, message);
          // A question still open in the AI app was answered on the phone instead.
          for (const rid of asked) {
            if (await store.getdel(`latch:elicitwait:${rid}`)) {
              send({ jsonrpc: "2.0", method: "notifications/cancelled", params: { requestId: rid, reason: "answered on the phone" } });
            }
          }
          if (reply !== undefined) send(reply);
        } catch {
          send({ jsonrpc: "2.0", id: (message as { id?: unknown }).id ?? null, error: { code: -32603, message: "internal error" } });
        } finally {
          clearInterval(keepalive);
          open = false;
          controller.close();
        }
      },
    });
    return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
  }

  // ---- Phones ----

  private async pair(request: Request): Promise<Response> {
    if (this.setupMode) return error(503, "this gateway is not set up yet");
    const body = (await readJson(request, 4096)) as Record<string, unknown>;
    const allowed = ["code", "platform", "model"];
    if (typeof body !== "object" || body === null || Object.keys(body).some((k) => !allowed.includes(k))) return error(400, "unexpected fields");
    if (!bounded(body.code, 16) || !bounded(body.platform, 16) || !bounded(body.model, 64)) {
      return error(400, "code, platform, and model are required");
    }
    const minute = `latch:pairfail:${Math.floor(Date.now() / 60_000)}`;
    if (Number((await this.store.get(minute)) ?? "0") >= MAX_PAIR_FAILURES_PER_MINUTE) {
      return error(429, "too many wrong codes; wait a minute");
    }
    const name = await this.store.getdel(`latch:pair:${normalizeCode(body.code)}`);
    if (name === null) {
      await this.store.incr(minute, 120_000);
      return error(403, "that pairing code is wrong or has expired");
    }
    const token = newToken("ldt");
    const record: DeviceRecord = {
      id: newId("d"), name, model: body.model.trim(), platform: body.platform.trim(),
      token_sha256: sha256(token), paired_at_ms: Date.now(),
    };
    // A phone that pairs again (reinstalled app, cleared data) left its old
    // entry behind: drop offline entries with the same name and model.
    const replaced = [];
    for (const d of await this.devices.records()) {
      if (d.name === record.name && d.model === record.model && d.platform === record.platform && !(await this.devices.live(d.id))) replaced.push(d.id);
    }
    await this.devices.addRecord(record);
    for (const id of replaced) await this.devices.revoke(id);
    return json(200, { device_id: record.id, name, token, protocol: PROTOCOL_VERSION, device_path: "/v1/device" });
  }

  private async device(request: Request): Promise<string | undefined> {
    const token = bearer(request.headers.get("authorization"));
    return token === undefined ? undefined : this.devices.deviceForToken(sha256(token));
  }

  private async hello(request: Request): Promise<Response> {
    const id = await this.device(request);
    if (!id) return error(401, "unknown or revoked device credential");
    const hello = (await readJson(request, 64 * 1024)) as Hello;
    if (hello?.type !== "hello") return error(400, "expected a hello message");
    try {
      validateHello(hello);
    } catch (e) {
      return error(422, (e as Error).message);
    }
    const now = Date.now();
    const connection = await this.devices.connect(id, hello, now);
    return json(200, { type: "welcome", protocol: PROTOCOL_VERSION, device_id: id, server_time_ms: now, connection });
  }

  private async poll(request: Request): Promise<Response> {
    const id = await this.device(request);
    if (!id) return error(401, "unknown or revoked device credential");
    const url = new URL(request.url);
    const conn = url.searchParams.get("connection") ?? "";
    if (!(await this.devices.current(id, conn))) return error(409, "connection replaced or expired; send hello again");
    const wait = Math.min(Number(url.searchParams.get("wait") ?? "25") * 1000 || this.config.maxPollWaitMs, this.config.maxPollWaitMs);
    // Phones since protocol 1.2 send hot=1 while an agent is actively using them.
    const message = await this.devices.nextMessage(id, conn, wait, url.searchParams.get("hot") === "1");
    // Refresh presence after a long wait so a live phone does not expire.
    const stillCurrent = await this.devices.current(id, conn);
    if (message) return new Response(message, { status: 200, headers: { "content-type": "application/json", "cache-control": "no-store" } });
    return stillCurrent ? noContent() : error(409, "connection replaced or expired; send hello again");
  }

  private async messages(request: Request): Promise<Response> {
    const id = await this.device(request);
    if (!id) return error(401, "unknown or revoked device credential");
    const conn = new URL(request.url).searchParams.get("connection") ?? "";
    const replaced = () => error(409, "connection replaced or expired; send hello again");
    let message: Record<string, unknown>;
    try {
      message = (await readJson(request, LIMITS.maxMessageBytes)) as Record<string, unknown>;
    } catch {
      // Do not echo the body: it may contain screen content.
      return error(400, "unparseable device message");
    }
    // Results are on every command's critical path: their presence check rides along in one batch.
    if (message?.type === "result") {
      if (typeof message.id !== "string" || typeof message.outcome !== "object") return error(400, "unparseable device message");
      return (await this.devices.deliverResult(id, conn, message.id, message.outcome as Outcome)) ? noContent() : replaced();
    }
    // Protocol 1.4: the phone waits for the owner on a running command.
    if (message?.type === "approval_request") {
      let request: ApprovalRequest;
      try {
        request = parseApprovalRequest(message);
      } catch (e) {
        return error(422, (e as Error).message);
      }
      return (await this.devices.recordApproval(id, conn, request)) ? noContent() : replaced();
    }
    const live = await this.devices.current(id, conn);
    if (!live) return replaced();
    const now = Date.now();
    switch (message?.type) {
      case "state":
        await this.devices.updateState(id, live, message.capabilities as Hello["capabilities"], message.session as Hello["session"], message.device_time_ms as number | undefined, now);
        break;
      case "bye":
        await this.devices.disconnect(id, conn, now);
        break;
      case "hello":
        return error(400, "send hello to /v1/device/hello");
      default:
        return error(400, "unparseable device message");
    }
    return noContent();
  }

  // ---- Owner API (the phone app, with the owner key) ----

  private async admin(request: Request, path: string, method: string): Promise<Response> {
    if (path === "devices" && method === "GET") {
      const online = await this.devices.online();
      const devices = (await this.devices.records()).map((d) => {
        const live = online.find((o) => o.device_id === d.id) ?? null;
        return {
          id: d.id, name: d.name, model: d.model, platform: d.platform, paired_at_ms: d.paired_at_ms,
          last_seen_ms: d.last_seen_ms ?? null, connected: live !== null, live,
        };
      });
      return json(200, { devices, server_time_ms: Date.now() });
    }
    if (path.startsWith("devices/") && method === "DELETE") {
      return (await this.devices.revoke(decodeURIComponent(path.slice("devices/".length)))) ? noContent() : error(404, "no such device");
    }
    if (path === "pairings" && method === "POST") {
      const body = (await readJson(request, 4096)) as Record<string, unknown>;
      if (!bounded(body?.name, 40) || Object.keys(body).length !== 1) return error(400, "name must be 1-40 characters");
      const code = pairingCode();
      const expires = Date.now() + PAIR_TTL_MS;
      await this.store.set(`latch:pair:${code}`, body.name.trim(), { px: PAIR_TTL_MS });
      return json(200, { code: `${code.slice(0, 4)}-${code.slice(4)}`, expires_at_ms: expires, gateway_url: this.baseUrl(request) });
    }
    if (path === "audit" && method === "GET") return json(200, { events: await this.devices.audit(200) });
    if (path === "clients" && method === "GET") {
      const clients = Object.values(await this.store.hgetall("latch:clients"))
        .map((v) => JSON.parse(v) as { id: string; name: string; created_at_ms: number; last_used_ms?: number; kind?: string })
        .sort((a, b) => a.created_at_ms - b.created_at_ms)
        .map((c) => ({ id: c.id, name: c.name, created_at_ms: c.created_at_ms, last_used_ms: c.last_used_ms ?? null, kind: c.kind ?? "key" }));
      return json(200, { clients });
    }
    if (path === "clients" && method === "POST") {
      const body = (await readJson(request, 4096)) as Record<string, unknown>;
      if (!bounded(body?.name, 40) || Object.keys(body).length !== 1) return error(400, "name must be 1-40 characters");
      if (Object.keys(await this.store.hgetall("latch:clients")).length >= 50) return error(429, "revoke unused clients first (limit 50)");
      const token = newToken("lmt");
      const record = { id: newId("m"), name: body.name.trim(), token_sha256: sha256(token), created_at_ms: Date.now() };
      await this.store.hset("latch:clients", record.id, JSON.stringify(record));
      await this.store.set(`latch:clienttoken:${record.token_sha256}`, record.id);
      return json(200, { id: record.id, token, mcp_url: `${this.baseUrl(request)}/mcp` });
    }
    if (path.startsWith("clients/") && method === "DELETE") {
      const id = decodeURIComponent(path.slice("clients/".length));
      const raw = (await this.store.hgetall("latch:clients"))[id];
      if (!raw) return error(404, "no such client");
      await this.store.hdel("latch:clients", id);
      const sha = (JSON.parse(raw) as { token_sha256?: string }).token_sha256;
      if (sha) await this.store.del(`latch:clienttoken:${sha}`);
      return noContent();
    }
    if (path === "oauth/requests" && method === "GET") {
      const requests = (await this.oauth.pendingRequests()).map((r) => ({
        id: r.id, client_name: r.client_name, match: r.match, created_at_ms: r.created_at_ms,
        return_to: (() => { try { const u = new URL(r.redirect_uri); return u.host || u.protocol.replace(":", ""); } catch { return ""; } })(),
      }));
      return json(200, { requests });
    }
    if (path.startsWith("oauth/requests/") && method === "POST") {
      const body = (await readJson(request, 1024)) as Record<string, unknown>;
      if (typeof body?.approve !== "boolean") return error(400, "approve must be true or false");
      const ok = await this.oauth.decideAsOwner(decodeURIComponent(path.slice("oauth/requests/".length)), body.approve);
      return ok ? noContent() : error(404, "that request expired or was already decided");
    }
    return error(404, "not found");
  }
}
