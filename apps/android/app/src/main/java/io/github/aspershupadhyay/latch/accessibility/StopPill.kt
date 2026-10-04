// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The always-visible session indicator and emergency stop, drawn over every
 * app while a session runs: a small espresso capsule with a breathing ember
 * dot and a red Stop button, kept small so it covers as little of the app as
 * possible. A tap anywhere stops everything; dragging moves it (it lifts
 * under the finger, then glides to the nearer side). A drag never stops.
 * Screenshots for the AI never show it, and it steps aside when the AI needs
 * the spot it sits on (LatchAccessibilityService.movePillAside).
 */
@SuppressLint("ViewConstructor")
class StopPill(
    context: Context,
    private val onStop: () -> Unit,
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onRelease: () -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 31, 26, 22) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(40, 255, 248, 238) }
    private val ember = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 138, 93) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 138, 93) }
    private val stopFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(217, 58, 43) }
    private val stopGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val h = dp(44f)
    private val padL = dp(11f)
    private val dotArea = dp(14f)
    private val gap = dp(7f)
    private val stopD = dp(34f)
    private val padR = dp(5f)
    private val w = padL + dotArea + gap + stopD + padR

    private val box = RectF()
    private val inner = RectF()
    private val glyph = RectF()

    /** 0..1, the breathing of the live dot. */
    private var breath = 0f
    private val breathing = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { breath = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Latch remote control is active. Double-tap to stop all activity. You can drag it to move it."
        elevation = dp(10f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(w.roundToInt(), h.roundToInt())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        breathing.start()
    }

    override fun onDetachedFromWindow() {
        breathing.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, r, r, bg)
        inner.set(box)
        inner.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(inner, r, r, edge)

        // The live dot breathes out a soft ring.
        val cx = padL + dotArea / 2
        val cy = height / 2f
        halo.alpha = (90 * (1f - breath)).roundToInt()
        canvas.drawCircle(cx, cy, dp(4f) + dp(4f) * breath, halo)
        canvas.drawCircle(cx, cy, dp(4f), ember)

        val sx = width - padR - stopD / 2
        canvas.drawCircle(sx, cy, stopD / 2, stopFill)
        val g = dp(5f)
        glyph.set(sx - g, cy - g, sx + g, cy + g)
        canvas.drawRoundRect(glyph, dp(2.5f), dp(2.5f), stopGlyph)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                dragging = false
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                    dragging = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start()
                }
                if (dragging) {
                    onMove((event.rawX - lastX).roundToInt(), (event.rawY - lastY).roundToInt())
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                if (dragging) onRelease() else performClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                if (dragging) onRelease()
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onStop()
        return true
    }
}
