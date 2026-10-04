// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.files

import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.FileItem
import io.github.aspershupadhyay.latch.protocol.FileLink
import io.github.aspershupadhyay.latch.protocol.FileLocation
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * The phone's real transfer code (streaming, AES-256-CTR, SHA-256 check,
 * pending files) against a real local HTTP server, with files in memory.
 */
class PhoneTransfersTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val iv = "ffffffffffffffffffffffffffffffff"
    private lateinit var server: ServerSocket
    private val stored = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private lateinit var base: String
    private val files = MemoryFiles()
    private lateinit var transfers: PhoneTransfers

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(::serve) }
            }
        }
        base = "http://127.0.0.1:${server.localPort}"
        transfers = PhoneTransfers(files, OkHttpClient()) { base }
    }

    /** One HTTP/1.1 request per connection: PUT stores the body, GET returns it. */
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString(Charsets.US_ASCII)
        }
        val (method, path) = line().split(" ").let { it[0] to it[1].substringBefore('?') }
        var length = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.lowercase().startsWith("content-length:")) length = header.substringAfter(':').trim().toInt()
        }
        val out = socket.getOutputStream()
        fun respond(status: String, body: ByteArray) {
            out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        if (method == "PUT") {
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            stored[path] = body
            respond("200 OK", ByteArray(0))
        } else {
            val body = stored[path]
            if (body == null) respond("404 Not Found", ByteArray(0)) else respond("200 OK", body)
        }
    }

    @After
    fun stop() {
        transfers.cancelAll()
        server.close()
    }

    private fun link(path: String) = FileLink("$base$path", emptyMap(), key, iv)

    private fun seal(plain: ByteArray): ByteArray = PhoneTransfers.cipherFor(link("/")).doFinal(plain)

    private fun sha(bytes: ByteArray) = PhoneTransfers.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fetch(path: String, name: String, sha256: String, size: Long?) = Command.FetchFile(
        FileLocation.DOWNLOADS, null, "Latch", name, "video/mp4", false, link(path), sha256, size,
    )

    @Test
    fun keystreamMatchesOpenssl() {
        // head -c 40 /dev/zero | openssl enc -aes-256-ctr -K 0001..1f -iv ffff..ff (the counter wraps at 128 bits)
        val out = PhoneTransfers.cipherFor(link("/")).doFinal(ByteArray(40))
        assertEquals("e999e41d4ca770da5387117b5d8f57eef29000b62a499fd0a9f39a6add2e7780f05d76ae4ab99fe5", PhoneTransfers.hex(out))
    }

    @Test
    fun downloadsDecryptCheckAndSave() = runBlocking {
        val plain = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        stored["/v1/blobs/a"] = seal(plain)
        val result = transfers.fetch(fetch("/v1/blobs/a", "clip.mp4", sha(plain), plain.size.toLong()), quickMs = 10_000)
        assertEquals("done", result.state)
        assertEquals(plain.size.toLong(), result.doneBytes)
        assertEquals("clip.mp4", result.item?.name)
        assertArrayEquals("saved byte for byte, nothing re-encoded", plain, files.committed["clip.mp4"])
    }

    @Test
    fun aChangedFileIsNeverKept() = runBlocking {
        val plain = "the real file".toByteArray()
        stored["/v1/blobs/b"] = seal(plain).also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            transfers.fetch(fetch("/v1/blobs/b", "x.mp4", sha(plain), plain.size.toLong()), quickMs = 10_000)
            fail("a tampered file must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
            assertTrue(e.message!!.contains("sha256"))
        }
        assertTrue(files.committed.isEmpty())
        assertEquals(1, files.aborted)
    }

    @Test
    fun uploadsEncryptAndReportTheChecksum() = runBlocking {
        val plain = ByteArray(1_000_003) { (it * 7).toByte() }
        files.readable["f_1"] = plain
        val result = transfers.push(Command.PushFile("f_1", link("/v1/blobs/c"), maxBytes = 10_000_000), quickMs = 10_000)
        assertEquals("done", result.state)
        assertEquals(sha(plain), result.sha256)
        val sent = stored.getValue("/v1/blobs/c")
        assertFalse("only ciphertext leaves the phone", sent.contentEquals(plain))
        assertArrayEquals(plain, PhoneTransfers.cipherFor(link("/")).doFinal(sent))
    }

    @Test
    fun aFileOverTheLimitIsNotSent() = runBlocking {
        files.readable["f_2"] = ByteArray(100)
        try {
            transfers.push(Command.PushFile("f_2", link("/v1/blobs/d"), maxBytes = 99))
            fail("over the limit")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
        }
        assertFalse(stored.containsKey("/v1/blobs/d"))
    }

    @Test
    fun linksGoOnlyToTheGatewayAndBlobStorage() {
        val gw = "https://latch-gateway.vercel.app"
        assertTrue(PhoneTransfers.isAllowedLink("https://latch-gateway.vercel.app/v1/blobs/x", gw, upload = false))
        assertTrue(PhoneTransfers.isAllowedLink("https://abc.private.blob.vercel-storage.com/latch/x.bin?s=1", gw, upload = false))
        assertTrue(PhoneTransfers.isAllowedLink("https://vercel.com/api/blob/?pathname=latch%2Fx.bin", gw, upload = true))
        assertFalse(PhoneTransfers.isAllowedLink("https://vercel.com/api/blob/?pathname=x", gw, upload = false))
        assertFalse(PhoneTransfers.isAllowedLink("http://latch-gateway.vercel.app/v1/blobs/x", gw, upload = false))
        assertFalse(PhoneTransfers.isAllowedLink("https://evil.example/v1/blobs/x", gw, upload = false))
        assertFalse(PhoneTransfers.isAllowedLink("https://blob.vercel-storage.com.evil.example/x", gw, upload = false))
        assertFalse(PhoneTransfers.isAllowedLink("https://192.168.1.1/x", null, upload = false))
    }

    /** Files in memory: what is committed, and how many writes were thrown away. */
    private class MemoryFiles : TransferFiles {
        class Target(val name: String, val bytes: ByteArrayOutputStream = ByteArrayOutputStream()) : TransferFiles.Target

        val committed = HashMap<String, ByteArray>()
        val readable = HashMap<String, ByteArray>()
        var aborted = 0

        override fun begin(location: FileLocation, folderId: String?, subfolder: String?, name: String, mime: String?, overwrite: Boolean) =
            Target(name)

        override fun output(target: TransferFiles.Target): OutputStream = (target as Target).bytes

        override fun commit(target: TransferFiles.Target, size: Long): FileItem {
            val t = target as Target
            committed[t.name] = t.bytes.toByteArray()
            return FileItem("f_new", t.name, "video", "downloads", "video/mp4", size)
        }

        override fun abort(target: TransferFiles.Target) {
            aborted++
        }

        override fun openRead(id: String): Triple<FileItem, Long?, java.io.InputStream> {
            val bytes = readable.getValue(id)
            return Triple(FileItem(id, "$id.bin", "file", "folder", null, bytes.size.toLong()), bytes.size.toLong(), ByteArrayInputStream(bytes))
        }
    }
}
