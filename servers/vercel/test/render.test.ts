import { test } from "node:test";
import assert from "node:assert/strict";
import { renderObservation, toolError } from "../src/render.js";
import { ProtocolError, normalizeObservation } from "../src/protocol.js";
import { shared } from "./shared.js";

test("observation rendering matches the Rust gateway byte for byte", () => {
  const input = JSON.parse(shared("mcp/render-input.json"));
  const obs = normalizeObservation(input.observation, 2000);
  assert.equal(renderObservation(input.device_id, obs, input.screenshot_withheld), shared("mcp/render-expected.txt"));
});

test("tool errors match the Rust gateway", () => {
  for (const c of JSON.parse(shared("mcp/tool-errors.json"))) {
    assert.deepEqual(toolError(new ProtocolError(c.code, c.message)), c.result);
  }
});
