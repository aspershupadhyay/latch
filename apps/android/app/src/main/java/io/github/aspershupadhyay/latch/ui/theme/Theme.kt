// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.R

/**
 * Latch tokens: "Midnight Ember" (owner 2026-10-21, after the redesign preview).
 * Two themes that follow the phone: Night (warm near-black) and Day (warm white,
 * which reads better outdoors and for older eyes). One family, Geist, for every
 * word; Geist Mono only for codes, keys, addresses, and numbers.
 * Every colour has one meaning:
 * - [ink] is the primary button (cream at night, near-black by day): calm, not a warning.
 * - [accent] (ember) is the brand and "selected": the tab, a switch, a link.
 * - [live] (teal) means a session is running: the AI can act now.
 * - [success] done, [warning] needs you, and [danger] is Stop and nothing else.
 * Ember and red never share a job, so "running" and "stopped" differ by hue
 * (teal against red) as well as by icon and words.
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
    /** A session is live; also files and the mirror. */
    val live: Color,
    val mint: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val disabled: Color,
    /** Filled primary buttons. */
    val ink: Color,
    val onInk: Color,
    /** Text on [accent], [danger], [success], and [warning] fills. */
    val onAccent: Color,
    /** A second, stronger hairline for edges that must read (inputs, buttons). */
    val border2: Color,
    /** Faint text and icons: hints, chevrons. At least 3:1 on every surface. */
    val text3: Color,
)

/** The brand ember, as on the website (launcher icon, intro, the logo). */
val Ember = Color(0xFFFF7A45)
/** Light ink on dark: the mark, and the main button at night. */
val Cream = Color(0xFFF6F2EC)
/** Stop, everywhere: the in-app button, the floating pill, the notification action. White on it is above 4.5:1. */
val StopRed = Color(0xFFD93A3F)
/** Text and glyphs on [StopRed]. */
val OnStop = Color(0xFFFFFFFF)

private val Night = Signal(
    dark = true,
    canvas = Color(0xFF0C0B0A), surface = Color(0xFF171513), surface2 = Color(0xFF221F1B),
    border = Color(0xFF2B2723), border2 = Color(0xFF3A342E),
    text = Cream, text2 = Color(0xFFB4ABA0), text3 = Color(0xFF8C8378),
    accent = Ember, live = Color(0xFF7FD6C8), mint = Color(0xFFA3D98B),
    success = Color(0xFF6FD39A), warning = Color(0xFFE8B04B), danger = Color(0xFFFF6B6B),
    disabled = Color(0xFF4A433C), ink = Cream, onInk = Color(0xFF14110F), onAccent = Color(0xFF14110F),
)

private val Day = Signal(
    dark = false,
    canvas = Color(0xFFF6F3EE), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEFEAE3),
    border = Color(0xFFE6E0D8), border2 = Color(0xFFD2CAC0),
    text = Color(0xFF17140F), text2 = Color(0xFF5E554B), text3 = Color(0xFF7A7065),
    accent = Color(0xFFC2410C), live = Color(0xFF0F766E), mint = Color(0xFF3F7F2A),
    success = Color(0xFF15803D), warning = Color(0xFFB45309), danger = Color(0xFFC62828),
    disabled = Color(0xFFB9B0A6), ink = Color(0xFF17140F), onInk = Color(0xFFFFFFFF), onAccent = Color(0xFFFFFFFF),
)

val LocalSignal = staticCompositionLocalOf { Night }

/** Geist: every word. Bundled (res/font, SIL OFL 1.1), so no download and no network. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Geist Mono: codes, keys, addresses, and numbers that line up. Never for words. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

/** Small labels: captions, tags, and tab names. Sentence case, 12 sp, never the smallest thing on screen to read. */
val SmallLabel = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)

private val LatchType = Typography(
    displayLarge = TextStyle(fontFamily = Geist, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.6).sp),
    displayMedium = TextStyle(fontFamily = Geist, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp),
    displaySmall = TextStyle(fontFamily = Geist, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp),
    headlineSmall = TextStyle(fontFamily = Geist, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Geist, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.15).sp),
    titleSmall = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    labelMedium = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = SmallLabel,
)

/** Follows the phone's light or dark setting; [dark] forces one (the camera screen is always dark). */
@Composable
fun LatchTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val signal = if (dark) Night else Day
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = signal.ink, onPrimary = signal.onInk, background = signal.canvas, surface = signal.surface,
        onBackground = signal.text, onSurface = signal.text, error = signal.danger, outline = signal.border2,
        outlineVariant = signal.border, surfaceVariant = signal.surface2, onSurfaceVariant = signal.text2,
        secondaryContainer = signal.surface2, surfaceContainer = signal.surface, surfaceContainerHigh = signal.surface2,
        surfaceContainerHighest = signal.surface2, surfaceContainerLow = signal.surface, surfaceContainerLowest = signal.canvas,
        inverseSurface = signal.text, inverseOnSurface = signal.canvas,
    )
    CompositionLocalProvider(LocalSignal provides signal) {
        MaterialTheme(colorScheme = scheme, typography = LatchType, content = content)
    }
}
