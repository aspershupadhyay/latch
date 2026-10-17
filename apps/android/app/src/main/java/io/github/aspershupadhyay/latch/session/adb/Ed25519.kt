// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The twisted Edwards group of Curve25519 (RFC 8032 / RFC 7748), implemented
 * with [BigInteger] for clarity and correctness rather than speed — a pairing
 * handshake runs these a few dozen times, so constant-time limb arithmetic is
 * unnecessary. This is the group BoringSSL's SPAKE2 (and so ADB wireless
 * pairing) is built on; [Spake2] uses the point operations here.
 *
 * Curve: −x² + y² = 1 + d·x²·y² over GF(2²⁵⁵ − 19). Points are affine (x, y)
 * and encode to 32 bytes as little-endian y with the low bit of x in bit 255,
 * exactly as RFC 8032 specifies.
 */
internal object Ed25519 {
    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** Group order of the base-point subgroup, ℓ = 2²⁵² + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))

    /** d = −121665/121666 mod p. */
    private val D: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** √(−1) mod p = 2^((p−1)/4), for point decompression. */
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** The standard base point B. */
    val B: Point = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
    )

    /** The neutral element (0, 1). */
    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    data class Point(val x: BigInteger, val y: BigInteger) {
        /** Twisted-Edwards addition with a = −1 (RFC 8032 §5.1.4). */
        operator fun plus(q: Point): Point {
            val x1x2 = x.multiply(q.x).mod(P)
            val y1y2 = y.multiply(q.y).mod(P)
            val dxy = D.multiply(x1x2).multiply(y1y2).mod(P) // d·x1·x2·y1·y2
            val denomX = BigInteger.ONE.add(dxy).mod(P)
            val denomY = BigInteger.ONE.subtract(dxy).mod(P)
            val nx = x.multiply(q.y).add(y.multiply(q.x)).multiply(denomX.modInverse(P)).mod(P)
            // y1·y2 − a·x1·x2 with a = −1 → y1·y2 + x1·x2
            val ny = y1y2.add(x1x2).multiply(denomY.modInverse(P)).mod(P)
            return Point(nx, ny)
        }

        fun scalarMul(scalar: BigInteger): Point {
            var result = IDENTITY
            var addend = this
            var k = scalar
            while (k.signum() > 0) {
                if (k.testBit(0)) result += addend
                addend += addend
                k = k.shiftRight(1)
            }
            return result
        }

        /** 32-byte encoding: little-endian y with (x mod 2) in the top bit. */
        fun encode(): ByteArray {
            val out = littleEndian32(y.mod(P))
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        fun isOnCurve(): Boolean {
            val xx = x.multiply(x).mod(P)
            val yy = y.multiply(y).mod(P)
            val lhs = yy.subtract(xx).mod(P) // −x² + y²
            val rhs = BigInteger.ONE.add(D.multiply(xx).multiply(yy)).mod(P)
            return lhs == rhs
        }
    }

    /** Decodes a 32-byte point, recovering x from y and the sign bit; null if invalid. */
    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val data = bytes.copyOf()
        val xBit = (data[31].toInt() and 0x80) != 0
        data[31] = (data[31].toInt() and 0x7f).toByte()
        val y = bigFromLittleEndian(data)
        if (y >= P) return null
        val yy = y.multiply(y).mod(P)
        val u = yy.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(yy).add(BigInteger.ONE).mod(P)
        var x = recoverX(u, v) ?: return null
        if (x.testBit(0) != xBit) x = P.subtract(x).mod(P)
        return Point(x, y).takeIf { it.isOnCurve() }
    }

    // x = sqrt(u/v) by the curve's rule (RFC 8032 §5.1.3): candidate = (u/v)^((p+3)/8).
    private fun recoverX(u: BigInteger, v: BigInteger): BigInteger? {
        val v3 = v.multiply(v).mod(P).multiply(v).mod(P)
        val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
        val exp = P.subtract(BigInteger.valueOf(5)).shiftRight(3) // (p−5)/8
        val candidate = u.multiply(v3).mod(P).multiply(u.multiply(v7).mod(P).modPow(exp, P)).mod(P)
        val vx2 = v.multiply(candidate).mod(P).multiply(candidate).mod(P)
        return when (vx2) {
            u.mod(P) -> candidate
            P.subtract(u).mod(P) -> candidate.multiply(SQRT_M1).mod(P)
            else -> null
        }
    }

    /** Reduces [bytes] (little-endian) mod ℓ to a scalar (RFC 8032). */
    fun scalarFromHash(bytes: ByteArray): BigInteger = bigFromLittleEndian(bytes).mod(L)

    /** The Ed25519 clamped scalar from a 32-byte seed's SHA-512 (for the keygen test vector). */
    fun clampedScalar(seed: ByteArray): BigInteger {
        val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        h[0] = (h[0].toInt() and 0xf8).toByte()
        h[31] = (h[31].toInt() and 0x7f or 0x40).toByte()
        return bigFromLittleEndian(h)
    }

    fun littleEndian32(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val be = value.toByteArray()
        for (i in be.indices) {
            val pos = be.size - 1 - i
            if (pos in 0..31) out[pos] = be[i]
        }
        return out
    }

    fun bigFromLittleEndian(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size + 1)
        for (i in bytes.indices) be[bytes.size - i] = bytes[i]
        return BigInteger(be)
    }
}
