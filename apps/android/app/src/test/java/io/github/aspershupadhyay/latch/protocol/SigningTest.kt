// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Protocol 1.12 (ADR-037): the phone signs the same bytes both gateways verify. */
class SigningTest {
    private fun shared(path: String) = javaClass.classLoader!!.getResource(path)!!.readText()

    private fun verify(key: String, connection: String, payload: String, sig: String): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(Signing.input(connection, payload))
            verify(Base64.getDecoder().decode(sig))
        }
    }.getOrDefault(false)

    @Test
    fun `the shared vector verifies with the app's signing input, and nothing else does`() {
        val v = Protocol.json.parseToJsonElement(shared("signing/vectors.json")).jsonObject
        val key = v.getValue("device_key").jsonPrimitive.content
        val valid = v.getValue("valid").jsonObject
        val conn = valid.getValue("connection").jsonPrimitive.content
        val payload = valid.getValue("payload").jsonPrimitive.content
        val sig = valid.getValue("sig").jsonPrimitive.content
        assertTrue(verify(key, conn, payload, sig))
        assertFalse(verify(key, "k_8", payload, sig))
        assertFalse(verify(key, conn, payload.replace("c_1", "c_2"), sig))
    }

    @Test
    fun `a sealed message carries the exact payload and a signature over it`() {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = object : MessageSigner {
            override val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            override fun sign(data: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(data)
                sign()
            }
        }
        val payload = """{"type":"bye","reason":"user_stopped \"quoted\""}"""
        val sealed = Protocol.json.parseToJsonElement(Signing.seal(signer, "k_3", payload)).jsonObject
        assertEquals("signed", sealed.getValue("type").jsonPrimitive.content)
        assertEquals(payload, sealed.getValue("payload").jsonPrimitive.content)
        assertTrue(verify(signer.publicKey, "k_3", payload, sealed.getValue("sig").jsonPrimitive.content))
    }
}
