// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: the Cloudflare relay's SQLite store behaves like the Redis store.
// MemoryStore is the reference (it mirrors Upstash); thousands of random
// operations must give the same answers from both. Node's built-in SQLite
// stands in for the Durable Object's.
import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { type Sql, SqlStore } from "../src/sqlstore.js";
import { type BatchOp, MemoryStore, type Store } from "../src/store.js";

export function nodeSql(): Sql {
  const db = new DatabaseSync(":memory:");
  return { exec: (query, ...bindings) => db.prepare(query).all(...bindings) as Record<string, unknown>[] };
}

/** A small seeded generator, so a failure repeats. */
function rng(seed: number) {
  return () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    return seed / 0x7fffffff;
  };
}

test("SqlStore answers like the reference store for random operations", async () => {
  const random = rng(75);
  const pick = <T>(xs: readonly T[]) => xs[Math.floor(random() * xs.length)]!;
  // Each key holds one type, as in the gateway (Redis would answer WRONGTYPE otherwise).
  const strings = ["s1", "s2", "latch:obs:x"];
  const lists = ["l1", "latch:q:x"];
  const hashes = ["h1", "latch:clients"];
  const fields = ["f1", "f2", "f3"];
  const value = () => pick(["1", "2", "x", "", "long value with spaces", '{"j":1}']);
  const memory = new MemoryStore();
  const sql = new SqlStore(nodeSql());

  const ops: ((s: Store) => Promise<unknown>)[] = [];
  for (let i = 0; i < 4000; i++) {
    const sk = pick(strings);
    const lk = pick(lists);
    const hk = pick(hashes);
    const field = pick(fields);
    const v = value();
    const n = Math.floor(random() * 4) - 1;
    // Chosen now: inside an operation it would differ between the two stores.
    const anyKey = pick([sk, lk, hk]);
    const counter = pick(["n1", "n2"]);
    const other = pick(strings);
    const nx = random() < 0.3;
    switch (Math.floor(random() * 17)) {
      case 0: ops.push((s) => s.get(sk)); break;
      case 1: ops.push((s) => s.set(sk, v, nx ? { nx: true } : {})); break;
      case 2: ops.push((s) => s.getdel(sk)); break;
      case 3: ops.push((s) => s.del(anyKey)); break;
      case 4: ops.push((s) => s.incr(counter, 60_000)); break;
      case 5: ops.push((s) => s.lpush(lk, v, 60_000)); break;
      case 6: ops.push((s) => s.rpop(lk)); break;
      case 7: ops.push((s) => s.lrange(lk, 0, n)); break;
      case 8: ops.push((s) => s.ltrim(lk, 0, Math.max(n, 0))); break;
      case 9: ops.push((s) => s.hset(hk, field, v)); break;
      case 10: ops.push((s) => s.hdel(hk, field)); break;
      case 11: ops.push((s) => s.hgetall(hk)); break;
      case 12: ops.push((s) => s.hget(hk, field)); break;
      case 13: ops.push((s) => s.mget([sk, other])); break;
      case 14: ops.push((s) => s.pexpire(anyKey, 60_000)); break;
      case 15: ops.push((s) => s.set(sk, v, { px: 60_000 })); break;
      case 16: {
        const batch: BatchOp[] = [
          { op: "set", key: sk, value: v },
          { op: "get", key: other },
          { op: "delIfEquals", key: sk, value: v },
          { op: "lpush", key: lk, value: v, px: 1000 },
          { op: "getdel", key: other },
          { op: "ltrim", key: lk, start: 0, stop: 2 },
        ];
        ops.push((s) => s.batch(batch));
        break;
      }
    }
  }
  for (const [i, op] of ops.entries()) assert.deepEqual(await op(sql), await op(memory), `operation ${i} differed`);
});

test("SqlStore: expiry, nx, and data surviving a new store on the same database", async () => {
  const sql = nodeSql();
  const store = new SqlStore(sql);
  assert.equal(await store.set("k", "v", { px: 20 }), true);
  assert.equal(await store.set("k", "w", { nx: true }), false);
  assert.equal(await store.get("k"), "v");
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(await store.get("k"), null, "expired");
  assert.equal(await store.set("k", "w", { nx: true }), true, "nx after expiry");
  await store.hset("h", "a", "1");
  await store.lpush("l", "x", 60_000);
  const reopened = new SqlStore(sql);
  assert.equal(await reopened.get("k"), "w");
  assert.deepEqual(await reopened.hgetall("h"), { a: "1" });
  assert.deepEqual(await reopened.lrange("l", 0, -1), ["x"]);
  assert.equal(await reopened.incrby("n", 5, 60_000), 5);
  assert.equal(await reopened.incrby("n", 2, 60_000), 7);
});

test("the gateway pairs a phone over SqlStore", async () => {
  const admin = "admin-token-for-sqlstore-0123456789abcd";
  const gw = new Gateway(new SqlStore(nodeSql()), configFromEnv({ LATCH_ADMIN_TOKEN: admin }));
  const post = (path: string, body: unknown, token?: string) =>
    gw.fetch(new Request(`https://relay.example${path}`, {
      method: "POST",
      headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    }));
  const code = await (await post("/v1/admin/pairings", { name: "Pixel" }, admin)).json() as { code: string };
  const paired = await post("/v1/pair", { code: code.code, platform: "android", model: "Pixel" });
  assert.equal(paired.status, 200);
  const again = await post("/v1/pair", { code: code.code, platform: "android", model: "Pixel" });
  assert.equal(again.status, 403, "codes are single use");
});
