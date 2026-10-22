// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// R70 (ADR-072): the program engine's pure parts match the Rust gateway on the shared cases.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { condition, fill, holds, resolve, selector, validate, type Want } from "../src/program.js";
import type { Observation, UiNode } from "../src/protocol.js";

const shared = (name: string) => JSON.parse(readFileSync(new URL(`../../../packages/schemas/v1/program/${name}`, import.meta.url), "utf8"));
const cases = shared("cases.json");
const expected = shared("expected.json");

const attempt = <T>(f: () => T): { ok: T } | { error: string } => {
  try {
    return { ok: f() };
  } catch (e) {
    return { error: (e as Error).message };
  }
};

test("program validation matches the Rust gateway", () => {
  assert.deepEqual(cases.validate.map((p: unknown) => attempt(() => validate(p))), expected.validate);
});

test("selectors resolve as on the Rust gateway", () => {
  const got = cases.resolve.map((c: { nodes: UiNode[]; sel: unknown; want: Want }) => resolve(c.nodes, selector(c.sel, 0), c.want));
  assert.deepEqual(got, expected.resolve);
});

test("conditions hold as on the Rust gateway", () => {
  const obs = cases.observation as Observation;
  assert.deepEqual(cases.holds.map((c: { cond: unknown }) => holds(obs, cases.vars, condition(c.cond, 0))), expected.holds);
});

test("templates fill as on the Rust gateway", () => {
  assert.deepEqual(cases.fill.map((c: { template: string; vars: Record<string, never> }) => attempt(() => fill(c.template, c.vars))), expected.fill);
});
