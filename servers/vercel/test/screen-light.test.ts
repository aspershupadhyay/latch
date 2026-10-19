// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
import assert from "node:assert/strict";
import { test } from "node:test";
import { screenLightCommand, screenShareProtectionCommand, screenShareProtectionText } from "../src/mcp.js";

// ADR-056: the same command the Rust gateway sends (mcp.rs screen_light_tests).
test("the screen light command runs the helper from Latch's own APK", () => {
  assert.equal(
    screenLightCommand(false),
    "p=$( (pm path io.github.aspershupadhyay.latch || pm path io.github.aspershupadhyay.latch.debug) 2>/dev/null | head -n1); " +
      "CLASSPATH=\"${p#package:}\" app_process / io.github.aspershupadhyay.latch.session.ScreenLight off",
  );
  assert.ok(screenLightCommand(true).endsWith("ScreenLight on"));
});

// ADR-057: the same command and answers as the Rust gateway (mcp.rs screen_light_tests).
test("the notifications switch turns Android 15's screen-share protection off and on", () => {
  assert.equal(
    screenShareProtectionCommand(true),
    "settings put global disable_screen_share_protections_for_apps_and_notifications 1 && getprop ro.build.version.sdk",
  );
  assert.ok(screenShareProtectionCommand(false).includes("notifications 0 &&"));
  assert.match(screenShareProtectionText(true, 34), /nothing to change/);
  assert.match(screenShareProtectionText(true, 35), /stop and start mirroring/);
  assert.match(screenShareProtectionText(false, 0), /hides notifications/);
});
