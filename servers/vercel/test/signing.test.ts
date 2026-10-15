// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R19 (ADR-037): device-key signatures, checked the way the Rust
// gateway checks them (servers/mcp/tests/e2e.rs), with a key made by Node's
// own crypto, so a second implementation signs and the gateway verifies.
import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { signingInput, verifySignature } from "../src/signing.js";
import { MemoryStore } from "../src/store.js";
import { shared } from "./shared.js";

test("the shared signature vector verifies, and nothing else does", () => {
  const v = JSON.parse(shared("signing/vectors.json"));
  const { connection, payload, sig } = v.valid;
  assert.ok(verifySignature(v.device_key, connection, payload, sig));
  assert.ok(!verifySignature(v.device_key, "k_8", payload, sig), "bound to its connection");
  assert.ok(!verifySignature(v.device_key, connection, payload.replace("c_1", "c_2"), sig), "covers the payload");
  assert.ok(!verifySignature(v.device_key, connection, payload, "AAAA"), "garbage signature");
});

const ADMIN = "admin-token-signing-0123456789abcdef01";

function phoneKey() {
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const pub = publicKey.export({ format: "der", type: "spki" }).toString("base64");
  const seal = (connection: string, message: unknown) => {
    const payload = JSON.stringify(message);
    const sig = sign("sha256", signingInput(connection, payload), { key: privateKey, dsaEncoding: "der" }).toString("base64");
    return { type: "signed", payload, sig };
  };
  return { pub, seal };
}

const hello = (key?: string) => ({
  type: "hello", protocol: "1.12",
  device: { platform: "android", os_version: "15", model: "Test", app_version: "0.1.0" },
  capabilities: [], session: { expires_at_ms: Date.now() + 60_000, approve_every_action: false, paused: false },
  ...(key !== undefined ? { device_key: key } : {}),
});

test("phones sign their messages, and a stolen token is not enough", async () => {
  const gw = new Gateway(new MemoryStore(), { ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }) });
  const send = async (path: string, token: string, body: unknown, auth = "Bearer") => {
    const res = await gw.fetch(new Request(`http://gw${path}`, { method: "POST", headers: { authorization: `${auth} ${token}`, "content-type": "application/json" }, body: JSON.stringify(body) }));
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  };
  const { code } = (await send("/v1/admin/pairings", ADMIN, { name: "Signing phone" })).body;
  const { token } = (await send("/v1/pair", "", { code, platform: "android", model: "Test" })).body;

  // The phone's first signed hello registers its key.
  const phone = phoneKey();
  const first = await send("/v1/device/hello", token, phone.seal("", hello(phone.pub)));
  assert.equal(first.status, 200, JSON.stringify(first.body));

  // A stolen token alone, a thief's own key, or a key without proof: refused.
  const plain = await send("/v1/device/hello", token, hello());
  assert.equal(plain.status, 401);
  assert.match(plain.body.error, /signs its messages/);
  const thief = phoneKey();
  const stolen = await send("/v1/device/hello", token, thief.seal("", hello(thief.pub)));
  assert.equal(stolen.status, 401);
  assert.match(stolen.body.error, /key changed/);
  assert.equal((await send("/v1/device/hello", token, hello(thief.pub))).status, 401);

  // The real phone again; its messages are bound to the new connection.
  const again = await send("/v1/device/hello", token, phone.seal("", hello(phone.pub)));
  assert.equal(again.status, 200);
  const path = `/v1/device/messages?connection=${encodeURIComponent(again.body.connection)}`;
  const state = { type: "state", capabilities: [], session: { expires_at_ms: Date.now() + 60_000, approve_every_action: false, paused: false } };
  assert.equal((await send(path, token, state)).status, 401, "unsigned");
  assert.equal((await send(path, token, phone.seal(first.body.connection, state))).status, 401, "replayed into another connection");
  assert.equal((await send(path, token, phone.seal(again.body.connection, state))).status, 204);
});

test("a phone from before signatures still connects unsigned", async () => {
  const gw = new Gateway(new MemoryStore(), { ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }) });
  const post = (path: string, token: string, body: unknown) =>
    gw.fetch(new Request(`http://gw${path}`, { method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body) }));
  const { code } = await (await post("/v1/admin/pairings", ADMIN, { name: "Old phone" })).json();
  const { token } = await (await post("/v1/pair", "", { code, platform: "android", model: "Old" })).json();
  const res = await post("/v1/device/hello", token, { ...hello(), protocol: "1.11" });
  assert.equal(res.status, 200);
});
