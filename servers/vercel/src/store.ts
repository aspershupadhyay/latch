// The handful of Redis operations the gateway needs. Vercel functions are
// stateless, so all shared state lives here: paired devices and clients
// (hashes only, never tokens), live sessions, command queues, and results.
// Screen content is stored only transiently (latest observation, results),
// always with a short expiry, and never with screenshots in the cache.

import { Redis } from "@upstash/redis";

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
}

/** Picks the store from the environment Vercel's Upstash integration provides. */
export function storeFromEnv(env: NodeJS.ProcessEnv): Store | undefined {
  const url = env.UPSTASH_REDIS_REST_URL ?? env.KV_REST_API_URL;
  const token = env.UPSTASH_REDIS_REST_TOKEN ?? env.KV_REST_API_TOKEN;
  return url && token ? new UpstashStore(url, token) : undefined;
}
