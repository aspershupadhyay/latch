// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import io.github.aspershupadhyay.latch.protocol.AdbResult
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.session.AdbShellBridge
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-device full-access ADB executor (R27, ADR-046): it opens an
 * authenticated [AdbConnection] to this phone's own `adbd` and runs the
 * command, returning ADB's real stdout, stderr, and exit code unchanged — no
 * allowlist, filter, or per-command approval (those are the owner's one-time
 * enablement and Stop, enforced above this layer).
 *
 * Output larger than one device→gateway frame is handed back a chunk at a time
 * with a `cursor`, exactly as the fake device and both gateways expect, so the
 * complete result is preserved through continuation rather than truncated.
 *
 * [openConnection] is a factory so the real app injects a socket transport and
 * tests inject an in-process fake adbd; the executor owns the protocol, not the
 * transport.
 */
internal class AdbShellExecutor(
    private val openConnection: () -> AdbConnection,
) : AdbShellBridge {
    private class Pending(val stdout: ByteArray, val exitCode: Int)

    private val lock = Mutex()
    private val pending = HashMap<String, Pending>()
    private var cursorSeq = 0

    override suspend fun run(command: Command.AdbShell): AdbResult = lock.withLock {
        command.continueFrom?.let { return nextChunk(it) }

        val line = command.commandLine ?: throw AdbProtocolException("command_line is required")
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val exit = withContext(Dispatchers.IO) {
            val connection = openConnection()
            connection.connect()
            connection.shell(
                command = line,
                stdin = command.stdin?.toByteArray(Charsets.UTF_8),
                onStdout = { stdoutBytes.write(it) },
                onStderr = { stderrBytes.write(it) },
            )
        }
        firstChunk(stdoutBytes.toByteArray(), stderrBytes.toByteArray(), exit)
    }

    /** The first frame of a result, chunking stdout and parking the remainder under a cursor. */
    private fun firstChunk(stdout: ByteArray, stderr: ByteArray, exit: Int): AdbResult {
        val stderrText = String(stderr, Charsets.UTF_8)
        if (stdout.size <= CHUNK_BYTES) {
            return AdbResult(stdout = String(stdout, Charsets.UTF_8), stderr = stderrText, exitCode = exit)
        }
        val head = stdout.copyOfRange(0, CHUNK_BYTES)
        val cursor = newCursor()
        pending[cursor] = Pending(stdout.copyOfRange(CHUNK_BYTES, stdout.size), exit)
        return AdbResult(stdout = String(head, Charsets.UTF_8), stderr = stderrText, cursor = cursor)
    }

    /** A later frame for a `continue_from` cursor. */
    private fun nextChunk(cursor: String): AdbResult {
        val part = pending.remove(cursor)
            ?: throw AdbProtocolException("no such adb.shell continuation")
        if (part.stdout.size <= CHUNK_BYTES) {
            return AdbResult(stdout = String(part.stdout, Charsets.UTF_8), stderr = "", exitCode = part.exitCode)
        }
        val head = part.stdout.copyOfRange(0, CHUNK_BYTES)
        val next = newCursor()
        pending[next] = Pending(part.stdout.copyOfRange(CHUNK_BYTES, part.stdout.size), part.exitCode)
        return AdbResult(stdout = String(head, Charsets.UTF_8), stderr = "", cursor = next)
    }

    private fun newCursor(): String = "adbc_${++cursorSeq}"

    companion object {
        /**
         * Most stdout bytes per device→gateway frame, under the 1 MiB gateway
         * frame limit with room for the message header and the stderr field.
         */
        const val CHUNK_BYTES = 900 * 1024
    }
}
