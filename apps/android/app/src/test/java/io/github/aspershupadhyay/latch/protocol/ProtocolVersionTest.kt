// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app warns the owner when the gateway speaks an older minor version. */
class ProtocolVersionTest {
    @Test
    fun minorVersions() {
        assertEquals(Protocol.VERSION.substringAfter('.').toInt(), Protocol.MINOR)
        assertEquals(2, Protocol.minorOf("1.2"))
        assertEquals(17, Protocol.minorOf("1.17"))
        assertNull(Protocol.minorOf("2.0"))
        assertNull(Protocol.minorOf("1.x"))
        assertNull(Protocol.minorOf(""))
    }
}
