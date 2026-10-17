// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import io.github.aspershupadhyay.latch.protocol.AdbResult
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.session.AdbShellBridge
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * Chunks are cut on character boundaries and sized by their JSON-encoded
 * length, so a multibyte character is never split and control-heavy output
 * cannot push a frame past the gateway's limit.
 *
 * Cancelling the caller (owner Stop, a gateway `cancel`, or a deadline the
 * caller asked for) closes the connection, which unblocks the socket read at
 * once; blocking socket IO would otherwise ignore coroutine cancellation and
 * hold the phone's command queue until the command ended on its own.
 *
 * [openConnection] is a factory so the real app injects a socket transport and
 * tests inject an in-process fake adbd; the executor owns the protocol, not the
 * transport.
 */
internal class AdbShellExecutor(
    private val openConnection: suspend () -> AdbConnection,
) : AdbShellBridge {
    private class Pending(val stdout: String, val stderr: String, val exitCode: Int)

    private val lock = Mutex()

    /** Unread remainders by cursor, oldest first; abandoned ones are evicted past [MAX_PENDING]. */
    private val pending = object : LinkedHashMap<String, Pending>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pending>?) = size > MAX_PENDING
    }
    private var cursorSeq = 0

    override suspend fun run(command: Command.AdbShell): AdbResult = lock.withLock {
        command.continueFrom?.let { cursor ->
            val part = pending.remove(cursor)
                ?: throw ProtocolException(ErrorCode.INVALID_REQUEST, "no such adb.shell continuation (each cursor works once)")
            return frame(part.stdout, part.stderr, part.exitCode)
        }

        val line = command.commandLine ?: throw ProtocolException(ErrorCode.INVALID_REQUEST, "command_line is required")
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val connection = try {
            withContext(Dispatchers.IO) { openConnection() }
        } catch (e: Exception) {
            if (e is CancellationException || e is ProtocolException) throw e
            currentCoroutineContext().ensureActive()
            throw ProtocolException(ErrorCode.TRANSPORT_UNAVAILABLE, "could not reach this phone's wireless debugging (is it on?): ${reason(e)}")
        }
        var started = false
        val exit = try {
            coroutineScope {
                // Closing the connection is the only way to interrupt a blocked socket read.
                val closeOnCancel = launch {
                    try { awaitCancellation() } finally { connection.close() }
                }
                try {
                    withContext(Dispatchers.IO) {
                        connection.connect()
                        started = true
                        connection.shell(
                            command = line,
                            stdin = command.stdin?.toByteArray(Charsets.UTF_8),
                            onStdout = { stdoutBytes.write(it) },
                            onStderr = { stderrBytes.write(it) },
                        )
                    }
                } finally {
                    closeOnCancel.cancel()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is ProtocolException) throw e
            currentCoroutineContext().ensureActive()
            // Before the command was sent a retry is safe; after, it may have partly run.
            throw if (!started) {
                ProtocolException(ErrorCode.TRANSPORT_UNAVAILABLE, "adbd refused the connection: ${reason(e)}")
            } else {
                ProtocolException(ErrorCode.INTERNAL, "the ADB connection broke while the command ran; it may have partly run: ${reason(e)}")
            }
        } finally {
            connection.close()
        }
        frame(String(stdoutBytes.toByteArray(), Charsets.UTF_8), String(stderrBytes.toByteArray(), Charsets.UTF_8), exit)
    }

    /**
     * One frame of a result: as much stdout as fits, then stderr, and the exit
     * code only with the last frame. The remainder is parked under a cursor.
     */
    private fun frame(stdout: String, stderr: String, exit: Int): AdbResult {
        val outEnd = fitting(stdout, 0, CHUNK_BYTES)
        val left = CHUNK_BYTES - jsonSize(stdout, 0, outEnd)
        val errEnd = if (outEnd == stdout.length) fitting(stderr, 0, left) else 0
        if (outEnd == stdout.length && errEnd == stderr.length) {
            return AdbResult(stdout = stdout, stderr = stderr, exitCode = exit)
        }
        val cursor = "adbc_${++cursorSeq}"
        pending[cursor] = Pending(stdout.substring(outEnd), stderr.substring(errEnd), exit)
        return AdbResult(stdout = stdout.substring(0, outEnd), stderr = stderr.substring(0, errEnd), cursor = cursor)
    }

    companion object {
        private fun reason(e: Exception) = e.message ?: e.javaClass.simpleName

        /**
         * Most JSON-encoded output bytes per device→gateway frame, under the
         * 1 MiB gateway frame limit with room for the message envelope.
         */
        const val CHUNK_BYTES = 900 * 1024

        /** Results a client may leave unread before the oldest is dropped. */
        const val MAX_PENDING = 4

        /** The end index of the longest prefix of [text] from [start] whose JSON size fits [budget]; never splits a surrogate pair. */
        internal fun fitting(text: String, start: Int, budget: Int): Int {
            var used = 0
            var i = start
            while (i < text.length) {
                val c = text[i]
                val pair = Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
                val cost = if (pair) 4 else jsonCost(c)
                if (used + cost > budget) break
                used += cost
                i += if (pair) 2 else 1
            }
            return i
        }

        /** The JSON-encoded UTF-8 size of `text[start, end)`. */
        internal fun jsonSize(text: String, start: Int, end: Int): Int {
            var size = 0
            var i = start
            while (i < end) {
                val c = text[i]
                if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text[i + 1])) {
                    size += 4; i += 2
                } else {
                    size += jsonCost(c); i += 1
                }
            }
            return size
        }

        /** Bytes one UTF-16 unit takes inside a JSON string: escapes, then UTF-8 width. */
        private fun jsonCost(c: Char): Int = when {
            c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\u000C' -> 2
            c < ' ' -> 6
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
    }
}
