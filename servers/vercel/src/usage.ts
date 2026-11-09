// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: how much of its platform's free allowance this relay has used, and
// the owner's other relays. Counts are kept in memory and added to the store
// now and then (one round trip), so counting costs almost nothing. They are
// estimates: the platform's own dashboard is the bill.

import type { Platform } from "./gateway.js";
import type { Store } from "./store.js";

export interface Allowance {
  id: "requests" | "store_commands";
  label: string;
  limit: number;
  period: "day" | "month";
}

/**
 * Free allowances as published when this was written (2026-11); owners can
 * set their own with LATCH_USAGE_LIMITS, e.g. `requests=1000000,store_commands=500000`.
 */
export const ALLOWANCES: Record<Platform, Allowance[]> = {
  vercel: [
    { id: "requests", label: "Vercel function calls", limit: 1_000_000, period: "month" },
    { id: "store_commands", label: "Upstash Redis commands", limit: 500_000, period: "month" },
  ],
  cloudflare: [
    // Each request reaches the Worker and then the Durable Object: the same count for both.
    { id: "requests", label: "Cloudflare Worker and Durable Object requests", limit: 100_000, period: "day" },
  ],
};

/** From this share of an allowance on, every tool answer names the other relays. */
export const NEAR_LIMIT = 0.8;
const FLUSH_MS = 15_000;
const FLUSH_EVERY = 25;
const KEEP_MS = 40 * 24 * 3600_000;
const RELAYS_KEY = "latch:relays";
export const MAX_RELAYS = 6;

export interface UsageLine extends Allowance {
  used: number;
  /** When this period ends (UTC). */
  resets_at_ms: number;
}

export interface OtherRelay {
  name: string;
  platform: string;
  mcp_url: string;
}

function periodOf(period: "day" | "month", now: number): { key: string; ends: number } {
  const d = new Date(now);
  if (period === "day") {
    return { key: d.toISOString().slice(0, 10), ends: Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) };
  }
  return { key: d.toISOString().slice(0, 7), ends: Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1) };
}

export function limitsFromEnv(platform: Platform, value: string | undefined): Allowance[] {
  const custom = new Map<string, number>();
  for (const part of (value ?? "").split(",")) {
    const [k, v] = part.split("=").map((s) => s.trim());
    if (k && v && /^\d{1,12}$/.test(v)) custom.set(k, Number(v));
  }
  return ALLOWANCES[platform].map((a) => (custom.has(a.id) ? { ...a, limit: custom.get(a.id)! } : a));
}

/** Short, plain words for an amount: 1.2k, 340k, 1.5M. */
const amount = (n: number) => (n >= 1e6 ? `${(n / 1e6).toFixed(n >= 1e7 ? 0 : 1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(n >= 1e4 ? 0 : 1)}k` : String(n));

export class Usage {
  private readonly pending = new Map<string, number>();
  private sinceFlush = 0;
  private lastFlush = Date.now();
  private flushing?: Promise<void>;
  private near?: { at: number; text: string | undefined };

  constructor(private readonly store: Store, readonly platform: Platform, private readonly allowances: Allowance[]) {}

  /** Counts one request and the store commands it made; adds them to the store now and then. */
  count(id: Allowance["id"], n = 1) {
    if (n <= 0) return;
    this.pending.set(id, (this.pending.get(id) ?? 0) + n);
  }

  /** Called once per request; returns the flush to wait on when one is due. */
  tick(): Promise<void> | undefined {
    this.sinceFlush += 1;
    if (this.sinceFlush < FLUSH_EVERY && Date.now() - this.lastFlush < FLUSH_MS) return undefined;
    return this.flush();
  }

  flush(): Promise<void> {
    if (this.flushing) return this.flushing;
    if (this.pending.size === 0) return Promise.resolve();
    const now = Date.now();
    const batch = [...this.pending];
    this.pending.clear();
    this.sinceFlush = 0;
    this.lastFlush = now;
    this.flushing = (async () => {
      try {
        for (const [id, n] of batch) {
          const allowance = this.allowances.find((a) => a.id === id);
          if (!allowance) continue;
          await this.store.incrby(`latch:usage:${id}:${periodOf(allowance.period, now).key}`, n, KEEP_MS);
          // The flush is billed too (INCRBY and PEXPIRE).
          this.count("store_commands", 2);
        }
      } catch {
        // A count that did not reach the store is lost, never a failed request.
      } finally {
        this.flushing = undefined;
      }
    })();
    return this.flushing;
  }

