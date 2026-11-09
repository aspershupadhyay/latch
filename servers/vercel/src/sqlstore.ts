// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: the gateway's store over SQLite, for a Cloudflare Durable Object
// (`ctx.storage.sql`) and, in tests, Node's built-in `node:sqlite`. One table:
// strings as they are, lists and hashes as JSON. Every call runs to the end
// without awaiting anything, so inside a Durable Object each one is atomic.
// Expired rows are dropped when read and swept now and then.

import type { BatchOp, Store } from "./store.js";

/** The one call the store needs: run a statement and get its rows. */
export interface Sql {
  exec(query: string, ...bindings: (string | number | null)[]): Record<string, unknown>[];
}

type Kind = "s" | "l" | "h";
interface Row { kind: Kind; value: string; expires_at: number | null }

/** Sweep expired rows about once every this many writes. */
const SWEEP_EVERY = 500;

export class SqlStore implements Store {
  private writes = 0;

  constructor(private readonly sql: Sql) {
    sql.exec("CREATE TABLE IF NOT EXISTS latch_kv (key TEXT PRIMARY KEY, kind TEXT NOT NULL, value TEXT NOT NULL, expires_at INTEGER)");
    sql.exec("CREATE INDEX IF NOT EXISTS latch_kv_expiry ON latch_kv (expires_at) WHERE expires_at IS NOT NULL");
  }

  private row(key: string): Row | undefined {
    const rows = this.sql.exec("SELECT kind, value, expires_at FROM latch_kv WHERE key = ?", key);
    const r = rows[0] as Row | undefined;
    if (!r) return undefined;
    if (r.expires_at !== null && r.expires_at <= Date.now()) {
      this.sql.exec("DELETE FROM latch_kv WHERE key = ?", key);
      return undefined;
    }
    return r;
  }

  private put(key: string, kind: Kind, value: string, expiresAt: number | null) {
    this.sql.exec("INSERT OR REPLACE INTO latch_kv (key, kind, value, expires_at) VALUES (?, ?, ?, ?)", key, kind, value, expiresAt);
    if (++this.writes % SWEEP_EVERY === 0) this.sql.exec("DELETE FROM latch_kv WHERE expires_at IS NOT NULL AND expires_at <= ?", Date.now());
  }

  private string(key: string): string | null {
    const r = this.row(key);
    return r?.kind === "s" ? r.value : null;
  }

  private list(key: string): { items: string[]; expiresAt: number | null } {
    const r = this.row(key);
    return r?.kind === "l" ? { items: JSON.parse(r.value) as string[], expiresAt: r.expires_at } : { items: [], expiresAt: null };
  }

  private writeList(key: string, items: string[], expiresAt: number | null) {
    if (items.length === 0) this.delSync(key);
    else this.put(key, "l", JSON.stringify(items), expiresAt);
  }

  private hash(key: string): { fields: Record<string, string>; expiresAt: number | null } {
    const r = this.row(key);
    return r?.kind === "h" ? { fields: JSON.parse(r.value) as Record<string, string>, expiresAt: r.expires_at } : { fields: {}, expiresAt: null };
  }

  private delSync(key: string) { this.sql.exec("DELETE FROM latch_kv WHERE key = ?", key); }

  private setSync(key: string, value: string, opts: { px?: number; nx?: boolean } = {}): boolean {
    if (opts.nx && this.row(key)) return false;
    this.put(key, "s", value, opts.px !== undefined ? Date.now() + opts.px : null);
    return true;
  }

  private getdelSync(key: string) { const v = this.string(key); this.delSync(key); return v; }

  private pexpireSync(key: string, px: number) {
    if (this.row(key)) this.sql.exec("UPDATE latch_kv SET expires_at = ? WHERE key = ?", Date.now() + px, key);
  }

  private lpushSync(key: string, value: string, px: number) {
    const { items } = this.list(key);
    this.writeList(key, [value, ...items], Date.now() + px);
  }

  private ltrimSync(key: string, start: number, stop: number) {
    const { items, expiresAt } = this.list(key);
    if (items.length === 0) return;
    this.writeList(key, items.slice(start, stop < 0 ? items.length + stop + 1 : stop + 1), expiresAt);
  }

  async get(key: string) { return this.string(key); }
  async set(key: string, value: string, opts?: { px?: number; nx?: boolean }) { return this.setSync(key, value, opts); }
  async getdel(key: string) { return this.getdelSync(key); }
  async del(key: string) { this.delSync(key); }
  async pexpire(key: string, px: number) { this.pexpireSync(key, px); }
  async incr(key: string, px: number) {
    const n = Number(this.string(key) ?? "0") + 1;
    this.put(key, "s", String(n), Date.now() + px);
    return n;
  }
  async incrby(key: string, by: number, px: number) {
    const n = Number(this.string(key) ?? "0") + by;
    this.put(key, "s", String(n), Date.now() + px);
    return n;
  }
  async lpush(key: string, value: string, px: number) { this.lpushSync(key, value, px); }
  async rpop(key: string) {
    const { items, expiresAt } = this.list(key);
    const last = items.pop();
    if (last === undefined) return null;
    this.writeList(key, items, expiresAt);
    return last;
  }
  async lrange(key: string, start: number, stop: number) {
    const { items } = this.list(key);
    return items.slice(start, stop < 0 ? items.length + stop + 1 : stop + 1);
  }
  async ltrim(key: string, start: number, stop: number) { this.ltrimSync(key, start, stop); }
  async hset(key: string, field: string, value: string) {
    const { fields, expiresAt } = this.hash(key);
    fields[field] = value;
    this.put(key, "h", JSON.stringify(fields), expiresAt);
  }
  async hdel(key: string, field: string) {
    const { fields, expiresAt } = this.hash(key);
    if (!(field in fields)) return false;
    delete fields[field];
    if (Object.keys(fields).length === 0) this.delSync(key);
    else this.put(key, "h", JSON.stringify(fields), expiresAt);
    return true;
  }
  async hgetall(key: string) { return this.hash(key).fields; }
  async hget(key: string, field: string) { return this.hash(key).fields[field] ?? null; }
  async mget(keys: string[]) { return keys.map((k) => this.string(k)); }
  async batch(ops: BatchOp[]) {
    return ops.map((o) => {
      switch (o.op) {
        case "get": return this.string(o.key);
        case "getdel": return this.getdelSync(o.key);
        case "set": this.setSync(o.key, o.value, { px: o.px }); return null;
        case "del": this.delSync(o.key); return null;
        case "delIfEquals": if (this.string(o.key) === o.value) this.delSync(o.key); return null;
        case "pexpire": this.pexpireSync(o.key, o.px); return null;
        case "lpush": this.lpushSync(o.key, o.value, o.px); return null;
        case "ltrim": this.ltrimSync(o.key, o.start, o.stop); return null;
      }
    });
  }
}
