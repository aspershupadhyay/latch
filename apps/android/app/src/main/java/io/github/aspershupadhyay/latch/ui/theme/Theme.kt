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
 * Latch tokens (2026-10 redesign): Apple-style neutrals, one deep indigo
 * accent for trust and action, and colour only where it means something
 * (live, attention, stop). Green appears only for "done" and "connected".
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
) {
    /** Backgrounds for the status card and alerts. White text stays above 4.5:1 on every stop. */
    val activeBrush get() = Brush.linearGradient(listOf(Color(0xFF2B2F8F), Color(0xFF4338CA), Color(0xFF5B4FD9)))
    val idleBrush get() = Brush.linearGradient(listOf(Color(0xFF0F1222), Color(0xFF1C2140)))
    val attentionBrush get() = Brush.linearGradient(listOf(Color(0xFFC2410C), Color(0xFFEA580C)))
    val stopBrush get() = Brush.linearGradient(listOf(Color(0xFF7F1D1D), Color(0xFF9F1239)))
}

private val Light = Signal(
    dark = false,
    canvas = Color(0xFFF2F2F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEFEFF4), border = Color(0xFFD8D8DE),
    text = Color(0xFF0B0B0F), text2 = Color(0xFF6C6C74), accent = Color(0xFF4F46E5), accent2 = Color(0xFF0A84FF),
    mint = Color(0xFF5E5CE6), success = Color(0xFF248A3D), warning = Color(0xFFC2620A), danger = Color(0xFFD70015),
    disabled = Color(0xFFAEAEB2), ink = Color(0xFF4F46E5), onInk = Color(0xFFFFFFFF),
)

private val Dark = Signal(
    dark = true,
    canvas = Color(0xFF000000), surface = Color(0xFF1C1C1E), surface2 = Color(0xFF2C2C2E), border = Color(0xFF38383A),
    text = Color(0xFFF5F5F7), text2 = Color(0xFF98989F), accent = Color(0xFF8B8BFF), accent2 = Color(0xFF64B5FF),
    mint = Color(0xFFA5A3FF), success = Color(0xFF30D158), warning = Color(0xFFFFB340), danger = Color(0xFFFF6961),
    disabled = Color(0xFF48484A), ink = Color(0xFF8B8BFF), onInk = Color(0xFF0B0B0F),
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
            primary = signal.accent, onPrimary = Color(0xFF0B0B0F), background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = Color(0xFF8E8E93),
            outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
            secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
            surfaceContainerHighest = Color(0xFF39393D),
        )
    } else {
        lightColorScheme(
            primary = signal.accent, onPrimary = Color.White, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = Color(0xFF8E8E93),
            outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
            secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
            surfaceContainerHighest = Color(0xFFE5E5EA),
        )
    }
    CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, typography = LatchType, content = content)
    }
}
