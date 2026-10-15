// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** Roadmap R7: waits shrink only for apps that always react fast, and never grow. */
class SettleStatsTest {
    private val key = "org.example|tap"

    @Test
    fun theDefaultHoldsUntilEnoughChangesWereSeen() {
        val stats = SettleStats()
        repeat(SettleStats.MIN_SAMPLES - 1) { stats.changed(key, 80) }
        assertEquals(450, stats.expectChange(key, 450))
        stats.changed(key, 80)
        assertEquals(200, stats.expectChange(key, 450)) // 1.5 × 80 = 120, raised to the floor
    }

    @Test
    fun theWaitFollowsTheSlowestChangeWithMargin() {
        val stats = SettleStats()
        repeat(7) { stats.changed(key, 100) }
        stats.changed(key, 220)
        assertEquals(330, stats.expectChange(key, 450))
    }

    @Test
    fun itNeverWaitsLongerThanTheDefault() {
        val stats = SettleStats()
        repeat(10) { stats.changed(key, 600) }
        assertEquals(450, stats.expectChange(key, 450))
        assertEquals(150, stats.expectChange(key, 150)) // a default below the floor wins
    }

    @Test
    fun aMissStartsOverWithTheFullDefault() {
        val stats = SettleStats()
        repeat(10) { stats.changed(key, 80) }
        stats.missed(key)
        assertEquals(450, stats.expectChange(key, 450))
    }

    @Test
    fun appsAndKindsAreSeparateAndOldOnesAreForgotten() {
        val stats = SettleStats(maxKeys = 2)
        repeat(10) { stats.changed(key, 80) }
        assertEquals(450, stats.expectChange("org.example|swipe", 450))
        repeat(10) { stats.changed("b|tap", 80) }
        repeat(10) { stats.changed("c|tap", 80) }
        assertEquals(450, stats.expectChange(key, 450)) // evicted: least recently used
    }

    @Test
    fun onlyTheNewestSamplesCount() {
        val stats = SettleStats(window = 8)
        stats.changed(key, 400)
        repeat(8) { stats.changed(key, 100) }
        assertEquals(200, stats.expectChange(key, 450)) // the slow 400 fell out of the window
    }
}
