// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R23 (protocol 1.14, ADR-041): with fast transfers on, the commands
// in upload_link and get_file_link move a file straight between this computer
// and the phone on the same network, and fall back to the gateway's link.
// Runs the real commands (bash, openssl, curl) against the fake phone, whose
// "Wi-Fi" address is a listener on 127.0.0.1. Same texts as the Rust gateway.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { execFile, spawn, type ChildProcess } from "node:child_process";
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
import { MemoryStore } from "../src/store.js";
import { startLocal } from "../src/local.js";
import { isLocalSlotUrl } from "../src/protocol.js";

const ADMIN = "admin-token-0123456789abcdef0123456789";
const FAKE = fileURLToPath(new URL("../../../target/debug/latch-fake-device", import.meta.url));
let server: Server;
let base: string;
let phone: ChildProcess | undefined;
let work: string;

before(async () => {
  assert.ok(existsSync(FAKE), "build the fake phone first: cargo build -p latch-fake-device");
  const config = { ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }), timing: { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 }, settleMs: 0 };
  server = await startLocal(new Gateway(new MemoryStore(), config), 0);
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  work = mkdtempSync(join(tmpdir(), "latch-wifi-"));
});

after(() => {
  phone?.kill();
  server.close();
  rmSync(work, { recursive: true, force: true });
});

async function api(path: string, init: RequestInit = {}) {
  const res = await fetch(base + path, { ...init, headers: { authorization: `Bearer ${ADMIN}`, "content-type": "application/json" } });
  return res.json();
}

const text = (r: Record<string, unknown>) => (r.content as { type: string; text?: string }[]).filter((c) => c.type === "text").map((c) => c.text).join("\n");
// Async: the gateway under test answers curl from this same process.
const shell = async (command: string) => (await promisify(execFile)("bash", ["-c", command], { cwd: work, encoding: "utf8" })).stdout;
const wifiUrl = (t: string) => {
  const url = [...t.matchAll(/"(http:\/\/127\.0\.0\.1:\d+\/[0-9a-f]{64})"/g)].map((m) => m[1]!)[0];
  assert.ok(url, t);
  return url;
};

test("same-Wi-Fi addresses must be private http addresses", () => {
  const path = "ab".repeat(32);
  for (const ok of ["192.168.1.20:41234", "10.0.0.7:1024", "172.20.3.4:65535", "127.0.0.1:5000", "169.254.9.9:8000"]) {
    assert.ok(isLocalSlotUrl(`http://${ok}/${path}`), ok);
  }
  for (const bad of [
    `https://192.168.1.20:41234/${path}`, `http://8.8.8.8:41234/${path}`, `http://example.com:41234/${path}`,
    `http://192.168.1.20:80/${path}`, `http://192.168.1.20/${path}`, "http://192.168.1.20:41234/short",
    `http://192.168.1.20:41234/${"AB".repeat(32)}`, `http://[fe80::1]:41234/${path}`, `http://192.168.1.20:+4123/${path}`,
    `http://192.168.1.20:01234/${path}`, `http://192.168.01.20:41234/${path}`, `http://172.32.0.1:41234/${path}`,
  ]) {
    assert.ok(!isLocalSlotUrl(bad), bad);
  }
});

test("files go straight between the computer and the phone on the same Wi-Fi", async () => {
  const { code } = await api("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: "Fake phone" }) });
  phone = spawn(FAKE, [base, code, "--poll", "--local-transfers"], { stdio: "ignore" });
  for (let i = 0; i < 200 && !(await api("/v1/admin/devices")).devices.some((d: { connected: boolean }) => d.connected); i++) {
    await new Promise((r) => setTimeout(r, 25));
  }
  const created = await api("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "Wi-Fi test" }) });
  const client = new Client({ name: "latch-wifi-test", version: "0.1.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(created.mcp_url), {
    requestInit: { headers: { authorization: `Bearer ${created.token}` } },
  }));
  const call = async (name: string, args: Record<string, unknown>) => client.callTool({ name, arguments: args });
  const run = (answer: string, path: string) => shell(answer.split("\n").find((l) => l.startsWith('f="<path>"'))!.replace("<path>", path));

  // Computer → phone: the command reaches the phone's address; the gateway never holds the file.
  const plain = Buffer.alloc(300_000, 9);
  writeFileSync(join(work, "clip.mp4"), plain);
  const upload = text(await call("upload_link", {}));
  assert.match(upload, /On the phone's Wi-Fi it goes straight to the phone; from anywhere else, through the gateway\./);
  const blobUrl = /"(http\S+\/v1\/blobs\/[^"]+)"/.exec(upload)![1]!;
  const printed = await run(upload, "clip.mp4");
  const sha256 = /([0-9a-f]{64})\s*$/.exec(printed)![1]!;
  assert.equal(sha256, createHash("sha256").update(plain).digest("hex"));
  assert.equal((await fetch(blobUrl)).status, 404, "nothing went through the gateway");
  const uploadId = /upload_id="([^"]+)"/.exec(upload)![1]!;
  const saved = text(await call("write_file", { location: "downloads", name: "clip.mp4", upload_id: uploadId, sha256 }));
  assert.match(saved, /^Saved "clip\.mp4" to Downloads \(293 KB\)\. file_id: (\S+)\.$/);
  const clip = /file_id: (\S+)\./.exec(saved)![1]!;

  // The Wi-Fi address is gone (here: already used), so the same command uses the gateway.
  const second = text(await call("upload_link", {}));
  assert.equal((await fetch(wifiUrl(second), { method: "PUT", body: "x" })).status, 201);
  writeFileSync(join(work, "note.txt"), "via the gateway");
  const hash = /([0-9a-f]{64})\s*$/.exec(await run(second, "note.txt"))![1]!;
  const viaGateway = text(await call("write_file", {
    location: "downloads", name: "note.txt", upload_id: /upload_id="([^"]+)"/.exec(second)![1]!, sha256: hash,
  }));
  assert.match(viaGateway, /^Saved "note\.txt" to Downloads \(15 B\)\./);

  // Nothing sent either way.
  const third = text(await call("upload_link", {}));
  const nothing = await call("write_file", { location: "downloads", name: "none.txt", upload_id: /upload_id="([^"]+)"/.exec(third)![1]!, sha256: hash });
  assert.ok(nothing.isError && /nothing was uploaded/.test(text(nothing)), text(nothing));

  // Phone → computer, straight from the phone, decrypted and checked by the command.
  const link = text(await call("get_file_link", { file_id: clip }));
  assert.match(link, /^Download link for "clip\.mp4" \(293 KB\), valid for 15 minutes and for one download, straight from the phone on its Wi-Fi\./);
  const command = link.split("\n").find((l) => l.startsWith("curl "))!.replaceAll("clip.mp4", "back.mp4");
  assert.match(await shell(command), /Saved and verified back\.mp4/);
  assert.deepEqual(readFileSync(join(work, "back.mp4")), plain);
  assert.match(await shell(command), /call get_file_link again with lane=cloud/, "one download per address");

  // lane=cloud: through the gateway, as before.
  const cloud = text(await call("get_file_link", { file_id: clip, lane: "cloud" }));
  assert.match(cloud, /The stored copy is encrypted/);
  const viaCloud = cloud.split("\n").find((l) => l.startsWith("curl "))!.replaceAll("clip.mp4", "cloud.mp4");
  assert.match(await shell(viaCloud), /Saved and verified cloud\.mp4/);
  const wrong = await call("get_file_link", { file_id: clip, lane: "usb" });
  assert.ok(wrong.isError && /lane must be auto or cloud/.test(text(wrong)), text(wrong));
  await client.close();
});
