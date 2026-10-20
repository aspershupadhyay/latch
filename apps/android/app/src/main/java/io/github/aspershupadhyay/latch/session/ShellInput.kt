// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.content.ClipData
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64

/**
 * The live mirror's input helper (ADR-057): real touch, scroll, and key
 * events, injected the way Android's own `input` command does, so a drag on
 * the computer moves the phone's screen as it happens and typing behaves as a
 * keyboard (the app's own cursor, Backspace, and hints).
 *
 * Like [ScreenLight] it is not called inside the app: with Full ADB on, the
 * app starts it through its own ADB shell as
 * `CLASSPATH=<this APK> app_process / io.github.aspershupadhyay.latch.session.ShellInput <port> <token>`,
 * because only the shell user may inject input. It listens on the phone's
 * own loopback, takes one connection that proves [token] (a random 256-bit
 * secret only the app knows), and exits when that connection closes or when
 * no one proves the token within [ACCEPT_MS].
 *
 * The connection carries one command per line (see [ShellInputCommands]):
 * `d|m|u|c x y` touch down, move, up, cancel; `s x y h v` scroll;
 * `k code meta` a key press; `t <base64>` text; `cg` reads the clipboard
 * (answered `cg <base64>` or `cg -`); `p n` answers `p n` once everything
 * before it was injected.
 */
