package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

val TileShape = RoundedCornerShape(26.dp)
val Gap = 12.dp

/** A row of bento tiles that share the tallest tile's height. */
@Composable
fun BentoRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Gap), content = content)
}

/** One bento tile. Clickable tiles announce themselves as buttons. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    color: Color? = null,
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    minHeight: Dp = 128.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val signal = LocalSignal.current
    var m = modifier.heightIn(min = minHeight).clip(TileShape)
    m = when {
        brush != null -> m.background(brush)
        else -> m.background(color ?: signal.surface)
    }
    if (brush == null && color == null && !signal.dark) m = m.border(BorderStroke(1.dp, signal.border), TileShape)
    if (onClick != null) m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    Column(m.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Small uppercase label at the top of a tile. */
@Composable
fun TileLabel(text: String, color: Color = LocalSignal.current.text2) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The big number or word that is the point of a tile. */
@Composable
fun TileValue(text: String, color: Color = LocalSignal.current.text) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun TileNote(text: String, color: Color = LocalSignal.current.text2, maxLines: Int = 3) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun ColumnScope.Push() = Spacer(Modifier.weight(1f))

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = LocalSignal.current.text, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalSignal.current.text2) }
    }
}

/** A coloured dot with a label: state at a glance, never colour alone. */
@Composable
fun StatusDot(color: Color, label: String, textColor: Color = LocalSignal.current.text2) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, contentColor: Color? = null) {
    val signal = LocalSignal.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = color ?: signal.ink,
            contentColor = contentColor ?: if (color == null) signal.onInk else Color.White,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentColor: Color? = null) {
    val signal = LocalSignal.current
    // A soft filled button, quieter than the primary one (Apple's "gray" button style).
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (contentColor != null) contentColor.copy(alpha = 0.16f) else signal.accent.copy(alpha = if (signal.dark) 0.18f else 0.10f),
            contentColor = contentColor ?: signal.accent,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** A value the owner copies elsewhere (address, link, key), with a Copy button. */
@Composable
fun CopyRow(label: String, value: String, onCopy: () -> Unit, masked: Boolean = false) {
    val signal = LocalSignal.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(signal.surface2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TileLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (masked) value.take(10) + "•".repeat(14) else value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = signal.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            SecondaryButton("Copy", onCopy)
        }
    }
}
