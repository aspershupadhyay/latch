// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.policy

import io.github.aspershupadhyay.latch.protocol.Rect

/**
 * Reads the words drawn inside one region of the screen (ADR-032). Used only
 * to raise the scrutiny of a tap on a control that has no label: what it
 * reads never leaves the phone and is never logged or shown to the AI.
 * Null when nothing could be read (protected window, no text, a failure).
 */
fun interface LabelReader {
    suspend fun read(region: Rect): String?
}
