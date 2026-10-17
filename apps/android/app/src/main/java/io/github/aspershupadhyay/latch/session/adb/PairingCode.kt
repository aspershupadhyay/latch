// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

/**
 * The six-digit wireless-debugging pairing code in what the owner typed, or
 * null. Android shows it as `123456`; people also type `123 456` or `123-456`.
 */
fun pairingCode(typed: CharSequence?): String? {
    val digits = typed?.filterNot { it == ' ' || it == '-' || it == ' ' }?.toString() ?: return null
    return digits.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
}
