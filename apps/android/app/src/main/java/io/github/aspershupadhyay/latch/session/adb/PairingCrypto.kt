// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The symmetric layer of ADB wireless-debugging pairing (R27, ADR-046),
 * matching `adb/pairing/aes_128_gcm.cpp` exactly: the SPAKE2 key material is
 * run through HKDF-SHA256 into a 16-byte AES key, and each message is sealed
 * with AES-128-GCM under a 12-byte nonce whose first bytes are a per-direction
 * sequence counter. Encrypt and decrypt keep independent counters, as adbd does.
 */
internal class PairingCipher(keyMaterial: ByteArray) {
    private val key = SecretKeySpec(hkdfSha256(keyMaterial, INFO, KEY_LEN), "AES")
    private var encSequence = 0L
    private var decSequence = 0L

    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = gcm(Cipher.ENCRYPT_MODE, nonce(encSequence), plaintext)
        encSequence++
        return out
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val out = gcm(Cipher.DECRYPT_MODE, nonce(decSequence), ciphertext)
        decSequence++
        return out
    }

    private fun gcm(mode: Int, nonce: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            doFinal(input)
        }

    /** 12-byte nonce: an 8-byte little-endian sequence counter, then zeros. */
    private fun nonce(sequence: Long): ByteArray =
        ByteArray(NONCE_LEN).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence) }

    companion object {
        private const val KEY_LEN = 16
        private const val NONCE_LEN = 12
        private const val TAG_BITS = 128
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
    }
}

/** HKDF-SHA256 (RFC 5869) with an empty salt, producing [length] bytes. */
internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    // Extract: PRK = HMAC(salt=0^HashLen, ikm).
    mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    // Expand.
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var filled = 0
    var counter = 1
    while (filled < length) {
        mac.reset()
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        previous = mac.doFinal()
        val take = minOf(previous.size, length - filled)
        previous.copyInto(out, filled, 0, take)
        filled += take
        counter++
    }
    return out
}
