// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ADB wireless-pairing packet framing (R27, ADR-046), matching
 * `pairing_connection.cpp`: a 6-byte packed header — version (1), type (1),
 * payload length (4, big-endian) — then the payload. Two packet types cross
 * the paired TLS channel: the SPAKE2 message, then the encrypted peer info.
 */
internal object PairingPacket {
    const val VERSION = 1 // kCurrentKeyHeaderVersion
    const val TYPE_SPAKE2_MSG = 0 // adb::proto::PairingPacket::SPAKE2_MSG
    const val TYPE_PEER_INFO = 1 // adb::proto::PairingPacket::PEER_INFO

    /** Total size of a PeerInfo blob (`kMaxPeerInfoSize`), zero-padded, exchanged whole. */
    const val PEER_INFO_SIZE = 8192
    const val PEER_INFO_TYPE_RSA_PUBLIC_KEY = 0 // adb::proto::PeerInfo::ADB_RSA_PUB_KEY

    private const val HEADER_SIZE = 6
    private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2 // kMaxPayloadSize

    fun write(output: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(VERSION.toByte())
        header.put(type.toByte())
        header.putInt(payload.size)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    /** Reads one packet; returns its type and payload, validating the header. */
    fun read(input: InputStream): Pair<Int, ByteArray> {
        val header = input.readFully(HEADER_SIZE)
        val version = header[0].toInt() and 0xff
        val type = header[1].toInt() and 0xff
        val length = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).int
        if (version != VERSION) throw AdbProtocolException("unsupported pairing header version $version")
        if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) throw AdbProtocolException("unknown pairing packet type $type")
        if (length <= 0 || length > MAX_PAYLOAD) throw AdbProtocolException("pairing payload length out of range: $length")
        return type to input.readFully(length)
    }

    /**
     * A PeerInfo blob carrying this client's ADB public key: a type byte then
     * the key bytes, zero-padded to [PEER_INFO_SIZE] (adbd expects the whole
     * fixed-size struct).
     */
    fun peerInfo(authPublicKey: ByteArray): ByteArray {
        require(authPublicKey.size <= PEER_INFO_SIZE - 1) { "public key too large for PeerInfo" }
        val blob = ByteArray(PEER_INFO_SIZE)
        blob[0] = PEER_INFO_TYPE_RSA_PUBLIC_KEY.toByte()
        authPublicKey.copyInto(blob, 1)
        return blob
    }

    /** The adb public key carried in a received PeerInfo blob (type byte stripped, NUL-trimmed). */
    fun peerInfoPublicKey(blob: ByteArray): ByteArray {
        val end = blob.indexOf(0.toByte(), from = 1).let { if (it < 0) blob.size else it }
        return blob.copyOfRange(1, end)
    }

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }
}
