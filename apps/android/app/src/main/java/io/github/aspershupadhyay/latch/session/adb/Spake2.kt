// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 as BoringSSL implements it (R27, ADR-046) — the password-authenticated
 * key exchange ADB wireless-debugging pairing runs. Transcribed byte-for-byte
 * from BoringSSL's `spake25519.cc` so it interoperates with a phone's adbd:
 * the same M and N points (which are not prime-order), the ×8 cofactor on the
 * ephemeral scalar, the cofactor-clearing password scalar, and the
 * exact length-prefixed SHA-512 transcript (client's fields first, then
 * server's, then the DH point, then SHA-512(password)).
 *
 * The password here is the pairing code bytes followed by 64 bytes of the TLS
 * exported keying material (`PairingSession` supplies it). Only the pairing
 * client role is exercised by Latch; the server role is included so the whole
 * exchange can be tested end to end without a device.
 */
internal class Spake2(
    private val role: Role,
    password: ByteArray,
    /** 64 random bytes for the ephemeral scalar; fixed only by tests. */
    random: ByteArray = ByteArray(64).also { SecureRandom().nextBytes(it) },
) {
    enum class Role { CLIENT, SERVER } // BoringSSL: client = alice, server = bob

    private val myName = if (role == Role.CLIENT) CLIENT_NAME else SERVER_NAME
    private val theirName = if (role == Role.CLIENT) SERVER_NAME else CLIENT_NAME

    // Ephemeral scalar exactly as BoringSSL: reduce 64 random bytes mod ℓ, then ×8
    // (left_shift_3) with NO further reduction. It must stay a multiple of 8: M and N
    // are not prime-order (each carries a small-order component), and only a
    // multiple-of-8 scalar cancels that component in the shared point. Reducing
    // 8·r mod ℓ again broke pairing with a real adbd most of the time.
    private val privateScalar: BigInteger =
        Ed25519.scalarFromHash(random).shiftLeft(3)

    // The full SHA-512(password) is the transcript's final field. The mask scalar is
    // its reduction mod ℓ plus the multiple of ℓ (0..7) that makes it ≡ 0 mod 8,
    // BoringSSL's "password scalar hack", which keeps the mask point in the
    // prime-order subgroup. adbd's message is built that way, so ours must be too.
    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val maskScalar: BigInteger = cofactorClearedPasswordScalar(Ed25519.scalarFromHash(passwordHash))

    /** This side's SPAKE2 message: P* = x·B + s·(M for client, N for server), 32 bytes. */
    val message: ByteArray = run {
        val p = Ed25519.B.scalarMul(privateScalar)
        val maskBase = if (role == Role.CLIENT) M else N
        (p + maskBase.scalarMul(maskScalar)).encode()
    }

    /**
     * Processes the peer's 32-byte message and returns 64 bytes of shared key
     * material (the SHA-512 transcript digest), from which [PairingCipher]
     * derives the AES key. Throws if the peer's point is invalid.
     */
    fun processMessage(theirMessage: ByteArray): ByteArray {
        val qstar = Ed25519.decode(theirMessage)
            ?: throw AdbProtocolException("peer SPAKE2 point was not on the curve")
        val peerMaskBase = if (role == Role.CLIENT) N else M
        val qext = qstar + peerMaskBase.scalarMul(maskScalar).negate() // Q* − s·(peer mask)
        val dh = qext.scalarMul(privateScalar).encode()

        val sha = MessageDigest.getInstance("SHA-512")
        if (role == Role.CLIENT) {
            prefixed(sha, myName); prefixed(sha, theirName); prefixed(sha, message); prefixed(sha, theirMessage)
        } else {
            prefixed(sha, theirName); prefixed(sha, myName); prefixed(sha, theirMessage); prefixed(sha, message)
        }
        prefixed(sha, dh)
        prefixed(sha, passwordHash)
        return sha.digest()
    }

    /** Hashes an 8-byte little-endian length then the data (BoringSSL's update_with_length_prefix). */
    private fun prefixed(sha: MessageDigest, data: ByteArray) {
        sha.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array())
        sha.update(data)
    }

    companion object {
        // Names include the trailing NUL (BoringSSL passes sizeof(...)).
        private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        // The fixed SPAKE2 points, exactly as encoded in BoringSSL's spake25519.cc.
        private val M = Ed25519.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        private val N = Ed25519.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        /** The TLS exported-keying-material label ADB appends the 64-byte export under. */
        val EXPORT_KEY_LABEL = "adb-label\u0000".toByteArray(Charsets.US_ASCII)
        const val EXPORT_KEY_LENGTH = 64

        /** BoringSSL's spake25519.cc: add ℓ, 2ℓ, 4ℓ as the low three bits require, so the result ≡ 0 mod 8. */
        internal fun cofactorClearedPasswordScalar(reduced: BigInteger): BigInteger {
            var scalar = reduced
            var order = Ed25519.L
            for (bit in 0..2) {
                if (scalar.testBit(bit)) scalar = scalar.add(order)
                order = order.shiftLeft(1)
            }
            return scalar
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
