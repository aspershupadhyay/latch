// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R35: the encoder's numbers for the page's stats overlay. */
class StreamStatsTest {
    @Test
    fun countsASecondAndStartsAgain() {
        val stats = StreamStats()
        stats.frame(1_000, key = true, sent = true, encodeUs = 6_000)
        stats.frame(500, key = false, sent = true, encodeUs = 10_000)
        stats.frame(500, key = false, sent = false, encodeUs = 1_000)
        // A timestamp on another clock is left out of the encode time.
        stats.frame(500, key = false, sent = true, encodeUs = -5)
        val s = stats.take()
        assertEquals(3, s.frames)
        assertEquals(1, s.keyFrames)
        assertEquals(1, s.drops)
        assertEquals(16L, s.kbits)
        assertEquals(8.0, s.encodeAvgMs!!, 0.001)
        assertEquals(10.0, s.encodeMaxMs!!, 0.001)
        val empty = stats.take()
        assertEquals(0, empty.frames)
        assertNull(empty.encodeAvgMs)
    }

    @Test
    fun theMessageCarriesTheNumbersAndThePongTheClock() {
        val s = StreamStats.Snapshot(60, 1, 2, 8_200, 6.14, 12.0)
        val m = Json.parseToJsonElement(MirrorMessages.stats(s, 8_000, 1080, 2400, "h264")).jsonObject
        assertEquals("stats", m["t"].toString().trim('"'))
        assertEquals("6.1", m["encode_ms"].toString())
        assertEquals("8000", m["target_kbps"].toString())
        val pong = Json.parseToJsonElement(MirrorMessages.pong(12.5, 99L)).jsonObject
        assertEquals("99", pong["phone_us"].toString())
        assertEquals(MirrorIn.Stats(true), MirrorMessages.parse("""{"t":"stats","on":true}"""))
    }
}
