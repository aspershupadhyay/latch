// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

class AdbProtocolTest {
    @Test fun messagesRoundTripThroughTheWireFormat() {
        val message = AdbMessage.of(AdbProtocol.A_OPEN, 7, 0, "shell:echo hi\u0000")
        val back = AdbMessage.read(ByteArrayInputStream(message.encode()))
        assertEquals(message, back)
        assertEquals(AdbProtocol.A_OPEN, back.command)
        assertEquals(7, back.arg0)
        assertEquals("shell:echo hi\u0000", String(back.payload, Charsets.UTF_8))
    }

    @Test fun headerIsLittleEndianWithCheckAndMagic() {
        val encoded = AdbMessage.of(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(1, 2, 3)).encode()
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_WRTE, buffer.int)
        assertEquals(1, buffer.int)
        assertEquals(2, buffer.int)
        assertEquals(3, buffer.int) // data_length
        assertEquals(6, buffer.int) // checksum: 1 + 2 + 3
        assertEquals(AdbProtocol.A_WRTE.inv(), buffer.int) // magic
    }

    @Test fun checksumIsTheUnsignedByteSum() {
        assertEquals(0xff + 0xff, AdbMessage.checksum(byteArrayOf(-1, -1)))
    }

    @Test fun aCorruptMagicIsRejected() {
        val bytes = AdbMessage.of(AdbProtocol.A_CNXN, 0, 0).encode()
        bytes[20] = (bytes[20] + 1).toByte() // scribble on the magic word
        assertThrows(AdbProtocolException::class.java) { AdbMessage.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun aBadChecksumIsRejected() {
        val bytes = AdbMessage.of(AdbProtocol.A_WRTE, 0, 0, byteArrayOf(9)).encode()
        bytes[16] = (bytes[16] + 1).toByte() // corrupt the checksum word
        assertThrows(AdbProtocolException::class.java) { AdbMessage.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun anOverLongPayloadIsRejectedWithoutAllocating() {
        val header = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(AdbProtocol.A_WRTE)
        header.putInt(0); header.putInt(0)
        header.putInt(AdbProtocol.MAX_PAYLOAD + 1)
        header.putInt(0)
        header.putInt(AdbProtocol.A_WRTE.inv())
        assertThrows(AdbProtocolException::class.java) { AdbMessage.read(ByteArrayInputStream(header.array())) }
    }

    @Test fun shortStreamThrowsEof() {
        assertThrows(EOFException::class.java) { AdbMessage.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
    }

    // --- ADB key encoding, checked against a real `adb keygen` public key ---

    @Test fun androidPublicKeyMatchesRealAdbOutput() {
        // Public data only (an `adb keygen` adbkey.pub blob): decode it to its
        // modulus and exponent, rebuild the key, and re-encode. Reproducing the
        // exact blob proves our n0inv, R², word count, and little-endian layout
        // match the platform byte for byte.
        val bytes = Base64.getDecoder().decode(REAL_ADB_PUBKEY)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int) // modulus size in 32-bit words
        buffer.int // n0inv (recomputed by our encoder)
        val modulus = bigEndianFromLittle(bytes, offset = 8, length = 256)
        val exponent = BigInteger.valueOf(
            ByteBuffer.wrap(bytes, 8 + 256 + 256, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong(),
        )
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey

        val reencoded = AdbKey.of(KeyPair(pub, null)).androidPublicKey()
        assertEquals(REAL_ADB_PUBKEY, Base64.getEncoder().encodeToString(reencoded))
    }

    @Test fun signTokenProducesAVerifiablePkcs1Signature() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = AdbKey.of(pair)
        val token = ByteArray(20) { it.toByte() } // adbd sends a 20-byte token
        val signature = key.signToken(token)
        assertEquals(256, signature.size) // one RSA-2048 block

        // adbd verifies by RSA-decrypting the signature and checking the bytes.
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.public)
        assertArrayEquals(AdbKey.SHA1_DIGEST_PREFIX + token, cipher.doFinal(signature))
    }

    @Test fun authPublicKeyIsBase64StructThenNameThenNul() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = String(AdbKey.of(pair).authPublicKey("me@host"), Charsets.US_ASCII)
        assertTrue(payload.endsWith(" me@host\u0000"))
        assertTrue(payload.substringBefore(' ').isNotEmpty())
    }

    private fun bigEndianFromLittle(bytes: ByteArray, offset: Int, length: Int): BigInteger {
        val be = ByteArray(length + 1) // leading 0 keeps it positive
        for (i in 0 until length) be[length - i] = bytes[offset + i]
        return BigInteger(be)
    }

    companion object {
        // From `adb keygen` (platform-tools); public key material only.
        private const val REAL_ADB_PUBKEY =
            "QAAAAJ20nG1Ljgj63lKgbOWgqwWrn/JBcBRvTK3Bx6sODZnt6sLvlAU+bDe3El96EvmOeUTo62FNevhfEbUMCMcUceY9XWKM6e/q5MIM0EshMXNBiOlmpp4m7/uNHWJPwJdFPdR6RAispU8Sri/VTVKLC+ryE75+2PagmtxYxRB6EHeZHjSE52R+zqd4Y0uo0aRpWWuAJOz6PJH3l+IjZtpbjSDzk0AIDD085YFrR65Zfo2S3j719Ln4/HzYc93Xrd7z6ao9Zas2L7fXvGZ0y0lerslfQaf2jyGIlycCSAkFwWH4/OPJ7ffZDnTy/T2WWr5k4DgcgvngIxRfC9j/LFBTCzZH8+isOPqt6jGmklDtmBh5q2RIZ7+XNhBs3y/M7ts8cDhX6hFm5llOfaJtiOo5R5JIc9Vk+CE9+4xUK7DylDW6TXB+vVwlgwpRDPE9RlayXt9FWN+HCk1bKVFkNgvHaWpS97wVcJXFHl63r69/Xa2U9RiqCj+BdCJaWZVUzYRU6E1D7iI1hwsn0WRDC2cwG9s3uFVRxNU3CBrLUb3d1VTz1J1tUAGyfTCsLxnqqGwXDPnekSt6PYfZP+N4KNbY6FFjFfW6YbqC+bPj+op4duNyjJmfwqjSn3I6fXbgKGZxTQUDNGywbAxNkWMJCb7v9inubSvqfuiwH4+ZbqK5RX4I2Z9erAEAAQA="
    }
}
