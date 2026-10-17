// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PairingCryptoTest {
    @Test fun hkdfMatchesRfc5869StyleOutput() {
        // Deterministic: the same ikm/info always yields the same 16-byte key.
        val a = hkdfSha256("key material".toByteArray(), "adb pairing_auth aes-128-gcm key".toByteArray(), 16)
        val b = hkdfSha256("key material".toByteArray(), "adb pairing_auth aes-128-gcm key".toByteArray(), 16)
        assertEquals(16, a.size)
        assertArrayEquals(a, b)
    }

    @Test fun twoPeersWithTheSameKeyMaterialTalk() {
        // The SPAKE2 shared secret feeds both sides; encrypt on one decrypts on the other,
        // and the per-direction sequence counters stay in step across several messages.
        val shared = ByteArray(64) { (it * 3 + 1).toByte() }
        val client = PairingCipher(shared)
        val server = PairingCipher(shared)
        for (text in listOf("first", "second message", "third")) {
            assertEquals(text, String(server.decrypt(client.encrypt(text.toByteArray()))))
        }
    }

    @Test fun aWrongKeyCannotDecrypt() {
        val good = ByteArray(64) { 1 }
        val bad = ByteArray(64) { 2 }
        val sealed = PairingCipher(good).encrypt("secret".toByteArray())
        assertThrows(Exception::class.java) { PairingCipher(bad).decrypt(sealed) }
    }

    @Test fun pairingPacketsRoundTrip() {
        val out = ByteArrayOutputStream()
        PairingPacket.write(out, PairingPacket.TYPE_SPAKE2_MSG, byteArrayOf(1, 2, 3, 4))
        val (type, payload) = PairingPacket.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(PairingPacket.TYPE_SPAKE2_MSG, type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), payload)
    }

    @Test fun badPairingHeaderIsRejected() {
        val bytes = byteArrayOf(9, 0, 0, 0, 0, 1, 7) // version 9 is unsupported
        assertThrows(AdbProtocolException::class.java) { PairingPacket.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun peerInfoCarriesTheKeyInAFixedSizeBlob() {
        val pubkey = "AAAA.... latch@android".toByteArray()
        val blob = PairingPacket.peerInfo(pubkey)
        assertEquals(PairingPacket.PEER_INFO_SIZE, blob.size)
        assertEquals(PairingPacket.PEER_INFO_TYPE_RSA_PUBLIC_KEY.toByte(), blob[0])
        assertArrayEquals(pubkey, PairingPacket.peerInfoPublicKey(blob))
    }
}
