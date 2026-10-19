// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer

/**
 * The mirror channel v1 (R30, ADR-051, docs/protocol/mirror.md), phone side.
 * Plain Kotlin, so the parser is tested against the same fixtures as the
 * gateway (`packages/schemas/v1/mirror`). The gateway already validated what
 * a viewer sent; the phone checks it again before touching the screen.
 */
sealed interface MirrorIn {
    data class Challenge(val version: Int, val connection: String) : MirrorIn
    data class Ready(val code: String?, val urls: List<String>) : MirrorIn
    data class Viewer(val on: Boolean, val codec: Codec) : MirrorIn
    data class Code(val code: String?) : MirrorIn
    data object Sync : MirrorIn
    data class Touch(val phase: Phase, val x: Int, val y: Int) : MirrorIn
    data class Wheel(val x: Int, val y: Int, val dx: Int, val dy: Int) : MirrorIn
    data class Text(val text: String) : MirrorIn
    data class Key(val key: MirrorKey) : MirrorIn
    data class Button(val name: MirrorButton) : MirrorIn
    data class Control(val action: MirrorAction) : MirrorIn
    data class Ping(val at: Double) : MirrorIn
    data class CodecChoice(val codec: Codec) : MirrorIn
    /** The viewer plays the phone's sound; the phone's own speaker goes quiet while [on]. */
    data class Sound(val on: Boolean) : MirrorIn
    /** Text from the computer's clipboard (ADR-057): onto the phone's clipboard, and into the field in focus. */
    data class Paste(val text: String) : MirrorIn
    /** Copy (or cut) what is selected on the phone, for the computer's clipboard (ADR-057). */
    data class Copy(val cut: Boolean) : MirrorIn
    /** A full-resolution screenshot for the computer (ADR-057). */
    data object Shot : MirrorIn
    /** From the gateway: a file the owner dropped on the page, to fetch into Downloads (ADR-057). */
    data class File(val url: String, val keyHex: String, val ivHex: String, val sha256: String, val name: String, val size: Long) : MirrorIn
}

enum class Codec(val wire: String) { H264("h264"), JPEG("jpeg") }
enum class Phase(val wire: String) { DOWN("down"), MOVE("move"), UP("up"), CANCEL("cancel") }
enum class MirrorKey(val wire: String) {
    BACKSPACE("backspace"), DELETE("delete"), ENTER("enter"), TAB("tab"), LEFT("left"), RIGHT("right"), UP("up"), DOWN("down"),
    // Since ADR-057: editing shortcuts from the computer's keyboard.
    HOME("home"), END("end"), SELECT_ALL("select_all"), WORD_BACKSPACE("word_backspace"), WORD_LEFT("word_left"), WORD_RIGHT("word_right"),
    NEWLINE("newline"),
}
enum class MirrorButton(val wire: String) { BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"), QUICK_SETTINGS("quick_settings") }
enum class MirrorAction(val wire: String) { PAUSE("pause"), RESUME("resume"), STOP("stop"), END("end") }

object MirrorMessages {
    const val VERSION = 1
    const val KIND_H264: Byte = 1
    const val KIND_JPEG: Byte = 2
    /** 48 kHz stereo 16-bit little-endian PCM, 20 ms per frame (ADR-055). */
    const val KIND_PCM: Byte = 3
    /** A full-resolution PNG screenshot the owner asked for (ADR-057). */
    const val KIND_SHOT: Byte = 4
    const val HEADER_BYTES = 10
    const val MAX_COORDINATE = 16_384
    const val MAX_TEXT_CHARS = 256
    const val MAX_PASTE_CHARS = 4_096
    private const val MAX_TEXT_BYTES = 16 * 1024

