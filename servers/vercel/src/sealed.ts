// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R20 (ADR-039): screen content is sealed before it reaches the
// database. Upstash (or any Redis) holds observations, results, approval
// requests, and queued commands only as AES-256-GCM ciphertext under a key
// derived from LATCH_ADMIN_TOKEN, which lives only in the gateway's own
// environment. The key name is the associated data, so a value moved to
// another key does not open. Values without the prefix are read as they are,
// so values written before an upgrade expire normally.

import { createCipheriv, createDecipheriv, hkdfSync, randomBytes } from "node:crypto";
import type { BatchOp, Store } from "./store.js";

const PREFIX = "s1:";

/** Keys whose values carry screen content, typed text, or the owner's answers. */
const SEALED = ["latch:q:", "latch:result:", "latch:approval:", "latch:obs:", "latch:base:", "latch:elicit:"];

export const isSealedKey = (key: string) => SEALED.some((p) => key.startsWith(p));

export function storeKey(adminToken: string): Buffer {
  return Buffer.from(hkdfSync("sha256", adminToken, "latch store v1", "screen content", 32));
}

export function seal(key: Buffer, name: string, plain: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(name, "utf8"));
  const body = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  return PREFIX + Buffer.concat([iv, body, cipher.getAuthTag()]).toString("base64");
}

/** The plain value, or null when it does not open under this key and name (treated as gone). */
export function open(key: Buffer, name: string, value: string | null): string | null {
  if (value === null || !value.startsWith(PREFIX)) return value;
  try {
    const raw = Buffer.from(value.slice(PREFIX.length), "base64");
    if (raw.length < 28) return null;
    const decipher = createDecipheriv("aes-256-gcm", key, raw.subarray(0, 12));
    decipher.setAAD(Buffer.from(name, "utf8"));
    decipher.setAuthTag(raw.subarray(raw.length - 16));
    return Buffer.concat([decipher.update(raw.subarray(12, raw.length - 16)), decipher.final()]).toString("utf8");
  } catch {
    console.error("latch: a stored value did not open (changed LATCH_ADMIN_TOKEN or tampering); treated as gone");
    return null;
  }
}

/** Wraps a store so content keys are sealed on write and opened on read. */
export class SealedStore implements Store {
  constructor(private readonly inner: Store, private readonly key: Buffer) {}

  private in(name: string, value: string) { return isSealedKey(name) ? seal(this.key, name, value) : value; }
  private out(name: string, value: string | null) { return isSealedKey(name) ? open(this.key, name, value) : value; }

  async get(key: string) { return this.out(key, await this.inner.get(key)); }
  set(key: string, value: string, opts?: { px?: number; nx?: boolean }) { return this.inner.set(key, this.in(key, value), opts); }
  async getdel(key: string) { return this.out(key, await this.inner.getdel(key)); }
  del(key: string) { return this.inner.del(key); }
  pexpire(key: string, px: number) { return this.inner.pexpire(key, px); }
  incr(key: string, px: number) { return this.inner.incr(key, px); }
  lpush(key: string, value: string, px: number) { return this.inner.lpush(key, this.in(key, value), px); }
  async rpop(key: string) { return this.out(key, await this.inner.rpop(key)); }
  async lrange(key: string, start: number, stop: number) {
    return (await this.inner.lrange(key, start, stop)).map((v) => this.out(key, v)).filter((v): v is string => v !== null);
  }
  ltrim(key: string, start: number, stop: number) { return this.inner.ltrim(key, start, stop); }
  hset(key: string, field: string, value: string) { return this.inner.hset(key, field, value); }
  hdel(key: string, field: string) { return this.inner.hdel(key, field); }
  hgetall(key: string) { return this.inner.hgetall(key); }
  hget(key: string, field: string) { return this.inner.hget(key, field); }
  async mget(keys: string[]) { return (await this.inner.mget(keys)).map((v, i) => this.out(keys[i]!, v)); }
  async batch(ops: BatchOp[]) {
    const sealed = ops.map((o): BatchOp => {
      if (o.op === "set" || o.op === "lpush") return { ...o, value: this.in(o.key, o.value) };
      // A compare needs the stored bytes; sealed values never take part in one.
      if (o.op === "delIfEquals" && isSealedKey(o.key)) throw new Error("delIfEquals on a sealed key");
      return o;
    });
    return (await this.inner.batch(sealed)).map((v, i) => this.out(ops[i]!.key, v));
  }
}
