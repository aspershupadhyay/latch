// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

/**
 * The six-digit wireless-debugging pairing code in what the owner typed, or
 * null. Android shows it as `123456`; people also type `123 456` or `123-456`,
 * or paste it with a stray newline or invisible character.
 */
fun pairingCode(typed: CharSequence?): String? {
    // A pasted code can carry a newline, tab, non-breaking or zero-width space, or a
    // dash variant; drop those. Anything else (letters, other scripts' digits) refuses.
    val digits = typed?.filterNot { it.isWhitespace() || Character.isSpaceChar(it) || it in SEPARATORS || Character.getType(it) == Character.FORMAT.toInt() }
        ?.toString() ?: return null
    return digits.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
}

private const val SEPARATORS = "-\u2010\u2011\u2012\u2013\u2014\u2212"
