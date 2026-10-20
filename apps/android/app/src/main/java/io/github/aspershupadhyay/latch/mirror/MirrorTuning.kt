// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import kotlin.math.roundToInt

/**
 * Typing from the mirror into the field in focus (R30). Accessibility can set
 * a field's whole text and its cursor, not send keys, so each key becomes the
 * field's new text and cursor. Works on code points, so an emoji is one
 * character for Backspace and the arrow keys.
 */
object TextEdit {
    data class Result(val text: String, val cursor: Int)

    /** [typed] replaces the selection [start]..[end] (a cursor when equal; -1 means none, so the end). */
    fun insert(current: String, start: Int, end: Int, typed: String): Result {
        val (s, e) = selection(current, start, end)
        return Result(current.substring(0, s) + typed + current.substring(e), s + typed.length)
    }

    /** Backspace: the selection, or the character before the cursor. Null when there is nothing to delete. */
    fun backspace(current: String, start: Int, end: Int): Result? {
        val (s, e) = selection(current, start, end)
        if (s != e) return Result(current.substring(0, s) + current.substring(e), s)
        if (s == 0) return null
        val before = current.offsetByCodePoints(s, -1)
        return Result(current.substring(0, before) + current.substring(s), before)
    }

    /** Delete: the selection, or the character after the cursor. */
    fun delete(current: String, start: Int, end: Int): Result? {
        val (s, e) = selection(current, start, end)
        if (s != e) return Result(current.substring(0, s) + current.substring(e), s)
        if (s >= current.length) return null
        val after = current.offsetByCodePoints(s, 1)
        return Result(current.substring(0, s) + current.substring(after), s)
    }

    /** Ctrl+Backspace: the selection, or back to the start of the word before the cursor. */
    fun wordBackspace(current: String, start: Int, end: Int): Result? {
        val (s, e) = selection(current, start, end)
        if (s != e) return Result(current.substring(0, s) + current.substring(e), s)
        if (s == 0) return null
        val to = wordStart(current, s)
        return Result(current.substring(0, to) + current.substring(s), to)
    }

    /**
     * The cursor after a movement key: one character left or right, a word
     * with Ctrl, the line's start or end for Home and End, or the field's
     * start or end for up and down.
     */
    fun move(current: String, start: Int, end: Int, key: MirrorKey): Int {
        val (s, e) = selection(current, start, end)
        return when (key) {
            MirrorKey.LEFT -> if (s != e) s else if (s == 0) 0 else current.offsetByCodePoints(s, -1)
            MirrorKey.RIGHT -> if (s != e) e else if (e >= current.length) current.length else current.offsetByCodePoints(e, 1)
            MirrorKey.WORD_LEFT -> wordStart(current, s)
            MirrorKey.WORD_RIGHT -> wordEnd(current, e)
            MirrorKey.HOME -> current.lastIndexOf('\n', s - 1) + 1
            MirrorKey.END -> current.indexOf('\n', e).let { if (it < 0) current.length else it }
            MirrorKey.UP -> 0
            else -> current.length
        }
    }

    private fun wordStart(text: String, from: Int): Int {
        var i = from
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    private fun wordEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    /**
     * What a field really holds: empty while it shows its hint. Some apps
     * (WhatsApp's "Message" box among them) report the hint as the field's
     * text without saying so; [placeholder] is that text once the mirror saw
     * the field report it right after the mirror emptied it.
     */
    fun realText(text: String, showingHint: Boolean, hint: String?, placeholder: String?): String = when {
        showingHint -> ""
        text.isNotEmpty() && (text == hint || text == placeholder) -> ""
        else -> text
    }

    private fun selection(current: String, start: Int, end: Int): Pair<Int, Int> {
        if (start < 0 || end < 0) return current.length to current.length
        val a = minOf(start, end).coerceIn(0, current.length)
        val b = maxOf(start, end).coerceIn(0, current.length)
        return a to b
    }
}

/** The size the encoder runs at: the screen scaled to fit [maxLongEdge], each side a multiple of 16. */
object EncoderSizing {
    fun size(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "screen size must be positive" }
        val scale = minOf(1.0, maxLongEdge.toDouble() / maxOf(width, height))
        fun align(v: Int) = maxOf(16, (v * scale).roundToInt() / 16 * 16)
        return align(width) to align(height)
    }
}

