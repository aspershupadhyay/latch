package io.github.aspershupadhyay.latch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Signal Field tokens: colours are named by meaning, not by component. */
@Immutable
data class Signal(
    val canvas: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val disabled: Color,
)

private val Light = Signal(
    canvas = Color(0xFFF6F7F9), surface = Color(0xFFFFFFFF), border = Color(0xFFDDE1E7),
    text = Color(0xFF14171C), text2 = Color(0xFF5B6470), accent = Color(0xFF2F5BEA),
    success = Color(0xFF1D8A55), warning = Color(0xFFB26B00), danger = Color(0xFFC3362B), disabled = Color(0xFFA3AAB4),
)

private val Dark = Signal(
    canvas = Color(0xFF0E1014), surface = Color(0xFF171A20), border = Color(0xFF2A2F38),
    text = Color(0xFFE9EDF2), text2 = Color(0xFF9AA4B2), accent = Color(0xFF7C9CFF),
    success = Color(0xFF4CC38A), warning = Color(0xFFF0A83A), danger = Color(0xFFFF6B5F), disabled = Color(0xFF5A616C),
)

val LocalSignal = staticCompositionLocalOf { Light }

@Composable
fun LatchTheme(content: @Composable () -> Unit) {
    val signal = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = signal.accent, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = signal.border,
            surfaceVariant = signal.surface, onSurfaceVariant = signal.text2,
        )
    } else {
        lightColorScheme(
            primary = signal.accent, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = signal.border,
            surfaceVariant = signal.surface, onSurfaceVariant = signal.text2,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
