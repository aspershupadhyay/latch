// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import io.github.aspershupadhyay.latch.protocol.AdbResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the app talks to [io.github.aspershupadhyay.latch.session.ShellInput]
 * (ADR-057): the command lines, and which keys and text it can send. Plain
 * Kotlin, tested on the JVM.
 */
object ShellInputCommands {
    const val HELPER = "io.github.aspershupadhyay.latch.session.ShellInput"

    // android.view.KeyEvent codes and meta states; constants, so this stays testable off Android.
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_A = 29
    const val KEYCODE_C = 31
    const val KEYCODE_V = 50
    const val KEYCODE_X = 52
    const val KEYCODE_TAB = 61
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_FORWARD_DEL = 112
    const val KEYCODE_MOVE_HOME = 122
    const val KEYCODE_MOVE_END = 123
    const val META_CTRL = 0x1000 or 0x2000

    /** The shell line that starts the helper from this APK; [apk] is the app's own `sourceDir`. */
    fun start(apk: String, port: Int, token: String): String {
        require(apk.none { it == '\'' || it.isISOControl() }) { "unexpected APK path" }
        require(token.all { it in '0'..'9' || it in 'a'..'f' }) { "token must be hex" }
        return "CLASSPATH='$apk' exec app_process / $HELPER $port $token"
    }

    fun touch(phase: Phase, x: Int, y: Int): String = "${when (phase) {
        Phase.DOWN -> "d"
        Phase.MOVE -> "m"
        Phase.UP -> "u"
        Phase.CANCEL -> "c"
    }} $x $y"

    fun scroll(x: Int, y: Int, dx: Int, dy: Int): String = "s $x $y $dx $dy"

    /** R44: the phone's new copies go to the computer while [on]. */
    fun watchClipboard(on: Boolean): String = if (on) "cw 1" else "cw 0"

    /** R42: two fingers down, moved, or lifted, at [f] (x0, y0, x1, y1). */
    fun pinch(phase: PinchPhase, f: IntArray): String = "${when (phase) {
        PinchPhase.START -> "pd"
        PinchPhase.MOVE -> "pm"
        PinchPhase.END -> "pu"
    }} ${f[0]} ${f[1]} ${f[2]} ${f[3]}"

    fun key(code: Int, meta: Int = 0): String = "k $code $meta"

    fun text(text: String): String = "t " + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** A key from the page as Android's key code and meta state, or null for one the helper does not press. */
    fun key(key: MirrorKey): Pair<Int, Int>? = when (key) {
        MirrorKey.BACKSPACE -> KEYCODE_DEL to 0
        MirrorKey.DELETE -> KEYCODE_FORWARD_DEL to 0
        MirrorKey.TAB -> KEYCODE_TAB to 0
        MirrorKey.LEFT -> KEYCODE_DPAD_LEFT to 0
        MirrorKey.RIGHT -> KEYCODE_DPAD_RIGHT to 0
        MirrorKey.UP -> KEYCODE_DPAD_UP to 0
        MirrorKey.DOWN -> KEYCODE_DPAD_DOWN to 0
        MirrorKey.HOME -> KEYCODE_MOVE_HOME to 0
        MirrorKey.END -> KEYCODE_MOVE_END to 0
        MirrorKey.SELECT_ALL -> KEYCODE_A to META_CTRL
        MirrorKey.WORD_BACKSPACE -> KEYCODE_DEL to META_CTRL
        MirrorKey.WORD_LEFT -> KEYCODE_DPAD_LEFT to META_CTRL
        MirrorKey.WORD_RIGHT -> KEYCODE_DPAD_RIGHT to META_CTRL
        MirrorKey.NEWLINE -> KEYCODE_ENTER to 0
        // Enter means the keyboard's action (Send, Search, Go), which only the field knows.
        MirrorKey.ENTER -> null
    }

    /**
     * [text] cut into runs the helper can type as key presses (printable
     * ASCII, on every phone's virtual keyboard map) and runs it cannot (emoji,
     * accents, other scripts), which go into the field another way. In order.
     */
    fun runs(text: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val run = StringBuilder()
        var keys: Boolean? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val typeable = cp in 0x20..0x7e
            if (keys != null && keys != typeable) {
                out += run.toString() to keys
                run.setLength(0)
            }
            keys = typeable
            run.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (keys != null) out += run.toString() to keys
        return out
    }

    fun randomToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The app's side of the input helper (ADR-057): starts it through the owner's
 * Full ADB shell, connects on the phone's loopback, and sends commands in
 * order on one writer thread. [shell] runs one command line and returns when
 * it ends; cancelling it ends the helper. Everything fails soft: while the
 * helper is not [ready], the mirror uses the accessibility service instead.
 */
