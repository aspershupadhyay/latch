// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch

import android.app.UiAutomation
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aspershupadhyay.latch.data.Pairing
import io.github.aspershupadhyay.latch.protocol.Capability
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Roadmap R29 (e): the soak. The real app, its accessibility service, and a
 * real gateway on an emulator, driven over MCP in a loop for hours: read the
 * screen, open Settings, go back, go home, put text on the clipboard. It
 * passes when almost every call answered, the session is still up at the end,
 * and the app's memory did not keep growing.
 *
 * Runs only with `-e soakMinutes N` (scripts/emulator-test.sh with
 * LATCH_SOAK_MINUTES, `.github/workflows/soak.yml`). Prints a summary to
 * logcat under the tag LatchSoak; no screen content is logged.
 */
@RunWith(AndroidJUnit4::class)
class SoakTest {
    private val args = InstrumentationRegistry.getArguments()
    private val gateway: String? = args.getString("gateway")
    private val ownerKey: String? = args.getString("ownerKey")
    private val mcpToken: String? = args.getString("mcpToken")
    private val minutes: Long? = args.getString("soakMinutes")?.toLongOrNull()
    private val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private var mcpSession: String? = null
    private var nextId = 1

    private fun rpc(method: String, params: JsonObject?, id: Int?): JsonObject? {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val request = Request.Builder().url("$gateway/mcp")
            .header("Authorization", "Bearer $mcpToken")
            .header("Accept", "application/json")
            .header("MCP-Protocol-Version", "2025-06-18")
            .apply { mcpSession?.let { header("Mcp-Session-Id", it) } }
            .post(message.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for $method" }
            response.header("Mcp-Session-Id")?.let { mcpSession = it }
            val body = response.body.string()
            if (id == null || body.isBlank()) return null
            return json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
        }
    }

    /** One tool call: whether it failed, and its first line (never screen text beyond it). */
    private fun call(tool: String, vararg arguments: Pair<String, String>): Pair<Boolean, String> {
        val result = rpc(
            "tools/call",
            buildJsonObject {
                put("name", tool)
                putJsonObject("arguments") { arguments.forEach { (k, v) -> put(k, v) } }
            },
            nextId++,
        ) ?: return true to "no answer"
        val text = result["content"]!!.jsonArray.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        return (result["isError"]?.jsonPrimitive?.boolean ?: false) to text
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun pssMb(): Long = Debug.getPss() / 1024

    @Test
    fun theAppStaysUpAndAnswersForHours(): Unit = runBlocking {
        assumeTrue(
            "needs -e gateway, ownerKey, mcpToken, soakMinutes",
            gateway != null && ownerKey != null && mcpToken != null && minutes != null,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LatchApp
        val paired = app.setup.pairAsOwner(gateway!!, ownerKey!!, "Soak", deviceDescriptor().model)
        onMain {
            app.settings.saveOwnerKey(ownerKey)
            app.settings.savePairing(Pairing(paired.gatewayUrl, paired.deviceId, paired.name), paired.deviceToken)
            app.autonomy.setAllowed("com.android.settings", true)
            app.settings.update {
                it.copy(
                    sessionMinutes = 480,
                    enabled = it.enabled + setOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE, Capability.NAV_GLOBAL, Capability.APP_LAUNCH, Capability.CLIPBOARD_WRITE),
                )
            }
        }
        // Throwaway emulator only: the owner's switch for the accessibility service.
        val shell = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component = "${app.packageName}/io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService"
        shell.executeShellCommand("settings put secure enabled_accessibility_services $component").close()
        shell.executeShellCommand("settings put secure accessibility_enabled 1").close()
        withTimeoutOrNull(60_000) { app.bridge.service.first { it != null } } ?: throw AssertionError("no accessibility service")
        onMain { app.session.start() }
        withTimeoutOrNull(60_000) { app.session.state.first { it is SessionState.Active } } ?: throw AssertionError("no session")
        rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "latch-soak"); put("version", "1") }
            },
            nextId++,
        )
        rpc("notifications/initialized", null, null)

        val started = System.currentTimeMillis()
        val until = started + minutes!! * 60_000
        var calls = 0
        val failures = mutableListOf<String>()
        val memory = mutableListOf(pssMb())
        var lastSample = started
        var round = 0
        while (System.currentTimeMillis() < until) {
            round++
            for ((tool, arguments) in listOf(
                "observe" to arrayOf<Pair<String, String>>(),
                "launch_app" to arrayOf("package" to "com.android.settings"),
                "press" to arrayOf("button" to "back"),
                "press" to arrayOf("button" to "home"),
                "set_clipboard" to arrayOf("text" to "soak $round"),
            )) {
                calls++
                val (failed, text) = runCatching { call(tool, *arguments) }.getOrElse { true to (it.message ?: "error") }
                // First lines only: Latch's own wording, not what was on the screen.
                if (failed) failures += "$tool: ${text.lineSequence().first().take(120)}"
            }
            val now = System.currentTimeMillis()
            if (now - lastSample >= 10 * 60_000) {
                lastSample = now
                memory += pssMb()
                // Hours-long runs outlast one session; the owner would tap + to extend.
                val state = app.session.state.value
                if (state is SessionState.Active && state.expiresAtMs - now < 60 * 60_000) onMain { app.session.extend() }
                Log.i("LatchSoak", "${(now - started) / 60_000} min: $calls calls, ${failures.size} failed, ${memory.last()} MB")
            }
            delay(200)
        }
        memory += pssMb()
        val summary = "soak ${minutes} min: $calls calls, ${failures.size} failed, memory ${memory.first()} → ${memory.last()} MB (peak ${memory.max()} MB)"
        Log.i("LatchSoak", summary)
        failures.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).forEach { (f, n) -> Log.i("LatchSoak", "  $n × $f") }

        assertTrue("session ended during the soak: ${app.session.state.value}", app.session.state.value is SessionState.Active)
        assertTrue("$summary; first failures: ${failures.take(5)}", failures.size * 100 <= calls)
        // Growth beyond double (plus room for caches) over hours means a leak.
        assertTrue("$summary: memory kept growing", memory.last() <= memory.first() * 2 + 64)
        onMain { app.session.stop() }
    }
}
