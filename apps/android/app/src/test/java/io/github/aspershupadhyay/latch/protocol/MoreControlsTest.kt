// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import io.github.aspershupadhyay.latch.policy.Consequence
import io.github.aspershupadhyay.latch.policy.Consequences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Protocol 1.15 (roadmap R25) on the phone: the new buttons, links, and
 * controls parse to the same commands and read in the same words as on both
 * gateways, and the phone judges links itself.
 */
class MoreControlsTest {
    private fun command(name: String, params: String): Command {
        val frame = """{"type":"command","id":"c_1","deadline_ms":15000,"command":{"name":"$name","params":$params}}"""
        return (GatewayParser.parse(frame) as GatewayMessage.CommandMessage).envelope.command
    }

    private fun refused(name: String, params: String) {
        try {
            command(name, params)
            fail("$name $params was accepted")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.INVALID_REQUEST, e.code)
        }
    }

    @Test
    fun newButtonsReadLikeTheGateways() {
        assertEquals(GlobalAction.LOCK_SCREEN, (command("nav.global", """{"action":"lock_screen"}""") as Command.Global).action)
        assertEquals("Take a screenshot (saved to the gallery)", GlobalAction.SCREENSHOT.description)
        assertEquals("Press the centre (select) key", GlobalAction.DPAD_CENTER.description)
        assertEquals("Show recent apps", GlobalAction.RECENTS.description)
        // System UI stays off limits: no button opens the notification shade.
        refused("nav.global", """{"action":"notifications"}""")
        refused("nav.global", """{"action":"quick_settings"}""")
    }

    @Test
    fun linksAndFreshLaunches() {
        val link = command("app.open_link", """{"url":"geo:0,0?q=coffee","package":"com.google.android.apps.maps"}""") as Command.OpenLink
        assertEquals("geo", link.scheme)
        assertEquals(Command.LaunchApp("com.example.notes", fresh = true), command("app.launch", """{"package":"com.example.notes","fresh":true}"""))
        assertEquals(Command.LaunchApp("com.example.notes"), command("app.launch", """{"package":"com.example.notes"}"""))
        for (url in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://x/y", "JavaScript:alert(1)", "https://a.example/a b", "example.com", "https:example.com")) {
            refused("app.open_link", """{"url":"$url","package":"com.example.notes"}""")
        }
    }

    @Test
    fun thePhoneJudgesLinksLikeTheGateways() {
        val pay = Consequences.judgeLink("upi://pay?pa=shop@bank&am=10", "com.example.pay")!!
        assertEquals(Consequence.CRITICAL, pay.consequence)
        assertEquals("Open upi://pay?pa=shop@bank&am=10 in com.example.pay", pay.title)
        val sms = Consequences.judgeLink("sms:+15550100?body=hi", "com.example.chat")!!
        assertEquals(Consequence.CONSEQUENTIAL, sms.consequence)
        assertEquals("link|com.example.chat|sms", sms.rememberKey)
        assertNull(Consequences.judgeLink("https://www.youtube.com/watch?v=x", "com.google.android.youtube"))
        assertEquals("https://www.youtube.com", Consequences.shortenLink("https://www.youtube.com/watch?v=x"))
        assertEquals(60, Consequences.shortenLink("geo:" + "1".repeat(100)).length)
    }

    @Test
    fun newControlsReadLikeTheGateways() {
        fun control(json: String) = (command("device.control", """{"control":$json}""") as Command.DeviceControl).control
        assertEquals("Set the ringer to vibrate", control("""{"kind":"ringer","mode":"vibrate"}""").description)
        assertEquals("Turn Do Not Disturb on", control("""{"kind":"do_not_disturb","on":true}""").description)
        assertEquals("Lock the screen to portrait", control("""{"kind":"rotation","mode":"portrait"}""").description)
        assertEquals("Set the screen timeout to 10 min", control("""{"kind":"screen_timeout","seconds":600}""").description)
        assertEquals("Set the screen timeout to 45 s", control("""{"kind":"screen_timeout","seconds":45}""").description)
        assertEquals("Open the battery settings", control("""{"kind":"panel","panel":"battery"}""").description)
        assertEquals("Open the NFC panel", control("""{"kind":"panel","panel":"nfc"}""").description)
        assertEquals(Capability.CONTROL_SOUND, control("""{"kind":"do_not_disturb","on":false}""").capability)
        assertEquals(Capability.CONTROL_BRIGHTNESS, control("""{"kind":"rotation","mode":"auto"}""").capability)
        refused("device.control", """{"control":{"kind":"screen_timeout","seconds":5}}""")
        refused("device.control", """{"control":{"kind":"ringer","mode":"loud"}}""")
        refused("device.control", """{"control":{"kind":"panel","panel":"developer"}}""")
    }

    @Test
    fun deviceStatusHasTheSharedShape() {
        val text = Protocol.json.encodeToString(DeviceStatus.serializer(), DeviceStatus(batteryPercent = 64, charging = true, network = "wifi", freeStorageMb = 20480))
        assertEquals("""{"battery_percent":64,"charging":true,"network":"wifi","free_storage_mb":20480}""", text)
    }
}