  async lines(now = Date.now()): Promise<UsageLine[]> {
    await this.flush();
    const keys = this.allowances.map((a) => `latch:usage:${a.id}:${periodOf(a.period, now).key}`);
    const values = await this.store.mget(keys);
    return this.allowances.map((a, i) => ({ ...a, used: Number(values[i] ?? 0) || 0, resets_at_ms: periodOf(a.period, now).ends }));
  }

  async others(): Promise<OtherRelay[]> {
    const raw = await this.store.get(RELAYS_KEY);
    if (!raw) return [];
    try {
      return (JSON.parse(raw) as OtherRelay[]).slice(0, MAX_RELAYS);
    } catch {
      return [];
    }
  }

  async setOthers(relays: OtherRelay[]) {
    await this.store.set(RELAYS_KEY, JSON.stringify(relays.slice(0, MAX_RELAYS)));
    this.near = undefined;
  }

  /** The lines `diagnose` ends with: this relay's use and where else the AI can reach the phone. */
  async report(): Promise<string> {
    const lines = await this.lines();
    let out = `Relay (${this.platform}) use, estimated:\n`;
    for (const l of lines) {
      out += `- ${l.label}: ${amount(l.used)} of ${amount(l.limit)} this ${l.period} (${Math.min(100, Math.round((100 * l.used) / l.limit))}%), resets ${new Date(l.resets_at_ms).toISOString().slice(0, 16).replace("T", " ")} UTC.\n`;
    }
    const others = await this.others();
    out += others.length === 0
      ? "- No other relay is set up for this phone. If this one runs out, the owner can add Vercel or Cloudflare in the Latch app (Connect → Add a relay).\n"
      : `- Other relays the phone is connected to: ${others.map((o) => `${o.name} (${o.mcp_url})`).join("; ")}. If this relay stops answering, use the same tools on one of those servers.\n`;
    return out;
  }

  /** One line for every tool answer once an allowance is nearly used up; undefined otherwise. Checked once a minute. */
  async nearLimit(): Promise<string | undefined> {
    if (this.near && Date.now() - this.near.at < 60_000) return this.near.text;
    const text = await this.computeNearLimit();
    this.near = { at: Date.now(), text };
    return text;
  }

  private async computeNearLimit(): Promise<string | undefined> {
    const lines = await this.lines();
    const worst = lines.reduce<UsageLine | undefined>((w, l) => (l.used / l.limit >= NEAR_LIMIT && (!w || l.used / l.limit > w.used / w.limit) ? l : w), undefined);
    if (!worst) return undefined;
    const pct = Math.min(100, Math.round((100 * worst.used) / worst.limit));
    const others = await this.others();
    const next = others.length === 0
      ? "Tell the user: in the Latch app, Connect → Add a relay sets up another one."
      : `If it stops answering, continue with the same tools on ${others.map((o) => `${o.name} (${o.mcp_url})`).join(" or ")}; the phone is connected there too.`;
    return `Note: this relay has used ${pct}% of its free ${worst.label} for this ${worst.period}. ${next}`;
  }
}

/** Wraps a store so every command it sends is counted (Upstash bills per command). */
export function countingStore(inner: Store, onCommands: (n: number) => void): Store {
  return new Proxy(inner, {
    get(target, prop, receiver) {
      const f = Reflect.get(target, prop, receiver);
      if (typeof f !== "function") return f;
      return (...args: unknown[]) => {
        // A pipeline is one request but each command in it is billed; lpush and incr add a pexpire.
        const ops = prop === "batch" ? (args[0] as { op: string }[]) : [{ op: String(prop) }];
        onCommands(ops.reduce((n, o) => n + (o.op === "lpush" || o.op === "incr" || o.op === "incrby" ? 2 : 1), 0));
        return f.apply(target, args);
      };
    },
  });
}

export function relaysOk(value: unknown): value is OtherRelay[] {
  if (!Array.isArray(value) || value.length > MAX_RELAYS) return false;
  return value.every((r) => {
    if (typeof r !== "object" || r === null) return false;
    const { name, platform, mcp_url } = r as Record<string, unknown>;
    if (typeof name !== "string" || !/^[\p{L}\p{N} ._()-]{1,40}$/u.test(name)) return false;
    if (typeof platform !== "string" || !/^[a-z]{1,16}$/.test(platform)) return false;
    if (typeof mcp_url !== "string" || mcp_url.length > 300) return false;
    try {
      const u = new URL(mcp_url);
      return u.protocol === "https:" && !u.username && !u.password && u.pathname === "/mcp" && !u.search && !u.hash;
    } catch {
      return false;
    }
  });
}
