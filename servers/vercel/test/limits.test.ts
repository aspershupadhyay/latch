// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R18: the owner limits an AI app to some phone apps, and each AI app
// has its own budget of tool calls per minute. The same checks as the Rust
// gateway's e2e (servers/mcp/tests/e2e.rs), through the official MCP SDK
// client, the Vercel gateway, and the Rust fake phone over long-poll.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore } from "../src/store.js";
import { startLocal } from "../src/local.js";

const ADMIN = "admin-token-limits-0123456789abcdef012";
const FAKE = fileURLToPath(new URL("../../../target/debug/latch-fake-device", import.meta.url));
const servers: Server[] = [];
let phone: ChildProcess | undefined;

async function gateway(callsPerMinute?: number) {
  const server = await startLocal(new Gateway(new MemoryStore(), {
    ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }),
    timing: { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 },
    settleMs: 0,
    ...(callsPerMinute !== undefined ? { callsPerMinute } : {}),
  }), 0);
  servers.push(server);
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const admin = async (path: string, init: RequestInit = {}) => {
    const res = await fetch(base + path, { ...init, headers: { authorization: `Bearer ${ADMIN}`, "content-type": "application/json" } });
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  };
  const connect = async (token: string) => {
    const client = new Client({ name: "latch-limits", version: "0.1.0" });
    await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { requestInit: { headers: { authorization: `Bearer ${token}` } } }));
    return async (name: string, args: Record<string, unknown> = {}) => {
      const r = await client.callTool({ name, arguments: args });
      return { isError: r.isError === true, text: (r.content as { text?: string }[]).map((c) => c.text ?? "").join("\n") };
    };
  };
  return { base, admin, connect };
}

let main: Awaited<ReturnType<typeof gateway>>;

before(async () => {
  assert.ok(existsSync(FAKE), "build the fake phone first: cargo build -p latch-fake-device");
  main = await gateway();
  const { code } = (await main.admin("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: "Limits phone" }) })).body;
  phone = spawn(FAKE, [main.base, code, "--poll"], { stdio: "ignore" });
  for (let i = 0; i < 200 && !(await main.admin("/v1/admin/devices")).body.devices.some((d: { connected: boolean }) => d.connected); i++) {
    await new Promise((r) => setTimeout(r, 25));
  }
});

after(() => {
  phone?.kill();
  for (const s of servers) s.close();
});

test("an AI app limited to some apps cannot use or see others", async () => {
  const created = (await main.admin("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Settings helper", apps: ["com.android.settings"] }) })).body;
  const call = await main.connect(created.token);

  // Inside its limit: works as usual.
  const settings = await call("launch_app", { package: "com.android.settings" });
  assert.ok(!settings.isError && settings.text.includes("app com.android.settings"), settings.text);
  // Outside: refused before the phone is asked.
  const chat = await call("launch_app", { package: "org.latch.demo.chat" });
  assert.ok(chat.isError && /policy_refused/.test(chat.text) && chat.text.includes("org.latch.demo.chat"), chat.text);
  // Going home is allowed, but the home screen is not shown to it.
  const home = await call("press", { button: "home" });
  assert.ok(home.isError && home.text.includes("not shown") && !home.text.includes("[n1]"), home.text);
  const look = await call("observe", { screenshot: false });
  assert.ok(look.isError && look.text.includes("not shown") && !look.text.includes("[n1]"), look.text);

  // The owner sees the limit; bad lists are refused; lifting it restores the phone's own rules.
  const listed = (await main.admin("/v1/admin/clients")).body.clients.find((c: { id: string }) => c.id === created.id);
  assert.deepEqual(listed.apps, ["com.android.settings"]);
  assert.equal((await main.admin(`/v1/admin/clients/${created.id}`, { method: "PATCH", body: JSON.stringify({ apps: ["bad package!"] }) })).status, 400);
  assert.equal((await main.admin(`/v1/admin/clients/${created.id}`, { method: "PATCH", body: JSON.stringify({ apps: null }) })).status, 204);
  const again = await call("launch_app", { package: "org.latch.demo.chat" });
  assert.ok(!again.isError, again.text);
});

test("each AI app has its own budget of calls", async () => {
  const busy = await gateway(3);
  const first = await busy.connect((await busy.admin("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Busy app" }) })).body.token);
  for (let i = 0; i < 3; i++) assert.match((await first("list_devices")).text, /^Latch gateway/);
  const over = await first("list_devices");
  assert.ok(over.isError && over.text.startsWith("rate_limited: this AI app made more than 3 tool calls in a minute."), over.text);
  // Another AI app is not slowed down by the first.
  const second = await busy.connect((await busy.admin("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Calm app" }) })).body.token);
  assert.match((await second("list_devices")).text, /^Latch gateway/);
});
