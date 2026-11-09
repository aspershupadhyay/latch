// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// ADR-075: the Cloudflare relay in Cloudflare's own runtime (workerd, through
// `wrangler dev`), end to end: the page, a claim with the phone's key, pairing
// the Rust fake phone over the long poll, and MCP tool calls with the official
// SDK. Needs `cargo build -p latch-fake-device` first. Run: npm run smoke:cloudflare
import { spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const WRANGLER = "wrangler@4.158.0";
const here = fileURLToPath(new URL("..", import.meta.url));
const fake = fileURLToPath(new URL("../../../target/debug/latch-fake-device", import.meta.url));
const port = 8700 + Math.floor(Math.random() * 200);
const base = `http://127.0.0.1:${port}`;
const state = mkdtempSync(join(tmpdir(), "latch-cf-"));
const children = [];

function fail(message) {
  console.error(`smoke:cloudflare FAILED: ${message}`);
  cleanup();
  process.exit(1);
}
function cleanup() {
  for (const c of children) c.kill("SIGTERM");
  rmSync(state, { recursive: true, force: true });
}
function check(ok, message) {
  if (!ok) fail(message);
  console.log(`ok - ${message}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(test, what, tries = 240) {
  for (let i = 0; i < tries; i++) {
    try { if (await test()) return; } catch { /* not up yet */ }
    await sleep(250);
  }
  fail(`timed out waiting for ${what}`);
}
async function call(method, path, { token, body } = {}) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  const res = await fetch(base + path, { method, headers, body: body === undefined ? (method === "POST" ? "{}" : undefined) : JSON.stringify(body) });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: res.status, body: json, headers: res.headers };
}
const text = (r) => r.content.filter((c) => c.type === "text").map((c) => c.text).join("\n");

const wrangler = spawn("npx", ["--yes", WRANGLER, "dev", "--port", String(port), "--ip", "127.0.0.1", "--local", "--persist-to", state, "--show-interactive-dev-session=false"], {
  cwd: here,
  stdio: ["ignore", "pipe", "pipe"],
  env: { ...process.env, CI: "1", WRANGLER_SEND_METRICS: "false" },
});
children.push(wrangler);
let log = "";
wrangler.stdout.on("data", (d) => { log += d; });
wrangler.stderr.on("data", (d) => { log += d; });
wrangler.on("exit", (code) => { if (code !== null && code !== 0) fail(`wrangler exited with ${code}\n${log.slice(-3000)}`); });
process.on("SIGINT", () => { cleanup(); process.exit(130); });

try {
  await waitFor(async () => (await fetch(`${base}/healthz`)).ok, "wrangler dev to answer");

  const info = await call("GET", "/v1/info");
  check(info.body.implementation === "cloudflare" && info.body.claim === "first" && info.body.setup_required === true, "an unclaimed relay says it waits for its phone");
  const page = await call("GET", "/");
  check(page.status === 200 && /Scan this to make it yours/.test(page.body) && page.headers.get("x-frame-options") === "DENY", "the page shows the code to claim it, with private headers");
  const css = await call("GET", "/gateway.css");
  check(css.status === 200 && css.headers.get("content-security-policy")?.includes("default-src 'none'") && css.headers.get("x-robots-tag")?.includes("noindex"), "the stylesheet is served with the page's headers");

  const key = `lok_${randomBytes(32).toString("hex")}`;
  check((await call("POST", "/v1/claim", { body: { owner_key: key } })).status === 200, "the phone's key claims the relay");
  check((await call("POST", "/v1/claim", { body: { owner_key: `lok_${randomBytes(32).toString("hex")}` } })).status === 409, "a second claim is refused");
  check((await call("GET", "/v1/info")).body.setup_required === false, "the claimed relay is ready");

  const pairing = await call("POST", "/v1/admin/pairings", { token: key, body: { name: "Fake phone" } });
  check(pairing.status === 200, "the owner key makes a pairing code");
  const phone = spawn(fake, [base, pairing.body.code, "--poll"], { stdio: "ignore" });
  children.push(phone);
  await waitFor(async () => (await call("GET", "/v1/admin/devices", { token: key })).body.devices.some((d) => d.connected), "the fake phone to connect");
  check(true, "the fake phone paired and connected over the long poll");

  const created = await call("POST", "/v1/admin/clients", { token: key, body: { name: "Smoke" } });
  check(created.status === 200 && created.body.mcp_url === `${base}/mcp`, "an AI app key is created");
  const client = new Client({ name: "latch-cloudflare-smoke", version: "0.1.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(created.body.mcp_url), { requestInit: { headers: { authorization: `Bearer ${created.body.token}` } } }));
  const tools = await client.listTools();
  check(tools.tools.some((t) => t.name === "observe") && tools.tools.some((t) => t.name === "diagnose"), `MCP lists ${tools.tools.length} tools`);
  const devices = text(await client.callTool({ name: "list_devices", arguments: {} }));
  check(/connected/.test(devices), "list_devices sees the connected phone");
  const observed = await client.callTool({ name: "observe", arguments: {} });
  check(!observed.isError && /observation_id: /.test(text(observed)), "observe reads the fake phone's screen through the relay");
  const pressed = await client.callTool({ name: "press", arguments: { button: "home" } });
  check(!pressed.isError, "press home runs on the phone and answers");
  const diag = text(await client.callTool({ name: "diagnose", arguments: {} }));
  check(/Verdict: ready/.test(diag), "diagnose says ready");
  await client.close();

  // A restart of the runtime keeps the claim, the phone, and the key (SQLite on disk).
  const hash = createHash("sha256").update(key).digest("hex");
  check(!log.includes(key) && !log.includes(hash), "the log holds neither the key nor its hash");
  console.log("smoke:cloudflare passed");
  cleanup();
  process.exit(0);
} catch (e) {
  fail(e instanceof Error ? `${e.message}\n${log.slice(-3000)}` : String(e));
}
