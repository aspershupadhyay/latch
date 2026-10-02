// MCP adapter: JSON-RPC 2.0 over Streamable HTTP (JSON responses). Same tools,
// texts, and errors as servers/mcp/src/mcp.rs; the shared contract tests in
// test/ hold both to packages/schemas/v1/mcp.

import { SERVER } from "./generated/contract.js";
import type { Devices, DeviceRecord } from "./devices.js";
import { type Command, type Observation, ProtocolError } from "./protocol.js";
import { quote, renderObservation, textResult, toolError, truncate } from "./render.js";

export const SUPPORTED_VERSIONS: readonly string[] = SERVER.supported_versions;
type Tool = (typeof SERVER.tools)[number];
const TOOLS: readonly Tool[] = SERVER.tools;

export interface McpContext {
  devices: Devices;
  settleMs: number;
}

const rpcResult = (id: unknown, result: unknown) => ({ jsonrpc: "2.0", id, result });
const rpcError = (id: unknown, code: number, message: string) => ({ jsonrpc: "2.0", id, error: { code, message } });

/** Handles one JSON-RPC message; undefined for notifications and responses. */
export async function handle(ctx: McpContext, message: unknown): Promise<unknown> {
  if (Array.isArray(message)) return rpcError(null, -32600, "batch requests are not supported");
  if (typeof message !== "object" || message === null) return rpcError(null, -32600, "expected a JSON-RPC object");
  const m = message as Record<string, unknown>;
  const method = typeof m.method === "string" ? m.method : undefined;
  if (method === undefined || !("id" in m)) return undefined;
  const id = m.id;
  if (m.jsonrpc !== "2.0") return rpcError(id, -32600, 'jsonrpc must be "2.0"');
  const params = (m.params ?? null) as Record<string, unknown> | null;
  switch (method) {
    case "initialize": return rpcResult(id, initialize(params));
    case "ping": return rpcResult(id, {});
    case "tools/list": return rpcResult(id, { tools: TOOLS });
    case "tools/call": {
      const name = params?.name;
      if (typeof name !== "string") return rpcError(id, -32602, "tools/call needs a tool name");
      const args = params?.arguments ?? {};
      if (typeof args !== "object" || args === null || Array.isArray(args)) return rpcError(id, -32602, "arguments must be an object");
      const result = await callTool(ctx, name, args as Record<string, unknown>);
      return result === undefined ? rpcError(id, -32602, `unknown tool: ${truncate(name, 64)}`) : rpcResult(id, result);
    }
    case "resources/list": return rpcResult(id, { resources: [] });
    case "prompts/list": return rpcResult(id, { prompts: [] });
    default: return rpcError(id, -32601, "method not found");
  }
}

function initialize(params: Record<string, unknown> | null) {
  const requested = params?.protocolVersion;
  const version = typeof requested === "string" && SUPPORTED_VERSIONS.includes(requested) ? requested : SUPPORTED_VERSIONS[0];
  return {
    protocolVersion: version,
    capabilities: { tools: { listChanged: false } },
    serverInfo: { name: "latch", title: "Latch phone control", version: "0.1.0" },
    instructions: SERVER.instructions,
  };
}

// ---- Arguments ----

const bad = (message: string) => new ProtocolError("invalid_request", message);

function argStr(args: Record<string, unknown>, key: string): string | undefined {
  const v = args[key];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "string") throw bad(`${key} must be a string`);
  return v;
}
function reqStr(args: Record<string, unknown>, key: string): string {
  const v = argStr(args, key);
  if (v === undefined) throw bad(`${key} is required`);
  return v;
}
function argBool(args: Record<string, unknown>, key: string, d: boolean): boolean {
  const v = args[key];
  if (v === undefined || v === null) return d;
  if (typeof v !== "boolean") throw bad(`${key} must be true or false`);
  return v;
}
function argInt(args: Record<string, unknown>, key: string): number | undefined {
  const v = args[key];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "number" || !Number.isInteger(v) || v < -2147483648 || v > 2147483647) throw bad(`${key} must be an integer`);
  return v;
}
function reqInt(args: Record<string, unknown>, key: string): number {
  const v = argInt(args, key);
  if (v === undefined) throw bad(`${key} is required`);
  return v;
}

