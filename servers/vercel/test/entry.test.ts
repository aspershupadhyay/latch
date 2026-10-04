// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// The Vercel Function only receives the HTTP methods api/gateway.ts exports;
// any other method gets Vercel's own 405 before the gateway sees it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as entry from "../api/gateway.js";

test("the Vercel entry point exports every method the gateway routes", () => {
  const routed = new Set<string>();
  const source = readFileSync(new URL("../src/gateway.ts", import.meta.url), "utf8");
  for (const m of source.matchAll(/m === "([A-Z]+)"/g)) routed.add(m[1]!);
  assert.ok(routed.has("PUT"), "uploads use PUT");
  for (const method of routed) {
    assert.equal(typeof (entry as Record<string, unknown>)[method], "function", `api/gateway.ts must export ${method}`);
  }
});
