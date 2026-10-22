// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Reads the same fixtures as the Rust tests (`packages/schemas/v1/fixtures`),
 * so the two implementations cannot silently disagree about the wire format.
 */
class ProtocolFixturesTest {
    private fun fixtures(kind: String): List<Pair<String, String>> {
        val url = javaClass.classLoader!!.getResource("fixtures/$kind") ?: error("fixtures/$kind is not on the test classpath")
        val files = File(url.toURI()).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no $kind fixtures", files.isNotEmpty())
        return files.map { it.name to it.readText() }
    }

    @Test
    fun validGatewayMessagesParseAndValidate() {
        for ((name, json) in fixtures("valid").filter { it.first.startsWith("g2d-") }) {
            try {
                GatewayParser.parse(json)
            } catch (e: ProtocolException) {
                fail("$name was rejected: ${e.code} ${e.message}")
            }
        }
    }

    @Test
    fun invalidGatewayMessagesAreRejected() {
        for ((name, json) in fixtures("invalid").filter { it.first.startsWith("g2d-") }) {
            try {
                GatewayParser.parse(json)
                fail("$name was accepted")
            } catch (expected: ProtocolException) {
                // The command id is still recoverable so the phone can answer with an error.
                if (name.contains("command")) assertTrue(GatewayParser.commandId(json) != null)
            }
        }
    }

    @Test
    fun deviceMessagesUseTheSharedShapes() {
        val all = fixtures("valid").toMap()
        val hello = Protocol.json.decodeFromString(HelloMessage.serializer(), all.getValue("d2g-hello.json"))
        assertEquals("1.0", hello.protocol)
        assertEquals("needs_permission", hello.capabilities.last().status)
        val reencoded = Protocol.json.decodeFromString(HelloMessage.serializer(), Protocol.json.encodeToString(HelloMessage.serializer(), hello))
        assertEquals(hello, reencoded)
        // Protocol 1.13: the phone says whether Android may stop Latch in the background.
        assertEquals(null, hello.batteryRestricted)
        val battery = Protocol.json.decodeFromString(HelloMessage.serializer(), all.getValue("d2g-hello-battery.json"))
        assertEquals(true, battery.batteryRestricted)
        assertTrue(Protocol.json.encodeToString(HelloMessage.serializer(), battery).contains("\"battery_restricted\":true"))

        val state = Protocol.json.decodeFromString(StateMessage.serializer(), all.getValue("d2g-state.json"))
        assertTrue(state.session.paused)
        // Protocol 1.20 (R67): the file places the owner shares.
        assertEquals(FileAccess(photos = false, folders = 2), state.session.files)
        val refused = GatewayParser.parse(all.getValue("g2d-refused.json")) as GatewayMessage.Refused
        assertEquals("type_text", refused.tool)
        assertEquals(ErrorCode.SENSITIVE_TARGET, refused.code)

        val result = Protocol.json.parseToJsonElement(all.getValue("d2g-result-observation.json"))
        val data = (result as kotlinx.serialization.json.JsonObject)["outcome"]!!.let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val observation = Protocol.json.decodeFromJsonElement(Observation.serializer(), data)
        assertEquals("o_7", observation.observationId)
        assertTrue(observation.nodes.last().sensitive)
        assertEquals(null, observation.nodes.last().text)
    }

    /** Protocol 1.17 (ADR-046, R27): the full-access ADB command and its result. */
    @Test
    fun parsesAdbShellCommandsAndResults() {
        val all = fixtures("valid").toMap()
        val run = (GatewayParser.parse(all.getValue("g2d-command-adb-shell.json")) as GatewayMessage.CommandMessage).envelope.command
        assertEquals(Command.AdbShell("getprop ro.product.model", null, 0, null), run)
        assertEquals(false, run.isAction)
        assertEquals(listOf(Capability.ADB_SHELL), run.requiredCapabilities)
        val stdin = (GatewayParser.parse(all.getValue("g2d-command-adb-shell-stdin.json")) as GatewayMessage.CommandMessage).envelope.command
        assertEquals(Command.AdbShell("cat", "hello from the agent\n", 5000, null), stdin)
        val next = (GatewayParser.parse(all.getValue("g2d-command-adb-shell-continue.json")) as GatewayMessage.CommandMessage).envelope.command
        assertEquals(Command.AdbShell(null, null, 0, "chunk_7a1"), next)

        val data = (Protocol.json.parseToJsonElement(all.getValue("d2g-result-adb-shell.json")) as kotlinx.serialization.json.JsonObject)["outcome"]!!
            .let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val result = Protocol.json.decodeFromJsonElement(AdbResult.serializer(), data)
        assertEquals(AdbResult("Pixel 8\n", "", 0, null), result)
        val chunkData = (Protocol.json.parseToJsonElement(all.getValue("d2g-result-adb-shell-chunk.json")) as kotlinx.serialization.json.JsonObject)["outcome"]!!
            .let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val chunk = Protocol.json.decodeFromJsonElement(AdbResult.serializer(), chunkData)
        assertEquals("chunk_7a2", chunk.cursor)
        assertEquals(null, chunk.exitCode)
    }

