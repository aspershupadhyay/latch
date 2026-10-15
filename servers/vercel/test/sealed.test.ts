// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R20 (ADR-039): screen content is sealed before it reaches the database.
import { test } from "node:test";
import assert from "node:assert/strict";
import { SealedStore, isSealedKey, open, seal, storeKey } from "../src/sealed.js";
import { MemoryStore } from "../src/store.js";

const key = storeKey("admin-token-sealed-0123456789abcdef0123");

test("content keys are sealed on every write path and open on every read path", async () => {
  const raw = new MemoryStore();
  const store = new SealedStore(raw, key);
  const screen = JSON.stringify({ elements: [{ text: "Balance 1,204.50" }] });

  await store.set("latch:obs:d1", screen, { px: 60_000 });
  await store.lpush("latch:q:d1:c1", '{"type":"command","text":"hunter2"}', 60_000);
  await store.batch([{ op: "set", key: "latch:result:c1", value: screen }, { op: "lpush", key: "latch:q:d1:c1", value: "second", px: 60_000 }]);

  for (const k of ["latch:obs:d1", "latch:result:c1"]) {
    const stored = (await raw.get(k))!;
    assert.match(stored, /^s1:/);
    assert.ok(!stored.includes("Balance"));
  }
  assert.ok((await raw.lrange("latch:q:d1:c1", 0, -1)).every((v) => v.startsWith("s1:") && !v.includes("hunter2")));

  assert.equal(await store.get("latch:obs:d1"), screen);
  assert.deepEqual(await store.mget(["latch:obs:d1", "latch:missing:x"]), [screen, null]);
  assert.deepEqual(await store.batch([{ op: "getdel", key: "latch:result:c1" }, { op: "get", key: "latch:result:c1" }]), [screen, null]);
  assert.equal(await store.rpop("latch:q:d1:c1"), '{"type":"command","text":"hunter2"}');
  assert.equal(await store.rpop("latch:q:d1:c1"), "second");
});

test("other keys stay readable to the gateway's own compares and counters", async () => {
  const raw = new MemoryStore();
  const store = new SealedStore(raw, key);
  await store.set("latch:lock:d1", "holder", { px: 1000 });
  assert.equal(await raw.get("latch:lock:d1"), "holder");
  await store.batch([{ op: "delIfEquals", key: "latch:lock:d1", value: "holder" }]);
  assert.equal(await raw.get("latch:lock:d1"), null);
  assert.equal(await store.incr("latch:calls:x:1", 1000), 1);
  assert.ok(!isSealedKey("latch:devices") && !isSealedKey("latch:ownerkey") && !isSealedKey("latch:audit"));
});

test("a sealed value opens only under its own key name and the gateway's key", () => {
  const sealed = seal(key, "latch:obs:d1", "screen");
  assert.equal(open(key, "latch:obs:d1", sealed), "screen");
  assert.equal(open(key, "latch:obs:d2", sealed), null, "moved to another phone's key");
  assert.equal(open(storeKey("another-admin-token-0123456789abcdef0"), "latch:obs:d1", sealed), null, "another gateway's key");
  const flipped = "s1:" + Buffer.from(Buffer.from(sealed.slice(3), "base64").map((b, i) => (i === 20 ? b ^ 1 : b))).toString("base64");
  assert.equal(open(key, "latch:obs:d1", flipped), null, "tampered");
  assert.equal(open(key, "latch:obs:d1", "s1:AAAA"), null, "truncated");
  assert.equal(open(key, "latch:obs:d1", '{"plain":"from before the upgrade"}'), '{"plain":"from before the upgrade"}');
  assert.notEqual(seal(key, "latch:obs:d1", "screen"), sealed, "a fresh nonce every time");
});
