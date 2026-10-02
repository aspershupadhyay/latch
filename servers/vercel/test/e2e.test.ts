// End to end: the Vercel gateway (as a local Node server with an in-memory
// store), the Rust fake phone over the HTTP long-poll transport, and the
// official MCP TypeScript SDK client with a console-created token.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore, type Store, UpstashStore } from "../src/store.js";
import { startLocal } from "../src/local.js";

const ADMIN = "admin-token-0123456789abcdef0123456789";
const FAKE = fileURLToPath(new URL("../../../target/debug/latch-fake-device", import.meta.url));
let server: Server;
let base: string;
let phone: ChildProcess | undefined;

const config = () => ({
  ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }),
  timing: { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 },
  settleMs: 0,
});

async function api(path: string, init: RequestInit = {}, token = ADMIN) {
  const res = await fetch(base + path, { ...init, headers: { authorization: `Bearer ${token}`, "content-type": "application/json", ...(init.headers ?? {}) } });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

// Set LATCH_TEST_UPSTASH_URL and _TOKEN to run against a real Upstash-compatible REST endpoint.
function testStore(): Store {
  const url = process.env.LATCH_TEST_UPSTASH_URL;
  const token = process.env.LATCH_TEST_UPSTASH_TOKEN;
  return url && token ? new UpstashStore(url, token) : new MemoryStore();
}

before(async () => {
  assert.ok(existsSync(FAKE), "build the fake phone first: cargo build -p latch-fake-device");
  if (process.env.LATCH_TEST_UPSTASH_URL) {
    // Only ever point this at a throwaway test database.
    await fetch(process.env.LATCH_TEST_UPSTASH_URL, {
      method: "POST",
      headers: { authorization: `Bearer ${process.env.LATCH_TEST_UPSTASH_TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify(["FLUSHDB"]),
    });
  }
  server = await startLocal(new Gateway(testStore(), config()), 0);
  const address = server.address();
  base = `http://127.0.0.1:${typeof address === "object" && address ? address.port : 0}`;
});

after(() => {
  phone?.kill();
  server.close();
});

async function waitFor(check: () => Promise<boolean>, what: string) {
  for (let i = 0; i < 200; i++) {
    if (await check()) return;
    await new Promise((r) => setTimeout(r, 25));
  }
  assert.fail(`timed out waiting for ${what}`);
}

const text = (r: Record<string, unknown>) => (r.content as { type: string; text?: string }[]).filter((c) => c.type === "text").map((c) => c.text).join("\n");
const obsId = (t: string) => [...t.matchAll(/observation_id: (\S+)/g)].at(-1)![1]!;
const element = (t: string, label: string) => {
  const line = t.split("\n").reverse().find((l) => l.includes(`"${label}"`));
  assert.ok(line, `${label} not in:\n${t}`);
  return line.slice(line.indexOf("[") + 1, line.indexOf("]"));
};

test("discovery and setup", async () => {
  const info = await (await fetch(`${base}/v1/info`)).json();
  assert.equal(info.implementation, "vercel");
  assert.deepEqual(info.transports, ["poll"]);
  assert.equal(info.setup_required, false);

  const unconfigured = await startLocal(new Gateway(new MemoryStore(), { ...config(), adminToken: undefined }), 0);
  const port = (unconfigured.address() as { port: number }).port;
  assert.equal((await (await fetch(`http://127.0.0.1:${port}/v1/info`)).json()).setup_required, true);
  assert.equal((await fetch(`http://127.0.0.1:${port}/v1/admin/devices`, { headers: { authorization: `Bearer ${ADMIN}` } })).status, 503);
  unconfigured.close();
});

test("an MCP client drives a phone through the Vercel gateway", async () => {
  const pairing = await api("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: "Fake phone" }) });
  assert.equal(pairing.status, 200);
  phone = spawn(FAKE, [base, pairing.body.code, "--poll"], { stdio: "ignore" });
  await waitFor(async () => (await api("/v1/admin/devices")).body.devices.some((d: { connected: boolean }) => d.connected), "phone to connect");

  const created = await api("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "SDK test" }) });
  assert.equal(created.status, 200);
  assert.equal(created.body.mcp_url, `${base}/mcp`);

  // Without a client token, nothing.
  assert.equal((await fetch(`${base}/mcp`, { method: "POST", body: "{}" })).status, 401);
  assert.equal((await api("/mcp", { method: "POST", body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "ping" }) })).status, 401, "admin token is not an MCP token");

  const client = new Client({ name: "latch-vercel-e2e", version: "0.1.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(created.body.mcp_url), {
    requestInit: { headers: { authorization: `Bearer ${created.body.token}` } },
  }));
  const tools = (await client.listTools()).tools.map((t) => t.name);
  assert.deepEqual(tools, ["list_devices", "observe", "tap", "type_text", "scroll", "swipe", "press", "list_apps", "launch_app"]);

  // Same text as the Rust gateway (servers/mcp/tests/e2e.rs).
  const apps = await client.callTool({ name: "list_apps", arguments: { query: " CHAT " } });
  assert.match(text(apps), /^1 of 3 launchable apps on \S+ match "chat" /);
  assert.match(text(apps), /org\.latch\.demo\.chat/);
  assert.doesNotMatch(text(apps), /com\.android\.settings/);

  let screen = await client.callTool({ name: "observe", arguments: {} });
  assert.ok(!screen.isError, text(screen));
  const timing = (screen._meta as Record<string, { total_ms: number; phone_ms: number }>)["latch/timing"]!;
  assert.ok(timing.total_ms >= timing.phone_ms && timing.phone_ms >= 0, JSON.stringify(timing));
  assert.equal((screen.content as { type: string }[]).filter((c) => c.type === "image").length, 1);

  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(text(screen)), element_id: element(text(screen), "Settings") } });
  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(text(screen)), element_id: element(text(screen), "Network & internet") } });
  assert.match(text(screen), /\[tap,checked\]/);
  const before = obsId(text(screen));
  screen = await client.callTool({ name: "tap", arguments: { observation_id: before, element_id: element(text(screen), "Wi-Fi") } });
  assert.match(text(screen), /\[tap,unchecked\]/);

  const stale = await client.callTool({ name: "tap", arguments: { observation_id: before, element_id: "n1" } });
  assert.ok(stale.isError);
  assert.match(text(stale), /stale_observation/);

  screen = await client.callTool({ name: "launch_app", arguments: { package: "org.latch.demo.chat" } });
  screen = await client.callTool({ name: "type_text", arguments: { observation_id: obsId(text(screen)), element_id: "n1", text: "hello from vercel" } });
  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(text(screen)), element_id: element(text(screen), "Send") } });
  assert.ok(!screen.isError, text(screen));
  assert.match(text(screen), /"hello from vercel"/);

  screen = await client.callTool({ name: "launch_app", arguments: { package: "org.latch.demo.login" } });
  const secret = await client.callTool({ name: "type_text", arguments: { observation_id: obsId(text(screen)), element_id: "n2", text: "hunter2" } });
  assert.match(text(secret), /sensitive_target/);

  const audit = await api("/v1/admin/audit");
  const auditText = JSON.stringify(audit.body);
  assert.match(auditText, /input.tap/);
  assert.doesNotMatch(auditText, /hello from vercel|Wi-Fi|hunter2/, "audit must not contain content");

  // Revoke: the phone learns on its next poll and exits; tools report it gone.
  const deviceId = (await api("/v1/admin/devices")).body.devices[0].id;
  assert.equal((await api(`/v1/admin/devices/${deviceId}`, { method: "DELETE" })).status, 204);
  await waitFor(async () => phone!.exitCode !== null, "phone to notice revocation");
  const gone = await client.callTool({ name: "observe", arguments: {} });
  assert.match(text(gone), /device_unavailable/);

  // Secret-link form for clients that accept only a URL; the admin token never works there.
  const ping = JSON.stringify({ jsonrpc: "2.0", id: 1, method: "ping" });
  assert.equal((await fetch(`${base}/mcp/${created.body.token}`, { method: "POST", body: ping })).status, 200);
  assert.equal((await fetch(`${base}/mcp/${ADMIN}`, { method: "POST", body: ping })).status, 401);

  // Revoking the client cuts MCP access.
  assert.equal((await api(`/v1/admin/clients/${created.body.id}`, { method: "DELETE" })).status, 204);
  await assert.rejects(client.listTools());
  assert.equal((await fetch(`${base}/mcp/${created.body.token}`, { method: "POST", body: ping })).status, 401);
  await client.close();
});
