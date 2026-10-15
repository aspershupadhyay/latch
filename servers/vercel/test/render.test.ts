// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
import { test } from "node:test";
import assert from "node:assert/strict";
import { type DiagnosisInput, renderDiagnosis } from "../src/diagnose.js";
import { SENSITIVE_APP_NOTE, renderChanges, renderObservation, toolError } from "../src/render.js";
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

test("diagnose text matches the Rust gateway byte for byte", () => {
  const input = JSON.parse(shared("mcp/diagnose-input.json")) as { cases: DiagnosisInput[] };
  assert.equal(input.cases.map(renderDiagnosis).join("---\n"), shared("mcp/diagnose-expected.txt"));
});

test("changes-since text matches the Rust gateway byte for byte", () => {
  const input = JSON.parse(shared("mcp/changes-input.json")) as { device_id: string; cases: { name: string; base: unknown; now: unknown }[] };
  const text = input.cases.map((c) => {
    const rendered = renderChanges(input.device_id, normalizeObservation(c.now, 2000), normalizeObservation(c.base, 2000), false) ?? "(shown in full)\n";
    return `== ${c.name}\n${rendered}`;
  }).join("");
  assert.equal(text, shared("mcp/changes-expected.txt"));
});

test("screenshots may be JPEG, PNG, or (since 1.10) WebP, nothing else", () => {
  const obs = (mime: string) => ({
    observation_id: "o_1", captured_at_ms: 0, screen: { width: 10, height: 10, rotation: 0 }, nodes: [],
    screenshot: { mime, width: 10, height: 10, data_base64: "AAAA" }, redacted_count: 0, truncated: false,
  });
  for (const ok of ["image/jpeg", "image/png", "image/webp"]) assert.equal(normalizeObservation(obs(ok), 10).screenshot?.mime, ok);
  assert.throws(() => normalizeObservation(obs("image/gif"), 10), /image\/webp/);
});
