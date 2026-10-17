// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {
    @Test fun acceptsTheSixDigitsHoweverTheyAreSpaced() {
        assertEquals("123456", pairingCode("123456"))
        assertEquals("123456", pairingCode(" 123 456 "))
        assertEquals("123456", pairingCode("123-456"))
        assertEquals("123456", pairingCode("123 456"))
    }

    @Test fun refusesAnythingElse() {
        assertNull(pairingCode(null))
        assertNull(pairingCode(""))
        assertNull(pairingCode("12345"))
        assertNull(pairingCode("1234567"))
        assertNull(pairingCode("12a456"))
        // Other scripts' digits are not what Android shows.
        assertNull(pairingCode("١٢٣٤٥٦"))
    }
}
