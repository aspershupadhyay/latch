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
 * Latch tokens: graphite neutrals, one emerald accent, and colour only where
 * it means something (live, attention, stop). Primary actions are ink on paper
 * (white on black in dark mode), so they read as buttons at a glance.
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
    val activeBrush get() = Brush.linearGradient(listOf(Color(0xFF064E3B), Color(0xFF0B3B30)))
    val idleBrush get() = Brush.linearGradient(listOf(Color(0xFF18181B), Color(0xFF232327)))
    val attentionBrush get() = Brush.linearGradient(listOf(Color(0xFF7C2D12), Color(0xFF9A3412)))
    val stopBrush get() = Brush.linearGradient(listOf(Color(0xFF7F1D1D), Color(0xFF881337)))
}

private val Light = Signal(
    dark = false,
    canvas = Color(0xFFF7F7F5), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF1F1EF), border = Color(0xFFE4E4E2),
    text = Color(0xFF0A0A0B), text2 = Color(0xFF5B5B63), accent = Color(0xFF047857), accent2 = Color(0xFF2563EB),
    mint = Color(0xFF0F766E), success = Color(0xFF047857), warning = Color(0xFFB45309), danger = Color(0xFFDC2626),
    disabled = Color(0xFFA1A1AA), ink = Color(0xFF0A0A0B), onInk = Color(0xFFFAFAFA),
)

private val Dark = Signal(
    dark = true,
    canvas = Color(0xFF09090B), surface = Color(0xFF131316), surface2 = Color(0xFF1C1C20), border = Color(0xFF2A2A2F),
    text = Color(0xFFFAFAFA), text2 = Color(0xFFA1A1AA), accent = Color(0xFF34D399), accent2 = Color(0xFF60A5FA),
    mint = Color(0xFF2DD4BF), success = Color(0xFF34D399), warning = Color(0xFFFBBF24), danger = Color(0xFFF87171),
    disabled = Color(0xFF52525B), ink = Color(0xFFFAFAFA), onInk = Color(0xFF09090B),
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
