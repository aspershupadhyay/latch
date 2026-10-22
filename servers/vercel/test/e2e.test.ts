// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// End to end: the Vercel gateway (as a local Node server with an in-memory
// store), the Rust fake phone over the HTTP long-poll transport, and the
// official MCP TypeScript SDK client with a console-created token.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore, type Store, UpstashStore } from "../src/store.js";
import { startLocal } from "../src/local.js";
import { isSealedKey } from "../src/sealed.js";

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

/** Every value written to the database, to check what it ever held (R20). */
const written: { key: string; value: string }[] = [];
function recording(inner: Store): Store {
  return new Proxy(inner, {
    get(target, prop, receiver) {
      const f = Reflect.get(target, prop, receiver);
      if (typeof f !== "function") return f;
      return (...args: unknown[]) => {
        if (prop === "set" || prop === "lpush") written.push({ key: args[0] as string, value: args[1] as string });
        if (prop === "batch") {
          for (const o of args[0] as { op: string; key: string; value?: string }[]) {
            if ((o.op === "set" || o.op === "lpush") && o.value !== undefined) written.push({ key: o.key, value: o.value });
          }
        }
        return f.apply(target, args);
      };
    },
  });
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
  server = await startLocal(new Gateway(recording(testStore()), config()), 0);
  const address = server.address();
  base = `http://127.0.0.1:${typeof address === "object" && address ? address.port : 0}`;
});

