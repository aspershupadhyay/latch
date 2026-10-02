package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/** The state vocabulary of the dot field (handbook chapter 09). */
enum class FieldState { READY, CONNECTED, CONNECTING, DISCONNECTED, AWAITING_APPROVAL, STOPPED }

/**
 * A small field of dots that explains connection state at a glance. It is a
 * visualisation, not decoration: every state also has text, and motion stops
 * entirely when the system asks for reduced motion.
 */
@Composable
fun SignalField(
    state: FieldState,
    description: String,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    tint: Color? = null,
) {
    val signal = LocalSignal.current
    val color = tint ?: when (state) {
        FieldState.READY -> signal.accent
        FieldState.CONNECTED -> signal.success
        FieldState.CONNECTING -> signal.accent
        FieldState.DISCONNECTED -> signal.disabled
        FieldState.AWAITING_APPROVAL -> signal.warning
        FieldState.STOPPED -> signal.danger
    }
    val animate = !reducedMotion && (state == FieldState.CONNECTED || state == FieldState.CONNECTING)
    val transition = rememberInfiniteTransition(label = "field")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (state == FieldState.CONNECTING) 1_600 else 9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit",
    )
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        val dot = r * 0.16f
        val ringAlpha = if (state == FieldState.DISCONNECTED) 0.15f else 0.25f
        drawCircle(color.copy(alpha = ringAlpha), radius = r * 0.62f, center = c, style = Stroke(width = 2.dp.toPx()))
        when (state) {
            FieldState.READY -> drawCircle(color, dot, c)
            FieldState.DISCONNECTED -> {
                drawCircle(color, dot, Offset(c.x - r * 0.62f, c.y))
                drawCircle(color, dot, Offset(c.x + r * 0.62f, c.y))
            }
            FieldState.AWAITING_APPROVAL -> {
                drawCircle(color, r * 0.82f, c, style = Stroke(width = 4.dp.toPx()))
                drawCircle(color, dot, Offset(c.x - r * 0.3f, c.y))
                drawCircle(color, dot, Offset(c.x + r * 0.3f, c.y))
            }
            FieldState.STOPPED -> {
                drawCircle(color.copy(alpha = 0.2f), r * 0.9f, c)
                drawCircle(color, dot * 1.4f, c)
            }
            FieldState.CONNECTED, FieldState.CONNECTING -> rotate(if (animate) angle else 0f, c) {
                drawCircle(color, dot, Offset(c.x - r * 0.62f, c.y))
                drawCircle(color, dot, Offset(c.x + r * 0.62f, c.y))
            }
        }
    }
}
