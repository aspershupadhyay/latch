// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64

/**
 * Something that signs with this phone's device key (protocol 1.12, ADR-037):
 * ECDSA P-256 / SHA-256, DER signatures. On the phone the key lives in the
 * Android keystore and never leaves it.
 */
interface MessageSigner {
    /** The public key as base64 SubjectPublicKeyInfo DER, as `hello.device_key` carries it. */
    val publicKey: String

    fun sign(data: ByteArray): ByteArray
}

/** The signed envelope every message from a 1.12 phone travels in; the same rules as `crates/protocol`. */
object Signing {
    const val CONTEXT = "latch-signed-v1"

    /** The bytes signed for [payload] on [connection] (empty for `hello`). */
    fun input(connection: String, payload: String): ByteArray = "$CONTEXT\n$connection\n$payload".toByteArray(Charsets.UTF_8)

    /** [payload] (a message's JSON text) wrapped in a signed envelope bound to [connection]. */
    fun seal(signer: MessageSigner, connection: String, payload: String): String {
        val sig = Base64.getEncoder().encodeToString(signer.sign(input(connection, payload)))
        return buildJsonObject {
            put("type", JsonPrimitive("signed"))
            put("payload", JsonPrimitive(payload))
            put("sig", JsonPrimitive(sig))
        }.toString()
    }
}
