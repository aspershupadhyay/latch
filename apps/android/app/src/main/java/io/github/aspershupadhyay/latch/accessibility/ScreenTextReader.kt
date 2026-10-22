// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.graphics.Bitmap
import io.github.aspershupadhyay.latch.protocol.Rect

/**
 * One line of words found in a picture of the screen, with where it was drawn
 * (screen pixels) and how sure the reader is, 0 to 1 (1 when it does not say).
 */
class TextLine(val text: String, val bounds: Rect, val confidence: Float = 1f)

/**
 * Reads the words drawn in a picture of the screen, on the phone (R67): text
 * in images, maps, and apps that draw their own screens (Flutter, games) has
 * no accessibility element, so the AI could not find or wait for it. The
 * picture and the words never leave the phone except as the observation the
 * AI already receives.
 */
fun interface ScreenTextReader {
    /** The lines in [bitmap], or none when reading fails or takes too long. */
    suspend fun lines(bitmap: Bitmap): List<TextLine>
}
