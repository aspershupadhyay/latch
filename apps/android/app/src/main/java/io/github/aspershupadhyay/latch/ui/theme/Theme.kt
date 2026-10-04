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
 * Latch tokens (2026-10 refresh): soft neutrals, a periwinkle accent, black
 * pill buttons, and pastel category cards (periwinkle, orchid, apricot, mint)
 * that carry dark text in both themes. Colour still means something: green
 * only for "done" and "connected", amber for attention, red for stop.
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
    /** Filled primary buttons. */
    val ink: Color,
    val onInk: Color,
    /** Pastel category cards; text on them is always [onPastel]. */
    val periwinkle: Color = Color(0xFFA9B6F8),
    val orchid: Color = Color(0xFFE7A8F4),
    val apricot: Color = Color(0xFFF7A55A),
    val mintCard: Color = Color(0xFFA6E3C8),
    val onPastel: Color = Color(0xFF0E0E12),
) {
    /** Backgrounds for the status card and alerts. White text stays above 4.5:1 on every stop. */
    val activeBrush get() = Brush.linearGradient(listOf(Color(0xFF3442B8), Color(0xFF3B4BC4), Color(0xFF5465D8)))
    val idleBrush get() = Brush.linearGradient(listOf(Color(0xFF0F1222), Color(0xFF1C2140)))
    val attentionBrush get() = Brush.linearGradient(listOf(Color(0xFFC2410C), Color(0xFFEA580C)))
    val stopBrush get() = Brush.linearGradient(listOf(Color(0xFF7F1D1D), Color(0xFF9F1239)))
}

private val Light = Signal(
    dark = false,
    canvas = Color(0xFFF3F3F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF0F0F4), border = Color(0xFFE3E3E9),
    text = Color(0xFF0E0E12), text2 = Color(0xFF6B6B75), accent = Color(0xFF4F62D8), accent2 = Color(0xFF2F7DE1),
    mint = Color(0xFF7A6FE0), success = Color(0xFF248A3D), warning = Color(0xFFC2620A), danger = Color(0xFFD70015),
    disabled = Color(0xFFAEAEB2), ink = Color(0xFF0E0E12), onInk = Color(0xFFFFFFFF),
)

private val Dark = Signal(
    dark = true,
    canvas = Color(0xFF0B0B0F), surface = Color(0xFF18181D), surface2 = Color(0xFF23232A), border = Color(0xFF2F2F37),
    text = Color(0xFFF4F4F7), text2 = Color(0xFF9C9CA8), accent = Color(0xFFA3B1FF), accent2 = Color(0xFF7DB8FF),
    mint = Color(0xFFB8AEFF), success = Color(0xFF30D158), warning = Color(0xFFFFB340), danger = Color(0xFFFF6961),
    disabled = Color(0xFF48484A), ink = Color(0xFFF4F4F7), onInk = Color(0xFF0E0E12),
    periwinkle = Color(0xFF8D9DF2), orchid = Color(0xFFD48BE8), apricot = Color(0xFFEE9845), mintCard = Color(0xFF7FCFAB),
)

val LocalSignal = staticCompositionLocalOf { Light }

private val LatchType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

@Composable
fun LatchTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val signal = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = signal.accent, onPrimary = Color(0xFF0E0E12), background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = Color(0xFF8E8E93),
            outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
            secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
            surfaceContainerHighest = Color(0xFF34343C),
        )
    } else {
        lightColorScheme(
            primary = signal.accent, onPrimary = Color.White, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = Color(0xFF8E8E93),
            outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
            secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
            surfaceContainerHighest = Color(0xFFE6E6EC),
        )
    }
    CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, typography = LatchType, content = content)
    }
}
