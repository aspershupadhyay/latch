// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.R

/**
 * Latch tokens, "terminal" (2026-10), matching the website: a near-black
 * canvas, hairline borders, Geist for words and Geist Mono for labels,
 * numbers, and codes, and one ember accent for action. The app is dark only.
 * Colour still means something: green for done and connected, amber for
 * attention, red for stop. The four group tints (clay, sage, butter, pool)
 * are dark panels washed with a hue, always with light text on them.
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
    /** Tinted group panels; text on them is always [onPastel]. */
    val clay: Color,
    val sage: Color,
    val butter: Color,
    val pool: Color,
    val onPastel: Color,
    /** A second, stronger hairline for edges that must read (inputs, buttons). */
    val border2: Color,
    /** Faint text: indexes, hints, separators. */
    val text3: Color,
) {
    /** Backgrounds for the status card. White text stays above 4.5:1 on every stop. */
    val activeBrush get() = Brush.linearGradient(listOf(HeroActive.first, HeroActive.second))
    val idleBrush get() = Brush.linearGradient(listOf(HeroIdle.first, HeroIdle.second))
    val attentionBrush get() = Brush.linearGradient(listOf(HeroAttention.first, HeroAttention.second))
    val stopBrush get() = Brush.linearGradient(listOf(HeroStopped.first, HeroStopped.second))
}

/** Hero card gradients (start to end): near-black washed with the state's colour. */
val HeroIdle = Color(0xFF16161A) to Color(0xFF0E0E11)
val HeroActive = Color(0xFF2E150B) to Color(0xFF140B08)
val HeroAttention = Color(0xFF2C2109) to Color(0xFF14100A)
val HeroStopped = Color(0xFF301011) to Color(0xFF150A0B)

/** The brand ember, as on the website (launcher icon, intro, overlays). */
val Ember = Color(0xFFFF6A3D)
/** Light ink on dark: the mark, and filled buttons on the hero card. */
val Cream = Color(0xFFEDEDEF)

private val Terminal = Signal(
    dark = true,
    canvas = Color(0xFF09090B), surface = Color(0xFF111114), surface2 = Color(0xFF18181C),
    border = Color(0xFF222228), border2 = Color(0xFF2E2E36),
    text = Color(0xFFEDEDEF), text2 = Color(0xFF8E8E98), text3 = Color(0xFF5D5D66),
    accent = Ember, accent2 = Color(0xFF7DD3E8), mint = Color(0xFFA3D98B),
    success = Color(0xFF4ADE80), warning = Color(0xFFF5B544), danger = Color(0xFFFF5A5A),
    disabled = Color(0xFF3A3A42), ink = Ember, onInk = Color(0xFF0A0A0A),
    clay = Color(0xFF1C1310), sage = Color(0xFF111A14), butter = Color(0xFF1B170D), pool = Color(0xFF0F171A),
    onPastel = Color(0xFFEDEDEF),
)

val LocalSignal = staticCompositionLocalOf { Terminal }

/** Geist: the words. Bundled (res/font, SIL OFL 1.1), so no download and no network. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Geist Mono: labels, numbers, codes, addresses, keys. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

/** Headlines and big numbers. */
val Display = Geist

/** The small uppercase "machine voice" used for captions, tags, and tile labels. */
val MonoLabel = TextStyle(fontFamily = GeistMono, fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp)

private val LatchType = Typography(
    displayLarge = TextStyle(fontFamily = GeistMono, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = Geist, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.4).sp),
    displaySmall = TextStyle(fontFamily = Geist, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontFamily = Geist, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.55).sp),
    titleLarge = TextStyle(fontFamily = Geist, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp),
    titleMedium = TextStyle(fontFamily = Geist, fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.15).sp),
    titleSmall = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    labelMedium = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = MonoLabel,
)

/** Latch is dark only: one crafted palette, no light variant to drift out of date. */
@Composable
fun LatchTheme(content: @Composable () -> Unit) {
    val signal = Terminal
    val scheme = darkColorScheme(
        primary = signal.accent, onPrimary = signal.onInk, background = signal.canvas, surface = signal.surface,
        onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = signal.border2,
        outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
        secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
        surfaceContainerHighest = Color(0xFF202026), surfaceContainerLow = signal.surface, surfaceContainerLowest = signal.canvas,
        inverseSurface = signal.text, inverseOnSurface = signal.canvas,
    )
    CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, typography = LatchType, content = content)
    }
}
