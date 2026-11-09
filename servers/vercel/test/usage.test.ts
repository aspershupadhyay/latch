// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: a relay counts its free-allowance use, tells the owner, and names
// the other relays to the AI in `diagnose` and, near the limit, in every answer.
import { test } from "node:test";
import assert from "node:assert/strict";
import { Relay } from "../src/relay.js";
import { MemoryStore } from "../src/store.js";
import { countingStore, limitsFromEnv, relaysOk, Usage } from "../src/usage.js";

const ADMIN = "admin-token-for-usage-tests-0123456789";

function relay(env: Record<string, string>, platform: "vercel" | "cloudflare" = "vercel") {
  const r = new Relay(new MemoryStore(), { LATCH_ADMIN_TOKEN: ADMIN, ...env }, platform);
  const call = async (method: string, path: string, opts: { token?: string; body?: unknown } = {}) => {
    const headers: Record<string, string> = { "content-type": "application/json", authorization: `Bearer ${opts.token ?? ADMIN}` };
    const res = await r.fetch(new Request(`https://relay.example${path}`, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) }));
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  };
  return { r, call };
}

async function mcpKey(call: ReturnType<typeof relay>["call"]) {
  return (await call("POST", "/v1/admin/clients", { body: { name: "Usage test" } })).body.token as string;
}

const tool = (call: ReturnType<typeof relay>["call"], token: string, name: string) =>
  call("POST", "/mcp", { token, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: {} } } });
const texts = (r: { body: { result: { content: { text: string }[] } } }) => r.body.result.content.map((c) => c.text).join("\n");

test("requests and Redis commands are counted for this month", async () => {
  const { r, call } = relay({});
  for (let i = 0; i < 5; i++) await call("GET", "/healthz");
  const usage = await call("GET", "/v1/admin/usage");
  assert.equal(usage.status, 200);
  assert.equal(usage.body.platform, "vercel");
  const requests = usage.body.lines.find((l: { id: string }) => l.id === "requests");
  assert.equal(requests.period, "month");
  assert.equal(requests.limit, 1_000_000);
  assert.ok(requests.used >= 6, `counted ${requests.used}`);
  const commands = usage.body.lines.find((l: { id: string }) => l.id === "store_commands");
  assert.ok(commands.used > 0, "Redis commands are counted on Vercel");
  assert.ok(usage.body.lines.every((l: { resets_at_ms: number }) => l.resets_at_ms > Date.now()));
  assert.equal(r.usage.platform, "vercel");
});

test("Cloudflare counts requests per day and no Redis", async () => {
  const { call } = relay({}, "cloudflare");
  await call("GET", "/healthz");
  await call("POST", "/v1/admin/pairings", { body: { name: "Pixel" } });
  const lines = (await call("GET", "/v1/admin/usage")).body.lines;
  assert.deepEqual(lines.map((l: { id: string; period: string }) => [l.id, l.period]), [["requests", "day"], ["store_rows", "day"]]);
  assert.ok(lines[1].used > 0, "SQLite rows written are counted on Cloudflare");
});

test("the owner registers the other relays; diagnose names them", async () => {
  const { call } = relay({});
  const bad = await call("PUT", "/v1/admin/relays", { body: { relays: [{ name: "x", platform: "cloudflare", mcp_url: "http://insecure.example/mcp" }] } });
  assert.equal(bad.status, 400);
  assert.equal((await call("PUT", "/v1/admin/relays", { token: "lmt_not-the-owner", body: { relays: [] } })).status, 401);
  const ok = await call("PUT", "/v1/admin/relays", { body: { relays: [{ name: "Cloudflare relay", platform: "cloudflare", mcp_url: "https://latch-relay.me.workers.dev/mcp" }] } });
  assert.equal(ok.status, 204);
  assert.equal((await call("GET", "/v1/admin/usage")).body.relays[0].mcp_url, "https://latch-relay.me.workers.dev/mcp");

  const key = await mcpKey(call);
  const diag = texts(await tool(call, key, "diagnose"));
  assert.match(diag, /Relay \(vercel\) use, estimated:/);
  assert.match(diag, /Vercel function calls: \d+ of 1\.0M this month/);
  assert.match(diag, /Cloudflare relay \(https:\/\/latch-relay\.me\.workers\.dev\/mcp\)/);
  // Far from the limit: no note on ordinary answers.
  assert.doesNotMatch(texts(await tool(call, key, "list_devices")), /Note: this relay/);
});

test("near the limit, every tool answer names the other relays", async () => {
  const { call } = relay({ LATCH_USAGE_LIMITS: "requests=10" });
  await call("PUT", "/v1/admin/relays", { body: { relays: [{ name: "Cloudflare relay", platform: "cloudflare", mcp_url: "https://latch-relay.me.workers.dev/mcp" }] } });
  const key = await mcpKey(call);
  for (let i = 0; i < 8; i++) await call("GET", "/healthz");
  const answer = texts(await tool(call, key, "list_devices"));
  assert.match(answer, /Note: this relay has used \d+% of its free Vercel function calls for this month\. If it stops answering, continue with the same tools on Cloudflare relay \(https:\/\/latch-relay\.me\.workers\.dev\/mcp\)/);
});

test("without other relays the note says how to add one", async () => {
  const store = new MemoryStore();
  const usage = new Usage(store, "cloudflare", limitsFromEnv("cloudflare", "requests=2"));
  usage.count("requests", 3);
  assert.match((await usage.nearLimit())!, /Connect → Add a relay/);
});

test("the counting store counts pipelined commands one by one", async () => {
  let n = 0;
  const store = countingStore(new MemoryStore(), (c) => { n += c; });
  await store.get("a");
  await store.lpush("l", "x", 1000);
  await store.batch([{ op: "get", key: "a" }, { op: "set", key: "b", value: "1" }]);
  assert.equal(n, 1 + 2 + 2);
});

test("on Cloudflare only writes count, twice each", async () => {
  let n = 0;
  const store = countingStore(new MemoryStore(), (c) => { n += c; }, { writesOnly: true });
  await store.get("a");
  await store.set("a", "1");
  await store.batch([{ op: "get", key: "a" }, { op: "lpush", key: "l", value: "x", px: 1000 }]);
  assert.equal(n, 4);
});

test("relay lists are checked", () => {
  assert.ok(relaysOk([]));
  assert.ok(!relaysOk([{ name: "a", platform: "x", mcp_url: "https://a.example/mcp?x=1" }]));
  assert.ok(!relaysOk([{ name: "a", platform: "x", mcp_url: "https://user:pw@a.example/mcp" }]));
  assert.ok(!relaysOk([{ name: "<script>", platform: "x", mcp_url: "https://a.example/mcp" }]));
  assert.ok(!relaysOk(new Array(7).fill({ name: "a", platform: "x", mcp_url: "https://a.example/mcp" })));
});
