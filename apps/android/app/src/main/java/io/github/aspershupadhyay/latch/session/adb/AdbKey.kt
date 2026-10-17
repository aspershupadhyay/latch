// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * The RSA identity ADB uses to authenticate a client to `adbd` (R27, ADR-046).
 *
 * ADB does not use a standard public-key encoding: adbd expects the modulus in
 * a little-endian Montgomery form (the `ANDROID_PUBKEY` struct), and the AUTH
 * signature is PKCS#1 v1.5 over the SHA-1 DigestInfo prefix joined to the raw
 * 20-byte token. Both are reproduced here exactly from the platform source, so
 * our key is accepted by a real adbd and matches `adb keygen` byte for byte.
 *
 * The private key is kept in the app's private storage, as `adb` keeps its own
 * key in a file — a keystore RSA key cannot be used for ADB's raw signing mode.
 */
class AdbKey private constructor(private val keyPair: KeyPair) {
    private val publicKey = keyPair.public as RSAPublicKey

    /**
     * The AUTH RSAPUBLICKEY payload adbd stores to trust this client: the
     * base64 of the [androidPublicKey] struct, a space, a name, and a NUL.
     */
    fun authPublicKey(name: String = "latch@android"): ByteArray =
        (Base64.getEncoder().encodeToString(androidPublicKey()) + " " + name + "\u0000").toByteArray(Charsets.US_ASCII)

    /**
     * Signs a 20-byte AUTH token the way adbd verifies it: PKCS#1 v1.5 over
     * [SHA1_DIGEST_PREFIX]`|| token` (the token is treated as a precomputed
     * SHA-1 digest, matching `adb_auth_sign`).
     */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(SHA1_DIGEST_PREFIX + token)
    }

    /**
     * The `ANDROID_PUBKEY` struct (524 bytes): modulus word count, -1/n[0] mod
     * 2^32, the 2048-bit modulus and R² little-endian, then the exponent.
     */
    fun androidPublicKey(): ByteArray {
        val n = publicKey.modulus
        val b32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = (b32 - n.mod(b32).modInverse(b32)).toInt() // -1 / n[0] mod 2^32
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(n) // (2^2048)^2 mod n

        return ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MODULUS_BYTES / 4)
            putInt(n0inv)
            put(littleEndian(n))
            put(littleEndian(rr))
            putInt(publicKey.publicExponent.toInt())
        }.array()
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        // BigInteger is big-endian two's complement with a possible sign byte;
        // ADB wants an unsigned fixed-width little-endian field.
        val out = ByteArray(MODULUS_BYTES)
        val be = value.toByteArray()
        val len = minOf(be.size, MODULUS_BYTES)
        for (i in 0 until len) out[i] = be[be.size - 1 - i]
        return out
    }

    companion object {
        const val MODULUS_BITS = 2048
        private const val MODULUS_BYTES = MODULUS_BITS / 8
        private const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

        /** The ASN.1 DigestInfo prefix for a SHA-1 hash, prepended before signing (RFC 3447). */
        val SHA1_DIGEST_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** Loads the key stored at [file], generating and saving a new 2048-bit RSA key the first time. */
        fun loadOrCreate(file: File): AdbKey {
            if (file.exists()) {
                val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(file.readText().trim()))
                val factory = KeyFactory.getInstance("RSA")
                val priv = factory.generatePrivate(spec) as RSAPrivateKey
                val pub = factory.generatePublic(
                    RSAPublicKeySpec(priv.modulus, (priv as? java.security.interfaces.RSAPrivateCrtKey)?.publicExponent ?: BigInteger.valueOf(65537)),
                )
                return AdbKey(KeyPair(pub, priv))
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(MODULUS_BITS) }.generateKeyPair()
            file.parentFile?.mkdirs()
            file.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
            file.setReadable(false, false)
            file.setReadable(true, true)
            return AdbKey(pair)
        }

        /** Wraps an existing key pair (tests, and callers that manage storage themselves). */
        fun of(keyPair: KeyPair) = AdbKey(keyPair)
    }

    /** The Java public key, for a fake adbd in tests to verify our AUTH signature. */
    internal fun javaPublicKeyForTest() = publicKey
}
