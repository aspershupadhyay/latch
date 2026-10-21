// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import io.github.aspershupadhyay.latch.protocol.CapabilityState
import io.github.aspershupadhyay.latch.protocol.DeviceDescriptor
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.MessageSigner
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.session.DeviceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.Signature
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's real mirror socket ([MirrorLink], message parsing, device-key
 * signing) against the gateway binaries on this machine (R30), with this test
 * playing the browser on `/v1/mirror/view`. Skipped when a gateway is not built.
 */
@RunWith(Parameterized::class)
class MirrorLinkGatewayTest(private val implementation: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun implementations() = listOf("rust", "vercel")

        private const val ADMIN = "admin-token-0123456789abcdef0123456789"
        private val repo = File("../../..").canonicalFile
        private const val KEY = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO-_"
    }

    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var base: String

    @After
    fun tearDown() {
        scope.cancel()
        process?.destroyForcibly()
    }

    private fun startGateway(wifiPort: Int? = null) {
        val port = ServerSocket(0).use { it.localPort }
        base = "http://127.0.0.1:$port"
        val builder = when (implementation) {
            "rust" -> {
                val bin = File(repo, "target/debug/latch-gateway")
                assumeTrue("build it with cargo build -p latch-gateway", bin.exists())
                ProcessBuilder(bin.path, "serve").apply {
                    environment()["LATCH_BIND"] = "127.0.0.1:$port"
                    environment()["LATCH_DATA_DIR"] = Files.createTempDirectory("latch").toString()
                    // R56: the Wi-Fi listener on the loopback brings the mirror's UDP socket with it.
                    wifiPort?.let { environment()["LATCH_WIFI_BIND"] = "127.0.0.1:$it" }
                }
            }
            else -> {
                val dir = File(repo, "servers/vercel")
                val tsx = File(dir, "node_modules/.bin/tsx")
                assumeTrue("run npm ci in servers/vercel", tsx.exists())
                ProcessBuilder(tsx.path, "src/local.ts").directory(dir).apply { environment()["PORT"] = port.toString() }
            }
        }
        builder.environment()["LATCH_ADMIN_TOKEN"] = ADMIN
        builder.redirectErrorStream(true).redirectOutput(File.createTempFile("latch-gateway", ".log"))
        process = builder.start()
        repeat(150) {
            if (runCatching { http.newCall(Request.Builder().url("$base/healthz").build()).execute().use { it.code == 200 } }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("$implementation gateway did not start")
    }

    private fun post(path: String, body: String, token: String? = null): String {
        val request = Request.Builder().url(base + path).post(body.toRequestBody(jsonType))
            .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return http.newCall(request).execute().use { it.body.string() }
    }

    private fun field(json: String, name: String) = Protocol.json.parseToJsonElement(json).jsonObject.getValue(name).jsonPrimitive.content

    private fun pair(): String {
        val code = field(post("/v1/admin/pairings", """{"name":"Mirror phone"}""", ADMIN), "code")
        return field(post("/v1/pair", """{"code":"$code","platform":"android","model":"JVM test"}"""), "token")
    }

    private fun signer(): MessageSigner {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return object : MessageSigner {
            override val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            override fun sign(data: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(data)
                sign()
            }
        }
    }

    /** What the phone side heard. */
    private class Heard : MirrorLink.Events {
        val ready = LinkedBlockingQueue<Pair<String?, List<String>>>()
        val messages = LinkedBlockingQueue<MirrorIn>()
        val refused = LinkedBlockingQueue<String>()
        override fun ready(code: String?, urls: List<String>) { ready.add(code to urls) }
        override fun message(message: MirrorIn) { messages.add(message) }
        override fun reconnecting() = Unit
        override fun refused(reason: String) { refused.add(reason) }
        fun next(): MirrorIn = messages.poll(5, TimeUnit.SECONDS) ?: error("no message for the phone")
    }

    /** The browser's side. */
    private class Browser : WebSocketListener() {
        val texts = LinkedBlockingQueue<String>()
        val frames = LinkedBlockingQueue<ByteArray>()
        val closed = LinkedBlockingQueue<Int>()
        override fun onMessage(webSocket: WebSocket, text: String) { texts.add(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.add(bytes.toByteArray()) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed.add(code) }
        fun text(kind: String): String {
            while (true) {
                val t = texts.poll(5, TimeUnit.SECONDS) ?: error("no $kind for the browser")
                if (t.contains("\"t\":\"$kind\"")) return t
            }
        }
    }

    private fun registerKey(token: String, signer: MessageSigner) {
        var connected = false
        val link = DeviceLink(
            http, base, token, scope,
            hello = {
                HelloMessage(
                    protocol = Protocol.VERSION,
                    device = DeviceDescriptor("android", "16", "JVM test", "0.1.0"),
                    capabilities = listOf(CapabilityState("device.info", "enabled")),
                    session = SessionInfo(System.currentTimeMillis() + 600_000, approveEveryAction = false, paused = false),
                    deviceTimeMs = System.currentTimeMillis(),
                )
            },
            onCommand = { Outgoing.error(it.id, ErrorCode.UNSUPPORTED_CAPABILITY, "unused") },
            events = object : DeviceLink.Events {
                override fun connected() { connected = true }
                override fun retrying(attempt: Int) = Unit
                override fun rejected(revoked: Boolean) = Unit
                override fun incompatible(gatewayVersion: String) = Unit
                override fun cancel(commandId: String) = Unit
            },
            signer = signer,
        )
        link.start()
        repeat(100) { if (connected) return else Thread.sleep(50) }
        error("the device link did not connect")
    }

    @Test
    fun thePhoneStreamsToTheBrowserAndTakesItsInput() {
        startGateway()
        val token = pair()
        val heard = Heard()
        if (implementation == "vercel") {
            // The serverless gateway cannot hold a stream; the phone says what is needed instead.
            val link = MirrorLink(http, base, token, KEY, null, heard)
            link.start()
            val reason = heard.refused.poll(10, TimeUnit.SECONDS) ?: error("no refusal")
            assertTrue(reason, reason.contains("self-hosted"))
            return
        }
        val key = signer()
        registerKey(token, key)

        // A start signed by any other key is refused.
        val impostor = Heard()
        MirrorLink(http, base, token, KEY, signer(), impostor).start()
        assertTrue(impostor.refused.poll(5, TimeUnit.SECONDS)!!.contains("signature"))

        val link = MirrorLink(http, base, token, KEY, key, heard)
        link.start()
        val (code, urls) = heard.ready.poll(5, TimeUnit.SECONDS) ?: error("not ready")
        assertEquals(6, code!!.length)
        assertEquals(listOf("$base/mirror"), urls)

        val browser = Browser()
        val ws = http.newWebSocket(Request.Builder().url("$base/v1/mirror/view").build(), browser)
        ws.send("""{"t":"join","code":"$code","codecs":["h264","jpeg"]}""")
        assertTrue(browser.text("joined").contains(KEY))
        var m = heard.next()
        if (m is MirrorIn.Code) m = heard.next()
        assertEquals(MirrorIn.Viewer(true, Codec.H264), m)

        // A frame reaches the browser byte for byte.
        val frame = MirrorMessages.frame(MirrorMessages.KIND_H264, true, 42, ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65, 7)))
        assertTrue(link.sendFrame(frame))
        assertArrayEquals(frame, browser.frames.poll(5, TimeUnit.SECONDS))

        // Input and pings come back to the phone, parsed.
        ws.send("""{"t":"touch","phase":"down","x":10,"y":20}""")
        ws.send("""{"t":"ping","at":12.5}""")
        assertEquals(MirrorIn.Touch(Phase.DOWN, 10, 20), heard.next())
        assertEquals(MirrorIn.Ping(12.5), heard.next())
        link.sendText(MirrorMessages.pong(12.5))
        assertTrue(browser.text("pong").contains("12.5"))

        // Stopping on the phone ends it in the browser at once.
        link.stop()
        assertEquals(4410, browser.closed.poll(5, TimeUnit.SECONDS))
    }

    /** R56: with the gateway's UDP socket up, video goes over UDP, in pieces, and arrives whole. */
    @Test
    fun framesGoOverUdpWhenTheGatewayHasIt() {
        assumeTrue("UDP is the self-hosted gateway's", implementation == "rust")
        startGateway(wifiPort = ServerSocket(0).use { it.localPort })
        val token = pair()
        val key = signer()
        registerKey(token, key)
        val heard = Heard()
        val link = MirrorLink(http, base, token, KEY, key, heard)
        link.start()
        val (code, _) = heard.ready.poll(5, TimeUnit.SECONDS) ?: error("not ready")
        val browser = Browser()
        val ws = http.newWebSocket(Request.Builder().url("$base/v1/mirror/view").build(), browser)
        ws.send("""{"t":"join","code":"$code","codecs":["h264","jpeg"]}""")
        browser.text("joined")
        repeat(100) { if (!link.onUdp) Thread.sleep(50) }
        assertTrue("the gateway answered the phone's UDP hello", link.onUdp)

        // A frame of several pieces reaches the browser byte for byte; sound too.
        val payload = ByteArray(5000) { (it % 251).toByte() }
        payload[0] = 0; payload[1] = 0; payload[2] = 0; payload[3] = 1; payload[4] = 0x65
        val frame = MirrorMessages.frame(MirrorMessages.KIND_H264, true, 42, ByteBuffer.wrap(payload))
        assertTrue(link.sendFrame(frame))
        assertArrayEquals(frame, browser.frames.poll(5, TimeUnit.SECONDS))
        val delta = MirrorMessages.frame(MirrorMessages.KIND_H264, false, 43, ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x41, 9)))
        assertTrue(link.sendFrame(delta))
        assertArrayEquals(delta, browser.frames.poll(5, TimeUnit.SECONDS))
        link.stop()
        assertEquals(4410, browser.closed.poll(5, TimeUnit.SECONDS))
    }
}
