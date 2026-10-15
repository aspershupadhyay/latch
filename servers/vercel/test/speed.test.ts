// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// R3: a database far from the function is reported to the owner through list_devices.
import { test } from "node:test";
import assert from "node:assert/strict";
import { Devices, SLOW_STORE_MS } from "../src/devices.js";
import { renderDevices, slowStoreNote } from "../src/mcp.js";
import { MemoryStore } from "../src/store.js";

const timing = { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 };

/** A store whose every round trip takes `delayMs`, like a database in another region. */
class SlowStore extends MemoryStore {
  constructor(private readonly delayMs: number) { super(); }
  override async hgetall(key: string) {
    await new Promise((r) => setTimeout(r, this.delayMs));
    return super.hgetall(key);
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