/**
 * Bitrate that backs off while frames are dropped on a slow network and comes
 * back slowly once it is clean (multiplicative down, gentle up).
 */
class BitrateGovernor(
    val startBps: Int = 8_000_000,
    private val minBps: Int = 1_000_000,
    private val maxBps: Int = 12_000_000,
) {
    var bps: Int = startBps
        private set
    private var lastChangeMs = Long.MIN_VALUE / 2
    private var lastDropMs = Long.MIN_VALUE / 2
    private var dropsInWindow = 0
    private var windowStartMs = Long.MIN_VALUE / 2

    /** A frame was dropped; returns the new bitrate when it should change. */
    fun dropped(now: Long): Int? {
        if (now - windowStartMs > WINDOW_MS) {
            windowStartMs = now
            dropsInWindow = 0
        }
        dropsInWindow++
        lastDropMs = now
        if (dropsInWindow < DROPS_TO_SLOW || now - lastChangeMs < DOWN_GAP_MS || bps <= minBps) return null
        return change(maxOf(minBps, (bps * 0.7).roundToInt()), now)
    }

    /** Called now and then while frames flow; returns a higher bitrate after a clean stretch. */
    fun clean(now: Long): Int? {
        if (now - lastDropMs < CLEAN_MS || now - lastChangeMs < CLEAN_MS || bps >= maxBps) return null
        return change(minOf(maxBps, (bps * 1.15).roundToInt()), now)
    }

    /** At the lowest bitrate: only a smaller picture (R38) can save more. */
    val atFloor: Boolean get() = bps <= minBps

    private fun change(to: Int, now: Long): Int {
        bps = to
        lastChangeMs = now
        dropsInWindow = 0
        windowStartMs = now
        return to
    }

    companion object {
        const val WINDOW_MS = 2_000L
        const val DROPS_TO_SLOW = 2
        const val DOWN_GAP_MS = 1_000L
        const val CLEAN_MS = 5_000L
    }
}

/**
 * R35: what the encoder did since the last report, for the page's stats
 * overlay. Encode time is from the frame's capture timestamp (the display's
 * monotonic clock, as `System.nanoTime`) to the encoder handing it out.
 */
class StreamStats {
    data class Snapshot(
        val frames: Int,
        val keyFrames: Int,
        val drops: Int,
        val kbits: Long,
        val encodeAvgMs: Double?,
        val encodeMaxMs: Double?,
    )

    private var frames = 0
    private var keyFrames = 0
    private var drops = 0
    private var bytes = 0L
    private var encodeSumUs = 0L
    private var encodeMaxUs = 0L
    private var encoded = 0

    /** One encoded frame of [size] bytes; [sent] false when the socket was too far behind. */
    @Synchronized
    fun frame(size: Int, key: Boolean, sent: Boolean, encodeUs: Long?) {
        if (!sent) {
            drops++
            return
        }
        frames++
        if (key) keyFrames++
        bytes += size
        // A timestamp on another clock (or none) gives nonsense; leave it out.
        if (encodeUs != null && encodeUs in 0..MAX_ENCODE_US) {
            encodeSumUs += encodeUs
            encodeMaxUs = maxOf(encodeMaxUs, encodeUs)
            encoded++
        }
    }

    /** The numbers since the last call, and a fresh start. */
    @Synchronized
    fun take(): Snapshot {
        val snapshot = Snapshot(
            frames, keyFrames, drops, bytes * 8 / 1000,
            if (encoded > 0) encodeSumUs / 1000.0 / encoded else null,
            if (encoded > 0) encodeMaxUs / 1000.0 else null,
        )
        frames = 0
        keyFrames = 0
        drops = 0
        bytes = 0
        encodeSumUs = 0
        encodeMaxUs = 0
        encoded = 0
        return snapshot
    }

    private companion object {
        const val MAX_ENCODE_US = 2_000_000L
    }
}

/**
 * R38: when the lowest bitrate still drops frames, a smaller picture and
 * fewer frames a second; back up a step after a long clean stretch. Steps
 * are far apart, since each one restarts the encoder (and sends a key frame).
 */
