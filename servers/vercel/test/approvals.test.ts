// Approvals answered in the AI app (protocol 1.4, MCP elicitation, ADR-023):
// the official MCP SDK client answers a question the phone is waiting on.
// The phone is scripted here over the long-poll HTTP endpoints, so the test
// controls exactly when it asks and what it sends back.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { ElicitRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { MemoryStore } from "../src/store.js";
import { startLocal } from "../src/local.js";

const ADMIN = "admin-token-0123456789abcdef0123456789";
const NONCE = "0123456789abcdef0123456789abcdef";
let server: Server;
let base: string;

before(async () => {
  const config = { ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }), timing: { pollIntervalMs: 20, hotPollIntervalMs: 10, resultIntervalMs: 20 }, settleMs: 0 };
  server = await startLocal(new Gateway(new MemoryStore(), config), 0);
  const address = server.address();
  base = `http://127.0.0.1:${typeof address === "object" && address ? address.port : 0}`;
});

after(() => server.close());

async function api(path: string, body: unknown, token = ADMIN) {
  const res = await fetch(base + path, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

/** A scripted phone: hello, then poll and post messages by hand. */
async function phone(remoteApprovals: boolean) {
  const pairing = await api("/v1/admin/pairings", { name: "Scripted phone" });
  const paired = await api("/v1/pair", { code: pairing.body.code, platform: "fake", model: "Scripted" }, "");
  const token = paired.body.token as string;
  const hello = await api("/v1/device/hello", {
    type: "hello", protocol: "1.4",
    device: { platform: "fake", os_version: "1", model: "Scripted", app_version: "test" },
    capabilities: ["device.info", "ui.observe", "screen.capture", "input.gesture", "input.text", "nav.global", "app.launch"]
      .map((capability) => ({ capability, status: "enabled" })),
    session: { expires_at_ms: Date.now() + 3_600_000, approve_every_action: false, paused: false, remote_approvals: remoteApprovals },
    device_time_ms: Date.now(),
  }, token);
  assert.equal(hello.status, 200);
  const conn = hello.body.connection as string;
  const poll = async () => {
    const res = await fetch(`${base}/v1/device/poll?connection=${conn}&wait=5&hot=1`, { headers: { authorization: `Bearer ${token}` } });
    return res.status === 200 ? (await res.json()) as Record<string, unknown> : undefined;
  };
  const post = (message: unknown) => api(`/v1/device/messages?connection=${conn}`, message, token);
  return { poll, post, deviceId: paired.body.device_id as string };
}

const listApps = { apps: [{ package: "com.example.chat", label: "Chat" }] };

async function client(answer: (message: string) => { action: string; content?: Record<string, unknown> }) {
  const created = await api("/v1/admin/clients", { name: "Elicitation test" });
  const c = new Client({ name: "latch-approvals-test", version: "0.1.0" }, { capabilities: { elicitation: {} } });
  const asked: string[] = [];
  c.setRequestHandler(ElicitRequestSchema, async (request) => {
    asked.push(request.params.message);
    return answer(request.params.message);
  });
  await c.connect(new StreamableHTTPClientTransport(new URL(created.body.mcp_url), {
    requestInit: { headers: { authorization: `Bearer ${created.body.token}` } },
  }));
  return { c, asked };
}

test("an approval the phone waits on is answered in the AI app", async () => {
  const p = await phone(true);
  const { c, asked } = await client(() => ({ action: "accept", content: { answer: "session" } }));
  const call = c.callTool({ name: "list_apps", arguments: { device_id: p.deviceId } });

  const command = await p.poll();
  assert.equal(command?.type, "command");
  const id = command!.id as string;
  assert.equal((await p.post({
    type: "approval_request", command_id: id, nonce: NONCE, title: "Let the AI use Chat?", detail: "It can see Chat's screen.",
    kind: "app", choices: ["session", "always", "deny"], remote: true, expires_at_ms: Date.now() + 110_000,
  })).status, 204);

  // The gateway relays the user's answer to the phone.
  let answer: Record<string, unknown> | undefined;
  for (let i = 0; i < 50 && answer?.type !== "approval_answer"; i++) answer = await p.poll();
  assert.deepEqual(answer, { type: "approval_answer", nonce: NONCE, choice: "session" });
  assert.equal(asked.length, 1);
  assert.match(asked[0]!, /Let the AI use Chat\?/);

  await p.post({ type: "result", id, outcome: { status: "ok", data: listApps } });
  const result = await call;
  assert.match(JSON.stringify(result.content), /com\.example\.chat/);
  await c.close();
});

test("declining in the AI app denies on the phone", async () => {
  const p = await phone(true);
  const { c } = await client(() => ({ action: "decline" }));
  const call = c.callTool({ name: "list_apps", arguments: { device_id: p.deviceId } });
  const command = await p.poll();
  const id = command!.id as string;
  await p.post({
    type: "approval_request", command_id: id, nonce: NONCE, title: "Tap “Send” in com.example.chat", detail: "Requested by an AI agent.",
    kind: "action", choices: ["once", "deny"], remote: true, expires_at_ms: Date.now() + 110_000,
  });
  let answer: Record<string, unknown> | undefined;
  for (let i = 0; i < 50 && answer?.type !== "approval_answer"; i++) answer = await p.poll();
  assert.deepEqual(answer, { type: "approval_answer", nonce: NONCE, choice: "deny" });
  await p.post({ type: "result", id, outcome: { status: "error", error: { code: "user_denied", message: "the owner denied this action" } } });
  const result = await call;
  assert.equal(result.isError, true);
  await c.close();
});

test("without the owner's switch the AI app is never asked, and the call waits for the phone", async () => {
  const p = await phone(false);
  const { c, asked } = await client(() => ({ action: "accept", content: { answer: "once" } }));
  const call = c.callTool({ name: "list_apps", arguments: { device_id: p.deviceId } });
  const command = await p.poll();
  const id = command!.id as string;
  await p.post({
    type: "approval_request", command_id: id, nonce: NONCE, title: "Let the AI use Chat?", detail: "It can see Chat's screen.",
    kind: "app", choices: ["session", "always", "deny"], remote: false, expires_at_ms: Date.now() + 110_000,
  });
  assert.equal(await p.poll(), undefined);
  await p.post({ type: "result", id, outcome: { status: "ok", data: listApps } });
  await call;
  assert.equal(asked.length, 0);
  await c.close();
});

test("approval requests are validated like the Rust gateway does", async () => {
  const p = await phone(true);
  const bad = await p.post({
    type: "approval_request", command_id: "c_1", nonce: "nope", title: "x", detail: "y", kind: "action", choices: ["once"], remote: true, expires_at_ms: 1,
  });
  assert.equal(bad.status, 422);
});

/** An AI app that cannot show questions (no elicitation), like most chat apps today. */
async function plainClient() {
  const created = await api("/v1/admin/clients", { name: "Plain test" });
  const c = new Client({ name: "latch-plain-test", version: "0.1.0" });
  await c.connect(new StreamableHTTPClientTransport(new URL(created.body.mcp_url), {
    requestInit: { headers: { authorization: `Bearer ${created.body.token}` } },
  }));
  return c;
}

const text = (r: unknown) => ((r as { content: { text?: string }[] }).content).map((c) => c.text ?? "").join("\n");

test("without elicitation the call pauses, the AI asks in the chat, and answer_approval finishes it", async () => {
  const p = await phone(true);
  const c = await plainClient();
  const call = c.callTool({ name: "list_apps", arguments: { device_id: p.deviceId } });
  const command = await p.poll();
  const id = command!.id as string;
  await p.post({
    type: "approval_request", command_id: id, nonce: NONCE, title: "Let the AI use Chat?", detail: "It can see Chat's screen.",
    kind: "app", choices: ["session", "always", "deny"], remote: true, expires_at_ms: Date.now() + 110_000,
  });
  // The tool returns at once and tells the AI to ask the user.
  const waiting = text(await call);
  assert.match(waiting, /waiting for the owner/);
  assert.match(waiting, new RegExp(`request_id "${id}"`));

  // The user answers in the chat; the AI passes it on, and the phone gets it.
  const resumed = c.callTool({ name: "answer_approval", arguments: { request_id: id, answer: "session" } });
  let answer: Record<string, unknown> | undefined;
  for (let i = 0; i < 50 && answer?.type !== "approval_answer"; i++) answer = await p.poll();
  assert.deepEqual(answer, { type: "approval_answer", nonce: NONCE, choice: "session" });
  await p.post({ type: "result", id, outcome: { status: "ok", data: listApps } });
  assert.match(text(await resumed), /com\.example\.chat/);

  // The question is gone once answered, and answers outside the offered choices are refused.
  const again = await c.callTool({ name: "answer_approval", arguments: { request_id: id, answer: "session" } });
  assert.equal(again.isError, true);
  await c.close();
});

test("answer_approval refuses answers the phone did not offer", async () => {
  const p = await phone(true);
  const c = await plainClient();
  const call = c.callTool({ name: "list_apps", arguments: { device_id: p.deviceId } });
  const id = (await p.poll())!.id as string;
  await p.post({
    type: "approval_request", command_id: id, nonce: NONCE, title: "Tap “Pay”", detail: "Money.",
    kind: "action", choices: ["once", "deny"], remote: true, expires_at_ms: Date.now() + 110_000,
  });
  await call;
  const wrong = await c.callTool({ name: "answer_approval", arguments: { request_id: id, answer: "always" } });
  assert.equal(wrong.isError, true);
  assert.match(text(wrong), /once, deny/);
  await c.close();
});
