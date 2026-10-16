// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch

import android.app.UiAutomation
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aspershupadhyay.latch.data.Pairing
import io.github.aspershupadhyay.latch.files.PhoneTransfers
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.FileLink
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.session.deviceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Roadmap R15: the real app, its real accessibility service, and a real container
 * gateway, on an emulator. An AI client (this test, speaking MCP like any other)
 * reads the screen, opens Settings, taps, and is cut off by Stop.
 *
 * Runs only when CI passes the gateway (`.github/workflows/ci.yml`, job
 * `emulator`). It switches the accessibility service on with a shell command,
 * which is acceptable on a throwaway emulator only (docs/operations/qa.md).
 */
@RunWith(AndroidJUnit4::class)
class EmulatorGatewayTest {
    private val args = InstrumentationRegistry.getArguments()
    private val gateway: String? = args.getString("gateway")
    private val ownerKey: String? = args.getString("ownerKey")
    private val mcpToken: String? = args.getString("mcpToken")
    private val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private var mcpSession: String? = null
    private var nextId = 1

    private data class ToolResult(val text: String, val isError: Boolean) {
        val observationId: String? get() = Regex("observation_id: (\\S+)").findAll(text).lastOrNull()?.groupValues?.get(1)

        /** The id of the element whose words are exactly [label], like the Python client's `element`. */
        fun element(label: String): String = text.lines().lastOrNull { "\"$label\"" in it || "desc=\"$label\"" in it }
            ?.let { Regex("\\[([^\\]\\s]+)]").find(it)?.groupValues?.get(1) }
            ?: throw AssertionError("\"$label\" is not on the screen:\n$text")
    }

