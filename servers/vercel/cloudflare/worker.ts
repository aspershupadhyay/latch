// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: the Latch relay on Cloudflare Workers. Every request goes to one
// Durable Object, which runs the same gateway as on Vercel over its own SQLite
// storage: one place for paired phones, queues, and results, so a phone's long
// poll and an AI app's tool call meet in memory with no database round trips.
// The stylesheet and robots.txt come from static assets (public/), with the
// headers vercel.json gives them.

import { PRIVATE_HEADERS } from "../src/gateway.js";
import { fetchPublicWithFetch, setPublicFetcher } from "../src/oauth.js";
import { PAGE_CSP } from "../src/page.js";
import { Relay } from "../src/relay.js";
import { SqlStore } from "../src/sqlstore.js";

/** The parts of the Workers runtime this file uses (no type package needed). */
interface SqlCursor { toArray(): Record<string, unknown>[] }
interface DurableObjectState { storage: { sql: { exec(query: string, ...bindings: unknown[]): SqlCursor } } }
interface DurableObjectStub { fetch(request: Request): Promise<Response> }
interface DurableObjectNamespace { idFromName(name: string): unknown; get(id: unknown): DurableObjectStub }
interface Fetcher { fetch(request: Request): Promise<Response> }
interface Env {
  RELAY: DurableObjectNamespace;
  ASSETS: Fetcher;
  [name: string]: unknown;
}

/** Only the Worker's text variables and secrets, as the gateway reads them from process.env on Vercel. */
function textVars(env: Env): Record<string, string | undefined> {
  const out: Record<string, string | undefined> = {};
  for (const [k, v] of Object.entries(env)) if (typeof v === "string") out[k] = v;
  return out;
}

setPublicFetcher(fetchPublicWithFetch);

/** The relay itself: one instance, named "relay", holds all of its state. */
export class LatchRelay {
  private readonly relay: Relay;

  constructor(state: DurableObjectState, env: Env) {
    const sql = { exec: (query: string, ...bindings: (string | number | null)[]) => state.storage.sql.exec(query, ...bindings).toArray() };
    this.relay = new Relay(new SqlStore(sql), textVars(env), "cloudflare");
  }

  fetch(request: Request): Promise<Response> {
    return this.relay.fetch(request);
  }
}

const STATIC = new Set(["/gateway.css", "/robots.txt"]);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (STATIC.has(path) && request.method === "GET") {
      const asset = await env.ASSETS.fetch(request);
      const response = new Response(asset.body, asset);
      for (const [k, v] of Object.entries(PRIVATE_HEADERS)) response.headers.set(k, v);
      response.headers.set("content-security-policy", PAGE_CSP);
      response.headers.set("cache-control", "no-store");
      return response;
    }
    return env.RELAY.get(env.RELAY.idFromName("relay")).fetch(request);
  },
};
