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
     * Opens a fresh plaintext connection to [endpoint] (`host:port`),
     * authenticating with [key]. Throws when [endpoint] is absent or malformed;
     * callers gate on a configured endpoint before reaching here.
     */
    fun connect(endpoint: String?, key: AdbKey): AdbConnection {
        val (host, port) = parse(endpoint)
            ?: throw AdbProtocolException("no wireless-debugging endpoint is set for this device")
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return AdbConnection(socket.getInputStream(), socket.getOutputStream(), key, closer = socket)
    }

    /** Parses `host:port`, defaulting a bare host to adbd's classic 5555; null when invalid. */
    fun parse(endpoint: String?): Pair<String, Int>? {
        val value = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = value.substringBeforeLast(':', value)
        val port = if (':' in value) value.substringAfterLast(':').toIntOrNull() ?: return null else 5555
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }
}
