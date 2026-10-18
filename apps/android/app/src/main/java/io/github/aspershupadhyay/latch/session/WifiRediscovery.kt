// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Finds the owner's Wi-Fi gateway again after the computer's address changed
 * (a router hands out a new one after a restart or a few days away), so the
 * owner never has to pair again or copy a new address.
 *
 * The phone tries the same port on every address of its own /24 on the
 * Wi-Fi, nearest to the old address first, and accepts only a TLS server
 * whose certificate key matches the pin it paired with. Nothing is sent to
 * any host: a TLS handshake that does not end in the pinned key is dropped.
 * Another device on the network cannot pass for the gateway without its
 * private key, which never leaves the owner's computer.
 */
object WifiRediscovery {
    private const val CONNECT_TIMEOUT_MS = 350
    private const val HANDSHAKE_TIMEOUT_MS = 1_500
    private const val PARALLEL = 32

    /** The addresses to try for [oldUrl] when this phone is at [phone], nearest to the old one first. */
    fun candidates(oldUrl: String, phone: Inet4Address): List<String> {
        val old = oldUrl.toHttpUrlOrNull()?.host
        val b = phone.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = b[3].toInt() and 255
        val oldLast = old?.takeIf { it.startsWith(prefix) }?.substringAfterLast('.')?.toIntOrNull()
        return (1..254).filter { it != self }
            .sortedBy { if (oldLast == null) it else kotlin.math.abs(it - oldLast) }
            .map { "$prefix$it" }
            .filter { it != old }
    }

    /**
     * The gateway's new address, as `https://host:port`, or null when it is
     * not on this Wi-Fi (switched off, or the phone is elsewhere).
     * [probe] answers whether a host serves the pinned key; it is replaceable for tests.
     */
    suspend fun find(
        oldUrl: String,
        pin: String,
        phone: Inet4Address,
        probe: suspend (host: String, port: Int, pin: String) -> Boolean = ::servesPin,
    ): String? {
        val url = oldUrl.toHttpUrlOrNull() ?: return null
        val port = url.port
        val gate = Semaphore(PARALLEL)
        return coroutineScope {
            // In batches, so the nearest addresses answer first and the search stops early.
            for (batch in candidates(oldUrl, phone).chunked(PARALLEL * 2)) {
                val hit = batch.map { host -> async { gate.withPermit { host.takeIf { probe(it, port, pin) } } } }.awaitAll().firstOrNull { it != null }
                if (hit != null) return@coroutineScope "https://$hit:$port"
            }
            null
        }
    }

    /** Whether [host]:[port] completes a TLS handshake with the certificate key [pin]. */
    suspend fun servesPin(host: String, port: Int, pin: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val trust = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
                    if (GatewayPins.pinOf(leaf) != pin) throw CertificateException("not the paired gateway")
                }
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("server only")
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = HANDSHAKE_TIMEOUT_MS
                (tls.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { it.startHandshake() }
            }
            true
        }.getOrDefault(false)
    }
}
