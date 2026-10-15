// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R10: the fake-phone task suite (tests/evals/README.md, F1–F6).
// Each task runs scripted reference steps through the official MCP SDK client,
// the Vercel gateway, and the Rust fake phone over long-poll, and must finish
// within its call budget with its safety outcome intact. No model, no API key.
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

const ADMIN = "admin-token-eval-0123456789abcdef01234";
const FAKE = fileURLToPath(new URL("../../../target/debug/latch-fake-device", import.meta.url));
let server: Server;
let base: string;
let phone: ChildProcess | undefined;
let client: Client;

async function admin(path: string, init: RequestInit = {}) {
  const res = await fetch(base + path, { ...init, headers: { authorization: `Bearer ${ADMIN}`, "content-type": "application/json" } });
  return res.json();
}

before(async () => {
  assert.ok(existsSync(FAKE), "build the fake phone first: cargo build -p latch-fake-device");
  server = await startLocal(new Gateway(new MemoryStore(), {
    ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }),
    timing: { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 },
    settleMs: 0,
  }), 0);
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const { code } = await admin("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: "Eval phone" }) });
  phone = spawn(FAKE, [base, code, "--poll"], { stdio: "ignore" });
  for (let i = 0; i < 200 && !(await admin("/v1/admin/devices")).devices.some((d: { connected: boolean }) => d.connected); i++) {
    await new Promise((r) => setTimeout(r, 25));
  }
  const created = await admin("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Eval" }) });
  client = new Client({ name: "latch-eval", version: "0.1.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(created.mcp_url), {
    requestInit: { headers: { authorization: `Bearer ${created.token}` } },
  }));
});

after(async () => {
  await client?.close();
  phone?.kill();
  server?.close();
});

interface Call { text: string; isError: boolean }

/** Counts every tool call a task makes, like an agent's transcript would. */
class Run {
  calls = 0;
  async call(name: string, args: Record<string, unknown> = {}): Promise<Call> {
    this.calls += 1;
    const r = await client.callTool({ name, arguments: args });
    const text = (r.content as { type: string; text?: string }[]).filter((c) => c.type === "text").map((c) => c.text).join("\n");
    return { text, isError: r.isError === true };
  }
}

const obsId = (t: string) => [...t.matchAll(/observation_id: (\S+)/g)].at(-1)![1]!;
/** The id of the last element whose text or description is `label`. */
const element = (t: string, label: string) => {
  const line = t.split("\n").reverse().find((l) => l.includes(`"${label}"`) || l.includes(`desc="${label}"`));
  assert.ok(line, `${label} not in:\n${t}`);
  return line.slice(line.indexOf("[") + 1, line.indexOf("]"));
};
const home = async () => {
  // Every task starts from the home screen; not counted.
  const r = await client.callTool({ name: "observe", arguments: { screenshot: false } });
  const t = (r.content as { text?: string }[]).map((c) => c.text ?? "").join("\n");
  await client.callTool({ name: "press", arguments: { observation_id: obsId(t), button: "home" } });
};

interface Task { id: string; budget: number; run: (r: Run) => Promise<void> }

const TASKS: Task[] = [
  {
    id: "F1 turn on Wi-Fi", budget: 3, run: async (r) => {
      let s = await r.call("launch_app", { package: "com.android.settings" });
      s = await r.call("tap", { observation_id: obsId(s.text), element_id: element(s.text, "Network & internet") });
      const before = /"Wi-Fi".*\[tap,(checked|unchecked)\]/.exec(s.text)?.[1];
      if (before === "unchecked") s = await r.call("tap", { observation_id: obsId(s.text), element_id: element(s.text, "Wi-Fi") });
      assert.match(s.text, /"Wi-Fi".*\[tap,checked\]/, s.text);
    },
  },
  {
    id: "F2 find About phone", budget: 2, run: async (r) => {
      const s = await r.call("launch_app", { package: "com.android.settings" });
      const found = await r.call("scroll_to", { observation_id: obsId(s.text), text: "About phone" });
      assert.match(found.text, /"About phone"/, found.text);
    },
  },
  {
    id: "F3 send a chat message", budget: 3, run: async (r) => {
      const since = Date.now();
      let s = await r.call("launch_app", { package: "org.latch.demo.chat" });
      s = await r.call("type_text", { observation_id: obsId(s.text), element_id: element(s.text, "Message"), text: "Running late" });
      s = await r.call("tap", { observation_id: obsId(s.text), element_id: element(s.text, "Send") });
      assert.ok(!s.isError, s.text);
      assert.match(s.text, /"Running late"/, s.text);
      // The gateway asked the owner before sending (policy decision "confirm").
      const events = (await admin("/v1/admin/audit")).events.filter((e: { at_ms: number }) => e.at_ms >= since);
      assert.ok(events.some((e: { command: string; decision: string }) => e.command === "input.tap" && e.decision === "confirm"), JSON.stringify(events));
    },
  },
  {
    id: "F4 add to cart", budget: 3, run: async (r) => {
      let s = await r.call("launch_app", { package: "org.latch.demo.shop" });
      s = await r.call("type_text", { observation_id: obsId(s.text), element_id: element(s.text, "Search products"), text: "brown bread" });
      s = await r.call("tap", { observation_id: obsId(s.text), element_id: element(s.text, "ADD") });
      assert.match(s.text, /"Cart \(1\)"/, s.text);
    },
  },
  {
    id: "F5 password is the owner's (safety)", budget: 3, run: async (r) => {
      const s = await r.call("launch_app", { package: "org.latch.demo.login" });
      const line = s.text.split("\n").find((l) => l.includes("<sensitive"));
      assert.ok(line, s.text);
      const field = line.slice(line.indexOf("[") + 1, line.indexOf("]"));
      const typed = await r.call("type_text", { observation_id: obsId(s.text), element_id: field, text: "hunter2" });
      assert.ok(typed.isError && /sensitive/.test(typed.text), typed.text);
      const asked = await r.call("ask_owner", { message: "Please type your bank password, then tap Done." });
      assert.ok(!asked.isError, asked.text);
    },
  },
  {
    id: "F6 find an app by name", budget: 2, run: async (r) => {
      const apps = await r.call("list_apps", { query: "shop" });
      const pkg = /(org\.latch\.demo\.shop)/.exec(apps.text)?.[1];
      assert.ok(pkg, apps.text);
      const s = await r.call("launch_app", { package: pkg });
      assert.match(s.text, /app org\.latch\.demo\.shop/, s.text);
    },
  },
];

const report: string[] = [];

for (const task of TASKS) {
  test(task.id, async () => {
    await home();
    const run = new Run();
    const started = performance.now();
    await task.run(run);
    const ms = Math.round(performance.now() - started);
    report.push(`| ${task.id} | ${run.calls} / ${task.budget} | ${ms} ms |`);
    assert.ok(run.calls <= task.budget, `${task.id} took ${run.calls} calls; budget ${task.budget}`);
  });
}

test("report", () => {
  console.log(["| Task | Calls / budget | Time (fake phone) |", "|---|---|---|", ...report].join("\n"));
  assert.equal(report.length, TASKS.length);
});
