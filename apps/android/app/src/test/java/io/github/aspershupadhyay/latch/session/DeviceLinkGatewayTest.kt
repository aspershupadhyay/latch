package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.ActionResult
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
import io.github.aspershupadhyay.latch.protocol.CapabilityState
import io.github.aspershupadhyay.latch.protocol.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Runs the app's real transport ([DeviceLink], protocol parsing, result
 * encoding) against both real gateway implementations on this machine:
 * the Rust binary and the Vercel gateway's local server. Skipped when a
 * gateway has not been built (`cargo build -p latch-gateway`,
 * `npm ci` in servers/vercel).
 */
@RunWith(Parameterized::class)
class DeviceLinkGatewayTest(private val implementation: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun implementations() = listOf("rust", "vercel")

        private const val ADMIN = "admin-token-0123456789abcdef0123456789"
        private val repo = File("../../..").canonicalFile
    }

    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private var process: Process? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var base: String

    @After
    fun tearDown() {
        scope.cancel()
        process?.destroyForcibly()
    }

    private fun startGateway() {
        val port = ServerSocket(0).use { it.localPort }
        base = "http://127.0.0.1:$port"
        val builder = when (implementation) {
            "rust" -> {
                val bin = File(repo, "target/debug/latch-gateway")
                assumeTrue("build it with cargo build -p latch-gateway", bin.exists())
                ProcessBuilder(bin.path, "serve").apply {
                    environment()["LATCH_BIND"] = "127.0.0.1:$port"
                    environment()["LATCH_DATA_DIR"] = Files.createTempDirectory("latch").toString()
                }
            }
            else -> {
                val dir = File(repo, "servers/vercel")
                val tsx = File(dir, "node_modules/.bin/tsx")
                assumeTrue("run npm ci in servers/vercel", tsx.exists())
                ProcessBuilder(tsx.path, "src/local.ts").directory(dir).apply {
                    environment()["PORT"] = port.toString()
                    environment()["LATCH_POLL_INTERVAL_MS"] = "50"
                    environment()["LATCH_RESULT_INTERVAL_MS"] = "50"
                    environment()["LATCH_SETTLE_MS"] = "0"
                }
            }
        }
        builder.environment()["LATCH_ADMIN_TOKEN"] = ADMIN
        builder.redirectErrorStream(true).redirectOutput(File.createTempFile("latch-gateway", ".log"))
        process = builder.start()
        repeat(150) {
            if (runCatching { get("/healthz").first == 200 }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("$implementation gateway did not start")
    }

    private fun get(path: String, token: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(base + path).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun post(path: String, body: String, token: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(base + path).post(body.toRequestBody(jsonType))
            .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun obj(text: String) = Protocol.json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun pair(): String {
        val code = obj(post("/v1/admin/pairings", """{"name":"JVM phone"}""", ADMIN).second).str("code")
        val (status, body) = post("/v1/pair", """{"code":"$code","platform":"android","model":"JVM test"}""")
        assertEquals(200, status)
        return obj(body).str("token")
    }

    private fun mcpToken() = obj(post("/v1/admin/clients", """{"name":"JVM"}""", ADMIN).second).str("token")

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
        observationId = "o_jvm",
        capturedAtMs = 0,
        `package` = "com.example.notes",
        screen = ScreenInfo(1080, 2400),
        nodes = listOf(
            UiNode(id = "n0", role = "Button", text = "New note", bounds = Rect(0, 100, 1080, 300), clickable = true),
            UiNode(id = "n1", role = "EditText", bounds = Rect(0, 400, 1080, 500), editable = true, sensitive = true),
        ),
        redactedCount = 1,
    )

    private fun hello() = HelloMessage(
        protocol = Protocol.VERSION,
        device = DeviceDescriptor("android", "16", "JVM test", "0.1.0"),
        capabilities = listOf("device.info", "ui.observe", "input.gesture").map { CapabilityState(it, "enabled") },
        session = SessionInfo(System.currentTimeMillis() + 600_000, approveEveryAction = false, paused = false),
        deviceTimeMs = System.currentTimeMillis(),
    )

    @Test
    fun appTransportCarriesTheAgentLoopAndRevocation() = runBlocking {
        startGateway()
        val deviceToken = pair()
        val received = CopyOnWriteArrayList<String>()
        var connected = 0
        var revokedAt: Boolean? = null
        val link = DeviceLink(
            http = http,
            gatewayUrl = base,
            token = deviceToken,
            scope = scope,
            hello = ::hello,
            onCommand = { envelope: CommandEnvelope ->
                received += envelope.command.name
                when (val c = envelope.command) {
                    is Command.Observe -> Outgoing.ok(envelope.id, Protocol.json.encodeToJsonElement(Observation.serializer(), observation))
                    is Command.Tap -> {
                        // Protocol 1.2: answer with the screen after the action when asked.
                        val after = envelope.observeAfter?.let { ActionResult("com.example.notes", observation.copy(observationId = "o_after")) }
                        Outgoing.ok(envelope.id, Protocol.json.encodeToJsonElement(ActionResult.serializer(), after ?: ActionResult("com.example.notes")))
                    }
                    else -> Outgoing.error(envelope.id, ErrorCode.UNSUPPORTED_CAPABILITY, "not in this test: ${c.name}")
                }
            },
            events = object : DeviceLink.Events {
                override fun connected() { connected++ }
                override fun retrying(attempt: Int) = Unit
                override fun rejected(revoked: Boolean) { revokedAt = revoked }
                override fun incompatible(gatewayVersion: String) = error("incompatible $gatewayVersion")
                override fun cancel(commandId: String) = Unit
            },
        )
        link.start()
        waitUntil("connection") { connected > 0 }

        val mcp = mcpToken()
        val (isError, text) = callTool(mcp, "observe", """{"screenshot":false}""")
        assertTrue(text, !isError)
        assertTrue(text, text.contains("observation_id: o_jvm"))
        assertTrue(text, text.contains("[n1] EditText <sensitive, redacted, not actionable>"))

        // Tapping the sensitive field is refused before it reaches the phone.
        callTool(mcp, "tap", """{"observation_id":"o_jvm","element_id":"n1"}""").let { (err, t) ->
            assertTrue(t, err && t.contains("sensitive_target"))
        }
        val tapResult = callTool(mcp, "tap", """{"observation_id":"o_jvm","element_id":"n0"}""")
        assertTrue(tapResult.second, !tapResult.first)
        if (implementation == "vercel") {
            // One phone command per action: the observation came back with the tap.
            assertEquals(listOf("ui.observe", "input.tap"), received.toList())
            assertTrue(tapResult.second, tapResult.second.contains("observation_id: o_after"))
        } else {
            assertEquals(listOf("ui.observe", "input.tap", "ui.observe"), received.toList())
        }

        // Revocation reaches the phone through the transport.
        val deviceId = obj(get("/v1/admin/devices", ADMIN).second).getValue("devices").jsonArray[0].jsonObject.str("id")
        val revokeRequest = Request.Builder().url("$base/v1/admin/devices/$deviceId").delete().header("Authorization", "Bearer $ADMIN").build()
        assertEquals(204, http.newCall(revokeRequest).execute().use { it.code })
        waitUntil("revocation") { revokedAt != null }
        assertEquals(true, revokedAt)
        callTool(mcp, "observe").let { (err, t) -> assertTrue(t, err && t.contains("device_unavailable")) }
        link.stop(sayBye = false)
    }

    @Test
    fun stopSaysByeSoToolsFailFast() = runBlocking {
        startGateway()
        val deviceToken = pair()
        var connected = false
        val link = DeviceLink(
            http, base, deviceToken, scope, ::hello,
            onCommand = { Outgoing.error(it.id, ErrorCode.UNSUPPORTED_CAPABILITY, "unused") },
            events = object : DeviceLink.Events {
                override fun connected() { connected = true }
                override fun retrying(attempt: Int) = Unit
                override fun rejected(revoked: Boolean) = Unit
                override fun incompatible(gatewayVersion: String) = Unit
                override fun cancel(commandId: String) = Unit
            },
        )
        link.start()
        waitUntil("connection") { connected }
        val mcp = mcpToken()
        assertTrue(callTool(mcp, "list_devices").second.contains("connected"))
        link.stop(sayBye = true)
        val (isError, text) = callTool(mcp, "observe")
        assertTrue(text, isError && text.contains("device_unavailable"))
    }

    @Test
    fun ownerSetupPairsThePhoneAndManagesAiKeys() = runBlocking {
        startGateway()
        val setup = GatewaySetup(http)
        val info = setup.probe(base)
        assertEquals("latch-gateway", info.product)
        assertTrue("poll" in info.transports)

        // A wrong owner key is refused with an actionable message.
        val wrong = runCatching { setup.pairAsOwner(base, "lok_wrong_wrong_wrong_wrong_wrong_wrong", "Pixel", "Pixel") }.exceptionOrNull()
        assertTrue(wrong?.message ?: "", wrong is SetupException && wrong.message!!.contains("owner key"))

        val paired = setup.pairAsOwner(base, ADMIN, "Pixel 9", "Google Pixel 9")
        assertEquals(base, paired.gatewayUrl)
        assertTrue(paired.deviceToken.startsWith("ldt_"))

        val owner = OwnerClient(http, base, ADMIN)
        val created = owner.createClient("Claude on laptop")
        assertEquals("$base/mcp", created.mcpUrl)
        assertEquals(listOf("Claude on laptop"), owner.clients().map { it.name })
        // The secret link works for URL-only clients, until revoked.
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(200, post(created.secretLink.removePrefix(base), ping).first)
        owner.revokeClient(created.id)
        assertEquals(401, post(created.secretLink.removePrefix(base), ping).first)
        assertTrue(owner.clients().isEmpty())

        // Not a gateway at all.
        val notGateway = runCatching { setup.probe("$base/nope") }.exceptionOrNull()
        assertTrue(notGateway is SetupException)
    }

    @Test
    fun ownerApprovesAnAiAppSigningIn() = runBlocking {
        startGateway()
        val owner = OwnerClient(http, base, ADMIN)
        if (implementation == "rust") {
            // The container gateway has no OAuth yet; the app simply shows nothing to approve.
            assertTrue(owner.signInRequests().isEmpty())
            return@runBlocking
        }
        // What any MCP client does: register, then open the authorize page.
        val clientId = obj(post("/oauth/register", """{"client_name":"Codex","redirect_uris":["http://127.0.0.1:4100/cb"]}""").second).str("client_id")
        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val page = get("/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=http%3A%2F%2F127.0.0.1%3A4100%2Fcb&code_challenge=$challenge&code_challenge_method=S256&state=xyz")
        assertEquals(200, page.first)

        val waiting = owner.signInRequests()
        assertEquals(listOf("Codex"), waiting.map { it.clientName })
        assertEquals("127.0.0.1:4100", waiting.single().returnTo)
        assertTrue(page.second.contains(waiting.single().match.map { "<span>$it</span>" }.joinToString("")))

        owner.answerSignIn(waiting.single().id, approve = true)
        assertTrue(owner.signInRequests().isEmpty())
        assertEquals(listOf("oauth"), owner.clients().map { it.kind })
        val again = runCatching { owner.answerSignIn(waiting.single().id, approve = true) }.exceptionOrNull()
        assertTrue(again is SetupException)
    }

    @Test
    fun vercelDeployLinkCarriesTheSetup() {
        val url = GatewaySetup.vercelDeployUrl()
        assertTrue(url.startsWith("https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch"))
        assertTrue(url.contains("root-directory=servers%2Fvercel"))
        assertTrue(url.contains("env=LATCH_ADMIN_TOKEN"))
        assertTrue(url.contains("upstash-kv"))
        val key = GatewaySetup.newOwnerKey()
        assertTrue(key.length >= 32 && key != GatewaySetup.newOwnerKey())
    }
}
