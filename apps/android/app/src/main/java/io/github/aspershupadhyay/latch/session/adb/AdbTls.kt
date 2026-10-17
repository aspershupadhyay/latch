// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS layer for ADB wireless debugging (R27, ADR-046): a TLS 1.3 socket
 * that presents our ADB key's self-signed certificate ([AdbKey.certificate])
 * as the client certificate. adbd authenticates us by that certificate — it is
 * pinned during pairing — so the client trusts any server certificate here;
 * the pairing handshake, not the PKI, establishes mutual trust.
 *
 * It also exports 64 bytes of keying material ("adb-label"), which the pairing
 * password binds to this exact TLS session. Android's TLS provider is
 * Conscrypt; its exporter is reached reflectively, as it has no public JCA API.
 */
internal object AdbTls {
    private const val HANDSHAKE_TIMEOUT_MS = 10_000

    /** Opens a TLS 1.3 socket to [host]:[port] presenting [key]'s client certificate. */
    fun socket(key: AdbKey, host: String, port: Int): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
        val socket = sslContext(key).socketFactory
            .createSocket(raw, host, port, true) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        socket.startHandshake()
        return socket
    }

    private fun sslContext(key: AdbKey): SSLContext {
        val keyManager = object : X509ExtendedKeyManager() {
            private val chain = arrayOf(key.certificate)
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = ALIAS
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?) = key.privateKey()
        }
        // adbd presents a self-signed certificate; trust is from pairing, not a CA.
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), null)
        }
    }

    /**
     * 64 bytes of RFC 5705 exported keying material under the "adb-label"
     * label, which the pairing password is bound to. Uses Conscrypt's exporter
     * reflectively; throws if unavailable so pairing fails loudly rather than
     * silently diverging from adbd.
     */
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray {
        // adbd exports under sizeof("adb-label") — the trailing NUL is part of the label.
        val label = String(Spake2.EXPORT_KEY_LABEL, Charsets.US_ASCII)
        val conscrypt = Class.forName("org.conscrypt.Conscrypt")
        val method = conscrypt.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType,
        )
        return method.invoke(null, socket, label, null, Spake2.EXPORT_KEY_LENGTH) as ByteArray
    }

    private const val ALIAS = "latch-adb"
}