    @Test
    fun parsesConfirmationRequests() {
        val all = fixtures("valid").toMap()
        val message = GatewayParser.parse(all.getValue("g2d-command-tap-point-confirm.json")) as GatewayMessage.CommandMessage
        assertEquals("high", message.envelope.confirm?.risk)
        assertEquals(Target.Point(980, 2300), (message.envelope.command as Command.Tap).target)
    }

    @Test
    fun parsesObserveAfterAndActionResultsWithObservations() {
        val all = fixtures("valid").toMap()
        val message = GatewayParser.parse(all.getValue("g2d-command-tap-observe-after.json")) as GatewayMessage.CommandMessage
        assertEquals(ObserveAfter(500, false, 400), message.envelope.observeAfter)

        fun data(name: String) = (Protocol.json.parseToJsonElement(all.getValue(name)) as kotlinx.serialization.json.JsonObject)["outcome"]!!
            .let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val withObservation = Protocol.json.decodeFromJsonElement(ActionResult.serializer(), data("d2g-result-action-observation.json"))
        assertEquals("o_8", withObservation.observation?.observationId)
        assertEquals("changed", withObservation.effect)
        val withError = Protocol.json.decodeFromJsonElement(ActionResult.serializer(), data("d2g-result-action-observation-error.json"))
        assertEquals("policy_refused", withError.observationError?.code)
        // What the phone sends round-trips through the same shape.
        val again = Protocol.json.decodeFromJsonElement(ActionResult.serializer(), Protocol.json.encodeToJsonElement(ActionResult.serializer(), withObservation))
        assertEquals(withObservation, again)
    }

    /** Protocol 1.6 (ADR-026): file commands and results in the shared shapes. */
    @Test
    fun parsesFileCommandsAndFileResults() {
        val all = fixtures("valid").toMap()
        val write = (GatewayParser.parse(all.getValue("g2d-command-file-write.json")) as GatewayMessage.CommandMessage).envelope.command
        assertEquals(
            Command.WriteFile(FileLocation.DOWNLOADS, null, "abc", "todo.txt", "text/plain", "bWlsaw==", append = false, overwrite = true),
            write,
        )
        assertEquals(true, write.isFileChange)
        val share = (GatewayParser.parse(all.getValue("g2d-command-share.json")) as GatewayMessage.CommandMessage).envelope.command
        assertEquals(Command.Share("com.google.android.youtube", listOf("f_0001", "f_0004"), "Sunset"), share)
        val data = (Protocol.json.parseToJsonElement(all.getValue("d2g-result-file-list.json")) as kotlinx.serialization.json.JsonObject)["outcome"]!!
            .let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val list = Protocol.json.decodeFromJsonElement(FileList.serializer(), data)
        assertEquals(listOf("beach.jpg"), list.items.map { it.name })
        assertEquals(51, list.nextOffset)
    }

    @Test
    fun fileNamesFollowTheGatewayRules() {
        for (good in listOf("a.txt", "Photo 2026 (1).jpg", "notes")) assertEquals(good, true, Limits.isValidFileName(good))
        for (bad in listOf("", ".hidden", "../x", "a/b", "a\\b", "a:b", " lead", "x".repeat(121))) {
            assertEquals(bad, false, Limits.isValidFileName(bad))
        }
    }

    @Test
    fun outgoingResultsHaveTheWireShape() {
        val ok = Protocol.json.parseToJsonElement(Outgoing.error("c_1", ErrorCode.STALE_OBSERVATION, "changed")).toString()
        assertEquals("""{"type":"result","id":"c_1","outcome":{"status":"error","error":{"code":"stale_observation","message":"changed"}}}""", ok)
    }

    @Test
    fun versionCompatibility() {
        assertTrue(Protocol.isCompatible("1.0"))
        assertTrue(Protocol.isCompatible("1.9"))
        // Two-digit minors compare as numbers: 1.10 gateways take WebP screenshots (R6).
        assertTrue(Protocol.isCompatible("1.10"))
        assertEquals(10, Protocol.minorOf("1.10"))
        assertTrue(!Protocol.isCompatible("2.0"))
        assertTrue(!Protocol.isCompatible("1.0.0"))
    }

    @Test
    fun parsesApprovalAnswersAndWritesApprovalRequestsInTheSharedShape() {
        val all = fixtures("valid").toMap()
        assertEquals(
            GatewayMessage.ApprovalAnswer("0123456789abcdef0123456789abcdef", "session"),
            GatewayParser.parse(all.getValue("g2d-approval-answer.json")),
        )
        val ours = Protocol.json.parseToJsonElement(
            Outgoing.approvalRequest(
                "c_42", "0123456789abcdef0123456789abcdef", "Tap “Send” in com.example.chat",
                "Requested by an AI agent connected through Latch.", app = false, choices = listOf("once", "session", "always", "deny"),
                remote = true, expiresAtMs = 1800000120000,
            ),
        )
        assertEquals(Protocol.json.parseToJsonElement(all.getValue("d2g-approval-request.json")), ours)
        val state = Protocol.json.decodeFromString(StateMessage.serializer(), all.getValue("d2g-state.json"))
        assertTrue(state.session.remoteApprovals)
    }
}