object ShellInput {
    private const val ACCEPT_MS = 15_000
    private const val AUTH_MS = 3_000

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1024..65535 }
        val token = args.getOrNull(1)?.takeIf { it.length >= 32 }
        if (port == null || token == null) {
            System.err.println("usage: ShellInput <port> <token>")
            System.exit(2)
            return
        }
        // Some Android versions build handlers inside the input manager.
        runCatching { Looper.prepare() }
        val injector = try {
            Injector()
        } catch (e: Throwable) {
            System.err.println("input injection is not available: ${describe(e)}")
            System.exit(1)
            return
        }
        val server = try {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            System.err.println("could not listen on port $port: ${e.message}")
            System.exit(3)
            return
        }
        val deadline = SystemClock.uptimeMillis() + ACCEPT_MS
        val expected = "auth $token".toByteArray()
        while (true) {
            val left = (deadline - SystemClock.uptimeMillis()).toInt()
            if (left <= 0) break
            server.soTimeout = left
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                break
            }
            socket.tcpNoDelay = true
            socket.soTimeout = AUTH_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val first = runCatching { reader.readLine() }.getOrNull()?.toByteArray()
            if (first == null || !MessageDigest.isEqual(first, expected)) {
                runCatching { socket.close() }
                continue
            }
            // One owner, one connection: nobody else gets in after this.
            runCatching { server.close() }
            socket.soTimeout = 0
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write("ready\n")
            writer.flush()
            serve(reader, writer, injector)
            runCatching { socket.close() }
            System.exit(0)
            return
        }
        System.err.println("the app did not connect")
        System.exit(4)
    }

    private fun serve(reader: BufferedReader, writer: BufferedWriter, injector: Injector) {
        var downAt = 0L
        // R44: the owner's switch on the page; the watch thread writes too, so writes take the writer's lock.
        val watch = ClipboardWatch(writer)
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return
            val parts = line.split(' ')
            try {
                when (parts[0]) {
                    "d", "m", "u", "c" -> {
                        val x = parts[1].toFloat()
                        val y = parts[2].toFloat()
                        val now = SystemClock.uptimeMillis()
                        if (parts[0] == "d") downAt = now
                        val action = when (parts[0]) {
                            "d" -> MotionEvent.ACTION_DOWN
                            "m" -> MotionEvent.ACTION_MOVE
                            "u" -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_CANCEL
                        }
                        injector.touch(downAt, now, action, x, y)
                    }
                    "s" -> injector.scroll(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat())
                    // R42: two fingers (pinch, turn): down, move, up; x0 y0 x1 y1.
                    "pd", "pm", "pu" -> {
                        val f = FloatArray(4) { parts[it + 1].toFloat() }
                        val now = SystemClock.uptimeMillis()
                        if (parts[0] == "pd") downAt = now
                        injector.twoFingers(downAt, now, parts[0], f)
                    }
                    "k" -> injector.key(parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
                    "t" -> injector.text(String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8))
                    "cg" -> {
                        val text = runCatching { Clipboard.text() }.getOrNull()
                        synchronized(writer) {
                            writer.write(if (text.isNullOrEmpty()) "cg -\n" else "cg ${Base64.getEncoder().encodeToString(text.toByteArray())}\n")
                            writer.flush()
                        }
                    }
                    "cw" -> watch.set(parts.getOrNull(1) == "1")
                    "p" -> synchronized(writer) {
                        writer.write("p ${parts.getOrNull(1).orEmpty()}\n")
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                // A malformed line is skipped; the app only sends well-formed ones.
                runCatching {
                    synchronized(writer) {
                        writer.write("e ${describe(e).replace('\n', ' ').take(200)}\n")
                        writer.flush()
                    }
                }
            }
        }
    }

    private fun describe(e: Throwable): String {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** Android's input manager, as `input` uses it: InputManagerGlobal from Android 14, InputManager before. */
    private class Injector {
        private val manager: Any
        private val inject: Method
        private val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        private val scrollPx = android.content.res.Resources.getSystem().displayMetrics.density * SCROLL_DP
        private val finger = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        private val mouse = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })

        init {
            val type = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
                ?: Class.forName("android.hardware.input.InputManager")
            manager = type.getDeclaredMethod("getInstance").invoke(null) ?: error("no input manager")
            inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }

        private fun send(event: InputEvent) {
            inject.invoke(manager, event, INJECT_ASYNC)
        }

        fun touch(downAt: Long, now: Long, action: Int, x: Float, y: Float) {
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                    size = 1f
                },
            )
            val event = MotionEvent.obtain(downAt, now, action, 1, finger, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                send(event)
            } finally {
                event.recycle()
            }
        }

        private val fingers = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )

        /** R42: "pd" puts two fingers down (one, then the second), "pm" moves both, "pu" lifts the second, then the first. */
        fun twoFingers(downAt: Long, now: Long, step: String, f: FloatArray) {
            fun coords(up: Boolean) = arrayOf(
                MotionEvent.PointerCoords().apply { x = f[0]; y = f[1]; pressure = 1f; size = 1f },
                MotionEvent.PointerCoords().apply { x = f[2]; y = f[3]; pressure = if (up) 0f else 1f; size = 1f },
            )
            val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            val events = when (step) {
                "pd" -> listOf(
                    MotionEvent.obtain(downAt, now, MotionEvent.ACTION_DOWN, 1, fingers, coords(false), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0),
                    MotionEvent.obtain(downAt, now, MotionEvent.ACTION_POINTER_DOWN or second, 2, fingers, coords(false), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0),
                )
                "pm" -> listOf(MotionEvent.obtain(downAt, now, MotionEvent.ACTION_MOVE, 2, fingers, coords(false), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
                else -> listOf(
                    MotionEvent.obtain(downAt, now, MotionEvent.ACTION_POINTER_UP or second, 2, fingers, coords(true), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0),
                    MotionEvent.obtain(downAt, now, MotionEvent.ACTION_UP, 1, fingers, coords(true), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0),
                )
            }
            for (event in events) {
                try {
                    send(event)
                } finally {
                    event.recycle()
                }
            }
        }

        /** [h] and [v] in phone pixels, as the page measured them; a mouse wheel scrolls in steps of [SCROLL_DP]. */
        fun scroll(x: Float, y: Float, h: Float, v: Float) {
            val now = SystemClock.uptimeMillis()
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    setAxisValue(MotionEvent.AXIS_HSCROLL, h / scrollPx)
                    // A wheel turned toward the person scrolls the content up.
                    setAxisValue(MotionEvent.AXIS_VSCROLL, -v / scrollPx)
                },
            )
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, mouse, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try {
                send(event)
            } finally {
                event.recycle()
            }
        }

        fun key(code: Int, meta: Int) {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                send(KeyEvent(now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
            }
        }

        fun text(text: String) {
            // The app checks with ShellInputCommands.typeable first; anything else is skipped.
            val events = keys.getEvents(text.toCharArray()) ?: return
            for (e in events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0))
        }
    }

    /**
     * R44: while the owner's switch is on, a new copy on the phone goes to the
     * app. Only the clip's description (when it was made, whether Android marks
     * it sensitive) is checked every [WATCH_MS], which does not count as reading
     * it; the text is read once per new copy, and a sensitive one (a password
     * manager's) never.
     */
    private class ClipboardWatch(private val writer: BufferedWriter) {
        @Volatile private var on = false
        private var thread: Thread? = null

        fun set(value: Boolean) {
            on = value
            if (value && thread == null) {
                thread = Thread({ run() }, "latch-clipboard-watch").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun run() {
            // Only copies made after the switch went on follow.
            var seen = runCatching { Clipboard.description()?.timestamp }.getOrNull()
            while (true) {
                Thread.sleep(WATCH_MS)
                if (!on) continue
                val description = runCatching { Clipboard.description() }.getOrNull() ?: continue
                if (description.timestamp == seen) continue
                seen = description.timestamp
                if (description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) continue
                if (!description.hasMimeType("text/*")) continue
                val text = runCatching { Clipboard.text() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                runCatching {
                    synchronized(writer) {
                        writer.write("cc ${Base64.getEncoder().encodeToString(text.toByteArray())}\n")
                        writer.flush()
                    }
                }
            }
        }
    }

    /** The clipboard as the shell reads it: IClipboard's arguments changed across Android versions. */
    private object Clipboard {
        private fun service(): Any {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            return Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: error("no clipboard service")
        }

        /** (package), (package, user), (package, attribution tag, user), (package, attribution tag, user, device). */
        private fun arguments(method: Method): Array<Any?> {
            var strings = 0
            return method.parameterTypes.map { type ->
                when (type) {
                    String::class.java -> if (strings++ == 0) "com.android.shell" else null
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }.toTypedArray()
        }

        /** What the clip is and when it was made, without reading it (R44). */
        fun description(): android.content.ClipDescription? {
            val service = service()
            val get = service.javaClass.methods.first { it.name == "getPrimaryClipDescription" }
            return get.invoke(service, *arguments(get)) as android.content.ClipDescription?
        }

        fun text(): String? {
            val service = service()
            val get = service.javaClass.methods.first { it.name == "getPrimaryClip" }
            var strings = 0
            // (package), (package, user), (package, attribution tag, user), (package, attribution tag, user, device).
            val args: List<Any?> = get.parameterTypes.map { type ->
                when (type) {
                    String::class.java -> if (strings++ == 0) "com.android.shell" else null
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            val clip = get.invoke(service, *args.toTypedArray<Any?>()) as ClipData? ?: return null
            if (clip.itemCount == 0) return null
            return clip.getItemAt(0).text?.toString()
        }
    }

    private const val INJECT_ASYNC = 0
    private const val WATCH_MS = 400L
    /** ClipDescription.EXTRA_IS_SENSITIVE (Android 13), as a string so older phones run this too. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val SCROLL_DP = 64f
}
