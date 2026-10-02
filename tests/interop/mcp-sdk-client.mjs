// Drives a running gateway (with a connected phone or fake phone) through the
// official MCP TypeScript SDK client over Streamable HTTP.
//
//   LATCH_URL=http://127.0.0.1:8787 LATCH_MCP_TOKEN=... node mcp-sdk-client.mjs
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const base = process.env.LATCH_URL ?? "http://127.0.0.1:8787";
const token = process.env.LATCH_MCP_TOKEN;
if (!token) throw new Error("set LATCH_MCP_TOKEN");

const transport = new StreamableHTTPClientTransport(new URL("/mcp", base), {
  requestInit: { headers: { Authorization: `Bearer ${token}` } },
});
const client = new Client({ name: "latch-interop", version: "0.1.0" });
await client.connect(transport);
console.log("server:", client.getServerVersion());

const { tools } = await client.listTools();
console.log("tools:", tools.map((t) => t.name).join(", "));
assert.ok(tools.some((t) => t.name === "observe"));

const text = (result) => result.content.filter((c) => c.type === "text").map((c) => c.text).join("\n");

const devices = await client.callTool({ name: "list_devices", arguments: {} });
console.log(text(devices));

let screen = await client.callTool({ name: "observe", arguments: { screenshot: true } });
assert.equal(screen.isError ?? false, false, text(screen));
console.log(text(screen));
console.log("images:", screen.content.filter((c) => c.type === "image").length);

const launch = await client.callTool({ name: "launch_app", arguments: { package: "com.android.settings" } });
assert.equal(launch.isError ?? false, false, text(launch));
const id = [...text(launch).matchAll(/observation_id: (\S+)/g)].at(-1)[1];
const stale = await client.callTool({ name: "tap", arguments: { observation_id: "o_does_not_exist", element_id: "n2" } });
assert.equal(stale.isError, true);
assert.match(text(stale), /stale_observation/);
const tap = await client.callTool({ name: "tap", arguments: { observation_id: id, element_id: "n2" } });
assert.equal(tap.isError ?? false, false, text(tap));
console.log(text(tap));
await client.close();
console.log("OK: official MCP TypeScript SDK client completed observe → launch → tap");
