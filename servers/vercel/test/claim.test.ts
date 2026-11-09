// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: a relay deployed without typing is claimed by the phone that made
// its key. A redeploy (or another Vercel instance) is a new Relay on the same store.
import { test } from "node:test";
import assert from "node:assert/strict";
import { CLAIM_KEY, CLAIM_WINDOW_MS, Relay } from "../src/relay.js";
import { sha256 } from "../src/secret.js";
import { MemoryStore } from "../src/store.js";

const KEY = "lok_" + "ab".repeat(32);
const OTHER = "lok_" + "cd".repeat(32);

function relay(store: MemoryStore, env: Record<string, string>, platform: "vercel" | "cloudflare" = "vercel") {
  const r = new Relay(store, env, platform);
  return async (method: string, path: string, opts: { token?: string; body?: unknown } = {}) => {
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (opts.token) headers.authorization = `Bearer ${opts.token}`;
    const body = opts.body !== undefined ? JSON.stringify(opts.body) : method === "POST" ? "{}" : undefined;
    const res = await r.fetch(new Request(`https://relay.example${path}`, { method, headers, body }));
    const text = await res.text();
    let parsed: unknown = text;
    try { parsed = JSON.parse(text); } catch { /* a page */ }
    return { status: res.status, body: parsed as Record<string, unknown> & string, headers: res.headers };
  };
}

test("Vercel: the pre-filled hash lets only that key claim the relay, once", async () => {
  const store = new MemoryStore();
  const call = relay(store, { LATCH_OWNER_KEY_SHA256: sha256(KEY) });

  const info = await call("GET", "/v1/info");
  assert.equal(info.body.setup_required, true);
  assert.equal(info.body.claim, "hash");
  assert.equal(info.body.implementation, "vercel");
  const page = await call("GET", "/");
  assert.match(page.body, /Scan this to make it yours/);
  assert.match(page.body, /<svg/);
  assert.equal((await call("GET", "/v1/admin/devices", { token: KEY })).status, 503, "no owner yet");

  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: OTHER } })).status, 403);
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: "short" } })).status, 400);
  assert.equal((await call("POST", "/v1/claim", { body: "not json" })).status, 400);

  const claimed = await call("POST", "/v1/claim", { body: { owner_key: KEY } });
  assert.equal(claimed.status, 200);
  assert.equal(claimed.headers.get("cache-control"), "no-store");
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: KEY } })).status, 409, "a claimed relay refuses claims");

  assert.equal((await call("GET", "/v1/admin/devices", { token: KEY })).status, 200);
  assert.equal((await call("GET", "/v1/admin/devices", { token: OTHER })).status, 401);
  const after = await call("GET", "/v1/info");
  assert.equal(after.body.setup_required, false);
  assert.equal(after.body.claim, undefined);
  assert.match((await call("GET", "/")).body, /Connect your phone/);

  const stored = (await store.get(CLAIM_KEY))!;
  assert.ok(!stored.includes(KEY), "the phone's key is stored only as a hash");

  // Another instance (or a redeploy) reads the claim and keeps the owner.
  const again = relay(store, { LATCH_OWNER_KEY_SHA256: sha256(KEY) });
  assert.equal((await again("GET", "/v1/admin/devices", { token: KEY })).status, 200);
  assert.equal((await again("POST", "/v1/claim", { body: { owner_key: KEY } })).status, 409);
});

test("a claimed relay pairs its phone as the owner, and rotation still works", async () => {
  const store = new MemoryStore();
  const call = relay(store, { LATCH_CLAIM: "first" }, "cloudflare");
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: KEY } })).status, 200);

  const code = await call("POST", "/v1/admin/pairings", { token: KEY, body: { name: "Pixel" } });
  assert.equal(code.status, 200);
  const paired = await call("POST", "/v1/pair", { body: { code: code.body.code, platform: "android", model: "Pixel" } });
  assert.equal(paired.status, 200);
  assert.match(String(paired.body.token), /.{32,}/);

  const rotated = await call("POST", "/v1/admin/owner-key", { token: KEY });
  assert.equal(rotated.status, 200);
  const next = String(rotated.body.owner_key);
  assert.equal((await call("GET", "/v1/admin/devices", { token: KEY })).status, 401);
  // A restart reads the claim again and keeps the rotated key.
  const restarted = relay(store, { LATCH_CLAIM: "first" }, "cloudflare");
  assert.equal((await restarted("GET", "/v1/admin/devices", { token: next })).status, 200);
  assert.equal((await restarted("GET", "/v1/admin/devices", { token: KEY })).status, 401);
});

test("Cloudflare: the first phone claims it within the window; later ones are refused", async () => {
  const store = new MemoryStore();
  const call = relay(store, { LATCH_CLAIM: "first" }, "cloudflare");
  const info = await call("GET", "/v1/info");
  assert.equal(info.body.claim, "first");
  assert.equal(info.body.implementation, "cloudflare");
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: OTHER } })).status, 200);
  const late = await call("POST", "/v1/claim", { body: { owner_key: KEY } });
  assert.equal(late.status, 409);
  assert.match(String(late.body.error), /already has an owner/);
  assert.equal((await call("GET", "/v1/admin/devices", { token: KEY })).status, 401);
});

test("a first-claim relay stops waiting after 24 hours", async () => {
  const store = new MemoryStore();
  const call = relay(store, { LATCH_CLAIM: "first" }, "cloudflare");
  await call("GET", "/v1/info");
  await store.set("latch:claim:first", String(Date.now() - CLAIM_WINDOW_MS - 1000));
  const r = await call("POST", "/v1/claim", { body: { owner_key: KEY } });
  assert.equal(r.status, 403);
  assert.match(String(r.body.error), /24 hours/);
});

test("LATCH_ADMIN_TOKEN wins: no claiming, the token works as before", async () => {
  const store = new MemoryStore();
  const admin = "admin-token-for-claim-tests-0123456789";
  const call = relay(store, { LATCH_ADMIN_TOKEN: admin, LATCH_CLAIM: "first", LATCH_OWNER_KEY_SHA256: sha256(KEY) });
  assert.equal((await call("GET", "/v1/info")).body.claim, undefined);
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: KEY } })).status, 409);
  assert.equal((await call("GET", "/v1/admin/devices", { token: admin })).status, 200);
});

test("without a hash, a token, or LATCH_CLAIM nothing can claim the relay", async () => {
  const store = new MemoryStore();
  const call = relay(store, {});
  assert.equal((await call("GET", "/v1/info")).body.claim, undefined);
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: KEY } })).status, 503);
  assert.match((await call("GET", "/")).body, /One step left/);
});

test("guessing is rate limited", async () => {
  const store = new MemoryStore();
  const call = relay(store, { LATCH_OWNER_KEY_SHA256: sha256(KEY) });
  for (let i = 0; i < 20; i++) assert.equal((await call("POST", "/v1/claim", { body: { owner_key: OTHER } })).status, 403);
  assert.equal((await call("POST", "/v1/claim", { body: { owner_key: KEY } })).status, 429);
});