after(() => {
  phone?.kill();
  server?.close();
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

// Regression from the 2026-10-04 phone run, matching the Rust gateway: each
// re-pairing of the same phone left an offline entry behind.
test("pairing again replaces the offline entry of the same phone", async () => {
  const own = await startLocal(new Gateway(new MemoryStore(), config()), 0);
  const url = `http://127.0.0.1:${(own.address() as { port: number }).port}`;
  const admin = async (path: string, init: RequestInit = {}) => {
    const res = await fetch(url + path, { ...init, headers: { authorization: `Bearer ${ADMIN}`, "content-type": "application/json" } });
    return res.json();
  };
  const pair = async (name: string, model: string) => {
    const { code } = await admin("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name }) });
    const res = await fetch(`${url}/v1/pair`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code, platform: "android", model }) });
    assert.equal(res.status, 200);
    return (await res.json()).device_id as string;
  };
  const first = await pair("realme RMX3710", "RMX3710");
  const other = await pair("Redmi", "22041216I");
  const again = await pair("realme RMX3710", "RMX3710");
  const ids = ((await admin("/v1/admin/devices")).devices as { id: string }[]).map((d) => d.id);
  assert.deepEqual(ids.sort(), [other, again].sort());
  assert.ok(!ids.includes(first));
  own.close();
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
  assert.deepEqual(tools, ["list_devices", "diagnose", "observe", "tap", "type_text", "scroll_to", "wait_for", "scroll", "swipe", "pinch", "gesture", "press", "list_apps", "launch_app", "open_link", "device_status", "ask_owner", "get_activity", "finish_task", "phone_control", "adb",
    "list_files", "read_file", "get_file_link", "upload_link", "transfer_status", "write_file", "create_folder", "rename_file", "delete_file",
    "set_clipboard", "share_to_app", "save_skill", "list_skills", "run_skill", "run_steps", "delete_skill",
    "answer_approval"]);

  // Same text as the Rust gateway (servers/mcp/tests/e2e.rs).
  const diagnosis = text(await client.callTool({ name: "diagnose", arguments: {} }));
  assert.match(diagnosis, /: connected, app protocol /);
  assert.ok(diagnosis.endsWith("  Ready.\nVerdict: ready (1 phone usable).\n"), diagnosis);
  const unknown = await client.callTool({ name: "diagnose", arguments: { device_id: "d_nope" } });
  assert.ok(unknown.isError && /no phone d_nope is paired/.test(text(unknown)), text(unknown));

  const apps = await client.callTool({ name: "list_apps", arguments: { query: " CHAT " } });
  assert.match(text(apps), /^1 of 4 launchable apps on \S+ match "chat" /);
  assert.match(text(apps), /org\.latch\.demo\.chat/);
  assert.doesNotMatch(text(apps), /com\.android\.settings/);

  let screen = await client.callTool({ name: "observe", arguments: {} });
  assert.ok(!screen.isError, text(screen));
  const timing = (screen._meta as Record<string, { total_ms: number; phone_ms: number; store_ms: number }>)["latch/timing"]!;
  assert.ok(timing.total_ms >= timing.phone_ms && timing.phone_ms >= 0, JSON.stringify(timing));
  assert.ok(timing.store_ms >= 0 && timing.store_ms <= timing.total_ms, JSON.stringify(timing));
  assert.equal((screen.content as { type: string }[]).filter((c) => c.type === "image").length, 1);

  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(text(screen)), element_id: element(text(screen), "Settings") } });
  // Protocol 1.9 effect, same text as the Rust gateway: the app changed, then a tap that does nothing says so.
  assert.ok(text(screen).startsWith("Done; another app is now in front. The screen after the action:"), text(screen));
  const full = text(screen);
  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(full), element_id: element(full, "Display") } });
  assert.ok(text(screen).startsWith("Done, but the screen did not visibly change"), text(screen));
  // R5, same text as the Rust gateway: the changes since the full screen, and unchanged elements keep their ids.
  assert.ok(text(screen).includes(`Changes since observation ${obsId(full)}: elements not listed here are unchanged and keep their ids.\n(no element changed)\n`), text(screen));
  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(text(screen)), element_id: element(full, "Network & internet") } });
  assert.match(text(screen), /\[tap,checked\]/);
  // Protocol 1.16 (ADR-044), same text as the Rust gateway: state words and headings reach the AI.
  assert.ok(text(screen).includes('Switch "Wi-Fi" state="On"'), text(screen));
  assert.ok(text(screen).includes('TextView "Network & internet" @(40,100,1040,200) [heading]'), text(screen));
  const before = obsId(text(screen));
  screen = await client.callTool({ name: "tap", arguments: { observation_id: before, element_id: element(text(screen), "Wi-Fi") } });
  assert.match(text(screen), /\[tap,unchecked\]/);
  assert.ok(text(screen).includes('state="Off"'), text(screen));

  const stale = await client.callTool({ name: "tap", arguments: { observation_id: before, element_id: "n1" } });
  assert.ok(stale.isError);
  assert.match(text(stale), /stale_observation/);

  // One-call tools (protocol 1.3), with the same text as the Rust gateway.
  screen = await client.callTool({ name: "press", arguments: { button: "back" } });
  screen = await client.callTool({ name: "scroll_to", arguments: { observation_id: obsId(text(screen)), text: "about phone" } });
  assert.match(text(screen), /^Found "about phone"\. The screen after the action:/);
  assert.match(text(screen), /"About phone"/);
  const waited = await client.callTool({ name: "wait_for", arguments: { text: "Bluetooth", timeout_ms: 100 } });
  assert.match(text(waited), /^"Bluetooth" did not appear within 100 ms\. The screen:/);
  screen = await client.callTool({ name: "wait_for", arguments: { text: "battery" } });
  assert.match(text(screen), /^"battery" is on screen\./);

  // Full-access ADB (R27, ADR-046): arbitrary command, exit code, stderr,
  // stdin, and multi-frame output rejoined into one result — no per-command
  // approval or filter. The fake phone enables adb.shell (Capability::ALL).
  const model = text(await client.callTool({ name: "adb", arguments: { command: "getprop ro.product.model" } }));
  assert.match(model, /exit code 0/);
  assert.match(model, /Pixel 8/);
  const missing = text(await client.callTool({ name: "adb", arguments: { command: "nope --x" } }));
  assert.match(missing, /exit code 127/);
  assert.match(missing, /not found/);
  const piped = text(await client.callTool({ name: "adb", arguments: { command: "cat", stdin: "ping\n" } }));
  assert.match(piped, /ping/);
  const seqOut = text(await client.callTool({ name: "adb", arguments: { command: "seq 500" } }));
  assert.match(seqOut, /\n1\n/);
  assert.match(seqOut, /\n500\n/);
  assert.match(seqOut, /exit code 0/);
  // Protocol 1.18: binary output as base64; a picture comes back as an image.
  const shot = await client.callTool({ name: "adb", arguments: { command: "screencap -p", base64: true } });
  assert.match(text(shot), /image\/png/);
  assert.equal((shot.content as { type: string }[]).filter((c) => c.type === "image").length, 1);
  const encoded = text(await client.callTool({ name: "adb", arguments: { command: "echo hi", base64: true } }));
  assert.match(encoded, /--- stdout \(base64\) ---\naGkK/);

  // Protocol 1.18: any gesture; a two-finger rotate reaches the phone.
  const rotated = await client.callTool({ name: "gesture", arguments: { observation_id: obsId(text(screen)), strokes: [
    { points: [{ x: 400, y: 1200 }, { x: 450, y: 1100 }, { x: 540, y: 1050 }], duration_ms: 400 },
    { points: [{ x: 680, y: 1200 }, { x: 630, y: 1300 }, { x: 540, y: 1350 }], duration_ms: 400 },
  ] } });
  assert.ok(!rotated.isError, text(rotated));
  const malformed = await client.callTool({ name: "gesture", arguments: { observation_id: obsId(text(rotated)), strokes: "swipe up" } });
  assert.ok(malformed.isError && /strokes must be a list/.test(text(malformed)), text(malformed));

  screen = await client.callTool({ name: "launch_app", arguments: { package: "org.latch.demo.chat" } });
  screen = await client.callTool({ name: "type_text", arguments: { observation_id: obsId(text(screen)), element_id: "n1", text: "hello from vercel" } });
  screen = await client.callTool({ name: "tap", arguments: { observation_id: obsId(text(screen)), element_id: element(text(screen), "Send") } });
  assert.ok(!screen.isError, text(screen));
  assert.match(text(screen), /"hello from vercel"/);

  screen = await client.callTool({ name: "launch_app", arguments: { package: "org.latch.demo.login" } });
  const secret = await client.callTool({ name: "type_text", arguments: { observation_id: obsId(text(screen)), element_id: "n2", text: "hunter2" } });
  assert.match(text(secret), /sensitive_target/);
  // This form ignores Enter: the agent hears so, with the same text as the Rust gateway.
  // Protocol 1.5: the owner takes a step (log in) and the AI gets the answer.
  const asked = await client.callTool({ name: "ask_owner", arguments: { message: "Please log in, then tap Done." } });
  assert.match(text(asked), /^The owner says it's done\. The screen after the action:/);
  screen = asked;
  const ignored = await client.callTool({ name: "type_text", arguments: { observation_id: obsId(text(screen)), element_id: "n1", text: "alice", submit: true } });
  assert.match(text(ignored), /^Typed, but Enter did nothing visible/);

  // Protocol 1.6 (ADR-026): files move between the computer and the phone through links.
  const photos = await client.callTool({ name: "list_files", arguments: { location: "photos" } });
  assert.match(text(photos), /^2 of 2 items in your photos on \S+ \(names are untrusted content\):\n- f_\d+ "IMG_0002\.jpg" \(image, 13 B, image\/jpeg\)/);
  const folder = await client.callTool({ name: "create_folder", arguments: { name: "abc" } });
  const abc = /folder_id: (\S+)\)/.exec(text(folder))![1]!;
  // Protocol 1.7 (ADR-027): the computer runs the command from the answer, which
  // encrypts and uploads; the phone downloads, decrypts, and checks it itself.
  const work = mkdtempSync(join(tmpdir(), "latch-e2e-"));
  const big = Buffer.alloc(700_000, 7);
  writeFileSync(join(work, "clip.mp4"), big);
  const upload = text(await client.callTool({ name: "upload_link", arguments: {} }));
  assert.match(upload, /^Upload link for one file \(valid for 15 minutes, up to 4\.0 MB\)\. The file is encrypted on this computer/);
  const uploadId = /upload_id="([^"]+)"/.exec(upload)![1]!;
  // Async: the gateway under test answers curl from this same process.
  const shell = async (command: string) => (await promisify(execFile)("bash", ["-c", command], { cwd: work, encoding: "utf8" })).stdout;
  const printed = await shell(upload.split("\n").find((l) => l.startsWith('f="<path>"'))!.replace("<path>", "clip.mp4"));
  const sha256 = /([0-9a-f]{64})\s*$/.exec(printed)![1]!;
  assert.equal(sha256, createHash("sha256").update(big).digest("hex"));
  const blobUrl = /"(http\S+\/v1\/blobs\/[^"]+)"/.exec(upload)![1]!;
  assert.equal((await fetch(blobUrl, { method: "PUT", body: big })).status, 409, "a link is filled once");
  const stored = Buffer.from(await (await fetch(blobUrl)).arrayBuffer());
  assert.equal(stored.length, big.length);
  assert.notDeepEqual(stored, big, "the gateway only ever holds ciphertext");
  const noHash = await client.callTool({ name: "write_file", arguments: { location: "folder", folder_id: abc, name: "clip.mp4", upload_id: uploadId } });
  assert.match(text(noHash), /sha256 is required/);
  const saved = text(await client.callTool({ name: "write_file", arguments: { location: "folder", folder_id: abc, name: "clip.mp4", upload_id: uploadId, sha256 } }));
  assert.match(saved, /^Saved "clip\.mp4" to your Latch folder \(684 KB\)\. file_id: (\S+)\.$/);
  assert.equal((await fetch(blobUrl)).status, 404, "the stored copy is deleted once the phone has it");
  const again = await client.callTool({ name: "write_file", arguments: { location: "folder", name: "again.mp4", upload_id: uploadId, sha256 } });
  assert.match(text(again), /upload_id is unknown/);
  const clip = /file_id: (\S+)\./.exec(saved)![1]!;

  // A file that changed on the way is never kept.
  const tamper = text(await client.callTool({ name: "upload_link", arguments: {} }));
  const tamperUrl = /"(http\S+\/v1\/blobs\/[^"]+)"/.exec(tamper)![1]!;
  assert.equal((await fetch(tamperUrl, { method: "PUT", body: Buffer.from("not what was promised") })).status, 200);
  const refused = await client.callTool({
    name: "write_file",
    arguments: { location: "downloads", name: "tampered.txt", upload_id: /upload_id="([^"]+)"/.exec(tamper)![1]!, sha256 },
  });
  assert.match(text(refused), /did not match its sha256/);

  // Phone → computer: the command from the answer downloads, decrypts, and verifies.
  const link = text(await client.callTool({ name: "get_file_link", arguments: { file_id: clip } }));
  assert.match(link, /^Download link for "clip\.mp4" \(684 KB\), valid for 15 minutes\. The stored copy is encrypted/);
  rmSync(join(work, "clip.mp4"));
  assert.match(await shell(link.split("\n").find((l) => l.startsWith("curl "))!), /Saved and verified clip\.mp4/);
  assert.deepEqual(readFileSync(join(work, "clip.mp4")), big);
  const getUrl = /curl -fsSL "([^"]+)"/.exec(link)![1]!;
  const downloaded = await fetch(getUrl);
  assert.equal(downloaded.headers.get("content-type"), "application/octet-stream");
  assert.equal(downloaded.headers.get("content-security-policy"), "sandbox; default-src 'none'");
  assert.equal((await fetch(`${base}/v1/blobs/ltr_${"0".repeat(64)}`)).status, 404);
  rmSync(work, { recursive: true, force: true });

  // The clipboard takes a caption to paste.
  const copied = await client.callTool({ name: "set_clipboard", arguments: { text: "Hello from Latch" } });
  assert.match(text(copied), /^Copied 16 characters to the phone's clipboard\./);
  // Protocol 1.8: the phone's activity log, filtered by kind, and the end of a task.
  const activity = text(await client.callTool({ name: "get_activity", arguments: { limit: 2, kinds: ["file"] } }));
  assert.match(activity, /^Activity on the phone, newest first \(2 of \d+\)\./);
  assert.match(activity, / file by AI: Ran file\./);
  assert.doesNotMatch(activity, /clipboard/);
  const wrongKind = await client.callTool({ name: "get_activity", arguments: { kinds: ["photos"] } });
  assert.equal(wrongKind.isError, true);
  assert.match(text(await client.callTool({ name: "finish_task", arguments: { summary: "Copied a caption" } })), /^Done\. The cursor is gone/);
  // Roadmap R14: the latest task as a timeline, ending at finish_task (same text as the Rust gateway).
  const replay = text(await client.callTool({ name: "get_activity", arguments: { task: "latest" } }));
  assert.match(replay, /^Latest AI task on the phone, oldest first: finished, /);
  assert.match(replay, /\n- \+0\.0 s /);
  assert.ok(replay.endsWith(" task by AI: Ran task.done\n"), replay);
  const mixed = await client.callTool({ name: "get_activity", arguments: { task: "latest", kinds: ["file"] } });
  assert.ok(mixed.isError && /task cannot be combined/.test(text(mixed)), text(mixed));
  // Roadmap R16: phone controls (same texts as the Rust gateway).
  assert.equal(text(await client.callTool({ name: "phone_control", arguments: { control: "volume", percent: 40 } })), "Done: Set the media volume to 40%. The phone set step 6 of 15 (40%); its own volume slider may show a slightly different number.");
  const panel = await client.callTool({ name: "phone_control", arguments: { control: "panel", panel: "wifi" } });
  assert.ok(!panel.isError && text(panel).includes("app com.android.settings"), text(panel));
  for (const [args, says] of [
    [{ control: "brightness", percent: 101 }, "percent must be 0-100"],
    [{ control: "flashlight" }, "on must be true or false"],
    [{ control: "airplane_mode" }, "control must be"],
  ] as const) {
    const r = await client.callTool({ name: "phone_control", arguments: args });
    assert.ok(r.isError && text(r).includes(says), text(r));
  }
  // Roadmap R25 (protocol 1.15): more buttons, links, status, and controls (same texts as the Rust gateway).
  for (const button of ["split_screen", "dpad_down"]) {
    const r = await client.callTool({ name: "press", arguments: { button } });
    assert.ok(!r.isError && text(r).includes("observation_id"), text(r));
  }
  for (const button of ["power_off", "notifications"]) {
    const r = await client.callTool({ name: "press", arguments: { button } });
    assert.ok(r.isError && text(r).includes("button must be one of back, home"), text(r));
  }
  const status = text(await client.callTool({ name: "device_status", arguments: {} }));
  for (const line of ["- battery: 81%, not charging", "- network: Wi-Fi", "- free storage: 11.7 GB", "- lock screen: unlocked", "- screen: 1080x2400 px"]) {
    assert.ok(status.includes(line), `${line} in ${status}`);
  }
  const opened = await client.callTool({ name: "open_link", arguments: { url: "https://shop.example/item/42", package: "org.latch.demo.shop" } });
  assert.ok(!opened.isError && text(opened).includes("app org.latch.demo.shop"), text(opened));
  const fresh = await client.callTool({ name: "launch_app", arguments: { package: "org.latch.demo.chat", fresh: true } });
  assert.ok(!fresh.isError && text(fresh).includes("app org.latch.demo.chat"), text(fresh));
  for (const [url, says] of [["intent://x#Intent;end", "intent: links cannot be opened"], ["https://a.example/a b", "printable ASCII"]] as const) {
    const r = await client.callTool({ name: "open_link", arguments: { url, package: "org.latch.demo.shop" } });
    assert.ok(r.isError && text(r).includes(says), text(r));
  }
  for (const [args, says] of [
    [{ control: "ringer", mode: "vibrate" }, "Done: Set the ringer to vibrate."],
    [{ control: "do_not_disturb", on: true }, "Done: Turn Do Not Disturb on."],
    [{ control: "rotation", mode: "portrait" }, "Done: Lock the screen to portrait."],
    [{ control: "screen_timeout", seconds: 600 }, "Done: Set the screen timeout to 10 min."],
  ] as const) {
    assert.equal(text(await client.callTool({ name: "phone_control", arguments: args })), says);
  }
  const page = await client.callTool({ name: "phone_control", arguments: { control: "panel", panel: "battery" } });
  assert.ok(!page.isError && text(page).includes("observation_id"), text(page));
  const short = await client.callTool({ name: "phone_control", arguments: { control: "screen_timeout", seconds: 5 } });
  assert.ok(short.isError && text(short).includes("seconds must be between 15 and 1800"), text(short));
  assert.match(text(await client.callTool({ name: "device_status", arguments: {} })), /- do not disturb: on/);
  const note = text(await client.callTool({ name: "write_file", arguments: { location: "downloads", subfolder: "abc", name: "todo.txt", text: "milk" } }));
  assert.match(note, /^Saved "todo\.txt" to Downloads \("abc"\) \(4 B\)\./);
  const shared = await client.callTool({ name: "share_to_app", arguments: { package: "com.linkedin.android", file_ids: [clip], text: "New video" } });
  assert.match(text(shared), /^Opened com\.linkedin\.android's share screen with 1 file\. Finish the post or message there\. The screen after the action:/);
  assert.equal(text(await client.callTool({ name: "delete_file", arguments: { file_id: clip } })), "Deleted.");

  const audit = await api("/v1/admin/audit");
  const auditText = JSON.stringify(audit.body);
  assert.match(auditText, /input.tap/);
  // Protocol 1.2: actions bring their observation back in the same phone command,
  // so only the explicit observe call above sent ui.observe (waits are ui.wait).
  assert.equal((audit.body.events as { command: string }[]).filter((e) => e.command === "ui.observe").length, 1, auditText);
  assert.doesNotMatch(auditText, /hello from vercel|Wi-Fi|hunter2|milk|todo|clip\.mp4/, "audit must not contain content");

  // ADR-031, same flow as the Rust gateway: save once, run with different carts, hand back what differs.
  const skill = {
    name: "order_groceries",
    description: "Add items to the cart in the shop app",
    params: [{ name: "items", type: "list", fields: ["name", "qty"] }],
    steps: [
      { do: "launch_app", package: "org.latch.demo.shop" },
      { for_each: "items", steps: [
        { do: "type", target: "Search products", text: "{item.name}", submit: true },
        { do: "tap", target: "ADD || +", near: "{item.name}" },
        { do: "tap", target: "+", near: "{item.name}", repeat: "{item.qty-1}" },
      ] },
      { do: "tap", target: "Not now", optional: true },
    ],
  };
  assert.match(text(await client.callTool({ name: "save_skill", arguments: { skill } })), /^Saved skill "order_groceries" \(5 steps as written; a run numbers every for_each step once per item/);
  assert.match(text(await client.callTool({ name: "list_skills", arguments: {} })), /items: list of \{name, qty\}/);
  let ran = text(await client.callTool({ name: "run_skill", arguments: { name: "order_groceries", params: { items: [{ name: "White bread", qty: 1 }, { name: "Paneer", qty: 3 }] } } }));
  assert.match(ran, /all 8 steps done/);
  assert.match(ran, /"Cart \(4\)"/);
  assert.match(ran, /\[n\d+\] TextView "3"/, "three paneer");
  ran = text(await client.callTool({ name: "run_skill", arguments: { name: "order_groceries", params: { items: [{ name: "Tomato", qty: 2 }, { name: "Milk", qty: 1 }] } } }));
  assert.match(ran, /all 8 steps done/);
  assert.match(ran, /"Cart \(7\)"/);
  ran = text(await client.callTool({ name: "run_skill", arguments: { name: "order_groceries", params: { items: [{ name: "Mango", qty: 1 }, { name: "Milk", qty: 1 }] } } }));
  assert.match(ran, /stopped: could not find "ADD \|\| \+" near "Mango"/);
  assert.match(ran, /from_step 4/);
  assert.match(ran, /observation_id/);
  ran = text(await client.callTool({ name: "run_skill", arguments: { name: "order_groceries", params: { items: [{ name: "Mango", qty: 1 }, { name: "Milk", qty: 1 }] }, from_step: 5 } }));
  assert.match(ran, /all 8 steps done/);
  assert.match(ran, /"Cart \(8\)"/);
  const badRun = await client.callTool({ name: "run_skill", arguments: { name: "order_groceries", params: { items: [{ name: "Milk", qty: "two" }] } } });
  assert.equal(badRun.isError, true);
  assert.match(text(badRun), /is not a whole number/);
  assert.equal(text(await client.callTool({ name: "delete_skill", arguments: { name: "order_groceries" } })), 'Deleted the skill "order_groceries".');

  // Turbo fixes 3 and 7 (R68): tap by words, near picks the row, repeat taps a quantity, in one call.
  assert.match(text(await client.callTool({ name: "run_steps", arguments: { steps: [{ do: "type", target: "Search products", text: "bread", submit: true }] } })), /all 1 steps done/);
  const tapped = text(await client.callTool({ name: "tap", arguments: { text: "+", near: "White bread", repeat: 2 } }));
  assert.match(tapped, /tap "\+" near "White bread" ×2/);
  assert.match(tapped, /"Cart \(10\)"/);
  const idRepeat = await client.callTool({ name: "tap", arguments: { element_id: "n1", repeat: 2, observation_id: "o_1" } });
  assert.ok(idRepeat.isError && /near and repeat go with text/.test(text(idRepeat)), text(idRepeat));

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

// ADR-048: the phone pressed Home, then lost the answer with its connection and
// reconnected. The gateway sends the same command again; the phone answers from
// memory, so the AI gets its answer and Home ran exactly once.
test("a lost answer arrives after a reconnect and the action runs once (R29)", async () => {
  const pairing = await api("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: "Flaky phone" }) });
  const flaky = spawn(FAKE, [base, pairing.body.code, "--poll", "--lose-answer", "nav.global"], { stdio: "ignore" });
  try {
    await waitFor(async () => (await api("/v1/admin/devices")).body.devices.some((d: { connected: boolean }) => d.connected), "phone to connect");
    const created = await api("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Reconnect test" }) });
    const client = new Client({ name: "latch-vercel-reconnect", version: "0.1.0" });
    await client.connect(new StreamableHTTPClientTransport(new URL(created.body.mcp_url), {
      requestInit: { headers: { authorization: `Bearer ${created.body.token}` } },
    }));
    const pressed = await client.callTool({ name: "press", arguments: { button: "home" } });
    assert.ok(!pressed.isError, text(pressed));
    const log = text(await client.callTool({ name: "get_activity", arguments: { limit: 50 } }));
    assert.equal(log.match(/Ran nav\.global/g)?.length, 1, log);
    assert.equal(flaky.exitCode, null, "the phone reconnected and is still serving");
    await client.close();
  } finally {
    flaky.kill();
  }
});

// ADR-049: a job starts on the phone, keeps running between calls, and is
// collected later with its exit code; it can be cancelled. Same text as Rust.
test("an adb job starts, runs on the phone, and is collected later (R29)", async () => {
  const pairing = await api("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: "Job phone" }) });
  const worker = spawn(FAKE, [base, pairing.body.code, "--poll"], { stdio: "ignore" });
  try {
    await waitFor(async () => (await api("/v1/admin/devices")).body.devices.some((d: { connected: boolean; name: string }) => d.connected && d.name === "Job phone"), "phone to connect");
    const device = (await api("/v1/admin/devices")).body.devices.find((d: { connected: boolean; name: string }) => d.connected && d.name === "Job phone").id as string;
    const created = await api("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Job test" }) });
    const client = new Client({ name: "latch-vercel-jobs", version: "0.1.0" });
    await client.connect(new StreamableHTTPClientTransport(new URL(created.body.mcp_url), {
      requestInit: { headers: { authorization: `Bearer ${created.body.token}` } },
    }));
    const adb = async (args: Record<string, unknown>) => client.callTool({ name: "adb", arguments: { device_id: device, ...args } });
    const started = text(await adb({ command: "sleep 1; echo built", background: true }));
    const job = started.split(" ")[2]!;
    assert.ok(started.startsWith(`Started job ${job} on the phone.`), started);
    assert.ok(text(await adb({ job })).startsWith(`job ${job} still running`));
    const done = text(await adb({ job, wait_ms: 5000 }));
    assert.ok(done.startsWith(`job ${job} finished, exit code 0`), done);
    assert.match(done, /--- stdout ---\nbuilt\n/);
    const gone = await adb({ job });
    assert.ok(gone.isError && /no such adb job/.test(text(gone)), text(gone));
    const long = text(await adb({ command: "sleep 60; echo never", background: true })).split(" ")[2]!;
    assert.ok(text(await adb({ job: long, cancel: true })).startsWith(`job ${long} cancelled`));
    const both = await adb({ command: "id", job });
    assert.ok(both.isError && /exactly one of command, continue_from, or job/.test(text(both)), text(both));
    await client.close();
  } finally {
    worker.kill();
  }
});

test("screen content and typed text reach the database only sealed (R20)", () => {
  const content = written.filter((w) => isSealedKey(w.key));
  assert.ok(content.some((w) => w.key.startsWith("latch:obs:")) && content.some((w) => w.key.startsWith("latch:q:")), "the session above wrote screens and commands");
  for (const w of content) assert.match(w.value, /^s1:[A-Za-z0-9+/=]+$/, w.key);
  const everything = written.map((w) => w.value).join("\n");
  assert.doesNotMatch(everything, /Wi-Fi|hello from vercel|hunter2|alice/, "no screen text or typed text in any stored value");
});