class QualityLadder {
    data class Level(val maxEdge: Int, val fps: Int)

    var index = 0
        private set
    val level: Level get() = LEVELS[index]
    /** True below full quality: the page says "Saving bandwidth". */
    val saving: Boolean get() = index > 0

    private var lastChangeMs = Long.MIN_VALUE / 2
    private var lastDropMs = Long.MIN_VALUE / 2
    private var windowStartMs = Long.MIN_VALUE / 2
    private var dropsInWindow = 0

    /** A frame was dropped; returns the lower level when it is time to step down. */
    fun dropped(now: Long, atFloor: Boolean): Level? {
        lastDropMs = now
        if (now - windowStartMs > WINDOW_MS) {
            windowStartMs = now
            dropsInWindow = 0
        }
        dropsInWindow++
        if (!atFloor || dropsInWindow < DROPS_TO_STEP || now - lastChangeMs < DOWN_GAP_MS || index >= LEVELS.lastIndex) return null
        return step(index + 1, now)
    }

    /** Frames flow; returns the higher level after [UP_CLEAN_MS] without a drop. */
    fun clean(now: Long): Level? {
        if (index == 0 || now - lastDropMs < UP_CLEAN_MS || now - lastChangeMs < UP_CLEAN_MS) return null
        return step(index - 1, now)
    }

    private fun step(to: Int, now: Long): Level {
        index = to
        lastChangeMs = now
        dropsInWindow = 0
        windowStartMs = now
        return level
    }

    companion object {
        val LEVELS = listOf(Level(1920, 60), Level(1280, 30), Level(960, 30))
        const val WINDOW_MS = 2_000L
        const val DROPS_TO_STEP = 4
        const val DOWN_GAP_MS = 3_000L
        const val UP_CLEAN_MS = 15_000L
    }
}

/**
 * R42: where two fingers are for a pinch from the computer: around the
 * centre, [scale] times a quarter of the screen's short side apart, turned
 * [angleDegrees], kept on the screen. Returns x0, y0, x1, y1.
 */
object PinchFingers {
    fun at(cx: Int, cy: Int, scale: Double, angleDegrees: Double, width: Int, height: Int): IntArray {
        val base = minOf(width, height) / 4.0
        val half = (base * scale / 2).coerceIn(MIN_HALF_SPAN, maxOf(width, height).toDouble())
        val a = Math.toRadians(angleDegrees)
        val dx = kotlin.math.cos(a) * half
        val dy = kotlin.math.sin(a) * half
        fun x(v: Double) = v.roundToInt().coerceIn(0, width - 1)
        fun y(v: Double) = v.roundToInt().coerceIn(0, height - 1)
        return intArrayOf(x(cx - dx), y(cy - dy), x(cx + dx), y(cy + dy))
    }

    /** Fingers closer than this would touch as one. */
    private const val MIN_HALF_SPAN = 24.0
}

/** H.264 Annex B helpers: a key frame must carry its SPS and PPS so the browser can start there. */
object AnnexB {
    /** The type of the first NAL unit after a start code, or -1; [hevc] reads H.265's 6-bit type. */
    fun firstNalType(frame: java.nio.ByteBuffer, hevc: Boolean = false): Int {
        val b = frame.duplicate()
        var zeros = 0
        while (b.hasRemaining()) {
            val v = b.get().toInt() and 0xff
            if (v == 0) {
                zeros++
            } else if (v == 1 && zeros >= 2) {
                if (!b.hasRemaining()) return -1
                val header = b.get().toInt() and 0xff
                return if (hevc) (header shr 1) and 0x3f else header and 0x1f
            } else {
                return -1
            }
        }
        return -1
    }

    /** [frame] with [config] (SPS and PPS; for H.265 also VPS) in front when it is a key frame that lacks them. */
    fun withConfig(config: ByteArray?, frame: java.nio.ByteBuffer, key: Boolean, hevc: Boolean = false): java.nio.ByteBuffer {
        if (!key || config == null || firstNalType(frame, hevc) == if (hevc) HEVC_VPS else 7) return frame
        val out = java.nio.ByteBuffer.allocate(config.size + frame.remaining())
        out.put(config).put(frame.duplicate()).flip()
        return out
    }

    private const val HEVC_VPS = 32
}
