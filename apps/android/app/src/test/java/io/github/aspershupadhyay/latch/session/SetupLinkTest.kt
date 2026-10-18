// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-054: what the Wi-Fi gateway's QR code holds, and what the owner may type instead. */
class SetupLinkTest {
    private val pin = "A".repeat(43)

    @Test
    fun theQrCodeSplitsIntoAddressAndCode() {
        val setup = Pairing.splitSetup("https://192.168.1.5:8788#pin=$pin&code=k7qxm2pd")
        assertEquals("https://192.168.1.5:8788#pin=$pin", setup.address)
        assertEquals("K7QXM2PD", setup.code)
        // The address part is what pairing reads, pin included.
        assertEquals(pin, Pairing.parse(setup.address, allowCleartext = false)?.pin)
    }

    @Test
    fun anAddressWithoutACodeStaysWhole() {
        val address = "https://192.168.1.5:8788#pin=$pin"
        assertEquals(Pairing.Setup(address, null), Pairing.splitSetup("  $address "))
        // "&code=" outside the fragment is not a setup code.
        assertNull(Pairing.splitSetup("https://example.com/?a=1&code=ABCDEFGH").code)
        assertNull(Pairing.splitSetup("https://192.168.1.5:8788#pin=$pin&code=SHORT").code)
    }

    @Test
    fun codesAndOwnerKeysAreToldApart() {
        assertTrue(Pairing.isCode("K7QX-M2PD"))
        assertTrue(Pairing.isCode(" k7qx m2pd "))
        assertTrue(Pairing.isCode("K7QXM2PD"))
        assertFalse(Pairing.isCode("lgt_" + "0".repeat(43)))
        assertFalse(Pairing.isCode("K7QX-M2P"))
    }
}
