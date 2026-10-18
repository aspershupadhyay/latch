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
}

enum class Codec(val wire: String) { H264("h264"), JPEG("jpeg") }
enum class Phase(val wire: String) { DOWN("down"), MOVE("move"), UP("up"), CANCEL("cancel") }
enum class MirrorKey(val wire: String) { BACKSPACE("backspace"), DELETE("delete"), ENTER("enter"), TAB("tab"), LEFT("left"), RIGHT("right"), UP("up"), DOWN("down") }
enum class MirrorButton(val wire: String) { BACK("back"), HOME("home"), RECENTS("recents") }
enum class MirrorAction(val wire: String) { PAUSE("pause"), RESUME("resume"), STOP("stop"), END("end") }

object MirrorMessages {
    const val VERSION = 1
    const val KIND_H264: Byte = 1
    const val KIND_JPEG: Byte = 2
    /** 48 kHz stereo 16-bit little-endian PCM, 20 ms per frame (ADR-055). */
    const val KIND_PCM: Byte = 3
    const val HEADER_BYTES = 10
    const val MAX_COORDINATE = 16_384
    const val MAX_TEXT_CHARS = 256
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
            else -> null
        }
    }

    /** 1–256 characters (code points), none of them control characters. */
    fun validText(text: String): Boolean {
        val count = text.codePointCount(0, text.length)
        return count in 1..MAX_TEXT_CHARS && text.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() }
    }

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
