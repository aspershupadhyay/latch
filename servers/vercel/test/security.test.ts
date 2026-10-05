// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Security regressions: unbiased pairing codes, open registration that cannot
// be filled for good, private addresses never fetched, one-time upload links.
import { test } from "node:test";
import assert from "node:assert/strict";
import { Links } from "../src/links.js";
import { OAuth, isPublicHttps } from "../src/oauth.js";
import { pairingCode } from "../src/secret.js";
import { MemoryStore } from "../src/store.js";

const host = { adminToken: "x".repeat(40), addApp: async () => "m_1", appExists: async () => true };
const register = (oauth: OAuth) =>
  oauth.register(new Request("http://gw/oauth/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ client_name: "app", redirect_uris: ["https://app.example/cb"] }),
  }));

test("pairing codes use only the unambiguous alphabet", () => {
  for (let i = 0; i < 500; i++) assert.match(pairingCode(), /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/);
});

test("a full registration list makes room instead of locking new apps out", async () => {
  const store = new MemoryStore();
  for (let i = 0; i < 200; i++) {
    await store.hset("latch:oauth:clients", `lc_old${i}`, JSON.stringify({ client_id: `lc_old${i}`, client_name: "x", redirect_uris: [], created_at_ms: i }));
  }
  const res = await register(new OAuth(store, host));
  assert.equal(res.status, 201);
  const all = await store.hgetall("latch:oauth:clients");
  assert.equal(Object.keys(all).length, 200);
  assert.equal(all.lc_old0, undefined, "the oldest registration made room");
});

test("registration is rate limited", async () => {
  const oauth = new OAuth(new MemoryStore(), host);
  const statuses = [];
  for (let i = 0; i < 31; i++) statuses.push((await register(oauth)).status);
  assert.equal(statuses.filter((s) => s === 201).length, 30);
  assert.equal(statuses.at(-1), 429);
});

test("client metadata documents are never fetched from private addresses", () => {
  assert.ok(isPublicHttps("https://client.example/meta.json"));
  for (const url of [
    "http://client.example/meta.json", "https://localhost/x", "https://localhost./x", "https://a.localhost/x",
    "https://127.0.0.1/x", "https://2130706433/x", "https://0.0.0.0/x", "https://10.1.2.3/x", "https://100.64.0.1/x",
    "https://169.254.169.254/x", "https://172.16.0.1/x", "https://192.168.1.1/x", "https://[::1]/x", "https://printer.local/x",
    "https://user:pw@client.example/x", "https://client.example:8443/x",
  ]) assert.ok(!isPublicHttps(url), url);
});

test("two uploads at once to one link: only the first is kept", async () => {
  const links = new Links(new MemoryStore(), "s".repeat(40));
  const { token } = await links.create("up", "http://gw");
  const results = await Promise.all([links.putBytes(token, Buffer.from("first")), links.putBytes(token, Buffer.from("second"))]);
  assert.deepEqual([...results].sort(), ["ok", "used"]);
});