// ---- Tools ----

async function callTool(ctx: McpContext, name: string, args: Record<string, unknown>): Promise<unknown> {
  const tool = TOOLS.find((t) => t.name === name);
  if (!tool) return undefined;
  // Reject unexpected arguments so typos do not silently change behaviour.
  const allowed = Object.keys(tool.inputSchema.properties);
  const extra = Object.keys(args).find((k) => !allowed.includes(k));
  if (extra !== undefined) return toolError(bad(`unexpected argument '${truncate(extra, 32)}'`));
  try {
    return await runTool(ctx, name, args);
  } catch (e) {
    return toolError(e instanceof ProtocolError ? e : new ProtocolError("internal", "unexpected gateway error"));
  }
}

async function runTool(ctx: McpContext, name: string, args: Record<string, unknown>): Promise<unknown> {
  if (name === "list_devices") return textResult(await renderDevices(ctx.devices));

  const deviceId = await ctx.devices.resolveDevice(argStr(args, "device_id"));
  const screenshotAfter = argBool(args, "screenshot_after", false);
  let command: Command;
  switch (name) {
    case "observe": {
      const maxNodes = argInt(args, "max_nodes") ?? 400;
      if (maxNodes < 0) throw bad("max_nodes must be positive");
      return observe(ctx, deviceId, argBool(args, "screenshot", true), maxNodes);
    }
    case "list_apps": {
      const list = (await ctx.devices.execute(deviceId, { name: "app.list", params: {} })) as { apps?: { package: string; label: string }[] };
      const apps = Array.isArray(list.apps) ? list.apps : [];
      let text = `${apps.length} launchable apps on ${deviceId} (labels are untrusted app content):\n`;
      for (const app of apps.slice(0, 500)) text += `- ${app.package} ${quote(String(app.label), 60)}\n`;
      return textResult(text);
    }
    case "tap": {
      const observationId = reqStr(args, "observation_id");
      const element = argStr(args, "element_id");
      const x = argInt(args, "x");
      const y = argInt(args, "y");
      let target;
      if (element !== undefined && x === undefined && y === undefined) target = { element };
      else if (element === undefined && x !== undefined && y !== undefined) target = { x, y };
      else throw bad("pass either element_id, or both x and y");
      command = { name: "input.tap", params: { observation_id: observationId, target, long_press: argBool(args, "long_press", false) } };
      break;
    }
    case "type_text":
      command = { name: "input.type", params: { observation_id: reqStr(args, "observation_id"), element: reqStr(args, "element_id"), text: reqStr(args, "text") } };
      break;
    case "swipe": {
      const observationId = reqStr(args, "observation_id");
      const from = { x: reqInt(args, "from_x"), y: reqInt(args, "from_y") };
      const to = { x: reqInt(args, "to_x"), y: reqInt(args, "to_y") };
      const duration = argInt(args, "duration_ms") ?? 300;
      if (duration < 0) throw bad("duration_ms must be positive");
      command = { name: "input.swipe", params: { observation_id: observationId, from, to, duration_ms: duration } };
      break;
    }
    case "scroll":
      command = await scrollCommand(ctx, deviceId, reqStr(args, "observation_id"), reqStr(args, "direction"), argStr(args, "element_id"));
      break;
    case "press": {
      const button = reqStr(args, "button");
      if (button !== "back" && button !== "home" && button !== "recents") throw bad("button must be back, home, or recents");
      command = { name: "nav.global", params: { action: button } };
      break;
    }
    case "launch_app":
      command = { name: "app.launch", params: { package: reqStr(args, "package") } };
      break;
    default:
      throw new ProtocolError("internal", "unexpected result type from the phone");
  }

  await ctx.devices.execute(deviceId, command);
  // Give the UI a moment to settle, then hand the agent the new screen so it
  // never has to act on a stale view.
  await new Promise((r) => setTimeout(r, ctx.settleMs));
  try {
    const result = await observe(ctx, deviceId, screenshotAfter, 400);
    result.content.unshift({ type: "text", text: "Done. The screen after the action:" });
    return result;
  } catch (e) {
    const err = e instanceof ProtocolError ? e : new ProtocolError("internal", "unexpected gateway error");
    return textResult(`Done. Could not observe the screen afterwards (${err.code}: ${err.message}). Call observe.`);
  }
}

