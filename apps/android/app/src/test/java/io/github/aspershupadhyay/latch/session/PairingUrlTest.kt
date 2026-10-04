// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUrlTest {
    @Test
    fun normalizesGatewayAddresses() {
        assertEquals("https://latch.example.com", Pairing.normalize(" latch.example.com/ ", allowCleartext = false))
        assertEquals("https://latch.example.com:8443", Pairing.normalize("https://latch.example.com:8443", allowCleartext = false))
        assertNull(Pairing.normalize("http://192.168.1.10:8787", allowCleartext = false))
        assertEquals("http://10.0.2.2:8787", Pairing.normalize("http://10.0.2.2:8787", allowCleartext = true))
        assertNull(Pairing.normalize("ftp://example.com", allowCleartext = true))
        assertNull(Pairing.normalize("", allowCleartext = true))
        assertNull(Pairing.normalize("has space.com", allowCleartext = true))
    }

}
