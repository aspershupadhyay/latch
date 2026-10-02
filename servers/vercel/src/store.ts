// The handful of Redis operations the gateway needs. Vercel functions are
// stateless, so all shared state lives here: paired devices and clients
// (hashes only, never tokens), live sessions, command queues, and results.
// Screen content is stored only transiently (latest observation, results),
// always with a short expiry, and never with screenshots in the cache.

import { Redis } from "@upstash/redis";

/**
 * One operation of a [Store.batch]. A batch is sent as a single pipeline (one
 * network round trip) and runs in order, but is not atomic.
 */
export type BatchOp =
  | { op: "get"; key: string }
  | { op: "getdel"; key: string }
  | { op: "set"; key: string; value: string; px?: number }
  | { op: "del"; key: string }
  /** Deletes the key only while it still holds `value` (lock release). */
  | { op: "delIfEquals"; key: string; value: string }
  | { op: "pexpire"; key: string; px: number }
  | { op: "lpush"; key: string; value: string; px: number }
  | { op: "ltrim"; key: string; start: number; stop: number };

const DEL_IF_EQUALS = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

export interface Store {
  get(key: string): Promise<string | null>;
  /** Returns false when `nx` is set and the key already exists. */
  set(key: string, value: string, opts?: { px?: number; nx?: boolean }): Promise<boolean>;
  getdel(key: string): Promise<string | null>;
  del(key: string): Promise<void>;
  pexpire(key: string, px: number): Promise<void>;
  incr(key: string, px: number): Promise<number>;
  lpush(key: string, value: string, px: number): Promise<void>;
  rpop(key: string): Promise<string | null>;
  lrange(key: string, start: number, stop: number): Promise<string[]>;
  ltrim(key: string, start: number, stop: number): Promise<void>;
  hset(key: string, field: string, value: string): Promise<void>;
  hdel(key: string, field: string): Promise<boolean>;
  hgetall(key: string): Promise<Record<string, string>>;
  hget(key: string, field: string): Promise<string | null>;
  mget(keys: string[]): Promise<(string | null)[]>;
  /** Runs `ops` in one round trip. Returns the value of each `get`/`getdel` (null for other ops). */
  batch(ops: BatchOp[]): Promise<(string | null)[]>;
}

/** Upstash Redis over REST: works from Vercel Functions without a socket. */
export class UpstashStore implements Store {
  private readonly redis: Redis;

  constructor(url: string, token: string) {
    // Keep values as strings; the gateway parses JSON itself.
    this.redis = new Redis({ url, token, automaticDeserialization: false });
  }

  async get(key: string) { return (await this.redis.get<string>(key)) ?? null; }
  async set(key: string, value: string, opts: { px?: number; nx?: boolean } = {}) {
    const result = opts.px !== undefined
      ? opts.nx ? await this.redis.set(key, value, { px: opts.px, nx: true }) : await this.redis.set(key, value, { px: opts.px })
      : opts.nx ? await this.redis.set(key, value, { nx: true }) : await this.redis.set(key, value);
    return result !== null;
  }
  async getdel(key: string) { return (await this.redis.getdel<string>(key)) ?? null; }
  async del(key: string) { await this.redis.del(key); }
  async pexpire(key: string, px: number) { await this.redis.pexpire(key, px); }
  async incr(key: string, px: number) {
    const [n] = await this.redis.pipeline().incr(key).pexpire(key, px).exec<[number, number]>();
    return n;
  }
  async lpush(key: string, value: string, px: number) { await this.redis.pipeline().lpush(key, value).pexpire(key, px).exec(); }
  async rpop(key: string) { return (await this.redis.rpop<string>(key)) ?? null; }
  async lrange(key: string, start: number, stop: number) { return await this.redis.lrange<string>(key, start, stop); }
  async ltrim(key: string, start: number, stop: number) { await this.redis.ltrim(key, start, stop); }
  async hset(key: string, field: string, value: string) { await this.redis.hset(key, { [field]: value }); }
  async hdel(key: string, field: string) { return (await this.redis.hdel(key, field)) > 0; }
  async hgetall(key: string) {
    // Without automatic deserialization Upstash returns HGETALL as a flat
    // [field, value, field, value, …] array (or null for a missing key).
    const raw = (await this.redis.hgetall(key)) as unknown;
    if (raw === null || raw === undefined) return {};
    if (Array.isArray(raw)) {
      const out: Record<string, string> = {};
      for (let i = 0; i + 1 < raw.length; i += 2) out[String(raw[i])] = String(raw[i + 1]);
      return out;
    }
    return raw as Record<string, string>;
  }
  async hget(key: string, field: string) { return (await this.redis.hget<string>(key, field)) ?? null; }
  async mget(keys: string[]) {
    if (keys.length === 0) return [];
    return (await this.redis.mget<(string | null)[]>(...keys)).map((v) => v ?? null);
  }
  async batch(ops: BatchOp[]) {
    if (ops.length === 0) return [];
    const p = this.redis.pipeline();
    // Pipeline position of each op's own reply (lpush adds a pexpire after it).
    const at: number[] = [];
    let n = 0;
    for (const o of ops) {
      at.push(n++);
      switch (o.op) {
        case "get": p.get(o.key); break;
        case "getdel": p.getdel(o.key); break;
        case "set": if (o.px !== undefined) p.set(o.key, o.value, { px: o.px }); else p.set(o.key, o.value); break;
        case "del": p.del(o.key); break;
        case "delIfEquals": p.eval(DEL_IF_EQUALS, [o.key], [o.value]); break;
        case "pexpire": p.pexpire(o.key, o.px); break;
        case "lpush": p.lpush(o.key, o.value); p.pexpire(o.key, o.px); n++; break;
        case "ltrim": p.ltrim(o.key, o.start, o.stop); break;
      }
    }
    const replies = await p.exec<unknown[]>();
    return ops.map((o, i) => (o.op === "get" || o.op === "getdel") && typeof replies[at[i]!] === "string" ? (replies[at[i]!] as string) : null);
  }
}

