// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import io.github.aspershupadhyay.latch.session.PhoneControls
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Protocol 1.11 phone controls: the same commands and words as both gateways. */
class PhoneControlTest {
    private fun shared(path: String): String {
        val url = javaClass.classLoader!!.getResource(path) ?: error("$path is not on the test classpath")
        return File(url.toURI()).readText()
    }

    /** packages/schemas/v1/mcp/control-expected.json: every command the gateways send parses and reads the same here. */
    @Test fun gatewayCommandsParseWithTheSameWords() {
        val cases = Protocol.json.parseToJsonElement(shared("mcp/control-expected.json")).jsonArray
        var checked = 0
        for (case in cases.map { it.jsonObject }) {
            val command = case["command"] as? JsonObject ?: continue
            val control = PhoneControl.parse(command.getValue("params").jsonObject.getValue("control").jsonObject)
            PhoneControl.validate(control)
            assertEquals(case.getValue("words").jsonPrimitive.content, control.describe())
            assertEquals(case.getValue("changes_screen").jsonPrimitive.content.toBoolean(), control.changesScreen)
            checked++
        }
        assertTrue(checked >= 8)
    }

    @Test fun eachGroupHasItsOwnCapability() {
        val groups = listOf(
            PhoneControl.Volume(PhoneControl.Stream.MEDIA, 40, null),
            PhoneControl.Media(PhoneControl.MediaAction.PAUSE),
            PhoneControl.Brightness(null, true),
            PhoneControl.OpenPanel(PhoneControl.Panel.WIFI),
            PhoneControl.Flashlight(true),
        )
        assertEquals(5, groups.map { it.capability }.toSet().size)
        assertEquals(listOf(Capability.CONTROL_FLASHLIGHT), Command.Control(PhoneControl.Flashlight(false)).requiredCapabilities)
    }

    @Test fun levelsConvertBothWays() {
        // A media stream with 15 steps: 40% is step 6, which reads back as 40%.
        assertEquals(6, PhoneControls.volumeIndex(40, 0, 15))
        assertEquals(40, PhoneControls.volumePercent(6, 15))
        // Alarms never go below their minimum step.
        assertEquals(1, PhoneControls.volumeIndex(0, 1, 7))
        assertEquals(15, PhoneControls.volumeIndex(100, 0, 15))
        // Brightness 0% still shows the screen.
        assertEquals(1, PhoneControls.brightnessLevel(0))
        assertEquals(255, PhoneControls.brightnessLevel(100))
        assertEquals(50, PhoneControls.brightnessPercent(PhoneControls.brightnessLevel(50)))
    }
}