class ShellInputLink(
    private val scope: CoroutineScope,
    private val apk: String,
    private val shell: suspend (String) -> AdbResult?,
) {
    @Volatile var ready = false
        private set

    /** R44: a copy made on the phone while the owner's switch is on. */
    @Volatile var onCopied: ((String) -> Unit)? = null

    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    // Display priority: a click or key from the computer must not queue behind a busy app's threads.
    private val out = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            r.run()
        }, "latch-shell-input").apply { isDaemon = true }
    }
    @Volatile private var clip: CompletableDeferred<String?>? = null
    private val pings = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val seq = AtomicInteger()

    /** Starts the helper; [ended] gets why it could not start or stopped (null when stopped on purpose). */
    fun start(started: () -> Unit, ended: (String?) -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var why: String? = null
            for (attempt in 0 until ATTEMPTS) {
                val token = ShellInputCommands.randomToken()
                val port = PORT_FROM + SecureRandom().nextInt(PORT_SPAN)
                val run = async { runCatching { shell(ShellInputCommands.start(apk, port, token)) } }
                val connected = connect(port, token) { run.isCompleted }
                if (connected == null) {
                    run.cancel()
                    val result = runCatching { run.await() }.getOrNull()?.getOrNull()
                    why = result?.stderr?.trim()?.lineSequence()?.lastOrNull()?.take(200)
                        ?: "Full ADB did not start the input helper"
                    // Exit 3: the port was taken; try another.
                    if (result?.exitCode == 3) continue
                    break
                }
                val (s, reader) = connected
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                ready = true
                started()
                read(reader)
                ready = false
                run.cancel()
                ended(if (isActive) "the input helper stopped" else null)
                return@launch
            }
            ended(why)
        }
    }

    fun stop() {
        ready = false
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        clip?.complete(null)
        pings.values.forEach { it.complete(Unit) }
        pings.clear()
    }

    /** Sends one command line; dropped when the helper is not connected. */
    fun send(line: String) {
        val w = writer ?: return
        out.execute {
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                runCatching { socket?.close() }
            }
        }
    }

    /** The phone's clipboard as the shell reads it, or null. */
    suspend fun clipboard(): String? {
        if (!ready) return null
        val waiting = CompletableDeferred<String?>()
        clip?.complete(null)
        clip = waiting
        send("cg")
        return withTimeoutOrNull(CLIP_WAIT_MS) { waiting.await() }
    }

    /** Waits until everything sent so far was injected (or a short time passed). */
    suspend fun flush() {
        if (!ready) return
        val id = seq.incrementAndGet().toString()
        val done = CompletableDeferred<Unit>()
        pings[id] = done
        send("p $id")
        withTimeoutOrNull(FLUSH_WAIT_MS) { done.await() }
        pings.remove(id)
    }

    private suspend fun connect(port: Int, token: String, gaveUp: () -> Boolean): Pair<Socket, BufferedReader>? {
        val until = System.currentTimeMillis() + CONNECT_MS
        while (System.currentTimeMillis() < until && !gaveUp()) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", port), 500)
                s.soTimeout = 3_000
                val w = s.getOutputStream()
                w.write("auth $token\n".toByteArray())
                w.flush()
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                if (reader.readLine() == "ready") {
                    s.soTimeout = 0
                    return s to reader
                }
                s.close()
            } catch (e: Exception) {
                runCatching { s.close() }
            }
            delay(CONNECT_RETRY_MS)
        }
        return null
    }

    private fun read(reader: BufferedReader) {
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return
            when {
                line.startsWith("cg ") -> {
                    val value = line.substring(3)
                    val text = if (value == "-") null else runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
                    clip?.complete(text)
                }
                line.startsWith("p ") -> pings.remove(line.substring(2))?.complete(Unit)
                line.startsWith("cc ") -> runCatching { String(Base64.getDecoder().decode(line.substring(3)), Charsets.UTF_8) }
                    .getOrNull()?.let { text -> onCopied?.invoke(text) }
            }
        }
    }

    companion object {
        private const val ATTEMPTS = 3
        private const val PORT_FROM = 20_000
        private const val PORT_SPAN = 40_000
        private const val CONNECT_MS = 6_000L
        private const val CONNECT_RETRY_MS = 100L
        private const val CLIP_WAIT_MS = 2_000L
        private const val FLUSH_WAIT_MS = 300L
    }
}
