package io.github.aspershupadhyay.latch.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * A visible pointer that shows the owner where the AI is acting: it glides to
 * each tap, pulses on the tap, and leaves a short trail on swipes and pinches.
 *
 * It can never press anything (the window is not touchable or focusable), is
 * hidden from screen readers, and is hidden while a screenshot is taken so the
 * AI never sees it. It is one window for the whole session, so it does not
 * add window-change events that would slow down smart settle.
 */
class CursorOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: CursorView? = null

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

    fun tap(x: Int, y: Int, kind: TapKind = TapKind.TAP) {
        view?.tap(x.toFloat(), y.toFloat(), kind)
    }

    fun stroke(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        view?.stroke(listOf(Stroke(fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat())), durationMs)
    }

    fun pinch(strokes: List<Stroke>, durationMs: Long) {
        view?.stroke(strokes, durationMs)
    }

    /** Hides the cursor for a screenshot; call the returned function to show it again. */
    fun hideForCapture(): () -> Unit {
        val v = view ?: return {}
        v.visibility = View.INVISIBLE
        return { v.visibility = View.VISIBLE }
    }

    enum class TapKind { TAP, LONG_PRESS, DOUBLE }

    data class Stroke(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    private class CursorView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val accent = Color.rgb(240, 168, 58)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = accent
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6 * density
            strokeCap = Paint.Cap.ROUND
            color = accent
        }

        private var x = -1f
        private var y = -1f
        private var fromX = -1f
        private var fromY = -1f
        private var moveStart = 0L
        private var strokes: List<Stroke> = emptyList()
        private var strokeStart = 0L
        private var strokeMs = 0L
        private val pulses = ArrayList<Pair<Long, Pair<Float, Float>>>()
        private var lastActivity = 0L

        private fun now() = SystemClock.uptimeMillis()

        fun tap(tx: Float, ty: Float, kind: TapKind) {
            val t = now()
            fromX = if (x < 0) tx else x
            fromY = if (y < 0) ty else y
            x = tx
            y = ty
            moveStart = t
            strokes = emptyList()
            pulses += (t + GLIDE_MS) to (tx to ty)
            if (kind == TapKind.DOUBLE) pulses += (t + GLIDE_MS + 160) to (tx to ty)
            if (kind == TapKind.LONG_PRESS) pulses += (t + GLIDE_MS + 450) to (tx to ty)
            lastActivity = t + if (kind == TapKind.LONG_PRESS) 650 else 0
            postInvalidateOnAnimation()
        }

        fun stroke(list: List<Stroke>, durationMs: Long) {
            val t = now()
            strokes = list
            strokeStart = t
            strokeMs = max(1, durationMs)
            list.firstOrNull()?.let {
                fromX = it.toX
                fromY = it.toY
                x = it.toX
                y = it.toY
                moveStart = 0
            }
            lastActivity = t + durationMs
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val t = now()
            val idle = t - lastActivity
            if (lastActivity == 0L || idle > IDLE_MS + FADE_MS) return
            val alpha = if (idle <= IDLE_MS) 1f else 1f - (idle - IDLE_MS).toFloat() / FADE_MS
            val loc = IntArray(2).also(::getLocationOnScreen)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

            val r = 11 * density
            // Swipe and pinch: a fading line behind each finger, and a dot at its tip.
            if (strokes.isNotEmpty()) {
                val p = min(1f, (t - strokeStart).toFloat() / strokeMs)
                trail.alpha = (160 * alpha).toInt()
                for (s in strokes) {
                    val tipX = s.fromX + (s.toX - s.fromX) * p
                    val tipY = s.fromY + (s.toY - s.fromY) * p
                    canvas.drawLine(s.fromX, s.fromY, tipX, tipY, trail)
                    dot(canvas, tipX, tipY, r, alpha)
                }
            } else {
                val p = if (moveStart == 0L) 1f else min(1f, (t - moveStart).toFloat() / GLIDE_MS)
                val e = 1f - (1f - p) * (1f - p)
                dot(canvas, fromX + (x - fromX) * e, fromY + (y - fromY) * e, r, alpha)
            }
            // Pulses: a ring that grows and fades where a finger went down.
            pulses.removeAll { t - it.first > PULSE_MS }
            for ((start, at) in pulses) {
                if (t < start) continue
                val q = (t - start).toFloat() / PULSE_MS
                ring.alpha = ((1f - q) * 255 * alpha).toInt()
                canvas.drawCircle(at.first, at.second, r + q * 28 * density, ring)
            }
            ring.alpha = 255
            canvas.restore()
            postInvalidateOnAnimation()
        }

        private fun dot(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
            shadow.alpha = (70 * alpha).toInt()
            fill.alpha = (235 * alpha).toInt()
            ring.alpha = (255 * alpha).toInt()
            canvas.drawCircle(cx, cy + 2 * density, r, shadow)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
        }

        companion object {
            const val GLIDE_MS = 110L
            const val PULSE_MS = 380L
            const val IDLE_MS = 1_200L
            const val FADE_MS = 300L
        }
    }
}
