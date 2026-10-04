package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.ui.theme.Cream
import io.github.aspershupadhyay.latch.ui.theme.Display
import io.github.aspershupadhyay.latch.ui.theme.Ember
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The Latch mark: a hook that has caught a pin. The hook is the owner's hold
 * on the phone; the pin is the AI, which only works while it is held. Drawn
 * on a 24-unit grid, the same geometry as the launcher icon
 * (res/drawable/ic_launcher_foreground.xml, scaled by 2.2 and moved by 27.6).
 */
private const val STROKE = 2.6f
private val PIN = Offset(16.4f, 17.2f)
private const val PIN_R = 2.5f

private fun hookPath(scale: Float) = Path().apply {
    moveTo(6.4f * scale, 18.4f * scale)
    lineTo(6.4f * scale, 10.4f * scale)
    // Half circle over the top, centre (11.4, 10.4), radius 5.
    cubicTo(6.4f * scale, 3.73f * scale, 16.4f * scale, 3.73f * scale, 16.4f * scale, 10.4f * scale)
    lineTo(16.4f * scale, 12.6f * scale)
}

/**
 * The mark. [hook] draws the hook from 0 to 1; [pinLift] raises the pin by
 * that many grid units (it falls into place as it goes to 0); [pinScale]
 * grows the pin.
 */
@Composable
fun LatchMark(
    modifier: Modifier = Modifier,
    color: Color = LocalSignal.current.text,
    pinColor: Color = color,
    hook: Float = 1f,
    pinLift: Float = 0f,
    pinScale: Float = 1f,
) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val full = hookPath(s)
        val path = if (hook >= 1f) {
            full
        } else {
            Path().also { out -> PathMeasure().apply { setPath(full, false) }.let { it.getSegment(0f, it.length * hook.coerceIn(0f, 1f), out, true) } }
        }
        if (hook > 0f) drawPath(path, color, style = Stroke(STROKE * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (pinScale > 0f) drawCircle(pinColor, radius = PIN_R * s * pinScale, center = Offset(PIN.x * s, (PIN.y - pinLift) * s))
    }
}

/** The app icon as drawn in the app: the mark in cream on a terracotta squircle. */
@Composable
fun LatchLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Ember).semantics { contentDescription = "Latch" },
        contentAlignment = Alignment.Center,
    ) {
        LatchMark(Modifier.size(size * 0.7f), color = Cream)
    }
}

private val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Opening animation, about 1.6 s: the squircle springs in, the hook draws
 * itself, the pin drops and clicks into it with a ripple, the name rises, and
 * everything lifts away to the app. A tap skips it; with reduced motion the
 * finished logo shows briefly and fades.
 */
@Composable
fun LatchIntro(reducedMotion: Boolean, onDone: () -> Unit) {
    val signal = LocalSignal.current
    val tile = remember { Animatable(if (reducedMotion) 1f else 0.55f) }
    val tileAlpha = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val hook = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val pinLift = remember { Animatable(if (reducedMotion) 0f else 9f) }
    val pinScale = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val squash = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }
    val word = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (reducedMotion) {
            delay(450)
            exit.animateTo(1f, tween(220))
            onDone()
            return@LaunchedEffect
        }
        launch { tileAlpha.animateTo(1f, tween(220)) }
        launch { tile.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
        delay(180)
        launch { hook.animateTo(1f, tween(560, easing = Settle)) }
        delay(470)
        pinScale.snapTo(1f)
        pinLift.animateTo(0f, tween(240, easing = CubicBezierEasing(0.55f, 0f, 0.9f, 0.6f)))
        // The click: the tile squashes, the pin settles, a ring spreads out.
        launch { squash.animateTo(0.92f, tween(70)); squash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)) }
        launch { ripple.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
        launch { pinLift.animateTo(0.9f, tween(90)); pinLift.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 900f)) }
        delay(140)
        word.animateTo(1f, tween(420, easing = Settle))
        delay(420)
        exit.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        onDone()
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(signal.canvas)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Skip") { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center) {
                // The ripple of the click, behind the tile.
                Canvas(Modifier.size(220.dp)) {
                    val r = ripple.value
                    if (r > 0f && r < 1f) {
                        drawCircle(Ember.copy(alpha = 0.28f * (1f - r)), radius = size.minDimension / 2 * (0.42f + 0.58f * r), style = Stroke(3.dp.toPx() * (1f - r) + 1f))
                    }
                }
                Box(
                    Modifier.size(112.dp)
                        .graphicsLayer {
                            val lift = 1f + 0.06f * exit.value
                            scaleX = tile.value * (2f - squash.value) * lift
                            scaleY = tile.value * squash.value * lift
                            alpha = tileAlpha.value
                            translationY = -24.dp.toPx() * exit.value
                        }
                        .clip(RoundedCornerShape(34.dp))
                        .background(Ember),
                    contentAlignment = Alignment.Center,
                ) {
                    LatchMark(Modifier.size(78.dp), color = Cream, hook = hook.value, pinLift = pinLift.value, pinScale = pinScale.value)
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = word.value * (1f - exit.value)
                    translationY = 14.dp.toPx() * (1f - word.value)
                },
            ) {
                Text("latch", style = MaterialTheme.typography.displayMedium.copy(fontFamily = Display, letterSpacing = (-0.5).sp), color = signal.text)
                Text("Your phone. Your rules.", style = MaterialTheme.typography.bodyMedium, color = signal.text2)
            }
        }
    }
}
