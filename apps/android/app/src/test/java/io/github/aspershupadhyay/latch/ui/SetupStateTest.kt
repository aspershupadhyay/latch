// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.protocol.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    private val fresh = SetupState(
        notificationsNeeded = true,
        notificationsOn = false,
        notificationsBlocked = false,
        notificationsSkipped = false,
        accessibilityOn = false,
        batteryOn = false,
        batterySkipped = false,
        preset = null,
        approveEveryAction = false,
        reducedMotion = true,
    )

    @Test fun stepsComeInOrder() {
        assertEquals(SetupStep.NOTIFICATIONS, fresh.current)
        assertEquals(SetupStep.ACCESSIBILITY, fresh.copy(notificationsOn = true).current)
        assertEquals(SetupStep.BATTERY, fresh.copy(notificationsOn = true, accessibilityOn = true).current)
        assertEquals(SetupStep.ACCESS, fresh.copy(notificationsOn = true, accessibilityOn = true, batteryOn = true).current)
    }

    @Test fun screenAccessCannotBeSkipped() {
        val skippedEverything = fresh.copy(notificationsSkipped = true, batterySkipped = true, preset = AccessPreset.LOOK)
        assertEquals(SetupStep.ACCESSIBILITY, skippedEverything.current)
        assertFalse(skippedEverything.complete)
        assertTrue(skippedEverything.copy(accessibilityOn = true).complete)
    }

    @Test fun olderAndroidNeedsNoNotificationPermission() {
        val old = fresh.copy(notificationsNeeded = false)
        assertTrue(old.done(SetupStep.NOTIFICATIONS))
        assertEquals(1, old.doneCount)
    }

    @Test fun nothingIsChosenForTheOwner() {
        // Default deny: until the owner picks a preset, the access step stays open.
        val ready = fresh.copy(notificationsOn = true, accessibilityOn = true, batteryOn = true)
        assertNull(ready.preset)
        assertFalse(ready.complete)
        assertNull(ready.copy(preset = AccessPreset.LOOK_AND_TAP).current)
    }

    @Test fun presetsMatchExactlyAndNeverIncludeDeviceInfoAlone() {
        assertNull(AccessPreset.matching(setOf(Capability.DEVICE_INFO)))
        assertEquals(AccessPreset.LOOK, AccessPreset.matching(AccessPreset.LOOK.capabilities + Capability.DEVICE_INFO))
        assertEquals(AccessPreset.EVERYTHING, AccessPreset.matching(Capability.entries.toSet()))
        assertNull(AccessPreset.matching(setOf(Capability.UI_OBSERVE, Capability.INPUT_TEXT)))
        AccessPreset.entries.forEach { assertTrue(Capability.UI_OBSERVE in it.capabilities) }
    }

    @Test fun noPresetEverTurnsOnFullAdb() {
        // ADR-046: full ADB stays off until the owner turns it on by itself (ADR-064).
        AccessPreset.entries.forEach { assertFalse(Capability.ADB_SHELL in it.capabilities) }
        // Having ADB on does not stop the cards from matching.
        assertEquals(AccessPreset.LOOK_AND_TAP, AccessPreset.matching(AccessPreset.LOOK_AND_TAP.capabilities + Capability.ADB_SHELL))
        // Only Everything covers every app.
        assertEquals(listOf(AccessPreset.EVERYTHING), AccessPreset.entries.filter { it.allApps })
    }
}
