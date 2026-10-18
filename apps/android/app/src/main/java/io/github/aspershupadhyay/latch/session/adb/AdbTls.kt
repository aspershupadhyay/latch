// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
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
 * password binds to this exact TLS session ([exportKeyingMaterial]).
 */
internal object AdbTls {
    /** Bounds connecting, the TLS handshake, and pairing/auth reads; never a running command. */
    const val HANDSHAKE_TIMEOUT_MS = 10_000

    /**
     * Opens a TLS 1.3 socket to [host]:[port] presenting [key]'s client
     * certificate. Reads time out after [HANDSHAKE_TIMEOUT_MS] so a silent peer
     * cannot hang setup; a caller that streams a command sets `soTimeout = 0`
     * once authenticated.
     */
    fun socket(key: AdbKey, host: String, port: Int): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
        raw.soTimeout = HANDSHAKE_TIMEOUT_MS
        return upgrade(key, raw, host, port)
    }

    /**
     * Switches an open plain connection to TLS 1.3 in place, presenting [key]'s
     * client certificate: the pairing port does this at once, the connect port
     * after adbd's `STLS`. Closing the returned socket closes [raw] too.
     */
    fun upgrade(key: AdbKey, raw: Socket, host: String, port: Int): SSLSocket {
        val socket = sslContext(key).socketFactory
            .createSocket(raw, host, port, true) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        // Some TLS socket types keep their own read timeout: bound the handshake on both.
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
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
     * label, which the pairing password is bound to.
     *
     * Android's own TLS provider is a repackaged Conscrypt (`com.android.org.conscrypt`),
     * so `org.conscrypt.Conscrypt` does not exist on a phone: looking it up made
     * every real pairing fail with "org.conscrypt.Conscrypt". Android 12+ has the
     * public `android.net.ssl.SSLSockets.exportKeyingMaterial`; Android 11 has the
     * same method on its TLS socket, reached reflectively. The unbundled Conscrypt
     * class remains only for JVM tests. Throws a readable error if none works, so
     * pairing fails loudly rather than silently diverging from adbd.
     */
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray {
        // adbd exports under sizeof("adb-label") — the trailing NUL is part of the label.
        val label = String(Spake2.EXPORT_KEY_LABEL, Charsets.US_ASCII)
        val length = Spake2.EXPORT_KEY_LENGTH
        if (android.os.Build.VERSION.SDK_INT >= 31 && android.net.ssl.SSLSockets.isSupportedSocket(socket)) {
            return android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, null, length)
                ?: throw SSLException("the TLS session has no keying material to export")
        }
        val exported = runCatching {
            socket.javaClass.getMethod("exportKeyingMaterial", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                .invoke(socket, label, null, length) as? ByteArray
        }.getOrNull() ?: runCatching {
            Class.forName("org.conscrypt.Conscrypt")
                .getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                .invoke(null, socket, label, null, length) as? ByteArray
        }.getOrNull()
        return exported?.takeIf { it.size == length }
            ?: throw SSLException("this phone's TLS cannot export the keying material wireless-debugging pairing needs; pairing needs Android 12 or newer here")
    }

    private const val ALIAS = "latch-adb"
}
