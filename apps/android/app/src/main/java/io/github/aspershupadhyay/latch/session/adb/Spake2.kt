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
 * the same M and N points, the ×8 cofactor on the ephemeral scalar, and the
 * exact length-prefixed SHA-512 transcript (client's fields first, then
 * server's, then the DH point, then SHA-512(password)).
 *
 * The password here is the pairing code bytes followed by 64 bytes of the TLS
 * exported keying material (`PairingSession` supplies it). Only the pairing
 * client role is exercised by Latch; the server role is included so the whole
 * exchange can be tested end to end without a device.
 */
internal class Spake2(private val role: Role, password: ByteArray) {
    enum class Role { CLIENT, SERVER } // BoringSSL: client = alice, server = bob

    private val myName = if (role == Role.CLIENT) CLIENT_NAME else SERVER_NAME
    private val theirName = if (role == Role.CLIENT) SERVER_NAME else CLIENT_NAME

    // Ephemeral scalar: reduce 64 random bytes mod ℓ, then ×8 to clear the cofactor.
    private val privateScalar: BigInteger =
        Ed25519.scalarFromHash(ByteArray(64).also { SecureRandom().nextBytes(it) })
            .multiply(BigInteger.valueOf(8)).mod(Ed25519.L)

    // The full SHA-512(password) is the transcript's final field; its reduction mod ℓ is
    // the mask scalar. M and N are prime-order, so the cofactor hack is a no-op on the point.
    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val maskScalar: BigInteger = Ed25519.scalarFromHash(passwordHash)

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

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
