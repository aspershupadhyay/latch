// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One ADB wireless-debugging pairing (R27, ADR-046), as the pairing client
 * against the phone's own adbd pairing server. It mirrors
 * `pairing_connection.cpp`:
 *
 * 1. TLS handshake, presenting our ADB key's certificate ([AdbTls]).
 * 2. The pairing password = the 6-digit code bytes + 64 bytes of this TLS
 *    session's exported keying material.
 * 3. Exchange one SPAKE2 message each; derive the shared key.
 * 4. Exchange AES-128-GCM-sealed PeerInfo blobs; ours carries our ADB public
 *    key, which adbd then trusts for the connect port.
 *
 * On success adbd has pinned our certificate and key, so [SocketAdbTransport]
 * can connect over TLS without any further prompt. Every byte here is built on
 * the parts verified off-device ([Spake2], [PairingCipher], [PairingPacket],
 * [Ed25519]); the live TLS/export/interop is verified on a real phone.
 */
internal object PairingSession {
    /**
     * Pairs with the adbd pairing server at [host]:[port] using [pairingCode].
     * Returns the peer's ADB public key on success. Blocking work runs on IO.
     */
    suspend fun pair(key: AdbKey, host: String, port: Int, pairingCode: String): ByteArray =
        withContext(Dispatchers.IO) {
            val socket = AdbTls.socket(key, host, port)
            try {
                val password = pairingCode.toByteArray(Charsets.UTF_8) + AdbTls.exportKeyingMaterial(socket)
                val input = socket.inputStream
                val output = socket.outputStream

                // 3. SPAKE2 message exchange.
                val spake2 = Spake2(Spake2.Role.CLIENT, password)
                PairingPacket.write(output, PairingPacket.TYPE_SPAKE2_MSG, spake2.message)
                val (msgType, peerMsg) = PairingPacket.read(input)
                if (msgType != PairingPacket.TYPE_SPAKE2_MSG) {
                    throw AdbProtocolException("expected a SPAKE2 message, got type $msgType")
                }
                val cipher = PairingCipher(spake2.processMessage(peerMsg))

                // 4. Encrypted PeerInfo exchange (our ADB public key for adbd to trust).
                PairingPacket.write(output, PairingPacket.TYPE_PEER_INFO, cipher.encrypt(PairingPacket.peerInfo(key.authPublicKey())))
                val (infoType, sealed) = PairingPacket.read(input)
                if (infoType != PairingPacket.TYPE_PEER_INFO) {
                    throw AdbProtocolException("expected peer info, got type $infoType")
                }
                PairingPacket.peerInfoPublicKey(cipher.decrypt(sealed))
            } finally {
                runCatching { socket.close() }
            }
        }
}
