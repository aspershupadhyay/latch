// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.files

import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Same-Wi-Fi transfers (protocol 1.14, ADR-041). For one transfer the phone
 * listens on its Wi-Fi address only, at a random port and a random 256-bit
 * path, until the transfer is done or [ttlMs] passes. The AI's computer
 * `PUT`s a file's ciphertext there (receive) or `GET`s it once (send); the
 * bytes are the same AES-256-CTR ciphertext as a cloud link's, so the network
 * sees nothing readable, and a received file is kept only if its SHA-256
 * matches (in [PhoneTransfers]). Wrong paths and methods get an error and
 * change nothing. The ciphertext waits in [dir] and is deleted after use or
 * expiry. Off unless the owner switched it on ([enabled]).
 */
class LocalSlots(
    /** Private folder for the ciphertext waiting in a slot. */
    private val dir: () -> File,
    /** The phone's Wi-Fi (or hotspot) IPv4 address, or null when there is none. */
    private val address: () -> InetAddress? = ::wifiAddress,
    private val enabled: () -> Boolean,
    private val ttlMs: Long = TTL_MS,
) {
    enum class Direction { RECEIVE, SEND }

    class Slot internal constructor(
        val url: String,
        val direction: Direction,
        val maxBytes: Long,
        val expiresAtMs: Long,
        internal val token: String,
        internal val file: File,
        internal val server: ServerSocket,
    ) {
        /** Receive: the computer's upload arrived. Send: the phone's ciphertext is ready. */
        @Volatile var filled = false
            internal set
        @Volatile var closed = false
            internal set
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val random = SecureRandom()

    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(code, message)

    /** Opens a slot; answers `unsupported_capability` when the switch is off or there is no Wi-Fi. */
    fun open(direction: Direction, maxBytes: Long): Slot {
        sweep()
        if (!enabled()) {
            fail(ErrorCode.UNSUPPORTED_CAPABILITY, "fast transfers on the same Wi-Fi are off; the owner can switch them on in Latch → Access → Files")
        }
        val ip = address() ?: fail(ErrorCode.UNSUPPORTED_CAPABILITY, "the phone is not on Wi-Fi")
        // A busy AI cannot leave many listeners open: the oldest goes first.
        while (slots.size >= MAX_OPEN) slots.values.minByOrNull { it.expiresAtMs }?.let(::close) ?: break
        val server = runCatching { ServerSocket(0, BACKLOG, ip) }.getOrElse {
            fail(ErrorCode.UNSUPPORTED_CAPABILITY, "the phone could not listen on its Wi-Fi")
        }
        server.soTimeout = ACCEPT_POLL_MS
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val folder = dir().also { it.mkdirs() }
        val slot = Slot(
            url = "http://${ip.hostAddress}:${server.localPort}/$token",
            direction = direction,
            maxBytes = maxBytes,
            expiresAtMs = System.currentTimeMillis() + ttlMs,
            token = token,
            file = File(folder, "local-$token.bin"),
            server = server,
        )
        slots[slot.url] = slot
        thread(isDaemon = true, name = "latch-local") { serve(slot) }
        return slot
    }

    /** The open slot at [url], if this phone opened one there. */
    fun find(url: String): Slot? {
        sweep()
        return slots[url]?.takeIf { !it.closed }
    }

    /** Receive: the ciphertext that arrived and its length; the caller [close]s the slot after reading. */
    fun received(slot: Slot): Pair<InputStream, Long> {
        if (slot.direction != Direction.RECEIVE || !slot.filled) {
            fail(ErrorCode.TARGET_NOT_FOUND, "nothing arrived at the phone's Wi-Fi address")
        }
        return slot.file.inputStream().buffered() to slot.file.length()
    }

    /** Send: where the phone writes the ciphertext; [ready] once it is complete. */
    fun output(slot: Slot): OutputStream {
        if (slot.direction != Direction.SEND || slot.filled) fail(ErrorCode.INVALID_REQUEST, "that Wi-Fi address does not take a file")
        return slot.file.outputStream().buffered()
    }

    fun ready(slot: Slot) {
        slot.filled = true
    }

    fun close(slot: Slot) {
        slot.closed = true
        runCatching { slot.server.close() }
        slots.remove(slot.url)
        slot.file.delete()
    }

    /** Session end or Stop: every listener closes and every waiting file is deleted. */
    fun closeAll() {
        slots.values.toList().forEach(::close)
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        slots.values.filter { it.expiresAtMs <= now }.forEach(::close)
    }

    /** Answers requests one at a time until one transfer is done or the slot expires. */
    private fun serve(slot: Slot) {
        try {
            while (!slot.closed && System.currentTimeMillis() < slot.expiresAtMs) {
                val socket = try {
                    slot.server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val done = socket.use { runCatching { handle(slot, it) }.getOrDefault(false) }
                if (done) {
                    // One transfer per address: stop listening, keep the file until it is used.
                    runCatching { slot.server.close() }
                    if (slot.direction == Direction.SEND) close(slot)
                    return
                }
            }
        } catch (_: Exception) {
            // The listener was closed (Stop, session end) or the network went away.
        }
        if (!slot.filled || slot.direction == Direction.SEND) close(slot) else runCatching { slot.server.close() }
    }

    /** One HTTP/1.1 request; true when it completed the slot's transfer. */
    private fun handle(slot: Slot, socket: Socket): Boolean {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream()
        fun respond(status: String, length: Long = 0) {
            out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        val head = readHead(input) ?: return false
        val requestLine = head.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            respond("400 Bad Request")
            return false
        }
        val (method, path) = requestLine[0] to requestLine[1]
        val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "").trim() }
        if (path != "/${slot.token}") {
            respond("404 Not Found")
            return false
        }
        return when {
            method == "PUT" && slot.direction == Direction.RECEIVE -> receive(slot, headers, input, out, ::respond)
            method == "GET" && slot.direction == Direction.SEND -> send(slot, out, ::respond)
            else -> {
                respond("405 Method Not Allowed")
                false
            }
        }
    }

    private fun receive(
        slot: Slot,
        headers: Map<String, String>,
        input: InputStream,
        out: OutputStream,
        respond: (String, Long) -> Unit,
    ): Boolean {
        if (slot.filled) {
            respond("409 Conflict", 0)
            return false
        }
        if (headers["transfer-encoding"] != null) {
            respond("411 Length Required", 0)
            return false
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length < 0) {
            respond("411 Length Required", 0)
            return false
        }
        if (length > slot.maxBytes) {
            respond("413 Content Too Large", 0)
            return false
        }
        val free = runCatching { slot.file.parentFile?.usableSpace }.getOrNull()
        if (free != null && length > free - FREE_SPACE_MARGIN_BYTES) {
            respond("507 Insufficient Storage", 0)
            return false
        }
        // curl asks before sending a large body.
        if (headers["expect"]?.lowercase() == "100-continue") {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        var copied = 0L
        slot.file.outputStream().use { file ->
            val buffer = ByteArray(BLOCK_BYTES)
            while (copied < length) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                if (n < 0) break
                file.write(buffer, 0, n)
                copied += n
            }
        }
        if (copied != length) {
            // A broken upload leaves nothing behind; the computer falls back to the cloud link.
            slot.file.delete()
            return false
        }
        slot.filled = true
        respond("201 Created", 0)
        return true
    }

    private fun send(slot: Slot, out: OutputStream, respond: (String, Long) -> Unit): Boolean {
        if (!slot.filled) {
            respond("404 Not Found", 0)
            return false
        }
        val length = slot.file.length()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        slot.file.inputStream().use { it.copyTo(out, BLOCK_BYTES) }
        out.flush()
        return true
    }

    /** The request line and header lines, or null for a request that is malformed or too large. */
    private fun readHead(input: InputStream): List<String>? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > MAX_HEAD_BYTES) return null
            when (b) {
                '\r'.code -> Unit
                '\n'.code -> {
                    if (line.isEmpty()) return lines.takeIf { it.isNotEmpty() }
                    lines += line.toString()
                    line.setLength(0)
                }
                else -> line.append(b.toChar())
            }
        }
    }

    companion object {
        /** How long a slot listens, like a cloud link. */
        const val TTL_MS = 15 * 60 * 1000L
        private const val MAX_OPEN = 4
        private const val BACKLOG = 4
        private const val ACCEPT_POLL_MS = 1_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val BLOCK_BYTES = 256 * 1024
        private const val FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024

        /**
         * The phone's IPv4 address on Wi-Fi, or on its own hotspot, which the
         * computer can reach: a private or link-local address on a wlan, ap,
         * or ethernet interface. Mobile data never qualifies.
         */
        fun wifiAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && WIFI_NAMES.any { prefix -> it.name.startsWith(prefix) } }
                .sortedBy { if (it.name == "wlan0") 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
        }.getOrNull()

        private val WIFI_NAMES = listOf("wlan", "swlan", "ap", "eth")
    }
}
