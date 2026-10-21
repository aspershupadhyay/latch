// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * R56 (ADR-061): the mirror's video and sound over UDP on the Wi-Fi, beside
 * its WebSocket. One lost Wi-Fi packet on TCP holds every later frame until
 * TCP sends it again (often 200 ms); over UDP the gateway asks for the missing
 * piece within milliseconds and skips a frame that does not come, so the
 * picture keeps moving. The wire format is the gateway's
 * (`servers/mcp/src/mirror_udp.rs`, docs/protocol/mirror.md); the shared
 * fixture `packages/schemas/v1/mirror/udp.json` holds both to the same bytes.
 */
object MirrorUdp {
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 17
    const val TAG_BYTES = 16
    const val MAX_FRAGMENT_BYTES = 1200
    const val FRAGMENT_HEADER_BYTES = 9

    const val DIR_PHONE: Byte = 1
    const val DIR_GATEWAY: Byte = 2

    const val T_HELLO: Byte = 1
    const val T_HELLO_ACK: Byte = 2
    const val T_FRAGMENT: Byte = 3
    const val T_NACK: Byte = 4
    const val T_REPORT: Byte = 5

    /** Frame kinds that go over UDP: video and sound. Stills and screenshots stay on the WebSocket. */
    val KINDS = setOf(MirrorMessages.KIND_H264, MirrorMessages.KIND_H265, MirrorMessages.KIND_PCM, MirrorMessages.KIND_OPUS)

    /** A fresh session id and key for one mirror connection, as the offer carries them. */
    fun newSession(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> =
        ByteArray(8).also(random::nextBytes) to ByteArray(32).also(random::nextBytes)

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** The offer on the authenticated WebSocket: `{"t":"udp","id":…,"key":…}`. */
    fun offer(id: ByteArray, key: ByteArray) = """{"t":"udp","id":"${hex(id)}","key":"${hex(key)}"}"""

    /**
     * Seals and opens packets of one session. Not thread-safe: each thread
     * that seals or opens has its own (the cipher keeps state between calls).
     */
    class Keys(key: ByteArray, private val id: ByteArray) {
        private val spec = SecretKeySpec(key.copyOf(), "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        init {
            require(key.size == 32 && id.size == 8) { "a 32-byte key and an 8-byte id" }
        }

        private fun nonce(direction: Byte, counter: Long) = ByteArray(12).also {
            it[0] = direction
            ByteBuffer.wrap(it, 4, 8).putLong(counter)
        }

        private fun header(counter: Long) = ByteBuffer.allocate(HEADER_BYTES).put(VERSION).put(id).putLong(counter).array()

        fun seal(direction: Byte, counter: Long, plaintext: ByteArray): ByteArray {
            val header = header(counter)
            cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BYTES * 8, nonce(direction, counter)))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        }

