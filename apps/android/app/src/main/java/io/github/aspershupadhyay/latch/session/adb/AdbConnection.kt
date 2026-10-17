// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One authenticated ADB session with a device's `adbd` (R27, ADR-046).
 *
 * This drives the real ADB handshake over a byte stream: the CNXN exchange,
 * the AUTH token/signature/public-key dance, and the per-command `shell,v2`
 * streams that carry separated stdout, stderr, and the true exit code — the
 * same results `adb shell` gives. The transport is injected as plain streams
 * so the handshake can be exercised against an in-process fake adbd; the
 * wireless path wraps the same streams in TLS after an STLS upgrade.
 *
 * Not thread-safe: one command at a time, which is all the bridge needs.
 */
internal class AdbConnection(
    rawIn: InputStream,
    private val output: OutputStream,
    private val key: AdbKey,
    private val features: String = "shell_v2,cmd",
    private val closer: java.io.Closeable? = null,
) : java.io.Closeable {
    private val input = BufferedInputStream(rawIn)

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { closer?.close() }
    }
    private var nextLocalId = 1

    /** The peer's connection banner (e.g. `device::...`), set once [connect] succeeds. */
    var peerBanner: String = ""
        private set

    private fun send(message: AdbMessage) {
        output.write(message.encode())
        output.flush()
    }

    /**
     * Performs the CNXN + AUTH handshake. The client's public key must already
     * be trusted by adbd (authorized over USB once, pre-seeded in `adb_keys`,
     * or exchanged during wireless pairing); otherwise adbd keeps asking and
     * this throws rather than hang.
     */
    fun connect() {
        send(AdbMessage.of(AdbProtocol.A_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.MAX_PAYLOAD, "host::features=$features\u0000"))
        var sentSignature = false
        var sentPublicKey = false
        while (true) {
            val message = AdbMessage.read(input)
            when {
                message.isCommand(AdbProtocol.A_CNXN) -> {
                    peerBanner = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
                    return
                }
                message.isCommand(AdbProtocol.A_AUTH) && message.arg0 == AdbProtocol.AUTH_TOKEN -> {
                    when {
                        !sentSignature -> {
                            send(AdbMessage.of(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, key.signToken(message.payload)))
                            sentSignature = true
                        }
                        !sentPublicKey -> {
                            // adbd did not know our key: offer it. On a device this
                            // raises the "Allow debugging" prompt; pre-seeded keys skip it.
                            send(AdbMessage.of(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, key.authPublicKey()))
                            sentPublicKey = true
                        }
                        else -> throw AdbProtocolException("adbd did not authorize this key (authorize the client on the device)")
                    }
                }
                message.isCommand(AdbProtocol.A_STLS) ->
                    throw AdbProtocolException("adbd requires TLS (wireless debugging); use a TLS transport")
                else -> throw AdbProtocolException("unexpected message during connect: ${message.command}")
            }
        }
    }

    /**
     * Runs one command with the `shell,v2` protocol and returns its exit code,
     * streaming stdout and stderr to the callbacks as they arrive. [stdin] is
     * piped to the command and its input is then closed, so filters like `cat`
     * terminate. `raw` mode means no pty, matching `adb exec-out`.
     */
    fun shell(
        command: String,
        stdin: ByteArray?,
        onStdout: (ByteArray) -> Unit,
        onStderr: (ByteArray) -> Unit,
    ): Int {
        val localId = nextLocalId++
        send(AdbMessage.of(AdbProtocol.A_OPEN, localId, 0, "shell,v2,raw:$command\u0000"))

        var remoteId = 0
        // Wait for the stream to open.
        while (true) {
            val message = AdbMessage.read(input)
            if (message.arg1 != localId) continue // not our stream
            when {
                message.isCommand(AdbProtocol.A_OKAY) -> { remoteId = message.arg0; break }
                message.isCommand(AdbProtocol.A_CLSE) ->
                    throw AdbProtocolException("adbd refused to open a shell stream")
                else -> throw AdbProtocolException("unexpected reply opening shell: ${message.command}")
            }
        }

        if (stdin != null) {
            writeShellPacket(localId, remoteId, ShellProtocol.ID_STDIN, stdin)
            writeShellPacket(localId, remoteId, ShellProtocol.ID_CLOSE_STDIN, ByteArray(0))
        }

        val parser = ShellProtocol(onStdout, onStderr)
        while (true) {
            val message = AdbMessage.read(input)
            if (message.arg1 != localId && message.arg0 != remoteId) continue
            when {
                message.isCommand(AdbProtocol.A_WRTE) -> {
                    send(AdbMessage.of(AdbProtocol.A_OKAY, localId, remoteId)) // ack so adbd keeps sending
                    parser.feed(message.payload)
                }
                message.isCommand(AdbProtocol.A_OKAY) -> Unit // flow-control ack for our writes
                message.isCommand(AdbProtocol.A_CLSE) -> {
                    send(AdbMessage.of(AdbProtocol.A_CLSE, localId, remoteId))
                    return parser.exitCode ?: 0
                }
                else -> throw AdbProtocolException("unexpected message in shell stream: ${message.command}")
            }
        }
    }

    /** Sends one shell-protocol packet inside a WRTE, waiting for adbd's flow-control OKAY. */
    private fun writeShellPacket(localId: Int, remoteId: Int, id: Byte, data: ByteArray) {
        val framed = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        framed.put(id); framed.putInt(data.size); framed.put(data)
        send(AdbMessage.of(AdbProtocol.A_WRTE, localId, remoteId, framed.array()))
        // adbd acks each WRTE with an OKAY before we may send the next.
        while (true) {
            val message = AdbMessage.read(input)
            if (message.isCommand(AdbProtocol.A_OKAY) && message.arg1 == localId) return
            if (message.isCommand(AdbProtocol.A_CLSE) && message.arg1 == localId) {
                throw AdbProtocolException("adbd closed the shell stream while sending stdin")
            }
        }
    }
}

/**
 * The ADB `shell,v2` sub-protocol carried inside WRTE payloads: a 1-byte id, a
 * 4-byte little-endian length, then that many bytes. We decode streamed stdout
 * and stderr and capture the real exit code, reassembling packets that span
 * WRTE boundaries.
 */
internal class ShellProtocol(
    private val onStdout: (ByteArray) -> Unit,
    private val onStderr: (ByteArray) -> Unit,
) {
    private var buffer = ByteArray(0)
    var exitCode: Int? = null
        private set

    fun feed(chunk: ByteArray) {
        buffer += chunk
        while (buffer.size >= 5) {
            val id = buffer[0]
            val length = ByteBuffer.wrap(buffer, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0) throw AdbProtocolException("bad shell packet length")
            if (buffer.size < 5 + length) break // wait for the rest of this packet
            val data = buffer.copyOfRange(5, 5 + length)
            buffer = buffer.copyOfRange(5 + length, buffer.size)
            when (id) {
                ID_STDOUT -> onStdout(data)
                ID_STDERR -> onStderr(data)
                ID_EXIT -> exitCode = if (data.isNotEmpty()) data[0].toInt() and 0xff else 0
                else -> Unit // window-size and other control packets: ignored
            }
        }
    }

    companion object {
        const val ID_STDIN: Byte = 0
        const val ID_STDOUT: Byte = 1
        const val ID_STDERR: Byte = 2
        const val ID_EXIT: Byte = 3
        const val ID_CLOSE_STDIN: Byte = 4
    }
}
