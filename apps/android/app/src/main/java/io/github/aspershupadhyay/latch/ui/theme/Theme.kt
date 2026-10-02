package io.github.aspershupadhyay.latch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Signal Field tokens, bento edition: colours are named by meaning. Tiles sit
 * on a quiet canvas; colour is reserved for state (active, attention, stop)
 * and for the one hero tile, so it still carries meaning.
 */
@Immutable
data class Signal(
    val dark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val accent: Color,
    val accent2: Color,
    val mint: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val disabled: Color,
) {
    /** Hero tile backgrounds by state. White text stays above 4.5:1 on every stop. */
    val activeBrush get() = Brush.linearGradient(listOf(Color(0xFF3F3FD8), Color(0xFF7C3AED)))
    val idleBrush get() = Brush.linearGradient(listOf(Color(0xFF1E2233), Color(0xFF2D3350)))
    val attentionBrush get() = Brush.linearGradient(listOf(Color(0xFFB45309), Color(0xFFC2410C)))
    val stopBrush get() = Brush.linearGradient(listOf(Color(0xFFB91C1C), Color(0xFF9F1239)))
}

private val Light = Signal(
    dark = false,
    canvas = Color(0xFFF2F3F8), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEBEDF5), border = Color(0xFFE1E4EE),
    text = Color(0xFF0F1222), text2 = Color(0xFF586074), accent = Color(0xFF4B4BE0), accent2 = Color(0xFF7C3AED),
    mint = Color(0xFF0E8F83), success = Color(0xFF13834A), warning = Color(0xFFA65F00), danger = Color(0xFFC92A30),
    disabled = Color(0xFF9AA0AE),
)

private val Dark = Signal(
    dark = true,
    canvas = Color(0xFF0A0B10), surface = Color(0xFF14161D), surface2 = Color(0xFF1C1F29), border = Color(0xFF262A36),
    text = Color(0xFFF2F4F8), text2 = Color(0xFF9EA6B8), accent = Color(0xFF8E8EFF), accent2 = Color(0xFFB794FF),
    mint = Color(0xFF3ED9C7), success = Color(0xFF3DD68C), warning = Color(0xFFFFB224), danger = Color(0xFFFF6B70),
    disabled = Color(0xFF596070),
)

val LocalSignal = staticCompositionLocalOf { Light }

private val LatchType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

@Composable
fun LatchTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val signal = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = signal.accent, onPrimary = Color.Black, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = signal.border,
            surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2, secondaryContainer = signal.surface2,
            surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
        )
    } else {
        lightColorScheme(
            primary = signal.accent, onPrimary = Color.White, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = signal.border,
            surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2, secondaryContainer = signal.surface2,
            surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
        )
    }
    CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, typography = LatchType, content = content)
    }
}
