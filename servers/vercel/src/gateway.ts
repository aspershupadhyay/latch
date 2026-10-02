// HTTP routes of the Vercel gateway. Same paths, status codes, and JSON shapes
// as the Rust gateway (servers/mcp/src/http.rs and device_http.rs), so the
// phone app and the owner console work against either.

import { type DeviceRecord, Devices, type Timing } from "./devices.js";
import { handle as handleMcp, SUPPORTED_VERSIONS } from "./mcp.js";
import { type Hello, PROTOCOL_VERSION, ProtocolError, LIMITS, type Outcome, validateHello } from "./protocol.js";
import { bearer, newId, newToken, normalizeCode, pairingCode, secretsEqual, sha256 } from "./secret.js";
import type { Store } from "./store.js";

export interface GatewayConfig {
  /** Owner secret (LATCH_ADMIN_TOKEN). Undefined or too short puts the gateway in setup mode. */
  adminToken?: string;
  /** Optional static MCP token (LATCH_MCP_TOKEN). */
  mcpToken?: string;
  publicUrl?: string;
  allowedOrigins: string[];
  timing: Timing;
  settleMs: number;
  maxPollWaitMs: number;
}

export function configFromEnv(env: NodeJS.ProcessEnv): GatewayConfig {
  const clean = (v?: string) => (v && v.trim() !== "" ? v.trim() : undefined);
  return {
    adminToken: clean(env.LATCH_ADMIN_TOKEN),
    mcpToken: clean(env.LATCH_MCP_TOKEN),
    publicUrl: clean(env.LATCH_PUBLIC_URL)?.replace(/\/+$/, ""),
    allowedOrigins: (env.LATCH_ALLOWED_ORIGINS ?? "").split(",").map((o) => o.trim().replace(/\/+$/, "")).filter(Boolean),
    timing: {
      pollIntervalMs: Number(env.LATCH_POLL_INTERVAL_MS ?? 1000),
      resultIntervalMs: Number(env.LATCH_RESULT_INTERVAL_MS ?? 300),
    },
    settleMs: Number(env.LATCH_SETTLE_MS ?? 600),
    maxPollWaitMs: 25_000,
  };
}

const PAIR_TTL_MS = 10 * 60 * 1000;
const MAX_PAIR_FAILURES_PER_MINUTE = 20;

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...headers } });
const error = (status: number, message: string) => json(status, { error: message });
const noContent = () => new Response(null, { status: 204 });
const unauthorized = () => json(401, { error: "missing or wrong bearer token" }, { "www-authenticate": "Bearer" });

function bounded(value: unknown, max: number): value is string {
  // eslint-disable-next-line no-control-regex
  return typeof value === "string" && value.trim() !== "" && [...value].length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
}

async function readJson(request: Request, limit: number): Promise<unknown> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > limit) throw new ProtocolError("invalid_request", "body too large");
  const text = await request.text();
  if (text.length > limit) throw new ProtocolError("invalid_request", "body too large");
  return JSON.parse(text);
}

export class Gateway {
  readonly devices: Devices;

  constructor(private readonly store: Store, private readonly config: GatewayConfig) {
    this.devices = new Devices(store, config.timing);
  }

  private get setupMode() {
    return this.config.adminToken === undefined || this.config.adminToken.length < 32;
  }

  private isAdmin(request: Request): boolean {
    const token = bearer(request.headers.get("authorization"));
    return !this.setupMode && token !== undefined && secretsEqual(token, this.config.adminToken!);
  }

  private baseUrl(request: Request): string {
    if (this.config.publicUrl) return this.config.publicUrl;
    const url = new URL(request.url);
    const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? url.host;
    const proto = request.headers.get("x-forwarded-proto") ?? url.protocol.replace(":", "");
    return `${proto}://${host}`;
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    try {
      return await this.route(request, path);
    } catch (e) {
      if (e instanceof ProtocolError) return error(400, e.message);
      if (e instanceof SyntaxError) return error(400, "malformed JSON");
      // Never echo internals: they could contain screen content.
      console.error("latch gateway error", e instanceof Error ? e.name : "unknown");
      return error(500, "internal gateway error");
    }
  }

  private async route(request: Request, path: string): Promise<Response> {
    const m = request.method;
    if (path === "/healthz" && m === "GET") return this.health();
    if (path === "/v1/info" && m === "GET") return this.info();
    if (path === "/mcp" || path.startsWith("/mcp/")) {
      if (m !== "POST") return json(405, { error: "this server does not offer a server-to-client stream; use POST" }, { allow: "POST" });
      // `/mcp/<client token>` is the secret-link form for clients that accept only a URL.
      const linkToken = path.startsWith("/mcp/") ? decodeURIComponent(path.slice("/mcp/".length)) : undefined;
      return this.mcp(request, linkToken ?? bearer(request.headers.get("authorization")));
    }
    if (path === "/v1/pair" && m === "POST") return this.pair(request);
    if (path === "/v1/device/hello" && m === "POST") return this.hello(request);
    if (path === "/v1/device/poll" && m === "GET") return this.poll(request);
    if (path === "/v1/device/messages" && m === "POST") return this.messages(request);
    if (path === "/v1/device") return error(426, "this gateway offers the HTTP poll transport only; see /v1/info");
    if (path.startsWith("/v1/admin/")) {
      if (this.setupMode) return error(503, "set LATCH_ADMIN_TOKEN (at least 32 characters) in the Vercel project settings, then redeploy");
      if (!this.isAdmin(request)) return unauthorized();
      return this.admin(request, path.slice("/v1/admin/".length), m);
    }
    return error(404, "not found");
  }

