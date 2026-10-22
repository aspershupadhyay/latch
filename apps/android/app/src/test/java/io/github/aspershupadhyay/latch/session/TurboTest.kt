// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R72 (ADR-074): animations go off for the AI and always come back as they were. */
class TurboTest {
    private class FakeSettings(val values: MutableMap<String, Float> = mutableMapOf(), var refuse: Boolean = false) : GlobalSettings {
        override fun get(key: String) = values[key]
        override fun put(key: String, value: Float): Boolean {
            if (refuse) return false
            values[key] = value
            return true
        }
    }

    private class FakeSnapshots : Snapshots {
        var saved: Map<String, Float>? = null
        override fun load() = saved
        override fun save(values: Map<String, Float>) { saved = values.toMap() }
        override fun clear() { saved = null }
    }

    private val keys = Turbo.KEYS

    @Test fun engageTurnsAnimationsOffAndRestorePutsTheOwnersValuesBack() {
        val settings = FakeSettings(keys.zip(listOf(1f, 0.5f, 1.5f)).toMap().toMutableMap())
        val turbo = Turbo(settings, FakeSnapshots())
        assertTrue(turbo.engage())
        assertTrue(turbo.active)
        keys.forEach { assertEquals(0f, settings.values[it]) }
        assertTrue(turbo.restore())
        assertFalse(turbo.active)
        assertEquals(listOf(1f, 0.5f, 1.5f), keys.map { settings.values[it] })
    }

    @Test fun engagingTwiceKeepsTheFirstSnapshot() {
        val settings = FakeSettings(keys.associateWith { 1f }.toMutableMap())
        val turbo = Turbo(settings, FakeSnapshots())
        turbo.engage()
        turbo.engage()
        turbo.restore()
        keys.forEach { assertEquals(1f, settings.values[it]) }
    }

    @Test fun aCrashBetweenEngageAndRestoreIsUndoneOnTheNextStart() {
        val settings = FakeSettings(keys.associateWith { 1f }.toMutableMap())
        val snapshots = FakeSnapshots()
        Turbo(settings, snapshots).engage()
        // The process dies; a new one starts with the same storage.
        val next = Turbo(settings, snapshots)
        assertTrue(next.active)
        assertTrue(next.restore())
        keys.forEach { assertEquals(1f, settings.values[it]) }
        assertFalse(next.restore())
    }

    @Test fun aRefusedWriteLeavesTheStandardTierAndNothingChanged() {
        val settings = FakeSettings(keys.associateWith { 1f }.toMutableMap(), refuse = true)
        val turbo = Turbo(settings, FakeSnapshots())
        assertFalse(turbo.engage())
        assertFalse(turbo.active)
        keys.forEach { assertEquals(1f, settings.values[it]) }
    }

    @Test fun missingValuesAreRestoredAsAndroidsDefault() {
        val settings = FakeSettings()
        val turbo = Turbo(settings, FakeSnapshots())
        turbo.engage()
        turbo.restore()
        keys.forEach { assertEquals(1f, settings.values[it]) }
    }
}
