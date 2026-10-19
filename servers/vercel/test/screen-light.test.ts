// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
import assert from "node:assert/strict";
import { test } from "node:test";
import { screenLightCommand } from "../src/mcp.js";

// ADR-056: the same command the Rust gateway sends (mcp.rs screen_light_tests).
test("the screen light command runs the helper from Latch's own APK", () => {
  assert.equal(
    screenLightCommand(false),
    "p=$( (pm path io.github.aspershupadhyay.latch || pm path io.github.aspershupadhyay.latch.debug) 2>/dev/null | head -n1); " +
      "CLASSPATH=\"${p#package:}\" app_process / io.github.aspershupadhyay.latch.session.ScreenLight off",
  );
  assert.ok(screenLightCommand(true).endsWith("ScreenLight on"));
});
