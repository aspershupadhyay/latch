// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R13 (protocol 1.13): a phone's hello says whether Android's battery
// optimisation applies to Latch, and `diagnose` passes the fix on. The same
// check as the Rust gateway's e2e (servers/mcp/tests/e2e.rs), through the
// official MCP SDK client.
import { after, test } from "node:test";
import assert from "node:assert/strict";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore } from "../src/store.js";
import { startLocal } from "../src/local.js";

const ADMIN = "admin-token-diagnose-0123456789abcdef0";
const servers: Server[] = [];
after(() => servers.forEach((s) => s.close()));

async function phoneSays(battery: unknown) {
  const server = await startLocal(new Gateway(new MemoryStore(), configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN })), 0);
  servers.push(server);
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const post = async (path: string, body: unknown, token = ADMIN) => {
    const res = await fetch(base + path, { method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body) });
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  };
  const { code } = (await post("/v1/admin/pairings", { name: "Battery phone" })).body;
  const { token } = (await post("/v1/pair", { code, platform: "android", model: "Test" }, "")).body;
  const hello = await post("/v1/device/hello", {
    type: "hello", protocol: "1.13",
    device: { platform: "android", os_version: "15", model: "Test", app_version: "0.1.0" },
    capabilities: [], session: { expires_at_ms: Date.now() + 600_000, approve_every_action: false, paused: false },
    ...(battery !== undefined ? { battery_restricted: battery } : {}),
  }, token);
  const mcpToken = (await post("/v1/admin/clients", { name: "Diagnose" })).body.token;
  const client = new Client({ name: "latch-diagnose", version: "0.1.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { requestInit: { headers: { authorization: `Bearer ${mcpToken}` } } }));
  const r = await client.callTool({ name: "diagnose", arguments: {} });
  await client.close();
  return { hello: hello.status, text: (r.content as { text?: string }[]).map((c) => c.text ?? "").join("\n") };
}

test("diagnose names battery optimisation only when the phone reports it", async () => {
  const on = await phoneSays(true);
  assert.equal(on.hello, 200);
  assert.match(on.text, /battery optimisation is on for Latch.*Battery → Unrestricted/);
  for (const quiet of [false, undefined]) assert.doesNotMatch((await phoneSays(quiet)).text, /battery optimisation/);
  assert.equal((await phoneSays("yes")).hello, 422, "only true or false");
});
