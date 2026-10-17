// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import io.github.aspershupadhyay.latch.protocol.AdbJobState
import io.github.aspershupadhyay.latch.protocol.AdbResult
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.session.AdbShellBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * Since 1.20 (ADR-049) a command can run as a job: it starts in this
 * executor's own scope, so it outlives the call that started it and any
 * reconnect of the link, and writes its output to two files under [jobDir]
 * (the app's private cache). Each collection reads what was written since the
 * last one; the job is forgotten, and its files deleted, once it finished and
 * everything was read, when it is cancelled, or when the owner presses Stop.
 * Files rather than memory, because ADR-046 allows no output cap: the disk is
 * the only limit, and nothing is kept once collected.
 *
 * [openConnection] is a factory so the real app injects a socket transport and
 * tests inject an in-process fake adbd; the executor owns the protocol, not the
 * transport.
 */
internal class AdbShellExecutor(
    private val jobDir: File? = null,
    private val openConnection: suspend () -> AdbConnection,
) : AdbShellBridge {
    private class Pending(val stdout: String, val stderr: String, val exitCode: Int?, val job: String?, val jobState: AdbJobState?)

    /** One background command (1.20). Offsets are what was already collected. */
    private class Job(val id: String, val out: File, val err: File) {
        val done = CompletableDeferred<Unit>()
        @Volatile var exit: Int? = null
        @Volatile var failure: String? = null
        var outRead = 0L
        var errRead = 0L
        var task: kotlinx.coroutines.Job? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File by lazy {
        (jobDir ?: File(System.getProperty("java.io.tmpdir"), "latch-adb-jobs")).apply { mkdirs() }
    }

    init {
        // Output left by a job of an earlier app run is nobody's any more.
        jobDir?.listFiles()?.forEach { it.delete() }
    }

    /** Guards [pending] and [cursorSeq]; commands themselves run in parallel, each on its own connection. */
    private val lock = Any()

    /** Unread remainders by cursor, oldest first; abandoned ones are evicted past [MAX_PENDING]. */
    private val pending = object : LinkedHashMap<String, Pending>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pending>?) = size > MAX_PENDING
    }
    private var cursorSeq = 0

    override suspend fun run(command: Command.AdbShell): AdbResult {
        command.continueFrom?.let { cursor ->
            val part = synchronized(lock) { pending.remove(cursor) }
                ?: throw ProtocolException(ErrorCode.INVALID_REQUEST, "no such adb.shell continuation (each cursor works once)")
            return frame(part.stdout, part.stderr, part.exitCode, part.job, part.jobState)
        }
        command.job?.let { return collect(it, command.waitMs, command.cancel) }
        if (command.background) return start(command)

        val line = command.commandLine ?: throw ProtocolException(ErrorCode.INVALID_REQUEST, "command_line is required")
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val exit = execute(line, command.stdin, { stdoutBytes.write(it) }, { stderrBytes.write(it) })
        // Since 1.18: base64 keeps binary output (screencap -p) byte for byte.
        val stdout = if (command.base64) {
            java.util.Base64.getEncoder().encodeToString(stdoutBytes.toByteArray())
        } else {
            String(stdoutBytes.toByteArray(), Charsets.UTF_8)
        }
        return frame(stdout, String(stderrBytes.toByteArray(), Charsets.UTF_8), exit)
    }

    /** Since 1.20: starts [command] as a job and answers with its id at once. */
    private fun start(command: Command.AdbShell): AdbResult {
        val line = command.commandLine ?: throw ProtocolException(ErrorCode.INVALID_REQUEST, "command_line is required")
        val id = synchronized(lock) { "adbj_${++cursorSeq}" }
        val job = Job(id, File(dir, "$id.out"), File(dir, "$id.err"))
        jobs[id] = job
        job.task = jobScope.launch {
            try {
                FileOutputStream(job.out).use { out ->
                    FileOutputStream(job.err).use { err ->
                        val run = suspend { execute(line, command.stdin, { out.write(it); out.flush() }, { err.write(it); err.flush() }) }
                        // The caller's own deadline, if it asked for one; Latch sets none (ADR-046).
                        job.exit = if (command.timeoutMs > 0) withTimeout(command.timeoutMs.toLong()) { run() } else run()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                job.failure = "it ran past the timeout_ms you asked for"
            } catch (e: CancellationException) {
                job.failure = "cancelled"
            } catch (e: Exception) {
                job.failure = (e as? ProtocolException)?.message ?: reason(e)
            } finally {
                job.done.complete(Unit)
            }
        }
        return AdbResult(stdout = "", stderr = "", job = id, jobState = AdbJobState.RUNNING)
    }

    /**
     * Since 1.20: what the job wrote since the last collection, after waiting up
     * to [waitMs] for it to finish; with [cancel], ends it first.
     */
    private suspend fun collect(id: String, waitMs: Long, cancel: Boolean): AdbResult {
        val job = jobs[id] ?: throw ProtocolException(
            ErrorCode.INVALID_REQUEST,
            "no such adb job (it finished and was collected, or the owner pressed Stop)",
        )
        if (cancel) {
            job.task?.cancel()
            // Closing the connection ends the command; give it a moment to say so.
            withTimeoutOrNull(2_000) { job.done.await() }
        } else if (waitMs > 0) {
            withTimeoutOrNull(waitMs) { job.done.await() }
        }
        val finished = job.done.isCompleted
        val (stdout, outBytes, outMore) = read(job.out, job.outRead)
        val (stderr, errBytes, errMore) = read(job.err, job.errRead)
        job.outRead += outBytes
        job.errRead += errBytes
        val drained = finished && !outMore && !errMore
        if (cancel || drained) forget(job)
        return when {
            cancel -> frame(stdout, stderr, null, id, AdbJobState.CANCELLED)
            drained && job.exit == null -> throw ProtocolException(
                ErrorCode.INTERNAL,
                "the ADB job ended without an exit code; it may have partly run: ${job.failure ?: "unknown"}",
            )
            drained -> frame(stdout, stderr, job.exit, id, AdbJobState.FINISHED)
            else -> frame(stdout, stderr, null, id, AdbJobState.RUNNING)
        }
    }

    /** Owner Stop: every job ends, and its output is deleted unread. */
    override fun stopJobs() {
        jobScope.coroutineContext.cancelChildren()
        jobs.values.toList().forEach(::forget)
    }

    private fun forget(job: Job) {
        jobs.remove(job.id)
        job.out.delete()
        job.err.delete()
    }

    /**
     * New text in [file] from [offset]: at most [READ_BYTES] bytes, ending on a
     * whole UTF-8 character; the bytes it took; and whether more is written already.
     */
    private fun read(file: File, offset: Long): Triple<String, Int, Boolean> {
        if (!file.exists()) return Triple("", 0, false)
        RandomAccessFile(file, "r").use { f ->
            val available = f.length() - offset
            if (available <= 0) return Triple("", 0, false)
            val bytes = ByteArray(minOf(available, READ_BYTES.toLong()).toInt())
            f.seek(offset)
            f.readFully(bytes)
            val whole = utf8Prefix(bytes)
            return Triple(String(bytes, 0, whole, Charsets.UTF_8), whole, offset + whole < f.length())
        }
    }

    /**
     * Runs [line] on a fresh adbd connection, handing output to [onStdout] and
     * [onStderr] as it arrives; returns the exit code.
     */
    private suspend fun execute(line: String, stdin: String?, onStdout: (ByteArray) -> Unit, onStderr: (ByteArray) -> Unit): Int {
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
                            stdin = stdin?.toByteArray(Charsets.UTF_8),
                            onStdout = onStdout,
                            onStderr = onStderr,
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
        return exit
    }

    /**
     * One frame of a result: as much stdout as fits, then stderr, and the exit
     * code only with the last frame. The remainder is parked under a cursor.
     */
    private fun frame(stdout: String, stderr: String, exit: Int?, job: String? = null, jobState: AdbJobState? = null): AdbResult {
        val outEnd = fitting(stdout, 0, CHUNK_BYTES)
        val left = CHUNK_BYTES - jsonSize(stdout, 0, outEnd)
        val errEnd = if (outEnd == stdout.length) fitting(stderr, 0, left) else 0
        if (outEnd == stdout.length && errEnd == stderr.length) {
            return AdbResult(stdout = stdout, stderr = stderr, exitCode = exit, job = job, jobState = jobState)
        }
        val cursor = synchronized(lock) {
            "adbc_${++cursorSeq}".also { pending[it] = Pending(stdout.substring(outEnd), stderr.substring(errEnd), exit, job, jobState) }
        }
        // Until the last frame of a job's answer, more of it is still to come.
        return AdbResult(
            stdout = stdout.substring(0, outEnd), stderr = stderr.substring(0, errEnd), cursor = cursor,
            job = job, jobState = job?.let { AdbJobState.RUNNING },
        )
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

        /** Most of a job's output read in one collection; the rest waits for the next. */
        const val READ_BYTES = 16 * 1024 * 1024

        /** How many leading bytes of [bytes] are whole UTF-8 characters. */
        internal fun utf8Prefix(bytes: ByteArray): Int {
            var end = bytes.size
            // Step back over at most three continuation bytes to the last lead byte.
            var i = end - 1
            var back = 0
            while (i >= 0 && back < 3 && (bytes[i].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i < 0) return end
            val lead = bytes[i].toInt() and 0xFF
            val width = when {
                lead < 0x80 -> 1
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            if (end - i < width) end = i
            return end
        }

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
