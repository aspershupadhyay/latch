// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * A gateway on the owner's own computer, reached over the home Wi-Fi
 * (roadmap R24, ADR-042), has a certificate it made itself. Its address
 * carries the SHA-256 of that certificate's public key (`#pin=…`), and for
 * that address (scheme, host, and port) Latch trusts exactly that key and
 * nothing else. Every other host keeps Android's normal certificate checks,
 * unchanged.
 */
object GatewayPins {
    private val pins = ConcurrentHashMap<String, String>()

    /** A pin is base64url (no padding) of 32 bytes. */
    fun isPin(pin: String): Boolean = PIN.matches(pin)

    /** Trust [url]'s host and port by [pin] from now on, or by the usual checks when [pin] is null. */
    fun set(url: String, pin: String?) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val key = "${parsed.host}:${parsed.port}"
        if (pin == null) pins.remove(key) else pins[key] = pin
    }

    internal fun forHost(host: String?, port: Int): String? = host?.let { pins["$it:$port"] }

    /** The pin of a certificate: base64url of the SHA-256 of its SubjectPublicKeyInfo. */
    fun pinOf(certificate: Certificate): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /** [builder] with TLS that honours the pins, and the platform's checks for everything else. */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val trust = PinningTrustManager(platform)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val usual = OkHttpClient().hostnameVerifier
        val verifier = HostnameVerifier { host, session ->
            val pin = forHost(host, session.peerPort)
            if (pin == null) {
                usual.verify(host, session)
            } else {
                runCatching { pinOf(session.peerCertificates.first()) == pin }.getOrDefault(false)
            }
        }
        return builder.sslSocketFactory(context.socketFactory, trust).hostnameVerifier(verifier)
    }

    /**
     * [builder] that trusts exactly one certificate key, [pin], for every host, and
     * leaves the shared pins alone. Find my computer (ADR-060) talks to a computer
     * it has only just met through this, so a stranger answering on the Wi-Fi can
     * never replace the pin of a gateway this phone already trusts.
     */
    fun pinnedTo(builder: OkHttpClient.Builder, pin: String): OkHttpClient.Builder {
        val trust = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
                if (pinOf(leaf) != pin) throw CertificateException("this is not the computer's certificate")
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                throw CertificateException("server certificates only")

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val verifier = HostnameVerifier { _, session ->
            runCatching { pinOf(session.peerCertificates.first()) == pin }.getOrDefault(false)
        }
        return builder.sslSocketFactory(context.socketFactory, trust).hostnameVerifier(verifier)
    }

    /** Checks the pin for pinned hosts; hands every other host to the platform. */
    private class PinningTrustManager(private val platform: X509TrustManager) : X509ExtendedTrustManager() {
        private val extended = platform as? X509ExtendedTrustManager

        private fun pinned(chain: Array<out X509Certificate>, pin: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
            if (pinOf(leaf) != pin) throw CertificateException("this is not the gateway's certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
            val session = (socket as? SSLSocket)?.handshakeSession
            val pin = forHost(session?.peerHost, session?.peerPort ?: -1)
            when {
                pin != null -> pinned(chain, pin)
                extended != null -> extended.checkServerTrusted(chain, authType, socket)
                else -> platform.checkServerTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
            val pin = forHost(engine?.peerHost, engine?.peerPort ?: -1)
            when {
                pin != null -> pinned(chain, pin)
                extended != null -> extended.checkServerTrusted(chain, authType, engine)
                else -> platform.checkServerTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = platform.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            platform.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            platform.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    private val PIN = Regex("^[A-Za-z0-9_-]{43}$")
}
