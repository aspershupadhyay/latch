// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R38: a smaller picture only when the lowest bitrate is not enough, and back after a clean stretch. */
class QualityLadderTest {
    @Test
    fun stepsDownOnlyAtTheBitrateFloor() {
        val ladder = QualityLadder()
        repeat(10) { assertNull(ladder.dropped(10_000L + it, atFloor = false)) }
        assertFalse(ladder.saving)
        var stepped: QualityLadder.Level? = null
        for (i in 0 until 4) stepped = ladder.dropped(20_000L + i * 100, atFloor = true) ?: stepped
        assertEquals(QualityLadder.Level(1920, 60), stepped)
        assertTrue(ladder.saving)
        // Not again at once: each step restarts the encoder.
        repeat(8) { assertNull(ladder.dropped(21_000L + it * 100, atFloor = true)) }
        for (i in 0 until 4) ladder.dropped(24_000L + i * 100, atFloor = true)
        assertEquals(2, ladder.index)
        assertEquals(QualityLadder.Level(1280, 30), ladder.level)
        for (i in 0 until 4) ladder.dropped(28_000L + i * 100, atFloor = true)
        assertEquals(3, ladder.index)
        // The bottom is the bottom.
        for (i in 0 until 8) assertNull(ladder.dropped(32_000L + i * 100, atFloor = true))
    }

    @Test
    fun comesBackAfterFifteenCleanSeconds() {
        val ladder = QualityLadder()
        for (i in 0 until 4) ladder.dropped(1_000L + i, atFloor = true)
        assertEquals(1, ladder.index)
        assertNull(ladder.clean(10_000))
        assertEquals(QualityLadder.Level(2560, 60), ladder.clean(1_003L + QualityLadder.UP_CLEAN_MS))
        assertFalse(ladder.saving)
        assertNull(ladder.clean(100_000), "already at the top")
    }

    private fun assertNull(value: Any?, message: String) = org.junit.Assert.assertNull(message, value)
}
