// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Builds [AdbShellExecutor]s that reach this phone's own `adbd` over TCP
 * (R27, ADR-046). The endpoint is `host:port` as Android's wireless debugging
 * (or `adb tcpip`) exposes it; the owner's authorization seeds our public key
 * into adbd's trust store, after which every command runs like direct ADB.
 *
 * The plaintext path here is what a classic `adb tcpip` adbd speaks and what
 * the CI emulator verifies. Android 11+ wireless debugging upgrades the same
 * connection to TLS after adbd's `STLS` ([AdbConnection], [AdbTls.upgrade]);
 * that live path is validated on a real device.
 */
internal object SocketAdbTransport {
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** The RSA identity for ADB, persisted in the app's private storage like `adb`'s own key. */
    fun key(filesDir: File): AdbKey = AdbKey.loadOrCreate(File(filesDir, "adb/adbkey"))

    /**
     * Opens a fresh connection to [endpoint], authenticating with [key]. Both
     * forms start as a plain TCP connection, as adbd expects: a `tls://host:port`
     * endpoint (Android wireless debugging) then switches to TLS with our client
     * certificate when adbd sends `STLS`; a bare `host:port` (`adb tcpip`, the
     * CI emulator) stays plaintext. Throws when [endpoint] is malformed.
     */
    fun connect(endpoint: String?, key: AdbKey): AdbConnection {
        val target = parse(endpoint)
            ?: throw AdbProtocolException("no wireless-debugging endpoint is set for this device")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        } catch (e: java.io.IOException) {
            runCatching { socket.close() }
            throw e
        }
        // The handshake is bounded; the command itself may run as long as it runs (ADR-046).
        socket.soTimeout = AdbTls.HANDSHAKE_TIMEOUT_MS
        var tls: SSLSocket? = null
        val closer = Closeable {
            runCatching { tls?.close() }
            runCatching { socket.close() }
        }
        return AdbConnection(
            socket.getInputStream(), socket.getOutputStream(), key,
            closer = closer,
            onConnected = {
                socket.soTimeout = 0
                tls?.soTimeout = 0
            },
            startTls = if (!target.tls) null else {
                {
                    val upgraded = AdbTls.upgrade(key, socket, target.host, target.port)
                    tls = upgraded
                    upgraded.inputStream to upgraded.outputStream
                }
            },
        )
    }

    data class Endpoint(val host: String, val port: Int, val tls: Boolean)

    /** Parses `[tls://]host:port`, defaulting a bare host to adbd's classic 5555; null when invalid. */
    fun parse(endpoint: String?): Endpoint? {
        var value = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tls = value.startsWith("tls://")
        if (tls) value = value.removePrefix("tls://")
        val host = value.substringBeforeLast(':', value)
        val port = if (':' in value) value.substringAfterLast(':').toIntOrNull() ?: return null else 5555
        if (host.isEmpty() || port !in 1..65535) return null
        return Endpoint(host, port, tls)
    }
}