  // ---- Public ----

  private async health() {
    return json(200, {
      status: this.setupMode ? "setup_required" : "ok",
      version: "0.1.0",
      protocol: PROTOCOL_VERSION,
      devices_connected: this.setupMode ? 0 : (await this.devices.online()).length,
    });
  }

  private info() {
    return json(200, {
      product: "latch-gateway",
      implementation: "vercel",
      version: "0.1.0",
      protocol: PROTOCOL_VERSION,
      transports: ["poll"],
      mcp_path: "/mcp",
      setup_required: this.setupMode,
    });
  }

  // ---- MCP ----

  private async isMcpClient(token: string | undefined): Promise<boolean> {
    if (token === undefined || token === "") return false;
    if (this.config.mcpToken && secretsEqual(token, this.config.mcpToken)) return true;
    const id = await this.store.get(`latch:clienttoken:${sha256(token)}`);
    if (!id) return false;
    const raw = (await this.store.hgetall("latch:clients"))[id];
    if (!raw) return false;
    await this.store.hset("latch:clients", id, JSON.stringify({ ...JSON.parse(raw), last_used_ms: Date.now() }));
    return true;
  }

  private async mcp(request: Request, token: string | undefined): Promise<Response> {
    const origin = request.headers.get("origin");
    if (origin !== null && !this.config.allowedOrigins.includes(origin.replace(/\/+$/, ""))) {
      return error(403, "origin not allowed; see LATCH_ALLOWED_ORIGINS");
    }
    if (this.setupMode || !(await this.isMcpClient(token))) return unauthorized();
    const version = request.headers.get("mcp-protocol-version");
    if (version !== null && !SUPPORTED_VERSIONS.includes(version)) return error(400, "unsupported MCP-Protocol-Version");
    let message: unknown;
    try {
      message = await readJson(request, 256 * 1024);
    } catch {
      return json(400, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
    }
    const reply = await handleMcp({ devices: this.devices, settleMs: this.config.settleMs }, message);
    return reply === undefined ? new Response(null, { status: 202 }) : json(200, reply);
  }

  // ---- Phones ----

  private async pair(request: Request): Promise<Response> {
    if (this.setupMode) return error(503, "this gateway is not set up yet");
    const body = (await readJson(request, 4096)) as Record<string, unknown>;
    const allowed = ["code", "platform", "model"];
    if (typeof body !== "object" || body === null || Object.keys(body).some((k) => !allowed.includes(k))) return error(400, "unexpected fields");
    if (!bounded(body.code, 16) || !bounded(body.platform, 16) || !bounded(body.model, 64)) {
      return error(400, "code, platform, and model are required");
    }
    const minute = `latch:pairfail:${Math.floor(Date.now() / 60_000)}`;
    if (Number((await this.store.get(minute)) ?? "0") >= MAX_PAIR_FAILURES_PER_MINUTE) {
      return error(429, "too many wrong codes; wait a minute");
    }
    const name = await this.store.getdel(`latch:pair:${normalizeCode(body.code)}`);
    if (name === null) {
      await this.store.incr(minute, 120_000);
      return error(403, "that pairing code is wrong or has expired");
    }
    const token = newToken("ldt");
    const record: DeviceRecord = {
      id: newId("d"), name, model: body.model.trim(), platform: body.platform.trim(),
      token_sha256: sha256(token), paired_at_ms: Date.now(),
    };
    await this.devices.addRecord(record);
    return json(200, { device_id: record.id, name, token, protocol: PROTOCOL_VERSION, device_path: "/v1/device" });
  }

  private async device(request: Request): Promise<string | undefined> {
    const token = bearer(request.headers.get("authorization"));
    return token === undefined ? undefined : this.devices.deviceForToken(sha256(token));
  }

  private async hello(request: Request): Promise<Response> {
    const id = await this.device(request);
    if (!id) return error(401, "unknown or revoked device credential");
    const hello = (await readJson(request, 64 * 1024)) as Hello;
    if (hello?.type !== "hello") return error(400, "expected a hello message");
    try {
      validateHello(hello);
    } catch (e) {
      return error(422, (e as Error).message);
    }
    const now = Date.now();
    const connection = await this.devices.connect(id, hello, now);
    return json(200, { type: "welcome", protocol: PROTOCOL_VERSION, device_id: id, server_time_ms: now, connection });
  }

  private async poll(request: Request): Promise<Response> {
    const id = await this.device(request);
    if (!id) return error(401, "unknown or revoked device credential");
    const url = new URL(request.url);
    const conn = url.searchParams.get("connection") ?? "";
    if (!(await this.devices.current(id, conn))) return error(409, "connection replaced or expired; send hello again");
    const wait = Math.min(Number(url.searchParams.get("wait") ?? "25") * 1000 || this.config.maxPollWaitMs, this.config.maxPollWaitMs);
    const message = await this.devices.nextMessage(id, conn, wait);
    // Refresh presence after a long wait so a live phone does not expire.
    const stillCurrent = await this.devices.current(id, conn);
    if (message) return new Response(message, { status: 200, headers: { "content-type": "application/json", "cache-control": "no-store" } });
    return stillCurrent ? noContent() : error(409, "connection replaced or expired; send hello again");
  }

  private async messages(request: Request): Promise<Response> {
    const id = await this.device(request);
    if (!id) return error(401, "unknown or revoked device credential");
    const conn = new URL(request.url).searchParams.get("connection") ?? "";
    const live = await this.devices.current(id, conn);
    if (!live) return error(409, "connection replaced or expired; send hello again");
    let message: Record<string, unknown>;
    try {
      message = (await readJson(request, LIMITS.maxMessageBytes)) as Record<string, unknown>;
    } catch {
      // Do not echo the body: it may contain screen content.
      return error(400, "unparseable device message");
    }
    const now = Date.now();
    switch (message?.type) {
      case "result":
        if (typeof message.id !== "string" || typeof message.outcome !== "object") return error(400, "unparseable device message");
        await this.devices.deliverResult(id, conn, message.id, message.outcome as Outcome);
        break;
      case "state":
        await this.devices.updateState(id, live, message.capabilities as Hello["capabilities"], message.session as Hello["session"], message.device_time_ms as number | undefined, now);
        break;
      case "bye":
        await this.devices.disconnect(id, conn, now);
        break;
      case "hello":
        return error(400, "send hello to /v1/device/hello");
      default:
        return error(400, "unparseable device message");
    }
    return noContent();
  }

  // ---- Owner console API ----

  private async admin(request: Request, path: string, method: string): Promise<Response> {
    if (path === "devices" && method === "GET") {
      const online = await this.devices.online();
      const devices = (await this.devices.records()).map((d) => {
        const live = online.find((o) => o.device_id === d.id) ?? null;
        return {
          id: d.id, name: d.name, model: d.model, platform: d.platform, paired_at_ms: d.paired_at_ms,
          last_seen_ms: d.last_seen_ms ?? null, connected: live !== null, live,
        };
      });
      return json(200, { devices, server_time_ms: Date.now() });
    }
    if (path.startsWith("devices/") && method === "DELETE") {
      return (await this.devices.revoke(decodeURIComponent(path.slice("devices/".length)))) ? noContent() : error(404, "no such device");
    }
    if (path === "pairings" && method === "POST") {
      const body = (await readJson(request, 4096)) as Record<string, unknown>;
      if (!bounded(body?.name, 40) || Object.keys(body).length !== 1) return error(400, "name must be 1-40 characters");
      const code = pairingCode();
      const expires = Date.now() + PAIR_TTL_MS;
      await this.store.set(`latch:pair:${code}`, body.name.trim(), { px: PAIR_TTL_MS });
      return json(200, { code: `${code.slice(0, 4)}-${code.slice(4)}`, expires_at_ms: expires, gateway_url: this.baseUrl(request) });
    }
    if (path === "audit" && method === "GET") return json(200, { events: await this.devices.audit(200) });
    if (path === "clients" && method === "GET") {
      const clients = Object.values(await this.store.hgetall("latch:clients"))
        .map((v) => JSON.parse(v) as { id: string; name: string; created_at_ms: number; last_used_ms?: number })
        .sort((a, b) => a.created_at_ms - b.created_at_ms)
        .map((c) => ({ id: c.id, name: c.name, created_at_ms: c.created_at_ms, last_used_ms: c.last_used_ms ?? null }));
      return json(200, { clients });
    }
    if (path === "clients" && method === "POST") {
      const body = (await readJson(request, 4096)) as Record<string, unknown>;
      if (!bounded(body?.name, 40) || Object.keys(body).length !== 1) return error(400, "name must be 1-40 characters");
      if (Object.keys(await this.store.hgetall("latch:clients")).length >= 50) return error(429, "revoke unused clients first (limit 50)");
      const token = newToken("lmt");
      const record = { id: newId("m"), name: body.name.trim(), token_sha256: sha256(token), created_at_ms: Date.now() };
      await this.store.hset("latch:clients", record.id, JSON.stringify(record));
      await this.store.set(`latch:clienttoken:${record.token_sha256}`, record.id);
      return json(200, { id: record.id, token, mcp_url: `${this.baseUrl(request)}/mcp` });
    }
    if (path.startsWith("clients/") && method === "DELETE") {
      const id = decodeURIComponent(path.slice("clients/".length));
      const raw = (await this.store.hgetall("latch:clients"))[id];
      if (!raw) return error(404, "no such client");
      await this.store.hdel("latch:clients", id);
      await this.store.del(`latch:clienttoken:${(JSON.parse(raw) as { token_sha256: string }).token_sha256}`);
      return noContent();
    }
    return error(404, "not found");
  }
}
