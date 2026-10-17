// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Proves the Edwards group against RFC 8032 §7.1: the base point, scalar
 * multiplication, encoding, and decompression. If these pass, the group the
 * SPAKE2 pairing relies on is correct.
 */
class Ed25519Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun basePointIsOnCurve() {
        assertTrue(Ed25519.B.isOnCurve())
    }

    @Test fun keygenMatchesRfc8032Vectors() {
        // public = encode(clamp(SHA-512(seed)[:32]) · B)
        for ((seed, pub) in VECTORS) {
            val scalar = Ed25519.clampedScalar(hex(seed))
            val point = Ed25519.B.scalarMul(scalar)
            assertEquals("vector $seed", pub, hex(point.encode()))
        }
    }

    @Test fun encodeThenDecodeRoundTrips() {
        val point = Ed25519.B.scalarMul(BigInteger.valueOf(123456789))
        val decoded = Ed25519.decode(point.encode())
        assertNotNull(decoded)
        assertEquals(point, decoded)
        assertTrue(decoded!!.isOnCurve())
    }

    @Test fun scalarMultiplicationIsDistributive() {
        // (a + b)·B == a·B + b·B
        val a = BigInteger.valueOf(7919)
        val b = BigInteger.valueOf(104729)
        assertEquals(Ed25519.B.scalarMul(a + b), Ed25519.B.scalarMul(a) + Ed25519.B.scalarMul(b))
    }

    companion object {
        private val VECTORS = listOf(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60" to
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb" to
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        )
    }
}
