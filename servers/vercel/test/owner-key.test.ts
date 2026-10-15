// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R19 (ADR-038): owner-key rotation, checked the way the Rust gateway
// checks it (servers/mcp/tests/e2e.rs). A redeploy is a new Gateway on the same store.
import { test } from "node:test";
import assert from "node:assert/strict";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore } from "../src/store.js";

const ADMIN = "admin-token-owner-key-0123456789abcdef";
const NEW_ADMIN = "recovered-admin-token-0123456789abcdef";

function deploy(store: MemoryStore, admin: string) {
  const gw = new Gateway(store, configFromEnv({ LATCH_ADMIN_TOKEN: admin }));
  return async (method: string, path: string, token?: string) => {
    const headers: Record<string, string> = token ? { authorization: `Bearer ${token}`, "content-type": "application/json" } : {};
    // Like the app: OkHttp needs a body on POST.
    const res = await gw.fetch(new Request(`http://gw/v1/admin/${path}`, { method, headers, body: method === "POST" ? "{}" : undefined }));
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null, cache: res.headers.get("cache-control") };
  };
}

test("the owner key rotates, and a new LATCH_ADMIN_TOKEN recovers access", async () => {
  const store = new MemoryStore();
  let admin = deploy(store, ADMIN);

  assert.equal((await admin("POST", "owner-key")).status, 401);
  assert.equal((await admin("POST", "owner-key", "lmt_not-the-owner")).status, 401);

  const first = await admin("POST", "owner-key", ADMIN);
  assert.equal(first.status, 200);
  assert.equal(first.cache, "no-store");
  const firstKey: string = first.body.owner_key;
  assert.match(firstKey, /^lok_[0-9a-f]{64}$/);
  assert.equal((await admin("GET", "devices", ADMIN)).status, 401, "LATCH_ADMIN_TOKEN stops working after a rotation");
  assert.equal((await admin("GET", "devices", firstKey)).status, 200);

  // Rotating again ends the key used to ask.
  const second = await admin("POST", "owner-key", firstKey);
  assert.equal(second.status, 200);
  const secondKey: string = second.body.owner_key;
  assert.notEqual(secondKey, firstKey);
  for (const old of [firstKey, ADMIN]) assert.equal((await admin("GET", "clients", old)).status, 401);
  assert.equal((await admin("GET", "clients", secondKey)).status, 200);
  const saved = await store.get("latch:ownerkey");
  assert.ok(saved && !saved.includes(secondKey) && !saved.includes(ADMIN), "only hashes are stored");

  // A redeploy with the same LATCH_ADMIN_TOKEN keeps the rotated key.
  admin = deploy(store, ADMIN);
  assert.equal((await admin("GET", "devices", ADMIN)).status, 401);
  assert.equal((await admin("GET", "devices", secondKey)).status, 200);

  // Recovery: the owner lost the key and sets a new LATCH_ADMIN_TOKEN.
  admin = deploy(store, NEW_ADMIN);
  assert.equal((await admin("GET", "devices", secondKey)).status, 401, "the rotated key ends with the token it was issued under");
  assert.equal((await admin("GET", "devices", NEW_ADMIN)).status, 200);

  // Going back to the old token later does not bring the rotated key back.
  admin = deploy(store, ADMIN);
  assert.equal((await admin("GET", "devices", secondKey)).status, 401);
  assert.equal((await admin("GET", "devices", ADMIN)).status, 200);
});
