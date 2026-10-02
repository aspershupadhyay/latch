package io.github.aspershupadhyay.latch.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * A desktop-style pointer that shows the owner where the AI is acting, with
 * phone touch feedback on top: it glides to each target, turns into a hand
 * over things that can be tapped (links, buttons), a text cursor over text
 * fields, and a grabbing hand while swiping, scrolling, or dragging. Each
 * touch shows a finger mark and ripple; a long press fills a ring; a pinch
 * shows both fingers.
 *
 * It appears when the AI acts and fades away a few seconds after its last
 * action, so it is gone once a task is done.
 *
 * It can never press anything (the window is not touchable or focusable), is
 * hidden from screen readers, and is hidden while a screenshot is taken so
 * the AI never sees it. It is one window for the whole session, so it adds
 * no window-change events that would slow down smart settle.
 */
class CursorOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: CursorView? = null

    /** The pointer's shape, as on a computer. */
    enum class Pointer { ARROW, HAND, TEXT, GRAB }

    /** How the finger touches down at the pointer's hotspot. */
    enum class Press { TAP, LONG_PRESS, DOUBLE }

    data class Stroke(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    fun attach() {
        if (view != null) return
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

    fun detach() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    /** Glides to a point and presses there. */
    fun press(x: Int, y: Int, pointer: Pointer, press: Press = Press.TAP) {
        view?.press(x.toFloat(), y.toFloat(), pointer, press)
    }

    /** Glides to a text field and shows a blinking caret while typing. */
    fun type(x: Int, y: Int) {
        view?.press(x.toFloat(), y.toFloat(), Pointer.TEXT, Press.TAP, typing = true)
    }

    /** One finger (swipe, scroll, drag) or two (pinch), moving over [durationMs]. */
    fun stroke(strokes: List<Stroke>, durationMs: Long, holdMs: Long = 0) {
        view?.stroke(strokes, durationMs, holdMs)
    }

    /** Hides the cursor for a screenshot; call the returned function to show it again. */
    fun hideForCapture(): () -> Unit {
        val v = view ?: return {}
        v.visibility = View.INVISIBLE
        return { v.visibility = View.VISIBLE }
    }

    private class CursorView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        /** Pointer drawings are in a 16-unit grid; one unit is this many pixels. */
        private val unit = 1.55f * density
        private val accent = Color.rgb(240, 168, 58)

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.BLACK
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        private val touch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 240, 168, 58) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = accent
        }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5 * density
            strokeCap = Paint.Cap.ROUND
            color = accent
        }

        // ---- State (screen pixels, uptime ms) ----
        private var pointer = Pointer.ARROW
        private var x = -1f
        private var y = -1f
        private var fromX = -1f
        private var fromY = -1f
        private var glideStart = 0L
        private var strokes: List<Stroke> = emptyList()
        private var strokeStart = 0L
        private var strokeMs = 0L
        private var holdMs = 0L
        private var press: Press? = null
        private var pressAt = 0L
        private var typingUntil = 0L
        /** When the current action's visuals end; the pointer lingers a moment, then fades away. */
        private var busyUntil = 0L

        private fun now() = SystemClock.uptimeMillis()

        /** 1 while the AI is acting, fading to 0 once it has been idle for [LINGER_MS]. */
        private fun visibility(t: Long): Float {
            val idle = t - busyUntil - LINGER_MS
            return if (idle <= 0) 1f else max(0f, 1f - idle.toFloat() / FADE_MS)
        }

        fun press(tx: Float, ty: Float, kind: Pointer, how: Press, typing: Boolean = false) {
            val t = now()
            glideTo(tx, ty, t)
            pointer = kind
            strokes = emptyList()
            press = how
            pressAt = t + GLIDE_MS
            typingUntil = if (typing) t + GLIDE_MS + TYPING_MS else 0
            val held = when (how) {
                Press.LONG_PRESS -> LONG_PRESS_MS
                Press.DOUBLE -> DOUBLE_GAP_MS
                Press.TAP -> 0L
            }
            busyUntil = max(pressAt + held + RIPPLE_MS, typingUntil)
            postInvalidateOnAnimation()
        }

        fun stroke(list: List<Stroke>, durationMs: Long, hold: Long) {
            val first = list.firstOrNull() ?: return
            val t = now()
            glideTo(first.fromX, first.fromY, t)
            pointer = Pointer.GRAB
            strokes = list
            holdMs = hold
            strokeStart = t + GLIDE_MS
            strokeMs = max(1, durationMs)
            press = null
            typingUntil = 0
            busyUntil = strokeStart + holdMs + strokeMs + TRAIL_FADE_MS
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
            glideStart = t
        }

        /** Where the hotspot is drawn at time [t]: gliding, then following the first finger. */
        private fun position(t: Long): Pair<Float, Float> {
            val first = strokes.firstOrNull()
            if (first != null && t >= strokeStart) {
                val p = ((t - strokeStart - holdMs).toFloat() / strokeMs).coerceIn(0f, 1f)
                return (first.fromX + (first.toX - first.fromX) * p) to (first.fromY + (first.toY - first.fromY) * p)
            }
            val p = if (glideStart == 0L) 1f else min(1f, (t - glideStart).toFloat() / GLIDE_MS)
            val e = 1f - (1f - p) * (1f - p) * (1f - p)
            return (fromX + (x - fromX) * e) to (fromY + (y - fromY) * e)
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
            drawPointer(canvas, px, py, alpha, t)

            canvas.restore()
            // Keep drawing until the fade has finished; then nothing is redrawn until the next action.
            postInvalidateOnAnimation()
        }

        private fun drawStrokes(canvas: Canvas, t: Long) {
            if (strokes.isEmpty() || t < strokeStart) return
            val elapsed = t - strokeStart
            val total = holdMs + strokeMs
            if (elapsed > total + TRAIL_FADE_MS) return
            val p = ((elapsed - holdMs).toFloat() / strokeMs).coerceIn(0f, 1f)
            val fade = if (elapsed <= total) 1f else 1f - (elapsed - total).toFloat() / TRAIL_FADE_MS
            trail.alpha = (150 * fade).toInt()
            touch.alpha = (110 * fade).toInt()
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
            touch.alpha = 110
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
                if (s < holdFor + TOUCH_MS) canvas.drawCircle(x, y, FINGER_DP * density, touch)
                val r = s - holdFor
                if (r in 0 until RIPPLE_MS) {
                    val q = r.toFloat() / RIPPLE_MS
                    ring.alpha = ((1f - q) * 255).toInt()
                    canvas.drawCircle(x, y, (FINGER_DP + q * 26) * density, ring)
                }
            }
            if (how == Press.LONG_PRESS && since < holdFor) progressRing(canvas, x, y, since.toFloat() / holdFor)
            ring.alpha = 255
        }

        private fun progressRing(canvas: Canvas, cx: Float, cy: Float, fraction: Float) {
            val r = (FINGER_DP + 6) * density
            ring.alpha = 255
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * fraction.coerceIn(0f, 1f), false, ring)
        }

        private fun drawPointer(canvas: Canvas, px: Float, py: Float, alpha: Float, t: Long) {
            val shape = shapes.getValue(pointer)
            canvas.save()
            canvas.translate(px, py)
            canvas.scale(unit, unit)
            canvas.translate(-shape.hotX, -shape.hotY)
            val a = (255 * alpha).toInt()
            outline.strokeWidth = 1.1f
            outline.alpha = a
            if (shape.body != null) {
                canvas.save()
                canvas.translate(0.5f, 0.8f)
                shadow.alpha = (60 * alpha).toInt()
                canvas.drawPath(shape.body, shadow)
                canvas.restore()
                fill.alpha = a
                canvas.drawPath(shape.body, fill)
                canvas.drawPath(shape.body, outline)
            }
            shape.lines?.let { lines ->
                // Text cursor: a white halo keeps the black I-beam visible on any background.
                halo.strokeWidth = 3.2f
                halo.alpha = a
                canvas.drawPath(lines, halo)
                outline.strokeWidth = if (shape.body == null) 1.3f else 0.9f
                canvas.drawPath(lines, outline)
            }
            if (pointer == Pointer.TEXT && t < typingUntil && (t / CARET_BLINK_MS) % 2 == 0L) {
                outline.strokeWidth = 1.4f
                canvas.drawLine(shape.hotX + 4f, shape.hotY - 5f, shape.hotX + 4f, shape.hotY + 5f, outline)
            }
            canvas.restore()
        }

        /** A pointer drawing: filled body, detail lines, and the hotspot that sits on the target. */
        private class Shape(val body: Path?, val lines: Path?, val hotX: Float, val hotY: Float)

        private val shapes: Map<Pointer, Shape> = mapOf(
            Pointer.ARROW to Shape(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(0f, 16f)
                    lineTo(4f, 12.5f)
                    lineTo(6.8f, 18.5f)
                    lineTo(9.2f, 17.4f)
                    lineTo(6.5f, 11.6f)
                    lineTo(11.5f, 11.6f)
                    close()
                },
                null, 0f, 0f,
            ),
            // A pointing hand, fingertip on the target: links and buttons.
            Pointer.HAND to Shape(
                Path().apply {
                    moveTo(4.2f, 1.3f)
                    cubicTo(4.2f, -0.4f, 6.8f, -0.4f, 6.8f, 1.3f)
                    lineTo(6.8f, 7.2f)
                    cubicTo(6.8f, 5.9f, 9.2f, 5.9f, 9.2f, 7.2f)
                    lineTo(9.2f, 8f)
                    cubicTo(9.2f, 6.7f, 11.6f, 6.7f, 11.6f, 8f)
                    lineTo(11.6f, 8.8f)
                    cubicTo(11.6f, 7.6f, 14f, 7.6f, 14f, 8.8f)
                    lineTo(14f, 13.5f)
                    cubicTo(14f, 17.2f, 12.2f, 19f, 9.2f, 19f)
                    lineTo(7.6f, 19f)
                    cubicTo(5.6f, 19f, 4.6f, 18.2f, 3.6f, 16.6f)
                    lineTo(0.6f, 11.8f)
                    cubicTo(-0.2f, 10.6f, 1.4f, 9f, 2.8f, 10.2f)
                    lineTo(4.2f, 11.4f)
                    close()
                },
                Path().apply {
                    moveTo(6.8f, 7.2f); lineTo(6.8f, 11.2f)
                    moveTo(9.2f, 8f); lineTo(9.2f, 11.2f)
                    moveTo(11.6f, 8.8f); lineTo(11.6f, 11.2f)
                },
                5.5f, 0f,
            ),
            // An I-beam centred on the text field.
            Pointer.TEXT to Shape(
                null,
                Path().apply {
                    moveTo(1f, 0f); quadTo(3f, 0f, 3f, 1.5f); quadTo(3f, 0f, 5f, 0f)
                    moveTo(3f, 1.5f); lineTo(3f, 14.5f)
                    moveTo(1f, 16f); quadTo(3f, 16f, 3f, 14.5f); quadTo(3f, 16f, 5f, 16f)
                },
                3f, 8f,
            ),
            // A closed, grabbing hand: swipes, scrolls, drags.
            Pointer.GRAB to Shape(
                Path().apply {
                    moveTo(2.5f, 7.5f)
                    cubicTo(2.5f, 5.8f, 4.9f, 5.8f, 4.9f, 7.5f)
                    cubicTo(4.9f, 5.6f, 7.3f, 5.6f, 7.3f, 7.5f)
                    cubicTo(7.3f, 5.6f, 9.7f, 5.6f, 9.7f, 7.5f)
                    cubicTo(9.7f, 5.8f, 12.1f, 5.8f, 12.1f, 7.5f)
                    lineTo(12.1f, 8.2f)
                    cubicTo(12.1f, 7f, 14.2f, 7f, 14.2f, 8.6f)
                    lineTo(14.2f, 12.5f)
                    cubicTo(14.2f, 15.8f, 12.4f, 17.6f, 9.2f, 17.6f)
                    lineTo(7.2f, 17.6f)
                    cubicTo(4.2f, 17.6f, 2.5f, 15.8f, 2.5f, 12.8f)
                    close()
                },
                Path().apply {
                    moveTo(4.9f, 7.5f); lineTo(4.9f, 9.6f)
                    moveTo(7.3f, 7.5f); lineTo(7.3f, 9.6f)
                    moveTo(9.7f, 7.5f); lineTo(9.7f, 9.6f)
                },
                8f, 10f,
            ),
        )

        companion object {
            const val GLIDE_MS = 140L
            const val TOUCH_MS = 110L
            const val RIPPLE_MS = 380L
            const val LONG_PRESS_MS = 600L
            const val DOUBLE_GAP_MS = 160L
            const val TRAIL_FADE_MS = 350L
            const val TYPING_MS = 1_200L
            const val CARET_BLINK_MS = 300L
            /** How long the pointer stays after the AI's last action before fading (it is usually thinking). */
            const val LINGER_MS = 4_000L
            const val FADE_MS = 400L
            const val FINGER_DP = 9f
        }
    }
}
