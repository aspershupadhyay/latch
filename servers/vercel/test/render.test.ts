// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
import { test } from "node:test";
import assert from "node:assert/strict";
import { SENSITIVE_APP_NOTE, renderObservation, toolError } from "../src/render.js";
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

test("money, account, and password apps carry the same caution as the Rust gateway", () => {
  const input = JSON.parse(shared("mcp/render-input.json"));
  const at = (pkg: string) => renderObservation("d_1", normalizeObservation({ ...input.observation, package: pkg }, 2000), false);
  assert.ok(at("com.phonepe.app").includes(SENSITIVE_APP_NOTE));
  assert.ok(at("in.org.npci.upiapp").includes(SENSITIVE_APP_NOTE));
  assert.ok(!at("com.android.settings").includes("Caution"));
  assert.equal(
    SENSITIVE_APP_NOTE,
    "Caution: this app may hold money, accounts, or passwords. Before acting here, tell the user what you are about to do and get their go-ahead in the chat.\n",
  );
});
