// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// R3: a database far from the function is reported to the owner through list_devices.
import { test } from "node:test";
import assert from "node:assert/strict";
import { Devices, SLOW_STORE_MS } from "../src/devices.js";
import { renderDevices, slowStoreNote } from "../src/mcp.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore, type Store } from "../src/store.js";

const timing = { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 };

/** A store whose every round trip takes `delayMs`, like a database in another region. */
class SlowStore extends MemoryStore {
  constructor(private readonly delayMs: number) { super(); }
  override async hgetall(key: string) {
    await new Promise((r) => setTimeout(r, this.delayMs));
    return super.hgetall(key);
  }
  override async get(key: string) {
    await new Promise((r) => setTimeout(r, this.delayMs));
    return super.get(key);
  }
}

test("slow-store note appears only above the threshold", () => {
  assert.equal(slowStoreNote(0), "");
  assert.equal(slowStoreNote(SLOW_STORE_MS - 1), "");
  assert.match(slowStoreNote(SLOW_STORE_MS), /^Note for the owner: one database round trip took \d+ ms/);
  assert.match(slowStoreNote(400), /same region as the Vercel function/);
});

test("list_devices warns when the database is slow, and not when it is fast", async () => {
  const fast = await renderDevices(new Devices(new MemoryStore(), timing));
  assert.doesNotMatch(fast, /Note for the owner/);
  const slow = await renderDevices(new Devices(new SlowStore(SLOW_STORE_MS + 30), timing));
  assert.match(slow, /No phones are paired yet/);
  assert.match(slow, /Note for the owner: one database round trip took \d+ ms/);
});

test("the owner API reports the database round trip, so the app can warn", async () => {
  const ADMIN = "admin-token-speed-0123456789abcdef0123";
  const speed = async (store: Store, token = ADMIN) => {
    const gw = new Gateway(store, configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }));
    const res = await gw.fetch(new Request("http://gw/v1/admin/speed", { headers: { authorization: `Bearer ${token}` } }));
    return { status: res.status, body: await res.json() as { store_ms: number; slow_ms: number; slow: boolean; region: string | null } };
  };
  assert.equal((await speed(new MemoryStore(), "wrong-token-0123456789abcdef0123456789")).status, 401);
  const fast = await speed(new MemoryStore());
  assert.equal(fast.status, 200);
  assert.deepEqual({ slow: fast.body.slow, slow_ms: fast.body.slow_ms }, { slow: false, slow_ms: SLOW_STORE_MS });
  const slow = await speed(new SlowStore(SLOW_STORE_MS + 30));
  assert.ok(slow.body.slow && slow.body.store_ms >= SLOW_STORE_MS, JSON.stringify(slow.body));
});
