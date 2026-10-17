// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import io.github.aspershupadhyay.latch.protocol.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.concurrent.thread

/**
 * Drives the real [AdbConnection] / [AdbShellExecutor] against an in-process
 * fake adbd that speaks the genuine ADB handshake and `shell,v2` protocol, so
 * the client's framing, AUTH signing, stream multiplexing, exit codes, stdin,
 * and output chunking are all exercised exactly as against a real device.
 */
class AdbConnectionTest {
    private val key = AdbKey.of(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())

    /** A minimal adbd: CNXN+AUTH (verifying the signature), then canned shell replies. */
    private fun fakeAdbd(serverIn: InputStream, serverOut: OutputStream, serverError: (Throwable) -> Unit) = thread {
        try {
            val token = ByteArray(20) { (it * 7).toByte() }
            fun send(m: AdbMessage) { serverOut.write(m.encode()); serverOut.flush() }

            AdbMessage.read(serverIn) // CNXN from client
            send(AdbMessage.of(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token))
            val sig = AdbMessage.read(serverIn)
            assertEquals(AdbProtocol.AUTH_SIGNATURE, sig.arg0)
            // Verify the client's signature exactly as adbd would.
            val verify = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.DECRYPT_MODE, key.javaPublicKeyForTest())
            }.doFinal(sig.payload)
            assertTrue(verify.contentEquals(AdbKey.SHA1_DIGEST_PREFIX + token))
            send(AdbMessage.of(AdbProtocol.A_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.MAX_PAYLOAD, "device::features=shell_v2,cmd\u0000"))

            while (true) {
                val message = try { AdbMessage.read(serverIn) } catch (_: Exception) { break }
                if (!message.isCommand(AdbProtocol.A_OPEN)) {
                    if (message.isCommand(AdbProtocol.A_CLSE)) break
                    continue
                }
                val client = message.arg0
                val remote = 100 + client
                val service = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
                val command = service.substringAfter("shell,v2,raw:")
                send(AdbMessage.of(AdbProtocol.A_OKAY, remote, client))
                serveCommand(command, client, remote, serverIn, ::send)
            }
        } catch (t: Throwable) {
            serverError(t)
        } finally {
            runCatching { serverOut.close() }
        }
    }

    /** Emulates a tiny shell: echo, cat (consumes stdin), nonexistent command, and a big producer. */
    private fun serveCommand(command: String, client: Int, remote: Int, serverIn: InputStream, send: (AdbMessage) -> Unit) {
        fun stdoutPacket(data: ByteArray) = shellPacket(ShellProtocol.ID_STDOUT, data)
        fun writeOut(data: ByteArray) {
            send(AdbMessage.of(AdbProtocol.A_WRTE, remote, client, stdoutPacket(data)))
            AdbMessage.read(serverIn) // client's flow-control OKAY
        }
        fun finish(code: Int) {
            send(AdbMessage.of(AdbProtocol.A_WRTE, remote, client, shellPacket(ShellProtocol.ID_EXIT, byteArrayOf(code.toByte()))))
            AdbMessage.read(serverIn) // OKAY
            send(AdbMessage.of(AdbProtocol.A_CLSE, remote, client))
        }
        when {
            command.startsWith("echo ") -> { writeOut((command.removePrefix("echo ") + "\n").toByteArray()); finish(0) }
            command == "cat" -> {
                val stdin = StringBuilder()
                while (true) {
                    val m = AdbMessage.read(serverIn)
                    if (m.isCommand(AdbProtocol.A_WRTE)) {
                        send(AdbMessage.of(AdbProtocol.A_OKAY, remote, client))
                        val id = m.payload[0]
                        val len = ByteBuffer.wrap(m.payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (id == ShellProtocol.ID_STDIN) stdin.append(String(m.payload, 5, len, Charsets.UTF_8))
                        if (id == ShellProtocol.ID_CLOSE_STDIN) break
                    }
                }
                writeOut(stdin.toString().toByteArray()); finish(0)
            }
            command == "big" -> { writeOut(ByteArray(1_000_000) { 'x'.code.toByte() }); finish(0) }
            else -> {
                send(AdbMessage.of(AdbProtocol.A_WRTE, remote, client, shellPacket(ShellProtocol.ID_STDERR, "not found\n".toByteArray())))
                AdbMessage.read(serverIn)
                finish(127)
            }
        }
    }

    private fun shellPacket(id: Byte, data: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(id).putInt(data.size).put(data).array()

    /** Wires a client executor to a fresh fake adbd over two pipes. */
    private fun executor(onError: (Throwable) -> Unit): AdbShellExecutor {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 4 * 1024 * 1024)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 4 * 1024 * 1024)
        fakeAdbd(serverIn, toClient, onError)
        return AdbShellExecutor { AdbConnection(clientIn, toServer, key) }
    }

    private fun adb(command: String? = null, stdin: String? = null, continueFrom: String? = null) =
        Command.AdbShell(commandLine = command, stdin = stdin, timeoutMs = 0, continueFrom = continueFrom)

    @Test fun runsACommandWithExitCodeLikeAdb() {
        var error: Throwable? = null
        val result = runBlocking { executor { error = it }.run(adb("echo hello world")) }
        error?.let { throw it }
        assertEquals("hello world\n", result.stdout)
        assertEquals(0, result.exitCode)
        assertNull(result.cursor)
    }

    @Test fun separatesStderrAndReturnsTheRealExitCode() {
        var error: Throwable? = null
        val result = runBlocking { executor { error = it }.run(adb("nope")) }
        error?.let { throw it }
        assertEquals("", result.stdout)
        assertEquals("not found\n", result.stderr)
        assertEquals(127, result.exitCode)
    }

    @Test fun pipesStdinThrough() {
        var error: Throwable? = null
        val result = runBlocking { executor { error = it }.run(adb("cat", stdin = "ping\n")) }
        error?.let { throw it }
        assertEquals("ping\n", result.stdout)
        assertEquals(0, result.exitCode)
    }

    @Test fun chunksLargeOutputAndResumesByCursor() {
        var error: Throwable? = null
        val exec = executor { error = it }
        val first = runBlocking { exec.run(adb("big")) }
        error?.let { throw it }
        assertEquals(AdbShellExecutor.CHUNK_BYTES, first.stdout.length)
        assertNull(first.exitCode)
        assertTrue(first.cursor != null)

        val total = StringBuilder(first.stdout)
        var cursor = first.cursor
        while (cursor != null) {
            val next = runBlocking { exec.run(adb(continueFrom = cursor)) }
            total.append(next.stdout)
            cursor = next.cursor
            if (cursor == null) assertEquals(0, next.exitCode)
        }
        assertEquals(1_000_000, total.length)
        assertTrue(total.all { it == 'x' })
    }
}
