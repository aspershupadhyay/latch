// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import io.github.aspershupadhyay.latch.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A pointer that shows the owner where the AI is acting, in the style of
 * desktop agents' cursors: one rounded terracotta arrow with a white edge and a
 * soft shadow, and a small label beside it saying what it is doing ("Latch ·
 * Tapping"). It moves along a gentle curve, faster for short hops, and dips
 * when it presses. Each touch shows a finger mark and ripple; a long press
 * fills a ring; swipes leave a fading trail; a pinch shows both fingers.
 *
 * The visuals never delay an action: the gesture runs at once and the
 * pointer catches up.
 *
 * It appears as soon as the AI does anything (also off screen: reading the
 * screen, saving a file, opening a share screen) and says exactly what is
 * happening: the command under way, "Waiting for your answer" while the
 * owner is asked, a progress bar while a file moves. Between commands it says
 * "Waiting for your AI" for a few seconds and then fades away; it disappears
 * at once when the AI says the task is done or the session stops.
 *
 * Every method may be called from any thread; drawing happens on the main thread.
 *
 * It can never press anything (the window is not touchable or focusable), is
 * hidden from screen readers, and is hidden while a screenshot is taken so
 * the AI never sees it. It is one window for the whole session, so it adds
 * no window-change events that would slow down smart settle.
 */
class CursorOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    @Volatile private var view: CursorView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun ui(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    /** What sits under the pointer; it picks the label (the arrow stays the same). */
    enum class Pointer { ARROW, HAND, TEXT, GRAB }

    /** How the finger touches down at the pointer's hotspot. */
    enum class Press { TAP, LONG_PRESS, DOUBLE }

    data class Stroke(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    fun attach() = ui {
        if (view != null) return@ui
        val v = CursorView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "Latch cursor" }
        runCatching { windows.addView(v, params) }.onSuccess { view = v }
    }

    fun detach() = ui {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    /** Glides to a point and presses there. */
    fun press(x: Int, y: Int, pointer: Pointer, press: Press = Press.TAP) = ui {
        view?.press(x.toFloat(), y.toFloat(), pointer, press)
    }

    /** Glides to a text field and shows a blinking caret while typing. */
    fun type(x: Int, y: Int) = ui {
        view?.press(x.toFloat(), y.toFloat(), Pointer.TEXT, Press.TAP, typing = true)
    }

    /** One finger (swipe, scroll, drag) or two (pinch), moving over [durationMs]. */
    fun stroke(strokes: List<Stroke>, durationMs: Long, holdMs: Long = 0) = ui {
        view?.stroke(strokes, durationMs, holdMs)
    }

    /** Says what the AI's current command is doing, e.g. "Saving “clip.mp4”"; the pointer stays where it is. */
    fun status(text: String) = ui {
        view?.status(text)
    }

    /** The current command finished: "Waiting for your AI", then the pointer fades unless another comes. */
    fun idle() = ui {
        view?.idle()
    }

    /** The task is done or the session stopped: gone at once. */
    fun finish() = ui {
        view?.finish()
    }

    /** A file moving: its label and how far along it is (0..1, or null when the size is unknown). Null ends it. */
    fun transfer(text: String?, fraction: Float?) = ui {
        view?.transfer(text, fraction)
    }

    /** Hides the cursor for a screenshot; call the returned function to show it again. Main thread only. */
    fun hideForCapture(): () -> Unit {
        val v = view ?: return {}
        v.visibility = View.INVISIBLE
        return { v.visibility = View.VISIBLE }
    }

    private class CursorView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        /** The arrow is drawn in a 24-unit grid; one unit is this many pixels. */
        private val unit = 1.25f * density
        /** Latch ember (the brand colour, as on the website), bright enough on dark and light screens. */
        private val accent = Color.rgb(255, 106, 61)

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.4f
            color = Color.WHITE
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val touch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = accent
        }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4 * density
            strokeCap = Paint.Cap.ROUND
            color = accent
        }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 17, 20) }
        private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1 * density
            color = Color.argb(70, 255, 255, 255)
        }
        private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            // Large enough to read at a glance over any app.
            textSize = 15f * density
            // Geist Mono, as in the app; the system bold face if the font cannot load.
            typeface = runCatching { resources.getFont(R.font.geist_mono_semibold) }.getOrElse { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 106, 61) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 106, 61) }

        // ---- State (screen pixels, uptime ms) ----
        private var x = -1f
        private var y = -1f
        private var fromX = -1f
        private var fromY = -1f
        private var glideStart = 0L
        private var glideMs = GLIDE_MIN_MS
        private var strokes: List<Stroke> = emptyList()
        private var strokeStart = 0L
        private var strokeMs = 0L
        private var holdMs = 0L
        private var press: Press? = null
        private var pressAt = 0L
        private var typingUntil = 0L
        private var caption = "Latch"
        /** When the current action's visuals end. */
        private var busyUntil = 0L
        /** When the AI last did anything; the pointer stays until it has been quiet for [LINGER_MS]. */
        private var lastActivity = 0L
        /** What the AI's current command is doing; null once it finished. */
        private var statusText: String? = null
        /** A command is running: the pointer stays. */
        private var running = false
        private var transferText: String? = null
        private var transferFraction: Float? = null

        private fun now() = SystemClock.uptimeMillis()

        /** 1 while the AI works (or a file moves), fading to 0 once it has been quiet for [LINGER_MS]. */
        private fun visibility(t: Long): Float {
            if (transferText != null || running) return 1f
            val idle = t - max(busyUntil, lastActivity) - LINGER_MS
            return if (idle <= 0) 1f else max(0f, 1f - idle.toFloat() / FADE_MS)
        }

        /** Where the pointer waits before the AI has touched anything: upper middle, out of the way. */
        private fun placeIfHidden(t: Long) {
            if (x >= 0 && visibility(t) > 0f) return
            val screen = resources.displayMetrics
            x = screen.widthPixels * 0.5f
            y = screen.heightPixels * 0.3f
            fromX = x
            fromY = y
            glideStart = 0L
            strokes = emptyList()
            press = null
        }

        fun status(text: String) {
            val t = now()
            placeIfHidden(t)
            statusText = text
            running = true
            lastActivity = t
            postInvalidateOnAnimation()
        }

        fun idle() {
            running = false
            statusText = null
            lastActivity = now()
            postInvalidateOnAnimation()
        }

        fun finish() {
            running = false
            statusText = null
            busyUntil = 0
            lastActivity = 0
            x = -1f
            strokes = emptyList()
            press = null
            invalidate()
        }

        fun transfer(text: String?, fraction: Float?) {
            val t = now()
            if (text != null) placeIfHidden(t)
            transferText = text
            transferFraction = fraction
            lastActivity = t
            postInvalidateOnAnimation()
        }

        /** The words beside the arrow: the gesture under way, a file moving, the command running, or waiting. */
        private fun labelText(t: Long): String = when {
            t < busyUntil -> caption
            transferText != null -> transferText!!
            statusText != null -> statusText!!
            else -> "Waiting for your AI"
        }

        fun press(tx: Float, ty: Float, kind: Pointer, how: Press, typing: Boolean = false) {
            val t = now()
            glideTo(tx, ty, t)
            strokes = emptyList()
            press = how
            pressAt = t + glideMs
            typingUntil = if (typing) t + glideMs + TYPING_MS else 0
            caption = when {
                typing -> "Typing"
                kind == Pointer.TEXT -> "Tapping a field"
                how == Press.LONG_PRESS -> "Holding"
                how == Press.DOUBLE -> "Double-tapping"
                else -> "Tapping"
            }
            val held = when (how) {
                Press.LONG_PRESS -> LONG_PRESS_MS
                Press.DOUBLE -> DOUBLE_GAP_MS
                Press.TAP -> 0L
            }
            busyUntil = max(pressAt + held + RIPPLE_MS, typingUntil)
            lastActivity = t
            postInvalidateOnAnimation()
        }

        fun stroke(list: List<Stroke>, durationMs: Long, hold: Long) {
            val first = list.firstOrNull() ?: return
            val t = now()
            glideTo(first.fromX, first.fromY, t)
            strokes = list
            holdMs = hold
            strokeStart = t + glideMs
            strokeMs = max(1, durationMs)
            press = null
            typingUntil = 0
            caption = when {
                list.size > 1 -> "Pinching"
                hold > 0 -> "Dragging"
                else -> "Swiping"
            }
            busyUntil = strokeStart + holdMs + strokeMs + TRAIL_FADE_MS
            lastActivity = t
            postInvalidateOnAnimation()
        }

        private fun glideTo(tx: Float, ty: Float, t: Long) {
            // After it faded away the pointer reappears at the target instead of flying in.
            val shown = x >= 0 && visibility(t) > 0f
            val (cx, cy) = position(t)
            fromX = if (shown) cx else tx
            fromY = if (shown) cy else ty
            x = tx
            y = ty
            // Short hops are quick, long moves take a little longer, as a hand would.
            val distanceDp = hypot(tx - fromX, ty - fromY) / density
            glideMs = (GLIDE_MIN_MS + distanceDp * GLIDE_MS_PER_DP).toLong().coerceAtMost(GLIDE_MAX_MS)
            glideStart = t
        }

        /** Where the hotspot is drawn at time [t]: gliding on a slight curve, then following the first finger. */
        private fun position(t: Long): Pair<Float, Float> {
            val first = strokes.firstOrNull()
            if (first != null && t >= strokeStart) {
                val p = ((t - strokeStart - holdMs).toFloat() / strokeMs).coerceIn(0f, 1f)
                return (first.fromX + (first.toX - first.fromX) * p) to (first.fromY + (first.toY - first.fromY) * p)
            }
            val p = if (glideStart == 0L) 1f else min(1f, (t - glideStart).toFloat() / glideMs)
            // Ease in and out, so it starts and lands softly.
            val e = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).let { it * it * it } / 2
            // Bend the path a little to one side, like a wrist turning.
            val dx = x - fromX
            val dy = y - fromY
            val bend = CURVE * 4 * e * (1 - e)
            return (fromX + dx * e - dy * bend) to (fromY + dy * e + dx * bend)
        }

        override fun onDraw(canvas: Canvas) {
            if (x < 0) return
            val t = now()
            val loc = IntArray(2).also(::getLocationOnScreen)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            // Shown while the AI works; once it stops acting the pointer fades away.
            val alpha = visibility(t)
            if (alpha <= 0f) {
                canvas.restore()
                return
            }

            drawStrokes(canvas, t)
            drawPress(canvas, t)
            val (px, py) = position(t)
            drawArrow(canvas, px, py, alpha, pressScale(t))
            drawLabel(canvas, px, py, alpha, t)

            canvas.restore()
            // Smooth frames while something moves; a few frames a second for the thinking dots,
            // so a waiting pointer costs almost nothing. Nothing is redrawn once it has faded.
            val moving = t < busyUntil + TRAIL_FADE_MS || (glideStart != 0L && t < glideStart + glideMs) || alpha < 1f
            if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(IDLE_FRAME_MS)
        }

        /** The arrow dips a little as it presses, and springs back. */
        private fun pressScale(t: Long): Float {
            val how = press ?: return 1f
            val since = t - pressAt
            val down = if (how == Press.LONG_PRESS) LONG_PRESS_MS else PRESS_DIP_MS
            return when {
                since < 0 -> 1f
                since < PRESS_DIP_MS -> 1f - 0.14f * since / PRESS_DIP_MS
                since < down -> 0.86f
                since < down + PRESS_DIP_MS -> 0.86f + 0.14f * (since - down) / PRESS_DIP_MS
                else -> 1f
            }
        }

        private fun drawStrokes(canvas: Canvas, t: Long) {
            if (strokes.isEmpty() || t < strokeStart) return
            val elapsed = t - strokeStart
            val total = holdMs + strokeMs
            if (elapsed > total + TRAIL_FADE_MS) return
            val p = ((elapsed - holdMs).toFloat() / strokeMs).coerceIn(0f, 1f)
            val fade = if (elapsed <= total) 1f else 1f - (elapsed - total).toFloat() / TRAIL_FADE_MS
            trail.alpha = (130 * fade).toInt()
            touch.alpha = (90 * fade).toInt()
            for (s in strokes) {
                val tipX = s.fromX + (s.toX - s.fromX) * p
                val tipY = s.fromY + (s.toY - s.fromY) * p
                canvas.drawLine(s.fromX, s.fromY, tipX, tipY, trail)
                // A finger on the glass for every touch point.
                if (elapsed <= total) canvas.drawCircle(tipX, tipY, FINGER_DP * density, touch)
            }
            // Holding before a drag: a ring fills while the item is picked up.
            if (holdMs > 0 && elapsed < holdMs) {
                val first = strokes.first()
                progressRing(canvas, first.fromX, first.fromY, elapsed.toFloat() / holdMs)
            }
        }

        private fun drawPress(canvas: Canvas, t: Long) {
            val how = press ?: return
            val since = t - pressAt
            if (since < 0) return
            val holdFor = if (how == Press.LONG_PRESS) LONG_PRESS_MS else 0L
            val downs = if (how == Press.DOUBLE) listOf(0L, DOUBLE_GAP_MS) else listOf(0L)
            for (down in downs) {
                val s = since - down
                if (s < 0) continue
                if (s < holdFor + TOUCH_MS) {
                    touch.alpha = 90
                    canvas.drawCircle(x, y, FINGER_DP * density, touch)
                }
                val r = s - holdFor
                if (r in 0 until RIPPLE_MS) {
                    val q = r.toFloat() / RIPPLE_MS
                    val eased = 1 - (1 - q) * (1 - q)
                    ring.alpha = ((1f - q) * 220).toInt()
                    canvas.drawCircle(x, y, (FINGER_DP + eased * 22) * density, ring)
                }
            }
            if (how == Press.LONG_PRESS && since < holdFor) progressRing(canvas, x, y, since.toFloat() / holdFor)
        }

        private fun progressRing(canvas: Canvas, cx: Float, cy: Float, fraction: Float) {
            val r = (FINGER_DP + 6) * density
            ring.alpha = 255
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * fraction.coerceIn(0f, 1f), false, ring)
        }

        private fun drawArrow(canvas: Canvas, px: Float, py: Float, alpha: Float, scale: Float) {
            canvas.save()
            canvas.translate(px, py)
            canvas.scale(unit * scale, unit * scale)
            // A soft shadow from three widening, fainter copies: cheap, no blur layer.
            for ((i, a) in SHADOW_ALPHAS.withIndex()) {
                canvas.save()
                val grow = 1f + 0.06f * (i + 1)
                canvas.translate(0.6f, 1.6f + i * 0.5f)
                canvas.scale(grow, grow)
                shadow.alpha = (a * alpha).toInt()
                canvas.drawPath(arrow, shadow)
                canvas.restore()
            }
            val a = (255 * alpha).toInt()
            edge.alpha = a
            fill.alpha = a
            canvas.drawPath(arrow, edge)
            canvas.drawPath(arrow, fill)
            canvas.restore()
        }

        /** "Latch · Tapping" in a dark pill beside the arrow, flipped to stay on screen. */
        private fun drawLabel(canvas: Canvas, px: Float, py: Float, alpha: Float, t: Long) {
            val padX = 13 * density
            val fraction = transferFraction.takeIf { transferText != null && t >= busyUntil }
            val h = (if (fraction != null) 42 else 34) * density
            val dotR = 4 * density
            val gap = 8 * density
            val margin = 8 * density
            // This view covers the whole screen; the canvas is in screen pixels.
            val loc = IntArray(2).also(::getLocationOnScreen)
            val screenW = (loc[0] + width).toFloat()
            val screenH = (loc[1] + height).toFloat()
            val chrome = padX * 2 + dotR * 2 + gap
            // Long words (a file name) are shortened so the label always fits on the screen.
            val text = android.text.TextUtils.ellipsize(
                "Latch · ${labelText(t)}", label, (screenW - margin * 2 - chrome).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END,
            ).toString()
            val w = chrome + label.measureText(text)
            var left = px + 22 * density
            var top = py + 26 * density
            if (left + w > screenW - margin) left = px - w - 8 * density
            if (top + h > screenH - margin) top = py - h - 10 * density
            left = left.coerceIn(margin, (screenW - w - margin).coerceAtLeast(margin))
            top = top.coerceIn(margin, (screenH - h - margin).coerceAtLeast(margin))
            val box = RectF(left, top, left + w, top + h)
            val a = alpha.coerceIn(0f, 1f)
            pill.alpha = (225 * a).toInt()
            pillEdge.alpha = (70 * a).toInt()
            label.alpha = (255 * a).toInt()
            dot.alpha = (255 * a).toInt()
            canvas.drawRoundRect(box, h / 2, h / 2, pill)
            canvas.drawRoundRect(box, h / 2, h / 2, pillEdge)
            // A small live dot that pulses while the AI acts.
            val pulse = 0.75f + 0.25f * kotlin.math.sin(t / (if (t < busyUntil) 140.0 else 260.0)).toFloat()
            val center = if (fraction != null) top + 16 * density else top + h / 2
            canvas.drawCircle(left + padX + dotR, center, dotR * pulse, dot)
            val textLeft = left + padX + dotR * 2 + gap
            val baseline = center - (label.descent() + label.ascent()) / 2
            canvas.drawText(text, textLeft, baseline, label)
            if (fraction != null) {
                // A slim progress bar under the words while a file moves.
                val barTop = top + h - 11 * density
                val barRight = left + w - padX
                track.alpha = (60 * a).toInt()
                bar.alpha = (255 * a).toInt()
                val r = 2f * density
                canvas.drawRoundRect(RectF(textLeft, barTop, barRight, barTop + 4 * density), r, r, track)
                canvas.drawRoundRect(RectF(textLeft, barTop, textLeft + (barRight - textLeft) * fraction.coerceIn(0f, 1f), barTop + 4 * density), r, r, bar)
            }
        }

        /** A rounded arrow with its tip (the hotspot) at 0,0. */
        private val arrow = Path().apply {
            moveTo(1.2f, 0.6f)
            cubicTo(0.4f, 0.1f, -0.4f, 0.6f, -0.2f, 1.6f)
            lineTo(3.6f, 20.4f)
            cubicTo(3.9f, 21.7f, 5.6f, 21.9f, 6.2f, 20.7f)
            lineTo(9.4f, 14.2f)
            lineTo(16.6f, 13.6f)
            cubicTo(17.9f, 13.5f, 18.4f, 11.8f, 17.3f, 11.0f)
            close()
        }

        companion object {
            const val GLIDE_MIN_MS = 160L
            const val GLIDE_MAX_MS = 380L
            const val GLIDE_MS_PER_DP = 0.45f
            /** How far the path bends, as a share of the distance. */
            const val CURVE = 0.08f
            const val PRESS_DIP_MS = 90L
            const val TOUCH_MS = 110L
            const val RIPPLE_MS = 420L
            const val LONG_PRESS_MS = 600L
            const val DOUBLE_GAP_MS = 160L
            const val TRAIL_FADE_MS = 350L
            const val TYPING_MS = 1_200L
            /** How long the pointer stays, "Waiting for your AI", after the AI's last command before fading. */
            const val LINGER_MS = 6_000L
            /** Frame interval while only the live dot pulses. */
            const val IDLE_FRAME_MS = 120L
            const val FADE_MS = 400L
            const val FINGER_DP = 9f
            val SHADOW_ALPHAS = intArrayOf(46, 26, 12)
        }
    }
}
