// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.policy

import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.UiNode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's tap check must agree with the gateways on every shared policy
 * case it can judge (`packages/schemas/v1/policy/cases.json`), so a gateway
 * that skips an approval is still caught on the phone.
 */
class ConsequencesTest {
    private fun shared(path: String) = javaClass.classLoader!!.getResource(path)!!.readText()
    private val consequences = Consequences(PolicyWords.parse(shared("policy/words.json")))

    @Test
    fun agreesWithSharedPolicyCasesOnTaps() {
        val doc = Protocol.json.parseToJsonElement(shared("policy/cases.json")).jsonObject
        val observation = Protocol.json.decodeFromJsonElement(Observation.serializer(), doc.getValue("observation"))
        var checked = 0
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val command = case.getValue("command").jsonObject
            if (command["name"]!!.jsonPrimitive.content != "input.tap") continue
            if ("session" in case || "capabilities" in case || "observation_age_ms" in case) continue
            val expect = case.getValue("expect").jsonObject
            val decision = expect.getValue("decision").jsonPrimitive.content
            if (decision == "deny") continue
            val params = command.getValue("params").jsonObject
            val target = params.getValue("target").jsonObject
            val node = target["element"]?.let { e -> observation.nodes.first { it.id == e.jsonPrimitive.content } }
                ?: Consequences.nodeAt(observation.nodes, target.getValue("x").jsonPrimitive.int, target.getValue("y").jsonPrimitive.int)
            val longPress = (params["long_press"])?.jsonPrimitive?.boolean ?: false
            val double = (params["double"])?.jsonPrimitive?.boolean ?: false
            if (longPress && double) continue
            val judgement = consequences.judgeTap(observation, node, longPress, double = double)
            val name = case.getValue("name").jsonPrimitive.content
            val want = when {
                decision == "allow" -> Consequence.NONE
                expect["remember"] is JsonNull -> Consequence.CRITICAL
                else -> Consequence.CONSEQUENTIAL
            }
            assertEquals(name, want, judgement.consequence)
            expect["title"]?.let { assertEquals(name, it.jsonPrimitive.content, judgement.title) }
            (expect["remember"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.let { assertEquals(name, it.content, judgement.rememberKey) }
            checked++
        }
        assertTrue("too few tap cases checked: $checked", checked >= 8)
    }

    @Test
    fun agreesWithSharedPolicyCasesOnTypeAndEnter() {
        val doc = Protocol.json.parseToJsonElement(shared("policy/cases.json")).jsonObject
        val observation = Protocol.json.decodeFromJsonElement(Observation.serializer(), doc.getValue("observation"))
        var checked = 0
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val command = case.getValue("command").jsonObject
            val params = command.getValue("params").jsonObject
            if (command["name"]!!.jsonPrimitive.content != "input.type" || params["submit"]?.jsonPrimitive?.boolean != true) continue
            val expect = case.getValue("expect").jsonObject
            val decision = expect.getValue("decision").jsonPrimitive.content
            if (decision == "deny") continue
            val field = observation.nodes.first { it.id == params.getValue("element").jsonPrimitive.content }
            val text = params.getValue("text").jsonPrimitive.content
            val judgement = consequences.judgeEnter(observation, field, text.codePointCount(0, text.length))
            val name = case.getValue("name").jsonPrimitive.content
            assertEquals(name, if (decision == "allow") Consequence.NONE else Consequence.CONSEQUENTIAL, judgement.consequence)
            expect["title"]?.let { assertEquals(name, it.jsonPrimitive.content, judgement.title) }
            (expect["remember"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.let { assertEquals(name, it.content, judgement.rememberKey) }
            checked++
        }
        assertEquals(2, checked)
    }

    private fun node(id: String, text: String? = null, description: String? = null, parent: String? = null, clickable: Boolean = true) =
        UiNode(id = id, parent = parent, role = "View", text = text, description = description, bounds = Rect(0, 0, 100, 100), clickable = clickable)

    private fun obs(pkg: String, vararg nodes: UiNode) = Observation("o_1", 0, pkg, ScreenInfo(100, 100), nodes.toList())

    @Test
    fun liveLabelsRaiseButNeverLower() {
        val o = obs("com.example", node("n1"))
        // The gateway's copy said nothing; what the phone sees inside the button right now says "Send".
        val live = listOf(node("live0"), node("live1", description = "Send"))
        assertEquals(Consequence.CONSEQUENTIAL, consequences.judgeTap(o, o.nodes[0], false, live).consequence)
        assertEquals(Consequence.NONE, consequences.judgeTap(o, o.nodes[0], false).consequence)
    }

    @Test
    fun phoneAppsAndPermissionPrompts() {
        val dialer = obs("com.google.android.dialer", node("n1", text = "Keypad"))
        assertEquals(Consequence.CONSEQUENTIAL, consequences.judgeTap(dialer, dialer.nodes[0], false).consequence)
        assertEquals(Consequence.CONSEQUENTIAL, consequences.judgeSwipe("com.android.incallui").consequence)
        assertEquals(Consequence.NONE, consequences.judgeSwipe("com.example").consequence)
        val prompt = obs("com.google.android.permissioncontroller", node("n1", text = "Only this time"))
        assertEquals(Consequence.CRITICAL, consequences.judgeTap(prompt, prompt.nodes[0], false).consequence)
    }

    @Test
    fun flagsMoneyAccountAndPasswordAppsLikeTheGateways() {
        // The same packages as latch_policy's sensitive_apps_are_recognised_by_package.
        listOf(
            "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
            "in.org.npci.upiapp", "com.csam.icici.bank.imobile", "com.x8bit.bitwarden.vault",
        ).forEach { assertTrue(it, consequences.isSensitiveApp(it)) }
        listOf("com.android.settings", "com.whatsapp", "com.google.android.apps.maps").forEach {
            assertTrue(it, !consequences.isSensitiveApp(it))
        }
        // The phone also reads the app's name, which the gateway never sees.
        assertTrue(consequences.isSensitiveApp("com.sbi.lotus", "YONO SBI: Banking & Lifestyle"))
    }
}
