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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/**
 * ADR-041 on the phone: the real listener on 127.0.0.1, spoken to the way
 * `curl` does (raw HTTP/1.1, `Expect: 100-continue`), and the real transfer
 * code reading from and writing to it.
 */
class LocalSlotsTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val iv = "0f0e0d0c0b0a09080706050403020100"
    private val dir: File = Files.createTempDirectory("latch-local").toFile()
    private var on = true
    private val loopback = InetAddress.getByName("127.0.0.1")
    private var ttlMs = LocalSlots.TTL_MS
    private val slots by lazy { LocalSlots(dir = { dir }, address = { loopback }, enabled = { on }, ttlMs = ttlMs) }
    private val files = MemoryFiles()
    private val transfers by lazy { PhoneTransfers(files, OkHttpClient(), slots) { "https://latch-gateway.example" } }

    @After
    fun stop() {
        transfers.cancelAll()
        dir.deleteRecursively()
    }

    private fun seal(plain: ByteArray) = PhoneTransfers.cipherFor(FileLink("x", emptyMap(), key, iv)).doFinal(plain)

    private fun sha(bytes: ByteArray) = PhoneTransfers.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** One raw HTTP/1.1 request, like curl: status code and body, or null when nothing listens. */
    private fun request(
        url: String,
        method: String,
        body: ByteArray? = null,
        expectContinue: Boolean = false,
        path: String? = null,
        sendBody: Boolean = true,
    ): Pair<Int, ByteArray>? = try {
        exchange(URI(url), method, body, expectContinue, path, sendBody)
    } catch (_: java.io.IOException) {
        null
    }

    private fun exchange(uri: URI, method: String, body: ByteArray?, expectContinue: Boolean, path: String?, sendBody: Boolean): Pair<Int, ByteArray>? {
        val socket = Socket(uri.host, uri.port)
        socket.use {
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val head = StringBuilder("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n")
            if (body != null) head.append("Content-Length: ${body.size}\r\n")
            if (expectContinue) head.append("Expect: 100-continue\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            out.flush()
            val input = socket.getInputStream()
            if (expectContinue) {
                val interim = readHead(input)
                assertTrue(interim, interim.startsWith("HTTP/1.1 100"))
            }
            if (body != null && sendBody) {
                out.write(body)
                out.flush()
            }
            val response = readHead(input)
            if (response.isEmpty()) return null
            val status = response.split(' ')[1].toInt()
            val length = Regex("(?i)content-length: (\\d+)").find(response)?.groupValues?.get(1)?.toInt() ?: 0
            val data = input.readNBytes(length)
            return status to data
        }
    }

    private fun readHead(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            if (out.size() >= 4 && out.toByteArray().takeLast(4) == "\r\n\r\n".toByteArray().toList()) break
        }
        return out.toString(Charsets.US_ASCII)
    }

    private fun fetch(url: String, name: String, sha256: String) = Command.FetchFile(
        FileLocation.DOWNLOADS, null, "Latch", name, "video/mp4", false, FileLink(url, emptyMap(), key, iv), sha256, null,
    )

    @Test
    fun aFileSentToThePhonesAddressIsSavedOnceChecked() = runBlocking {
        val plain = ByteArray(2 * 1024 * 1024 + 5) { (it % 241).toByte() }
        val slot = transfers.openLocal(send = false, maxBytes = 10_000_000)
        assertTrue(slot.url, Regex("^http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{64}$").matches(slot.url))

        assertEquals("a wrong path changes nothing", 404, request(slot.url, "PUT", seal(plain), path = "/" + "0".repeat(64), sendBody = false)?.first)
        assertEquals(405, request(slot.url, "GET")?.first)
        assertEquals(201, request(slot.url, "PUT", seal(plain), expectContinue = true)?.first)
        Thread.sleep(200)
        assertNull("one transfer per address: the listener is closed", request(slot.url, "PUT", seal(plain)))

        val saved = transfers.fetch(fetch(slot.url, "clip.mp4", sha(plain)), quickMs = 10_000)
        assertEquals("done", saved.state)
        assertArrayEquals(plain, files.committed["clip.mp4"])
        assertTrue("the waiting ciphertext is deleted", dir.listFiles().orEmpty().isEmpty())
        assertNull(slots.find(slot.url))
    }

    @Test
    fun aChangedFileOnTheWiFiIsNeverKept() = runBlocking {
        val plain = "the real file".toByteArray()
        val slot = transfers.openLocal(send = false, maxBytes = 1_000)
        assertEquals(201, request(slot.url, "PUT", seal(plain).also { it[0] = (it[0].toInt() xor 1).toByte() })?.first)
        try {
            transfers.fetch(fetch(slot.url, "x.txt", sha(plain)), quickMs = 10_000)
            fail("a tampered file must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
        }
        assertTrue(files.committed.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun nothingArrivedMeansTheGatewaysCopy() = runBlocking {
        val slot = transfers.openLocal(send = false, maxBytes = 1_000)
        try {
            transfers.fetch(fetch(slot.url, "x.txt", sha(ByteArray(1))), quickMs = 10_000)
            fail("nothing arrived")
        } catch (e: ProtocolException) {
            assertEquals("the gateway retries with its cloud link on this code", ErrorCode.TARGET_NOT_FOUND, e.code)
        }
    }

    @Test
    fun aFileTooLargeForTheAddressIsRefused() {
        val slot = transfers.openLocal(send = false, maxBytes = 10)
        assertEquals(413, request(slot.url, "PUT", ByteArray(11), sendBody = false)?.first)
        assertEquals("still listening after a refusal", 201, request(slot.url, "PUT", ByteArray(10))?.first)
    }

    @Test
    fun aPhoneFileIsReadOnceFromItsAddress() = runBlocking {
        val plain = ByteArray(700_001) { (it * 3).toByte() }
        files.readable["f_1"] = plain
        val slot = transfers.openLocal(send = true, maxBytes = 10_000_000)
        assertEquals("not ready before the phone wrote it", 404, request(slot.url, "GET")?.first)
        val pushed = transfers.push(Command.PushFile("f_1", FileLink(slot.url, emptyMap(), key, iv), maxBytes = 10_000_000), quickMs = 10_000)
        assertEquals("done", pushed.state)
        assertEquals(sha(plain), pushed.sha256)
        assertEquals(405, request(slot.url, "PUT", ByteArray(1), sendBody = false)?.first)
        val (status, sealed) = request(slot.url, "GET")!!
        assertEquals(200, status)
        assertFalse("ciphertext on the Wi-Fi too", sealed.contentEquals(plain))
        assertArrayEquals(plain, PhoneTransfers.cipherFor(FileLink("x", emptyMap(), key, iv)).doFinal(sealed))
        Thread.sleep(200)
        assertNull("one download per address", request(slot.url, "GET"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun offOrWithoutWiFiThePhoneSaysUnsupported() {
        on = false
        try {
            transfers.openLocal(send = false, maxBytes = 10)
            fail("off by default")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.UNSUPPORTED_CAPABILITY, e.code)
        }
        val noWifi = LocalSlots(dir = { dir }, address = { null }, enabled = { true })
        try {
            noWifi.open(LocalSlots.Direction.RECEIVE, 10)
            fail("no Wi-Fi")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.UNSUPPORTED_CAPABILITY, e.code)
        }
    }

    @Test
    fun anAddressExpiresAndStopClosesEveryOne() {
        ttlMs = 1_500
        val slot = transfers.openLocal(send = false, maxBytes = 10)
        Thread.sleep(3_000)
        assertNull("expired", request(slot.url, "PUT", ByteArray(1)))
        assertNull(slots.find(slot.url))
        val other = transfers.openLocal(send = false, maxBytes = 10)
        transfers.cancelAll()
        Thread.sleep(200)
        assertNull("Stop closes it", request(other.url, "PUT", ByteArray(1)))
    }

    @Test
    fun theWiFiAddressIsNeverMobileData() {
        // Whatever this machine has, the answer is a private or link-local IPv4 address, or none.
        LocalSlots.wifiAddress()?.let { assertTrue(it.hostAddress, it.isSiteLocalAddress || it.isLinkLocalAddress) }
    }

    private class MemoryFiles : TransferFiles {
        class Target(val name: String, val bytes: ByteArrayOutputStream = ByteArrayOutputStream()) : TransferFiles.Target

        val committed = HashMap<String, ByteArray>()
        val readable = HashMap<String, ByteArray>()

        override fun begin(location: FileLocation, folderId: String?, subfolder: String?, name: String, mime: String?, overwrite: Boolean) =
            Target(name)

        override fun output(target: TransferFiles.Target): OutputStream = (target as Target).bytes

        override fun commit(target: TransferFiles.Target, size: Long): FileItem {
            val t = target as Target
            committed[t.name] = t.bytes.toByteArray()
            return FileItem("f_new", t.name, "video", "downloads", "video/mp4", size)
        }

        override fun abort(target: TransferFiles.Target) = Unit

        override fun openRead(id: String): Triple<FileItem, Long?, java.io.InputStream> {
            val bytes = readable.getValue(id)
            return Triple(FileItem(id, "$id.bin", "file", "folder", null, bytes.size.toLong()), bytes.size.toLong(), ByteArrayInputStream(bytes))
        }
    }
}
