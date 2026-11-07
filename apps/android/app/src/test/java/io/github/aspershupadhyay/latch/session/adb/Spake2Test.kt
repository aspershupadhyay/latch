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
 * ×8 scalar cancellation, and the transcript ordering are internally correct.
 * [matchesBoringSslKnownAnswer] pins the bytes to an independent transcription
 * of BoringSSL's spake25519.cc, which is what a phone's adbd runs.
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

    /**
     * A known answer from a line-by-line transcription of BoringSSL's
     * spake25519.cc (unreduced ×8 ephemeral scalar, cofactor-cleared password
     * scalar). This client random is one where the old code (8·r reduced mod ℓ,
     * raw password scalar) derived a different key than adbd: M and N carry a
     * small-order component, so the old code failed most real pairings.
     */
    @Test fun matchesBoringSslKnownAnswer() {
        val password = "482913".toByteArray() + ByteArray(64) { it.toByte() }
        val client = Spake2(Spake2.Role.CLIENT, password, hex(CLIENT_RANDOM))
        val server = Spake2(Spake2.Role.SERVER, password, hex(SERVER_RANDOM))
        assertArrayEquals(hex(CLIENT_MSG), client.message)
        assertArrayEquals(hex(SERVER_MSG), server.message)
        assertArrayEquals(hex(KEY), client.processMessage(hex(SERVER_MSG)))
        assertArrayEquals(hex(KEY), server.processMessage(hex(CLIENT_MSG)))
    }

    @Test fun thePasswordScalarIsAMultipleOfEightAndTheSamePoint() {
        for (i in 0 until 16) {
            val reduced = Ed25519.scalarFromHash(ByteArray(64) { (it * 31 + i).toByte() })
            val cleared = Spake2.cofactorClearedPasswordScalar(reduced)
            assertEquals(0, cleared.mod(java.math.BigInteger.valueOf(8)).toInt())
            assertEquals(reduced, cleared.mod(Ed25519.L))
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val CLIENT_RANDOM = "564b926fa76685d12e06515188df271466a21dbbe9f18fe6a97680c3a720e4fcfd817f8a608a16faf31badc21cd32762ee3997e24aa80b069feff833ce1c5e52"
        const val SERVER_RANDOM = "b2deea05b96947a03abf20fb4faab5c88215b1fdd7098fb957549b4366bacbba5fbf3c543e392294e41a6e2caae57d456061e7e38e200751124578c70307425e"
        const val CLIENT_MSG = "c0fc06a0e43b69f65785926143b0e3499e53442bc06ca774815fdd9b20f3e61e"
        const val SERVER_MSG = "70db8e6291018f9516250f74c57c5c3556a1eb6690c4e1135ff8ca1eb7792c90"
        const val KEY = "51f1c014abd71da85c6994af78333ea886d99b7adfda2a919afd3b4520cc771883d71fb79a0d1ded2787f357b3923e6f3fc216ca236fe5803bea0fc2c445bcd9"
    }
}
