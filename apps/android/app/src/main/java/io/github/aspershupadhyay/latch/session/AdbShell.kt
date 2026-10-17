// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.AdbResult
import io.github.aspershupadhyay.latch.protocol.Command

/**
 * Since 1.17 (ADR-046, R27): the owner-authorized full-access ADB executor.
 *
 * When the owner enables full-access ADB and authorizes Android wireless
 * debugging, an implementation runs the command line on this device over the
 * loopback ADB connection and returns ADB's own output. It is deliberately
 * unrestricted: no command allowlist, per-command approval, output filter, or
 * quota — Android's own ADB permissions set the boundary (ADR-046). The result
 * goes only to the authenticated client and is never written to the activity
 * log. Large output is chunked over several [AdbResult]s joined by a [cursor].
 *
 * The concrete wireless-debugging transport is device-gated and verified on a
 * real phone (roadmap R27); [CommandExecutor] holds this as null until it is
 * wired, and refuses `adb.shell` as unsupported in the meantime.
 */
interface AdbShellBridge {
    suspend fun run(command: Command.AdbShell): AdbResult
}