        /** The counter and plaintext of [direction]'s [packet] (its first [length] bytes), or null. */
        fun open(direction: Byte, packet: ByteArray, length: Int = packet.size): Pair<Long, ByteArray>? {
            if (length < HEADER_BYTES + TAG_BYTES || packet[0] != VERSION) return null
            for (i in 0 until 8) if (packet[1 + i] != id[i]) return null
            val counter = ByteBuffer.wrap(packet, 9, 8).long
            return try {
                cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BYTES * 8, nonce(direction, counter)))
                cipher.updateAAD(packet, 0, HEADER_BYTES)
                counter to cipher.doFinal(packet, HEADER_BYTES, length - HEADER_BYTES)
            } catch (e: java.security.GeneralSecurityException) {
                null
            }
        }
    }

    /** Takes each counter once, and none 64 or more behind the newest (as the gateway's). */
    class ReplayWindow {
        private var highest = -1L
        private var seen = 0L

        fun accept(counter: Long): Boolean {
            if (counter < 0) return false
            if (highest < 0 || counter > highest) {
                val shift = if (highest < 0) 64 else counter - highest
                seen = if (shift >= 64) 0 else seen shl shift.toInt()
                seen = seen or 1
                highest = counter
                return true
            }
            val back = highest - counter
            if (back >= 64 || seen and (1L shl back.toInt()) != 0L) return false
            seen = seen or (1L shl back.toInt())
            return true
        }
    }

    /** [frame] as fragment plaintexts of frame [seq]. */
    fun fragments(seq: Int, frame: ByteArray): List<ByteArray> {
        val count = maxOf(1, (frame.size + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES)
        require(count <= 0xFFFF) { "frame too large" }
        return List(count) { i ->
            val from = i * MAX_FRAGMENT_BYTES
            val to = minOf(frame.size, from + MAX_FRAGMENT_BYTES)
            ByteBuffer.allocate(FRAGMENT_HEADER_BYTES + to - from)
                .put(T_FRAGMENT).putInt(seq).putShort(i.toShort()).putShort(count.toShort())
                .put(frame, from, to - from).array()
        }
    }

    /** The gateway's request for pieces of a frame again: [indices], or all of it when empty. */
    data class Nack(val seq: Int, val indices: List<Int>)

    fun parseNack(plain: ByteArray): Nack? {
        if (plain.size < 7 || plain[0] != T_NACK) return null
        val b = ByteBuffer.wrap(plain, 1, plain.size - 1)
        val seq = b.int
        val n = b.short.toInt() and 0xFFFF
        if (b.remaining() != 2 * n) return null
        return Nack(seq, List(n) { b.short.toInt() and 0xFFFF })
    }

    /** What arrived at the gateway in its last report window. */
    data class Report(val received: Long, val lost: Long, val skipped: Long) {
        /** Losing 2 % or more, or skipping frames: back the bitrate off. */
        val lossy: Boolean get() = skipped > 0 || (lost > 0 && lost * 50 >= received + lost)
    }

    fun parseReport(plain: ByteArray): Report? {
        if (plain.size != 13 || plain[0] != T_REPORT) return null
        val b = ByteBuffer.wrap(plain, 1, 12)
        return Report(b.int.toLong() and 0xFFFFFFFFL, b.int.toLong() and 0xFFFFFFFFL, b.int.toLong() and 0xFFFFFFFFL)
    }

    /**
     * The last frames' fragments, to send again when the gateway asks; the
     * oldest go first past [maxFrames] or [maxBytes].
     */
    class ResendRing(private val maxFrames: Int = 240, private val maxBytes: Long = 6L * 1024 * 1024) {
        private val frames = LinkedHashMap<Int, List<ByteArray>>()
        private var bytes = 0L

        @Synchronized fun put(seq: Int, fragments: List<ByteArray>) {
            frames.remove(seq)?.let { old -> bytes -= old.sumOf { it.size.toLong() } }
            frames[seq] = fragments
            bytes += fragments.sumOf { it.size.toLong() }
            val it = frames.entries.iterator()
            while ((frames.size > maxFrames || bytes > maxBytes) && it.hasNext()) {
                bytes -= it.next().value.sumOf { f -> f.size.toLong() }
                it.remove()
            }
        }

        /** The fragments [nack] asks for, if the frame is still kept. */
        @Synchronized fun get(nack: Nack): List<ByteArray> {
            val all = frames[nack.seq] ?: return emptyList()
            return if (nack.indices.isEmpty()) all else nack.indices.mapNotNull { all.getOrNull(it) }
        }
    }
}

/**
 * One mirror connection's UDP link to the gateway at [host]:[port]. It says
 * hello until the gateway answers, then [ready] turns true and the mirror
 * sends video and sound here; it sends pieces again when asked, hands the
 * gateway's loss reports to [onReport], and calls [onDown] when the gateway
 * has been silent too long (the mirror goes back to its WebSocket).
 */
