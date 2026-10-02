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
        assertEquals(AccessPreset.LOOK, AccessPreset.matching(setOf(Capability.DEVICE_INFO, Capability.UI_OBSERVE)))
        assertEquals(AccessPreset.EVERYTHING, AccessPreset.matching(Capability.entries.toSet()))
        assertNull(AccessPreset.matching(setOf(Capability.UI_OBSERVE, Capability.INPUT_TEXT)))
        AccessPreset.entries.forEach { assertTrue(Capability.UI_OBSERVE in it.capabilities) }
    }
}