type Entry = { value: string | string[] | Map<string, string>; expiresAt?: number };

/** In-process store for tests and `npm run dev`. Not shared between instances. */
export class MemoryStore implements Store {
  private readonly data = new Map<string, Entry>();

  private entry(key: string): Entry | undefined {
    const e = this.data.get(key);
    if (e?.expiresAt !== undefined && e.expiresAt <= Date.now()) {
      this.data.delete(key);
      return undefined;
    }
    return e;
  }
  private list(key: string, create = false): string[] | undefined {
    const e = this.entry(key);
    if (e && Array.isArray(e.value)) return e.value;
    if (!create) return undefined;
    const value: string[] = [];
    this.data.set(key, { value });
    return value;
  }
  private hash(key: string, create = false): Map<string, string> | undefined {
    const e = this.entry(key);
    if (e && e.value instanceof Map) return e.value;
    if (!create) return undefined;
    const value = new Map<string, string>();
    this.data.set(key, { value });
    return value;
  }

  async get(key: string) { const e = this.entry(key); return typeof e?.value === "string" ? e.value : null; }
  async set(key: string, value: string, opts: { px?: number; nx?: boolean } = {}) {
    if (opts.nx && this.entry(key)) return false;
    this.data.set(key, { value, expiresAt: opts.px !== undefined ? Date.now() + opts.px : undefined });
    return true;
  }
  async getdel(key: string) { const v = await this.get(key); this.data.delete(key); return v; }
  async del(key: string) { this.data.delete(key); }
  async pexpire(key: string, px: number) { const e = this.entry(key); if (e) e.expiresAt = Date.now() + px; }
  async incr(key: string, px: number) {
    const n = Number((await this.get(key)) ?? "0") + 1;
    this.data.set(key, { value: String(n), expiresAt: Date.now() + px });
    return n;
  }
  async lpush(key: string, value: string, px: number) { this.list(key, true)!.unshift(value); await this.pexpire(key, px); }
  async rpop(key: string) { return this.list(key)?.pop() ?? null; }
  async lrange(key: string, start: number, stop: number) {
    const l = this.list(key) ?? [];
    return l.slice(start, stop < 0 ? l.length + stop + 1 : stop + 1);
  }
  async ltrim(key: string, start: number, stop: number) {
    const l = this.list(key);
    if (l) l.splice(0, l.length, ...l.slice(start, stop + 1));
  }
  async hset(key: string, field: string, value: string) { this.hash(key, true)!.set(field, value); }
  async hdel(key: string, field: string) { return this.hash(key)?.delete(field) ?? false; }
  async hgetall(key: string) { return Object.fromEntries(this.hash(key) ?? new Map()); }
  async hget(key: string, field: string) { return this.hash(key)?.get(field) ?? null; }
  async mget(keys: string[]) { return Promise.all(keys.map((k) => this.get(k))); }
  async batch(ops: BatchOp[]) {
    const out: (string | null)[] = [];
    for (const o of ops) {
      let value: string | null = null;
      switch (o.op) {
        case "get": value = await this.get(o.key); break;
        case "getdel": value = await this.getdel(o.key); break;
        case "set": await this.set(o.key, o.value, { px: o.px }); break;
        case "del": await this.del(o.key); break;
        case "delIfEquals": if ((await this.get(o.key)) === o.value) await this.del(o.key); break;
        case "pexpire": await this.pexpire(o.key, o.px); break;
        case "lpush": await this.lpush(o.key, o.value, o.px); break;
        case "ltrim": await this.ltrim(o.key, o.start, o.stop); break;
      }
      out.push(value);
    }
    return out;
  }
}

/** Picks the store from the environment Vercel's Upstash integration provides. */
export function storeFromEnv(env: NodeJS.ProcessEnv): Store | undefined {
  const url = env.UPSTASH_REDIS_REST_URL ?? env.KV_REST_API_URL;
  const token = env.UPSTASH_REDIS_REST_TOKEN ?? env.KV_REST_API_TOKEN;
  return url && token ? new UpstashStore(url, token) : undefined;
}
