package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

val CardShape = RoundedCornerShape(24.dp)

/** An icon on a soft tinted square: the visual anchor of every row. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp, fill: Color = tint.copy(alpha = 0.14f)) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** A plain card that groups related rows. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(color ?: LocalSignal.current.surface).padding(vertical = 6.dp),
        content = content,
    )
}

/** Thin line between rows inside a [Card], indented past the icon. */
@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), thickness = 1.dp, color = LocalSignal.current.border.copy(alpha = 0.6f))
}

/**
 * One line of the app: icon, title, optional subtitle, and something on the
 * right (a chevron, a switch, a status). Tappable rows announce themselves
 * as buttons.
 */
@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = LocalSignal.current.accent,
    titleColor: Color = LocalSignal.current.text,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val signal = LocalSignal.current
    var m = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (onClick != null) m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = signal.text2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (showChevron) {
            Spacer(Modifier.width(12.dp))
            Chevron()
        }
    }
}

@Composable
fun Chevron() {
    Icon(LatchIcons.ChevronRight, contentDescription = null, tint = LocalSignal.current.text2.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
}

/** Small rounded label with an icon, e.g. "Encrypted" or "High risk". */
@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null, fill: Color = color.copy(alpha = 0.14f)) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Screen header: one big title and at most one quiet line under it. */
@Composable
fun Header(title: String, subtitle: String? = null) = ScreenTitle(title, subtitle)

/** Section caption above a card. */
@Composable
fun SectionCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalSignal.current.text2,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** A thin progress bar that glides to its new value. */
@Composable
fun ProgressBar(progress: Float, reducedMotion: Boolean, color: Color = LocalSignal.current.accent) {
    val shown by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        animationSpec = if (reducedMotion) tween(0) else tween(500, easing = FastOutSlowInEasing),
        label = "progress",
    )
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(50)).background(color))
    }
}

/** What the status orb shows. */
enum class OrbState { IDLE, CONNECTING, ACTIVE, PAUSED, ATTENTION, STOPPED }

/**
 * The session state as an icon inside soft rings. While a session is live the
 * rings breathe outward, so "something can act on this phone" is visible at a
 * glance. Motion stops when the system asks for reduced motion.
 */
@Composable
fun StatusOrb(state: OrbState, description: String, reducedMotion: Boolean, size: Dp = 76.dp, tint: Color = Color.White) {
    val animate = !reducedMotion && (state == OrbState.ACTIVE || state == OrbState.CONNECTING || state == OrbState.ATTENTION)
    val transition = rememberInfiniteTransition(label = "orb")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (state == OrbState.CONNECTING) 1_200 else 2_400, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "wave",
    )
    val icon = when (state) {
        OrbState.IDLE -> LatchIcons.Shield
        OrbState.CONNECTING -> LatchIcons.Cloud
        OrbState.ACTIVE -> LatchIcons.ShieldCheck
        OrbState.PAUSED -> LatchIcons.Pause
        OrbState.ATTENTION -> LatchIcons.Warning
        OrbState.STOPPED -> LatchIcons.Stop
    }
    Box(Modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val base = this.size.minDimension * 0.3f
            val max = this.size.minDimension / 2
            if (animate) {
                for (i in 0 until 2) {
                    val t = (wave + i * 0.5f) % 1f
                    drawCircle(tint.copy(alpha = 0.35f * (1f - t)), radius = base + (max - base) * t, center = c, style = Stroke(width = 2.dp.toPx()))
                }
            } else {
                drawCircle(tint.copy(alpha = 0.22f), radius = max * 0.92f, center = c, style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(tint.copy(alpha = 0.18f), radius = base, center = c)
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.36f))
    }
}

/** A round check that marks a finished step. */
@Composable
fun DoneMark(size: Dp = 28.dp) {
    val signal = LocalSignal.current
    Box(Modifier.size(size).clip(CircleShape).background(signal.success), contentAlignment = Alignment.Center) {
        Icon(LatchIcons.Check, contentDescription = "Done", tint = if (signal.dark) Color.Black else Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
