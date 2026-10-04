// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

val CardShape = RoundedCornerShape(10.dp)

/**
 * A line symbol in a small square chip of its own colour: the visual anchor of
 * every row. [tint] colours the symbol; the chip is a faint wash of it with a
 * hairline edge, unless [fill] is given.
 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 36.dp, fill: Color? = null) {
    val shape = RoundedCornerShape(size * 0.24f)
    var m = Modifier.size(size).clip(shape).background(fill ?: tint.copy(alpha = 0.10f))
    if (fill == null) m = m.border(1.dp, tint.copy(alpha = 0.28f), shape)
    Box(m, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

/**
 * On/off switch: dark knob on the ember track when on, a quiet hairline track
 * when off. [onPastel] is kept for callers; the tinted panels are dark, so the
 * same switch reads on them.
 */
@Composable
fun LatchSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, onPastel: Boolean = false) {
    val signal = LocalSignal.current
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = signal.onInk,
            checkedTrackColor = signal.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = signal.text2,
            uncheckedTrackColor = if (onPastel) Color.Black.copy(alpha = 0.35f) else signal.surface2,
            uncheckedBorderColor = signal.border2,
            disabledCheckedTrackColor = signal.accent.copy(alpha = 0.35f),
            disabledCheckedThumbColor = signal.onInk.copy(alpha = 0.8f),
            disabledUncheckedTrackColor = signal.surface,
            disabledUncheckedThumbColor = signal.disabled,
            disabledUncheckedBorderColor = signal.border,
        ),
    )
}

/** A panel with a hairline edge that groups related rows. A tinted [color] gets an edge of the same hue. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val signal = LocalSignal.current
    val edge = if (color == null || color.alpha >= 1f) signal.border else color.copy(alpha = (color.alpha * 2.4f).coerceAtMost(0.45f))
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(signal.surface).background(color ?: Color.Transparent)
            .border(1.dp, edge, CardShape).padding(vertical = 4.dp),
        content = content,
    )
}

/** Thin line between rows inside a [Card], indented past the icon. */
@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 68.dp), thickness = 0.5.dp, color = LocalSignal.current.border)
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
    var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    if (onClick != null) m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = signal.text2, maxLines = 4, overflow = TextOverflow.Ellipsis) }
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
    Icon(LatchIcons.ChevronRight, contentDescription = null, tint = LocalSignal.current.text3, modifier = Modifier.size(18.dp))
}

/** Small tag with an icon, e.g. "Encrypted" or "High risk": mono caps in a hairline box. */
@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null, fill: Color = color.copy(alpha = 0.08f)) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.clip(shape).background(fill).border(1.dp, color.copy(alpha = 0.4f), shape).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Screen header: one big title and at most one quiet line under it. */
@Composable
fun Header(title: String, subtitle: String? = null) = ScreenTitle(title, subtitle)

/** Section caption above a card: an ember tick and a mono uppercase label, like the website's section labels. */
@Composable
fun SectionCaption(text: String) {
    val signal = LocalSignal.current
    Row(Modifier.padding(start = 2.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 8.dp, height = 2.dp).background(signal.accent))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = signal.text2, modifier = Modifier.semantics { heading() })
    }
}

/** A thin progress bar that glides to its new value. */
@Composable
fun ProgressBar(progress: Float, reducedMotion: Boolean, color: Color = LocalSignal.current.accent) {
    val shown by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        animationSpec = if (reducedMotion) tween(0) else tween(500, easing = FastOutSlowInEasing),
        label = "progress",
    )
    Box(Modifier.fillMaxWidth().height(3.dp).background(color.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(color))
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
        OrbState.IDLE -> LatchIcons.Mark
        OrbState.CONNECTING -> LatchIcons.Cloud
        OrbState.ACTIVE -> LatchIcons.Mark
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

/** A square check that marks a finished step. */
@Composable
fun DoneMark(size: Dp = 28.dp) {
    val signal = LocalSignal.current
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(signal.success), contentAlignment = Alignment.Center) {
        Icon(LatchIcons.Check, contentDescription = "Done", tint = signal.onInk, modifier = Modifier.size(size * 0.6f))
    }
}