    private fun rpc(method: String, params: JsonObject?, id: Int?): JsonObject? {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val request = Request.Builder().url("$gateway/mcp")
            .header("Authorization", "Bearer $mcpToken")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .apply { mcpSession?.let { header("Mcp-Session-Id", it) } }
            .post(message.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for $method" }
            response.header("Mcp-Session-Id")?.let { mcpSession = it }
            val body = response.body.string()
            if (id == null || body.isBlank()) return null
            val data = if (response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                body.split("\n\n").map { chunk -> chunk.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.substring(5).trimStart() } }
                    .first { it.isNotBlank() && json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.contentOrNull == id.toString() }
            } else {
                body
            }
            val answer = json.parseToJsonElement(data).jsonObject
            answer["error"]?.let { throw AssertionError("$method failed: $it") }
            return answer["result"]?.jsonObject
        }
    }

    private fun call(tool: String, vararg arguments: Pair<String, String>): ToolResult {
        val result = rpc(
            "tools/call",
            buildJsonObject {
                put("name", tool)
                putJsonObject("arguments") { arguments.forEach { (k, v) -> put(k, v) } }
            },
            nextId++,
        )!!
        val text = result["content"]!!.jsonArray.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        return ToolResult(text, result["isError"]?.jsonPrimitive?.boolean ?: false)
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun waitFor(what: String, block: suspend () -> Unit) {
        withTimeoutOrNull(60_000) { block() } ?: throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun anAiClientOperatesTheEmulatorThroughTheGatewayAndStopEndsIt(): Unit = runBlocking {
        assumeTrue("needs -e gateway, ownerKey, mcpToken (CI emulator job)", gateway != null && ownerKey != null && mcpToken != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LatchApp

        // The owner pairs the phone with their owner key, as in setup.
        val model = deviceDescriptor().model
        val paired = app.setup.pairAsOwner(gateway!!, ownerKey!!, "Emulator", model)
        onMain {
            app.settings.saveOwnerKey(ownerKey)
            app.settings.savePairing(Pairing(paired.gatewayUrl, paired.deviceId, paired.name), paired.deviceToken)
            // The owner switched Settings on for the AI (Access → Apps); every other rule still applies.
            app.autonomy.setAllowed("com.android.settings", true)
        }

        // Throwaway emulator only: the owner's switch for the accessibility service.
        // The default UiAutomation would suppress every other accessibility service, Latch's included.
        val shell = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component = "${app.packageName}/io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService"
        shell.executeShellCommand("settings put secure enabled_accessibility_services $component").close()
        shell.executeShellCommand("settings put secure accessibility_enabled 1").close()
        waitFor("the accessibility service to start") { app.bridge.service.first { it != null } }

        onMain { app.session.start() }
        waitFor("the session to connect (now ${app.session.state.value})") { app.session.state.first { it is SessionState.Active } }

        rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "latch-emulator-test"); put("version", "1") }
            },
            nextId++,
        )
        rpc("notifications/initialized", null, null)

        val devices = call("list_devices")
        assertTrue(devices.text, paired.deviceId in devices.text)

        // R13 on a real phone: diagnose names battery optimisation exactly when Android applies it.
        val restricted = !app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        val diagnosis = call("diagnose")
        assertFalse(diagnosis.text, diagnosis.isError)
        assertTrue(diagnosis.text, ("battery optimisation" in diagnosis.text) == restricted)

        // Default deny: until the owner switches capabilities on, the phone refuses.
        val refused = call("launch_app", "package" to "com.android.settings")
        assertTrue(refused.text, refused.isError)

        // The owner switches on what the task needs (Access → Capabilities); screen capture stays off.
        onMain {
            app.settings.update {
                it.copy(enabled = it.enabled + setOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE, Capability.NAV_GLOBAL, Capability.APP_LAUNCH))
            }
        }

        // Open Settings, read it, tap into a page, and read the change. The switch reaches
        // the gateway with the phone's next state update, so the first try may still be refused.
        var screen = call("launch_app", "package" to "com.android.settings")
        for (attempt in 1..10) {
            if (!screen.isError) break
            delay(500)
            screen = call("launch_app", "package" to "com.android.settings")
        }
        assertFalse(screen.text, screen.isError)
        if ("Network & internet" !in screen.text) screen = call("wait_for", "text" to "Network & internet")
        assertFalse(screen.text, screen.isError)
        screen = call("observe")
        screen = call("tap", "observation_id" to screen.observationId!!, "element_id" to screen.element("Network & internet"))
        assertFalse(screen.text, screen.isError)
        if ("Internet" !in screen.text) screen = call("wait_for", "text" to "Internet")
        assertTrue(screen.text, "Internet" in screen.text)
        assertFalse(call("press", "button" to "home").isError)

        // R23 (ADR-041): with fast transfers on, a file goes straight to the phone's Wi-Fi
        // address and back. This test plays the AI's computer, as `curl` would.
        onMain {
            app.settings.update {
                it.copy(enabled = it.enabled + setOf(Capability.FILE_READ, Capability.FILE_WRITE), localTransfers = true)
            }
        }
        var upload = call("upload_link")
        for (attempt in 1..10) {
            if ("straight to the phone" in upload.text) break
            delay(500)
            upload = call("upload_link")
        }
        assertTrue(upload.text, "straight to the phone" in upload.text)
        val key = Regex("-K ([0-9a-f]{64})").find(upload.text)!!.groupValues[1]
        val iv = Regex("-iv ([0-9a-f]{32})").find(upload.text)!!.groupValues[1]
        val toPhone = Regex("--connect-timeout 2 -T \"\\\$t\" \"(http://[^\"]+)\"").find(upload.text)?.groupValues?.get(1)
            ?: throw AssertionError("no Wi-Fi address in:\n${upload.text}")
        val plain = ByteArray(1_500_000) { (it % 253).toByte() }
        val cipher = { PhoneTransfers.cipherFor(FileLink("x", emptyMap(), key, iv)) }
        val put = Request.Builder().url(toPhone).put(cipher().doFinal(plain).toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(put).execute().use { assertTrue("PUT answered ${it.code}", it.code == 201) }
        val sha = PhoneTransfers.hex(MessageDigest.getInstance("SHA-256").digest(plain))
        val uploadId = Regex("upload_id=\"([^\"]+)\"").find(upload.text)!!.groupValues[1]
        val saved = call("write_file", "location" to "downloads", "name" to "latch-wifi-test.bin", "upload_id" to uploadId, "sha256" to sha)
        assertTrue(saved.text, !saved.isError && saved.text.startsWith("Saved \"latch-wifi-test"))
        val fileId = Regex("file_id: (\\S+)\\.").find(saved.text)!!.groupValues[1]
        val link = call("get_file_link", "file_id" to fileId)
        assertTrue(link.text, "straight from the phone" in link.text)
        val fromPhone = Regex("curl -fsSL --connect-timeout 2 \"(http://[^\"]+)\"").find(link.text)!!.groupValues[1]
        val back = http.newCall(Request.Builder().url(fromPhone).build()).execute().use {
            assertTrue("GET answered ${it.code}", it.code == 200)
            it.body.bytes()
        }
        val key2 = Regex("-K ([0-9a-f]{64})").find(link.text)!!.groupValues[1]
        val iv2 = Regex("-iv ([0-9a-f]{32})").find(link.text)!!.groupValues[1]
        assertTrue("the file came back byte for byte", PhoneTransfers.cipherFor(FileLink("x", emptyMap(), key2, iv2)).doFinal(back).contentEquals(plain))
        // No clean-up: deleting asks the owner (nobody answers here), and the emulator is thrown away.

        // The emergency stop: the session ends and the AI can no longer reach the phone.
        onMain { app.session.stop() }
        val after = call("observe")
        assertTrue(after.text, after.isError)
    }
}
