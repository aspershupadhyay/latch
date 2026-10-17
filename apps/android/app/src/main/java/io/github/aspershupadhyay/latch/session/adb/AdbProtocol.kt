// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Android Debug Bridge wire protocol (R27, ADR-046), implemented from the
 * platform source rather than a third-party library so every byte on the wire
 * is ours to read and reason about.
 *
 * A connection is a stream of 24-byte little-endian message headers, each
 * optionally followed by a payload:
 *
 * ```
 * struct amessage { u32 command, arg0, arg1, data_length, data_crc32, magic }
 * ```
 *
 * `data_crc32` is the ADB "checksum": the unsigned sum of the payload bytes,
 * not a real CRC. `magic` is `command xor 0xffffffff`. These are validated on
 * every inbound message so a corrupt or desynced stream fails loudly.
 */
internal object AdbProtocol {
    // Command codes (little-endian four-char codes), from system/core/adb.
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534c5453

    // AUTH subtypes (arg0 on an A_AUTH message).
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** STLS protocol version carried in arg0 of an A_STLS message. */
    const val STLS_VERSION = 1

    /** The connection version the host advertises (ADB "A_VERSION"). */
    const val CONNECT_VERSION = 0x0100_0001

    /**
     * Largest payload we accept or send. ADB negotiates this in CNXN; 1 MiB is
     * the modern adbd default. We never grow a buffer past it, so a hostile or
     * corrupt length cannot exhaust memory.
     */
    const val MAX_PAYLOAD = 1024 * 1024

    const val HEADER_SIZE = 24
}

/**
 * One ADB message: a command, its two arguments, and an optional payload.
 * Immutable; [encode] produces the exact bytes a peer expects, and
 * [AdbMessage.read] parses and validates one message from a stream.
 */
internal data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
) {
    /** The 24-byte header followed by the payload, ready to write to the socket. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payload.size)
        buffer.putInt(checksum(payload))
        buffer.putInt(command.inv())
        buffer.put(payload)
        return buffer.array()
    }

    /** True when this message is `command`; small readability helper. */
    fun isCommand(command: Int) = this.command == command

    // A value class would be cleaner, but arrays need custom equals/hashCode.
    override fun equals(other: Any?): Boolean =
        other is AdbMessage &&
            command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        ((command * 31 + arg0) * 31 + arg1) * 31 + payload.contentHashCode()

    companion object {
        private val EMPTY = ByteArray(0)

        fun of(command: Int, arg0: Int, arg1: Int, payload: ByteArray = EMPTY) =
            AdbMessage(command, arg0, arg1, payload)

        fun of(command: Int, arg0: Int, arg1: Int, payload: String) =
            AdbMessage(command, arg0, arg1, payload.toByteArray(Charsets.UTF_8))

        /** The ADB checksum of a payload: the unsigned sum of its bytes, mod 2^32. */
        fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) sum += b.toInt() and 0xff
            return sum
        }

        /**
         * Reads exactly one message from [input], blocking until it arrives.
         * Throws [AdbProtocolException] on a bad magic, checksum, or an
         * over-long payload, and [EOFException] when the peer closes.
         */
        fun read(input: InputStream): AdbMessage {
            val header = input.readFully(AdbProtocol.HEADER_SIZE)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            val crc = buffer.int
            val magic = buffer.int
            if (magic != command.inv()) {
                throw AdbProtocolException("bad message magic: desynced or corrupt stream")
            }
            if (length < 0 || length > AdbProtocol.MAX_PAYLOAD) {
                throw AdbProtocolException("message payload length out of range: $length")
            }
            val payload = if (length == 0) ByteArray(0) else input.readFully(length)
            if (checksum(payload) != crc) throw AdbProtocolException("bad payload checksum")
            return AdbMessage(command, arg0, arg1, payload)
        }
    }
}

/** A violation of the ADB wire protocol: a desynced stream, bad framing, or a refusal. */
internal class AdbProtocolException(message: String) : Exception(message)

/** Reads exactly [n] bytes or throws [EOFException]; [InputStream.read] may return short. */
internal fun InputStream.readFully(n: Int): ByteArray {
    val out = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(out, read, n - read)
        if (r < 0) throw EOFException("stream closed after $read of $n bytes")
        read += r
    }
    return out
}
