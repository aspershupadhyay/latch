package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import io.github.aspershupadhyay.latch.ui.theme.Cream
import io.github.aspershupadhyay.latch.ui.theme.Ember
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/**
 * The idea of Latch in one picture: an AI on the left, your phone on the
 * right, and between them a latch that every request passes through. Small
 * signals travel along the line and the latch breathes, so it reads as a
 * guarded connection rather than an open one. Static when motion is reduced.
 */
@Composable
fun ConnectionIllustration(reducedMotion: Boolean, modifier: Modifier = Modifier, onPastel: Boolean = false) {
    val base = LocalSignal.current
    // On a pastel card the drawing uses dark ink and white shapes, the same in both themes.
    val signal = if (onPastel) base.copy(surface2 = Color.White, border = base.onPastel.copy(alpha = 0.18f), text = base.onPastel, text2 = base.onPastel.copy(alpha = 0.7f), accent = Ember, canvas = Cream) else base
    val sparkle = rememberVectorPainter(LatchIcons.Sparkle)
    val shield = rememberVectorPainter(LatchIcons.Mark)
    val transition = rememberInfiniteTransition(label = "illustration")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart), label = "flow")
    val progress = if (reducedMotion) 0.35f else t

    Canvas(modifier.semantics { contentDescription = "An AI connects to your phone only through Latch" }) {
        val w = size.width
        val h = size.height
        val cy = h * 0.5f
        val node = h * 0.17f
        val ai = Offset(w * 0.15f, cy)
        val latch = Offset(w * 0.5f, cy)
        val phoneW = h * 0.34f
        val phoneH = h * 0.62f
        val phone = Offset(w * 0.85f, cy)

        // Soft glow behind the latch.
        drawCircle(
            Brush.radialGradient(listOf(signal.accent.copy(alpha = 0.22f), signal.accent.copy(alpha = 0f)), center = latch, radius = h * 0.5f),
            radius = h * 0.5f,
            center = latch,
        )

        // The path a request takes: AI → latch → phone, as one gentle wave.
        val path = Path().apply {
            moveTo(ai.x + node, ai.y)
            cubicTo(w * 0.28f, cy - h * 0.18f, w * 0.38f, cy - h * 0.18f, latch.x - node, latch.y)
            moveTo(latch.x + node, latch.y)
            cubicTo(w * 0.62f, cy + h * 0.18f, w * 0.7f, cy + h * 0.18f, phone.x - phoneW / 2, phone.y)
        }
        drawPath(
            path,
            signal.text2.copy(alpha = 0.45f),
            style = Stroke(width = 2.2f * density, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 7f * density))),
        )

        // Signals in flight.
        val measure = PathMeasure().apply { setPath(path, false) }
        val length = measure.length
        if (length > 0f) {
            for (i in 0 until 3) {
                val p = (progress + i / 3f) % 1f
                val at = measure.getPosition(p * length)
                drawCircle(signal.accent.copy(alpha = 0.25f), radius = 7f * density, center = at)
                drawCircle(signal.accent, radius = 3.5f * density, center = at)
            }
        }

        // AI node.
        drawCircle(signal.surface2, radius = node, center = ai)
        drawCircle(signal.border, radius = node, center = ai, style = Stroke(1.5f * density))
        drawIcon(sparkle, ai, node * 1.05f, signal.text)

        // Latch node with a breathing ring.
        val pulse = if (reducedMotion) 0.5f else (t * 2f) % 1f
        drawCircle(signal.accent.copy(alpha = 0.35f * (1f - pulse)), radius = node * (1.15f + 0.45f * pulse), center = latch, style = Stroke(2f * density))
        drawCircle(signal.accent, radius = node * 1.1f, center = latch)
        drawIcon(shield, latch, node * 1.15f, signal.canvas)

        // Phone: body, screen, and a few lines of "content".
        val topLeft = Offset(phone.x - phoneW / 2, phone.y - phoneH / 2)
        drawRoundRect(signal.surface2, topLeft, Size(phoneW, phoneH), CornerRadius(phoneW * 0.22f))
        drawRoundRect(signal.border, topLeft, Size(phoneW, phoneH), CornerRadius(phoneW * 0.22f), style = Stroke(1.5f * density))
        drawRoundRect(signal.text2.copy(alpha = 0.5f), Offset(phone.x - phoneW * 0.14f, topLeft.y + phoneH * 0.06f), Size(phoneW * 0.28f, phoneH * 0.025f), CornerRadius(50f))
        for (i in 0 until 3) {
            val lineY = topLeft.y + phoneH * (0.22f + i * 0.14f)
            val lineW = phoneW * (if (i == 1) 0.5f else 0.66f)
            drawRoundRect(signal.text2.copy(alpha = 0.35f), Offset(topLeft.x + phoneW * 0.17f, lineY), Size(lineW, phoneH * 0.045f), CornerRadius(50f))
        }
        // The phone's "tap target", lit when a signal arrives.
        val lit = if (reducedMotion) 0.6f else (1f - ((progress * 3f) % 1f)).coerceIn(0f, 1f)
        val tap = Offset(phone.x, topLeft.y + phoneH * 0.76f)
        drawCircle(signal.accent.copy(alpha = 0.2f + 0.5f * lit), radius = phoneW * 0.14f, center = tap)
        drawCircle(signal.accent, radius = phoneW * 0.06f, center = tap)
    }
}

private fun DrawScope.drawIcon(painter: VectorPainter, center: Offset, size: Float, tint: Color) {
    translate(center.x - size / 2, center.y - size / 2) {
        with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
    }
}