    /** Fields each message may carry besides `t`; anything else makes it invalid. */
    private val fields = mapOf(
        "challenge" to setOf("version", "connection"),
        "ready" to setOf("code", "code_expires_ms", "urls"),
        "viewer" to setOf("on", "codec"),
        "code" to setOf("code"),
        "sync" to emptySet(),
        "touch" to setOf("phase", "x", "y"),
        "wheel" to setOf("x", "y", "dx", "dy"),
        "text" to setOf("text"),
        "key" to setOf("key"),
        "button" to setOf("name"),
        "control" to setOf("action"),
        "ping" to setOf("at"),
        "codec" to setOf("codec"),
        "sound" to setOf("on"),
        "paste" to setOf("text"),
        "copy" to emptySet(),
        "cut" to emptySet(),
        "shot" to emptySet(),
        "file" to setOf("url", "key", "iv", "sha256", "name", "size"),
    )

    /** One text message from the gateway, or null for anything the phone must ignore. */
    fun parse(raw: String): MirrorIn? {
        if (raw.length > MAX_TEXT_BYTES) return null
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val t = o.string("t") ?: return null
        val allowed = fields[t] ?: return null
        if (o.keys.any { it != "t" && it !in allowed }) return null
        fun coord(name: String): Int? = o.int(name)?.takeIf { it in 0..MAX_COORDINATE }
        fun delta(name: String): Int? = o.int(name)?.takeIf { it in -MAX_COORDINATE..MAX_COORDINATE }
        return when (t) {
            "challenge" -> MirrorIn.Challenge(o.int("version") ?: return null, o.string("connection") ?: return null)
            "ready" -> MirrorIn.Ready(
                (o.nullableString("code") ?: return null).value,
                (o["urls"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(),
            )
            "viewer" -> MirrorIn.Viewer(
                o.bool("on") ?: return null,
                o.string("codec")?.let { c -> Codec.entries.firstOrNull { it.wire == c } ?: return null } ?: Codec.H264,
            )
            "code" -> MirrorIn.Code((o.nullableString("code") ?: return null).value)
            "sync" -> MirrorIn.Sync
            "touch" -> MirrorIn.Touch(o.enum("phase", Phase.entries) { it.wire } ?: return null, coord("x") ?: return null, coord("y") ?: return null)
            "wheel" -> MirrorIn.Wheel(coord("x") ?: return null, coord("y") ?: return null, delta("dx") ?: return null, delta("dy") ?: return null)
            "text" -> o.string("text")?.takeIf { validText(it) }?.let { MirrorIn.Text(it) }
            "key" -> MirrorIn.Key(o.enum("key", MirrorKey.entries) { it.wire } ?: return null)
            "button" -> MirrorIn.Button(o.enum("name", MirrorButton.entries) { it.wire } ?: return null)
            "control" -> MirrorIn.Control(o.enum("action", MirrorAction.entries) { it.wire } ?: return null)
            "ping" -> (o["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.let { MirrorIn.Ping(it) }
            "codec" -> MirrorIn.CodecChoice(o.enum("codec", Codec.entries) { it.wire } ?: return null)
            "sound" -> MirrorIn.Sound(o.bool("on") ?: return null)
            "paste" -> o.string("text")?.takeIf { validPaste(it) }?.let { MirrorIn.Paste(it) }
            "copy" -> MirrorIn.Copy(cut = false)
            "cut" -> MirrorIn.Copy(cut = true)
            "shot" -> MirrorIn.Shot
            "file" -> {
                fun hex(name: String, length: Int) = o.string(name)?.takeIf { v -> v.length == length && v.all { it in '0'..'9' || it in 'a'..'f' } }
                val name = o.string("name")?.takeIf { validFileName(it) } ?: return null
                val size = (o["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
                MirrorIn.File(
                    o.string("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null,
                    hex("key", 64) ?: return null,
                    hex("iv", 32) ?: return null,
                    hex("sha256", 64) ?: return null,
                    name,
                    size,
                )
            }
            else -> null
        }
    }

    /** 1–256 characters (code points), none of them control characters. */
    fun validText(text: String): Boolean {
        val count = text.codePointCount(0, text.length)
        return count in 1..MAX_TEXT_CHARS && text.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() }
    }

    /** 1–4096 characters; line breaks and tabs are kept, other control characters are not. */
    fun validPaste(text: String): Boolean {
        val count = text.codePointCount(0, text.length)
        return count in 1..MAX_PASTE_CHARS &&
            text.codePoints().noneMatch { it != '\n'.code && it != '\t'.code && Character.getType(it) == Character.CONTROL.toInt() }
    }

    /** A file name with no folder: 1–200 characters, no slashes or control characters. */
    fun validFileName(name: String): Boolean =
        name.codePointCount(0, name.length) in 1..200 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    // ---- What the phone sends ----

    fun start(key: String): String = buildJsonObject {
        put("t", "start")
        put("version", VERSION)
        put("key", key)
    }.toString()

    fun screen(width: Int, height: Int, rotation: Int): String = buildJsonObject {
        put("t", "screen")
        put("w", width)
        put("h", height)
        put("rotation", rotation)
    }.toString()

    /** One step on the page: who, which app, the Activity line (never screen or typed text). */
    data class Step(val atMs: Long, val by: String, val text: String, val app: String?)

    fun ai(session: String, owner: Boolean, working: Boolean, steps: List<Step>): String = buildJsonObject {
        put("t", "ai")
        put("session", session)
        put("owner", owner)
        put("working", working)
        put(
            "steps",
            buildJsonArray {
                for (s in steps) {
                    add(
                        buildJsonObject {
                            put("at", s.atMs)
                            put("by", s.by)
                            put("text", s.text.take(200))
                            s.app?.let { put("app", it.take(60)) }
                        },
                    )
                }
            },
        )
    }.toString()

    fun pong(at: Double): String = buildJsonObject {
        put("t", "pong")
        put("at", at)
    }.toString()

    fun notice(text: String): String = buildJsonObject {
        put("t", "notice")
        put("text", text)
    }.toString()

    fun end(): String = """{"t":"end"}"""

    /** Text for the computer's clipboard (ADR-057); only ever in answer to the owner's copy. */
    fun clipboard(text: String): String = buildJsonObject {
        put("t", "clipboard")
        put("text", text)
    }.toString()

    /** The phone's state at a glance for the page (ADR-057); null fields are what Android would not say. */
    fun status(battery: Int?, charging: Boolean?, network: String?, signal: Int?): String = buildJsonObject {
        put("t", "status")
        battery?.let { put("battery", it) }
        charging?.let { put("charging", it) }
        network?.let { put("network", it) }
        signal?.let { put("signal", it) }
    }.toString()

    /** How the owner's input reaches the phone: "keys" (the Full ADB input helper) or "basic" (accessibility). */
    fun input(mode: String): String = buildJsonObject {
        put("t", "input")
        put("mode", mode)
    }.toString()

    /** A binary frame: kind, flags (bit 0 key frame), timestamp µs big-endian, payload. */
    fun frame(kind: Byte, key: Boolean, timestampUs: Long, payload: ByteBuffer): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.remaining())
        val b = ByteBuffer.wrap(out)
        b.put(kind)
        b.put(if (key) 1 else 0)
        b.putLong(timestampUs)
        b.put(payload)
        return out
    }

    // ---- JSON helpers ----

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A string or JSON null (as `Result`-like: null means invalid, a wrapped null means null). */
    private fun JsonObject.nullableString(name: String): Nullable? = when (val v = this[name]) {
        null, JsonNull -> Nullable.NULL
        is JsonPrimitive -> if (v.isString) Nullable(v.content) else null
        else -> null
    }

    private fun JsonObject.int(name: String): Int? {
        val p = this[name] as? JsonPrimitive ?: return null
        if (p.isString || p.content.contains('.') || p.content.contains('e', ignoreCase = true)) return null
        return p.intOrNull
    }

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun <E> JsonObject.enum(name: String, values: List<E>, wire: (E) -> String): E? = string(name)?.let { s -> values.firstOrNull { wire(it) == s } }
}

/** A JSON value that may be null on purpose; the class itself being null means "invalid". */
@JvmInline
value class Nullable(val value: String?) {
    companion object {
        val NULL = Nullable(null)
    }
}