class UdpMediaLink(
    host: String,
    port: Int,
    id: ByteArray,
    key: ByteArray,
    private val onReport: (MirrorUdp.Report) -> Unit,
    private val onDown: () -> Unit,
) {
    private val socket = DatagramSocket().apply {
        runCatching { trafficClass = LowLatencySockets.INTERACTIVE_TOS }
        runCatching { sendBufferSize = SEND_BUFFER_BYTES }
        soTimeout = RECEIVE_TIMEOUT_MS
        connect(InetSocketAddress(host, port))
    }
    private val sealer = MirrorUdp.Keys(key, id)
    private val opener = MirrorUdp.Keys(key, id)
    private val window = MirrorUdp.ReplayWindow()
    private val ring = MirrorUdp.ResendRing()
    private var counter = 0L
    private var seq = 0
    private val backlog = AtomicLong()
    @Volatile var ready = false
        private set
    @Volatile private var closed = false
    @Volatile private var heardMs = 0L
    @Volatile private var helloMs = 0L
    @Volatile private var startedMs = 0L

    // Display priority, as the mirror's other threads: a frame must not wait behind a busy app.
    private val sender = Executors.newSingleThreadScheduledExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            r.run()
        }, "latch-udp-send").apply { isDaemon = true }
    }
    private val receiver = Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        receive()
    }, "latch-udp-receive").apply { isDaemon = true }

    fun start() {
        startedMs = System.currentTimeMillis()
        receiver.start()
        sender.scheduleWithFixedDelay(::keepAlive, 0, KEEPALIVE_TICK_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Sends one frame unless [limitBytes] already wait to go out; false when it
     * was dropped (the encoder then backs off, as on the WebSocket).
     */
    fun send(frame: ByteArray, limitBytes: Long): Boolean {
        if (!ready || closed) return false
        if (backlog.get() > limitBytes) return false
        val pieces = synchronized(this) { MirrorUdp.fragments(seq++, frame) }
        val size = pieces.sumOf { it.size.toLong() }
        backlog.addAndGet(size)
        return try {
            sender.execute {
                try {
                    ring.put(ByteBuffer.wrap(pieces[0], 1, 4).int, pieces)
                    pieces.forEach(::sendPlain)
                } finally {
                    backlog.addAndGet(-size)
                }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            backlog.addAndGet(-size)
            false
        }
    }

    fun close() {
        closed = true
        ready = false
        sender.shutdownNow()
        socket.close()
    }

    /** On the sender thread only: the sealer and counter are its own. */
    private fun sendPlain(plain: ByteArray) {
        if (closed) return
        val packet = sealer.seal(MirrorUdp.DIR_PHONE, counter++, plain)
        runCatching { socket.send(DatagramPacket(packet, packet.size)) }
    }

    private fun keepAlive() {
        val now = System.currentTimeMillis()
        // Never answered, or silent since: the gateway cannot be reached over UDP; back to the WebSocket.
        if (now - maxOf(heardMs, startedMs) > SILENT_MS) {
            down()
            return
        }
        if (now - helloMs >= if (ready) KEEPALIVE_MS else HELLO_MS) {
            helloMs = now
            sendPlain(byteArrayOf(MirrorUdp.T_HELLO))
        }
    }

    private fun down() {
        if (closed) return
        close()
        onDown()
    }

    private fun receive() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (!closed) {
            try {
                packet.setData(buf)
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: java.io.IOException) {
                return
            }
            val (counter, plain) = opener.open(MirrorUdp.DIR_GATEWAY, buf, packet.length) ?: continue
            if (!window.accept(counter) || plain.isEmpty()) continue
            heardMs = System.currentTimeMillis()
            when (plain[0]) {
                MirrorUdp.T_HELLO_ACK -> ready = true
                MirrorUdp.T_NACK -> MirrorUdp.parseNack(plain)?.let { nack ->
                    val again = ring.get(nack)
                    if (again.isNotEmpty()) runCatching { sender.execute { again.forEach(::sendPlain) } }
                }
                MirrorUdp.T_REPORT -> MirrorUdp.parseReport(plain)?.let(onReport)
            }
        }
    }

    companion object {
        const val SEND_BUFFER_BYTES = 1 shl 20
        const val RECEIVE_TIMEOUT_MS = 500
        const val KEEPALIVE_TICK_MS = 100L
        const val HELLO_MS = 250L
        const val KEEPALIVE_MS = 1_000L
        /** No word from the gateway this long: back to the WebSocket. */
        const val SILENT_MS = 3_000L
    }
}