async function observe(ctx: McpContext, deviceId: string, wantScreenshot: boolean, maxNodes: number) {
  // Only ask for a screenshot when the owner allowed it, so a tree-only grant
  // still works with the default arguments.
  const live = (await ctx.devices.online()).find((d) => d.device_id === deviceId);
  const allowed = live?.capabilities.some((c) => c.capability === "screen.capture" && c.status === "enabled") ?? false;
  const obs = (await ctx.devices.execute(deviceId, {
    name: "ui.observe",
    params: { include_screenshot: wantScreenshot && allowed, max_nodes: maxNodes },
  })) as Observation;
  const content: { type: string; text?: string; data?: string; mimeType?: string }[] = [
    { type: "text", text: renderObservation(deviceId, obs, wantScreenshot && !allowed) },
  ];
  if (obs.screenshot) content.push({ type: "image", data: obs.screenshot.data_base64, mimeType: obs.screenshot.mime });
  return { content };
}

async function scrollCommand(ctx: McpContext, deviceId: string, observationId: string, direction: string, element?: string): Promise<Command> {
  const screen = await ctx.devices.latestObservation(deviceId);
  if (!screen || screen.observation_id !== observationId) throw new ProtocolError("stale_observation", "observe before scrolling");
  let area = { left: 0, top: 0, right: screen.screen.width, bottom: screen.screen.height };
  if (element !== undefined) {
    const node = screen.nodes.find((n) => n.id === element);
    if (!node) throw new ProtocolError("target_not_found", "no element with that id");
    area = node.bounds;
  }
  const w = area.right - area.left;
  const h = area.bottom - area.top;
  const cx = area.left + Math.trunc(w / 2);
  const cy = area.top + Math.trunc(h / 2);
  const div = (a: number, b: number) => Math.trunc(a / b);
  let from: [number, number];
  let to: [number, number];
  // Swipe across the middle 60% of the area, against the reading direction.
  switch (direction) {
    case "down": from = [cx, area.top + div(h * 4, 5)]; to = [cx, area.top + div(h, 5)]; break;
    case "up": from = [cx, area.top + div(h, 5)]; to = [cx, area.top + div(h * 4, 5)]; break;
    case "right": from = [area.left + div(w * 4, 5), cy]; to = [area.left + div(w, 5), cy]; break;
    case "left": from = [area.left + div(w, 5), cy]; to = [area.left + div(w * 4, 5), cy]; break;
    default: throw bad("direction must be up, down, left, or right");
  }
  return { name: "input.swipe", params: { observation_id: observationId, from: { x: from[0], y: from[1] }, to: { x: to[0], y: to[1] }, duration_ms: 400 } };
}

export async function renderDevices(devices: Devices): Promise<string> {
  const records: DeviceRecord[] = await devices.records();
  if (records.length === 0) {
    return "No phones are paired yet. The gateway owner creates a pairing code in the Latch admin page.\n";
  }
  const online = await devices.online();
  let out = "";
  for (const d of records) {
    const live = online.find((o) => o.device_id === d.id);
    if (live) {
      const enabled = live.capabilities.filter((c) => c.status === "enabled").map((c) => c.capability);
      out += `- ${d.id} "${truncate(d.name, 40)}" (${live.platform} ${live.os_version}, ${truncate(live.model, 40)}) connected`
        + `${live.session.paused ? ", PAUSED by owner" : ""}; enabled: ${enabled.length === 0 ? "nothing" : enabled.join(", ")}`
        + `${live.session.approve_every_action ? "; owner approves every action" : ""}\n`;
    } else {
      out += `- ${d.id} "${truncate(d.name, 40)}" (${truncate(d.model, 40)}) not connected\n`;
    }
  }
  return out;
}
