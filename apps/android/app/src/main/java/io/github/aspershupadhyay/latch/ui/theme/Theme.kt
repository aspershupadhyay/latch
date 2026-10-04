// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Latch tokens, "paper and ember" (2026-10): warm paper and espresso ink, one
 * terracotta accent for action, and four earthy pastels (clay, sage, butter,
 * pool) for the Access groups and Home tiles, always with dark text on them.
 * Colour still means something: green for done and connected, amber for
 * attention, red for stop. Headlines are set in a serif for warmth.
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
    /** Pastel cards; text on them is always [onPastel]. */
    val clay: Color,
    val sage: Color,
    val butter: Color,
    val pool: Color,
    val onPastel: Color = Color(0xFF1F1A16),
) {
    /** Backgrounds for the status card. White text stays above 4.5:1 on every stop. */
    val activeBrush get() = Brush.linearGradient(listOf(HeroActive.first, HeroActive.second))
    val idleBrush get() = Brush.linearGradient(listOf(HeroIdle.first, HeroIdle.second))
    val attentionBrush get() = Brush.linearGradient(listOf(HeroAttention.first, HeroAttention.second))
    val stopBrush get() = Brush.linearGradient(listOf(HeroStopped.first, HeroStopped.second))
}

/** Hero card gradients (start to end), shared by Home and the brushes above. */
val HeroIdle = Color(0xFF2A211B) to Color(0xFF45352A)
val HeroActive = Color(0xFF8F3417) to Color(0xFFB8492A)
val HeroAttention = Color(0xFF7E4F08) to Color(0xFFA35A0A)
val HeroStopped = Color(0xFF6E1C17) to Color(0xFF922920)

/** The brand terracotta, the same in both themes (launcher icon, intro, overlays). */
val Ember = Color(0xFFC2532D)
val Cream = Color(0xFFFFF8EE)

private val Light = Signal(
    dark = false,
    canvas = Color(0xFFF5EFE6), surface = Color(0xFFFFFBF5), surface2 = Color(0xFFEFE6DA), border = Color(0xFFE6DACB),
    text = Color(0xFF1F1A16), text2 = Color(0xFF74685D), accent = Color(0xFFB54A26), accent2 = Color(0xFF2F6F62),
    mint = Color(0xFF5E7A4A), success = Color(0xFF2F7D4F), warning = Color(0xFFA85C0A), danger = Color(0xFFB3261E),
    disabled = Color(0xFFB9AEA2), ink = Color(0xFF1F1A16), onInk = Color(0xFFFFFBF5),
    clay = Color(0xFFF3C2A9), sage = Color(0xFFCAD9BC), butter = Color(0xFFF4DA90), pool = Color(0xFFBEDBD4),
)

private val Dark = Signal(
    dark = true,
    canvas = Color(0xFF14100D), surface = Color(0xFF201A16), surface2 = Color(0xFF2B241F), border = Color(0xFF3A312A),
    text = Color(0xFFF5ECE1), text2 = Color(0xFFAA9C8E), accent = Color(0xFFF08A5D), accent2 = Color(0xFF7CC2B1),
    mint = Color(0xFFA9C48F), success = Color(0xFF6CC08B), warning = Color(0xFFF2B14C), danger = Color(0xFFFF7A6B),
    disabled = Color(0xFF574C43), ink = Color(0xFFF5ECE1), onInk = Color(0xFF1F1A16),
    clay = Color(0xFFDE9A79), sage = Color(0xFFA3BC91), butter = Color(0xFFDDC067), pool = Color(0xFF8DC2B7),
)

val LocalSignal = staticCompositionLocalOf { Light }

/** The serif used for headlines and big numbers: warm and editorial, from the system (no font download). */
val Display = FontFamily.Serif

private val LatchType = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = Display, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp),
    displaySmall = TextStyle(fontFamily = Display, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
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
            primary = signal.accent, onPrimary = Color(0xFF1F1A16), background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = Color(0xFF8A7D70),
            outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
            secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
            surfaceContainerHighest = Color(0xFF3A312A),
        )
    } else {
        lightColorScheme(
            primary = signal.accent, onPrimary = Color.White, background = signal.canvas, surface = signal.surface,
            onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = Color(0xFF8A7D70),
            outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
            secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
            surfaceContainerHighest = Color(0xFFE6DACB),
        )
    }
    CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, typography = LatchType, content = content)
    }
}
