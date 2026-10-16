// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.CapabilityState
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.DeviceDescriptor
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Roadmap R24 (ADR-042): the app's real client against the real Rust gateway
 * serving phones over TLS on the local network (`LATCH_WIFI_BIND`), with the
 * certificate trusted only by the pin in the address the gateway prints. The
 * AI side speaks MCP to the same gateway on its plain local address, as an AI
 * app on the same computer does.
 */
class WifiGatewayTest {
    private val admin = "admin-token-0123456789abcdef0123456789"
    private val repo = File("../../..").canonicalFile
    private val plain = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var local: String
    private lateinit var printed: String

    @After
    fun tearDown() {
        scope.cancel()
        process?.destroyForcibly()
    }

    private fun startGateway() {
        val bin = File(repo, "target/debug/latch-gateway")
        assumeTrue("build it with cargo build -p latch-gateway", bin.exists())
        val port = ServerSocket(0).use { it.localPort }
        val wifiPort = ServerSocket(0).use { it.localPort }
        local = "http://127.0.0.1:$port"
        val log = File.createTempFile("latch-gateway", ".log")
        process = ProcessBuilder(bin.path, "serve").apply {
            environment()["LATCH_BIND"] = "127.0.0.1:$port"
            environment()["LATCH_WIFI_BIND"] = "127.0.0.1:$wifiPort"
            environment()["LATCH_DATA_DIR"] = Files.createTempDirectory("latch").toString()
            environment()["LATCH_ADMIN_TOKEN"] = admin
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        repeat(150) {
            val line = log.readLines().firstOrNull { "#pin=" in it }
            if (line != null && runCatching { get("/healthz").first == 200 }.getOrDefault(false)) {
                // This machine's own Wi-Fi address may differ; the listener here is on 127.0.0.1.
                val pin = line.substringAfter("#pin=").trim()
                printed = "https://127.0.0.1:$wifiPort#pin=$pin"
                return
            }
            Thread.sleep(100)
        }
        error("gateway did not start: ${log.readText()}")
    }

    private fun get(path: String, token: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(local + path).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return plain.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun post(path: String, body: String, token: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(local + path).post(body.toRequestBody(jsonType))
            .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return plain.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun obj(text: String) = Protocol.json.parseToJsonElement(text).jsonObject

    private fun callTool(token: String, name: String, args: String = "{}"): Pair<Boolean, String> {
        val (status, body) = post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""", token)
        assertEquals(200, status)
        val result = obj(body).getValue("result").jsonObject
        val text = result.getValue("content").jsonArray.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
        return (result["isError"]?.jsonPrimitive?.content == "true") to text
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        repeat(200) {
            if (check()) return
            Thread.sleep(50)
        }
        error("timed out waiting for $what")
    }

    private val observation = Observation(
        observationId = "o_wifi",
        capturedAtMs = 0,
        `package` = "com.example.notes",
        screen = ScreenInfo(1080, 2400),
        nodes = listOf(UiNode(id = "n0", role = "Button", text = "New note", bounds = Rect(0, 100, 1080, 300), clickable = true)),
        redactedCount = 0,
    )

    private fun hello() = HelloMessage(
        protocol = Protocol.VERSION,
        device = DeviceDescriptor("android", "16", "JVM test", "0.1.0"),
        capabilities = listOf("device.info", "ui.observe", "file.read").map { CapabilityState(it, "enabled") },
        session = SessionInfo(System.currentTimeMillis() + 600_000, approveEveryAction = false, paused = false),
        deviceTimeMs = System.currentTimeMillis(),
    )

    @Test
    fun addressesWithAPinParseAndNeedHttps() {
        val pin = "A".repeat(43)
        assertEquals(Pairing.Address("https://192.168.1.5:8788", pin), Pairing.parse("  https://192.168.1.5:8788#pin=$pin ", allowCleartext = false))
        assertEquals(Pairing.Address("https://latch.example.com", null), Pairing.parse("latch.example.com", allowCleartext = false))
        assertNull("a pin is 43 base64url characters", Pairing.parse("https://192.168.1.5:8788#pin=short", allowCleartext = false))
        assertNull("only #pin= fragments", Pairing.parse("https://192.168.1.5:8788#x=$pin", allowCleartext = false))
        assertNull("a pin needs https", Pairing.parse("http://192.168.1.5:8788#pin=$pin", allowCleartext = true))
    }

    @Test
    fun aPhonePairsAndWorksOverTheWifiListenerByPinOnly() = runBlocking {
        startGateway()
        val address = Pairing.parse(printed, allowCleartext = false)!!
        assertNotNull(address.pin)
        val http = Pairing.client()

        // Without the pin the gateway's own certificate is refused, as any unknown certificate is.
        GatewayPins.set(address.url, null)
        try {
            GatewaySetup(http).pairAsOwner(address.url, admin, "Pixel", "Pixel")
            fail("an unpinned self-made certificate must be refused")
        } catch (_: SetupException) {
        }
        // A wrong pin is refused too.
        GatewayPins.set(address.url, "A".repeat(43))
        try {
            GatewaySetup(http).pairAsOwner(address.url, admin, "Pixel", "Pixel")
            fail("a wrong pin must be refused")
        } catch (_: SetupException) {
        }

        GatewayPins.set(address.url, address.pin)
        val paired = GatewaySetup(http).pairAsOwner(address.url, admin, "Pixel", "Pixel")
        assertEquals(address.url, paired.gatewayUrl)

        val received = CopyOnWriteArrayList<String>()
        val pushedTo = CopyOnWriteArrayList<String>()
        var connected = 0
        val link = DeviceLink(
            http = http,
            gatewayUrl = address.url,
            token = paired.deviceToken,
            scope = scope,
            hello = ::hello,
            onCommand = { envelope: CommandEnvelope ->
                received += envelope.command.name
                when (val c = envelope.command) {
                    is Command.Observe -> Outgoing.ok(envelope.id, Protocol.json.encodeToJsonElement(Observation.serializer(), observation))
                    is Command.PushFile -> {
                        pushedTo += c.link.url
                        Outgoing.error(envelope.id, ErrorCode.TARGET_NOT_FOUND, "no such file in this test")
                    }
                    else -> Outgoing.error(envelope.id, ErrorCode.UNSUPPORTED_CAPABILITY, "not in this test: ${c.name}")
                }
            },
            events = object : DeviceLink.Events {
                override fun connected() { connected++ }
                override fun retrying(attempt: Int) = Unit
                override fun rejected(revoked: Boolean) = Unit
                override fun incompatible(gatewayVersion: String) = error("incompatible $gatewayVersion")
                override fun cancel(commandId: String) = Unit
            },
        )
        link.start()
        waitUntil("the phone to connect over TLS") { connected > 0 }

        // The AI app on the same computer uses the plain local address.
        val mcp = obj(post("/v1/admin/clients", """{"name":"Local AI"}""", admin).second).getValue("token").jsonPrimitive.content
        val (isError, text) = callTool(mcp, "observe", """{"screenshot":false}""")
        assertTrue(text, !isError && "observation_id: o_wifi" in text)

        // File links for this phone point at the Wi-Fi address it connected through,
        // so they match its paired gateway (and pass the app's own link check).
        callTool(mcp, "get_file_link", """{"file_id":"f_1"}""")
        assertEquals(1, pushedTo.size)
        assertTrue(pushedTo.first(), pushedTo.first().startsWith(address.url + "/v1/blobs/ltr_"))
        assertTrue(received.toString(), received.containsAll(listOf("ui.observe", "file.local", "file.push")))
        link.stop(sayBye = true)
    }
}
