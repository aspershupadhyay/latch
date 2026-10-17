// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies the SPAKE2 exchange by running both roles (as adbd and Latch would)
 * and confirming they derive the same 64-byte key from the same password and
 * disagree on a mismatch. This proves the role symmetry, the M/N masking, the
 * ×8 scalar cancellation, and the transcript ordering are internally correct;
 * byte-level interop with a device's BoringSSL adbd is verified on the phone.
 */
class Spake2Test {
    @Test fun bothRolesAgreeOnTheKey() {
        val password = "123456-exported-keying-material".toByteArray()
        val client = Spake2(Spake2.Role.CLIENT, password)
        val server = Spake2(Spake2.Role.SERVER, password)

        val clientKey = client.processMessage(server.message)
        val serverKey = server.processMessage(client.message)

        assertEquals(64, clientKey.size)
        assertArrayEquals(clientKey, serverKey)
    }

    @Test fun messagesAreValidCurvePoints() {
        val s = Spake2(Spake2.Role.CLIENT, "pw".toByteArray())
        assertEquals(32, s.message.size)
        val point = Ed25519.decode(s.message)
        assertEquals(true, point?.isOnCurve())
    }

    @Test fun aDifferentPasswordDerivesADifferentKey() {
        val client = Spake2(Spake2.Role.CLIENT, "123456".toByteArray())
        val server = Spake2(Spake2.Role.SERVER, "654321".toByteArray())
        // The handshake still completes (points are valid) but the keys differ.
        val clientKey = client.processMessage(server.message)
        val serverKey = server.processMessage(client.message)
        assertFalse(clientKey.contentEquals(serverKey))
    }

    @Test fun anInvalidPeerPointIsRejected() {
        val s = Spake2(Spake2.Role.CLIENT, "pw".toByteArray())
        assertThrows(AdbProtocolException::class.java) { s.processMessage(ByteArray(32) { 0xff.toByte() }) }
    }

    @Test fun pairingKeyFlowsIntoTheCipher() {
        // The derived material drives the AES-GCM layer end to end.
        val password = "778899-ekm".toByteArray()
        val client = Spake2(Spake2.Role.CLIENT, password)
        val server = Spake2(Spake2.Role.SERVER, password)
        val clientCipher = PairingCipher(client.processMessage(server.message))
        val serverCipher = PairingCipher(server.processMessage(client.message))
        assertEquals("peer-info", String(serverCipher.decrypt(clientCipher.encrypt("peer-info".toByteArray()))))
    }
}
