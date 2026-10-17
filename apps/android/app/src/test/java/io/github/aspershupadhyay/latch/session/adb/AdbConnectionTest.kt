// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import io.github.aspershupadhyay.latch.protocol.AdbResult
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
            // 2-byte characters across chunk edges, and control bytes that JSON escapes to 6 bytes.
            command == "bigutf" -> { writeOut("é".repeat(500_000).toByteArray()); finish(0) }
            command == "nul" -> { writeOut(ByteArray(300_000)); finish(0) }
            command == "bigerr" -> {
                send(AdbMessage.of(AdbProtocol.A_WRTE, remote, client, shellPacket(ShellProtocol.ID_STDERR, ByteArray(1_000_000) { 'e'.code.toByte() })))
                AdbMessage.read(serverIn)
                finish(1)
            }
            // Every byte value, which UTF-8 text would mangle.
            command == "bin" -> { writeOut(ByteArray(256) { it.toByte() }); finish(0) }
            // Never finishes: only cancellation (which closes the connection) ends it.
            command == "hang" -> Unit
            else -> {
                send(AdbMessage.of(AdbProtocol.A_WRTE, remote, client, shellPacket(ShellProtocol.ID_STDERR, "not found\n".toByteArray())))
                AdbMessage.read(serverIn)
                finish(127)
            }
        }
    }

    private fun shellPacket(id: Byte, data: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(id).putInt(data.size).put(data).array()

    /** Wires a client executor whose every connection reaches a fresh fake adbd over two pipes. */
    private fun executor(onError: (Throwable) -> Unit): AdbShellExecutor = AdbShellExecutor {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 4 * 1024 * 1024)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 4 * 1024 * 1024)
        fakeAdbd(serverIn, toClient, onError)
        AdbConnection(clientIn, toServer, key)
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

    /** Reads a whole result through its cursors, checking every frame fits the gateway's frame budget. */
    private fun drain(exec: AdbShellExecutor, command: String): Pair<AdbResult, AdbResult> {
        var frame = runBlocking { exec.run(adb(command)) }
        val out = StringBuilder()
        val err = StringBuilder()
        while (true) {
            val encoded = Json.encodeToString(AdbResult.serializer(), frame.copy(cursor = null, exitCode = null))
            assertTrue("frame of ${encoded.toByteArray().size} bytes", encoded.toByteArray().size <= AdbShellExecutor.CHUNK_BYTES + 64)
            out.append(frame.stdout)
            err.append(frame.stderr)
            val cursor = frame.cursor ?: break
            assertNull(frame.exitCode)
            frame = runBlocking { exec.run(adb(continueFrom = cursor)) }
        }
        return AdbResult(out.toString(), err.toString(), frame.exitCode) to frame
    }

    @Test fun neverSplitsAMultibyteCharacterAcrossChunks() {
        var error: Throwable? = null
        val (all, _) = drain(executor { error = it }, "bigutf")
        error?.let { throw it }
        assertEquals("é".repeat(500_000), all.stdout)
        assertEquals(0, all.exitCode)
    }

    @Test fun sizesChunksByTheirJsonEncodingSoControlBytesFit() {
        var error: Throwable? = null
        val (all, _) = drain(executor { error = it }, "nul")
        error?.let { throw it }
        assertEquals(300_000, all.stdout.length)
        assertTrue(all.stdout.all { it == '\u0000' })
    }

    @Test fun chunksLargeStderrToo() {
        var error: Throwable? = null
        val (all, _) = drain(executor { error = it }, "bigerr")
        error?.let { throw it }
        assertEquals(1_000_000, all.stderr.length)
        assertEquals(1, all.exitCode)
    }

    @Test fun cancellingARunningCommandClosesTheConnectionAtOnce() {
        var error: Throwable? = null
        val exec = executor { error = it }
        runBlocking {
            val running = async { exec.run(adb("hang")) }
            delay(200)
            running.cancel()
            // Blocked socket IO ignores cancellation unless the connection is closed.
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
        }
        error?.let { throw it }
    }

    @Test fun aCommandDoesNotWaitBehindALongRunningOne() {
        var error: Throwable? = null
        val exec = executor { error = it }
        runBlocking {
            val long = async(kotlinx.coroutines.Dispatchers.Default) { exec.run(adb("hang")) }
            delay(200)
            // Each command has its own connection: this one finishes while "hang" still runs.
            val quick = withTimeout(5_000) { exec.run(adb("echo beside")) }
            assertEquals("beside\n", quick.stdout)
            assertTrue(long.isActive)
            long.cancel()
            withTimeout(5_000) { long.join() }
        }
        error?.let { throw it }
    }

    @Test fun anUnknownCursorIsAnInvalidRequest() {
        val exec = executor { }
        val e = runCatching { runBlocking { exec.run(adb(continueFrom = "adbc_404")) } }.exceptionOrNull()
        assertTrue(e is ProtocolException && e.code == ErrorCode.INVALID_REQUEST)
    }

    @Test fun abandonedResultsAreDroppedOldestFirst() {
        val exec = executor { }
        val cursors = (1..AdbShellExecutor.MAX_PENDING + 1).map { runBlocking { exec.run(adb("big")) }.cursor!! }
        val oldest = runCatching { runBlocking { exec.run(adb(continueFrom = cursors.first())) } }.exceptionOrNull()
        assertTrue(oldest is ProtocolException)
        assertEquals(0, runBlocking { exec.run(adb(continueFrom = cursors.last())) }.exitCode)
    }

    @Test fun base64KeepsBinaryOutputByteForByte() {
        var error: Throwable? = null
        val result = runBlocking { executor { error = it }.run(Command.AdbShell("bin", null, 0, null, base64 = true)) }
        error?.let { throw it }
        assertTrue(java.util.Base64.getDecoder().decode(result.stdout).contentEquals(ByteArray(256) { it.toByte() }))
        assertEquals(0, result.exitCode)
    }

    @Test fun keepsSurrogatePairsWhole() {
        val text = "a\uD83D\uDE00b" // a, one emoji (two UTF-16 units), b
        assertEquals(1, AdbShellExecutor.fitting(text, 0, 4))
        assertEquals(3, AdbShellExecutor.fitting(text, 0, 5))
        assertEquals(6, AdbShellExecutor.jsonSize(text, 0, text.length))
    }

    /** Wireless debugging: adbd answers CNXN with STLS, and the handshake continues over TLS. */
    @Test fun upgradesToTlsWhenAdbdAsks() {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 1 shl 16)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 1 shl 16)
        var upgraded = false
        var error: Throwable? = null
        val server = thread {
            try {
                fun send(m: AdbMessage) { toClient.write(m.encode()); toClient.flush() }
                assertTrue(AdbMessage.read(serverIn).isCommand(AdbProtocol.A_CNXN))
                send(AdbMessage.of(AdbProtocol.A_STLS, AdbProtocol.STLS_VERSION, 0))
                val reply = AdbMessage.read(serverIn)
                assertTrue(reply.isCommand(AdbProtocol.A_STLS))
                assertEquals(AdbProtocol.STLS_VERSION, reply.arg0)
                // Over "TLS" (the same pipes here) adbd trusts the paired certificate and connects.
                send(AdbMessage.of(AdbProtocol.A_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.MAX_PAYLOAD, "device::\u0000"))
            } catch (t: Throwable) { error = t }
        }
        val connection = AdbConnection(clientIn, toServer, key, startTls = { upgraded = true; clientIn to toServer })
        connection.connect()
        server.join(5_000)
        error?.let { throw it }
        assertTrue(upgraded)
        assertEquals("device::", connection.peerBanner)
    }

    @Test fun refusesStlsWithoutATlsTransport() {
        val toServer = PipedOutputStream()
        PipedInputStream(toServer, 1 shl 16)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 1 shl 16)
        toClient.write(AdbMessage.of(AdbProtocol.A_STLS, AdbProtocol.STLS_VERSION, 0).encode())
        val e = runCatching { AdbConnection(clientIn, toServer, key).connect() }.exceptionOrNull()
        assertTrue(e is AdbProtocolException)
    }
}
