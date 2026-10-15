// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.aspershupadhyay.latch.protocol.MessageSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * This phone's device key for one pairing (protocol 1.12, ADR-037): a P-256
 * key made in the Android keystore, which signs but cannot be read out, so a
 * copied phone token alone cannot speak for this phone. Forgetting the
 * pairing deletes it.
 */
class KeystoreSigner private constructor(private val alias: String) : MessageSigner {
    private val store: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun entry(): KeyStore.PrivateKeyEntry {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
        return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    override val publicKey: String by lazy { Base64.getEncoder().encodeToString(entry().certificate.publicKey.encoded) }

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(entry().privateKey)
        update(data)
        sign()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"

        private fun alias(deviceId: String) = "latch-device-$deviceId"

        /** The key for this pairing, made on first use; null when the keystore cannot make one. */
        fun forPairing(deviceId: String): KeystoreSigner? = runCatching { KeystoreSigner(alias(deviceId)).also { it.publicKey } }.getOrNull()

        /** Deletes the key of a pairing that was forgotten. */
        fun delete(deviceId: String) {
            runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias(deviceId)) }
        }
    }
}
