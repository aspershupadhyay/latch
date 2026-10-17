// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Builds [AdbShellExecutor]s that reach this phone's own `adbd` over TCP
 * (R27, ADR-046). The endpoint is `host:port` as Android's wireless debugging
 * (or `adb tcpip`) exposes it; the owner's authorization seeds our public key
 * into adbd's trust store, after which every command runs like direct ADB.
 *
 * The plaintext path here is what a classic `adb tcpip` adbd speaks and what
 * the CI emulator verifies. Android 11+ wireless debugging additionally
 * requires a TLS upgrade after pairing; that transport is layered on the same
 * [AdbConnection] and is validated on a real device.
 */
internal object SocketAdbTransport {
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** The RSA identity for ADB, persisted in the app's private storage like `adb`'s own key. */
    fun key(filesDir: File): AdbKey = AdbKey.loadOrCreate(File(filesDir, "adb/adbkey"))

    /**
     * Opens a fresh connection to [endpoint], authenticating with [key]. A
     * `tls://host:port` endpoint (Android wireless debugging) connects over TLS
     * with the client certificate; a bare `host:port` uses the plaintext path
     * (`adb tcpip`, and the CI emulator). Throws when [endpoint] is malformed.
     */
    fun connect(endpoint: String?, key: AdbKey): AdbConnection {
        val target = parse(endpoint)
            ?: throw AdbProtocolException("no wireless-debugging endpoint is set for this device")
        if (target.tls) {
            val tls = AdbTls.socket(key, target.host, target.port)
            return AdbConnection(tls.inputStream, tls.outputStream, key, closer = tls)
        }
        val socket = Socket()
        socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        return AdbConnection(socket.getInputStream(), socket.getOutputStream(), key, closer = socket)
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
