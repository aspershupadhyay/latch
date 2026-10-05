// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap is the owner's only when the AI is not acting; logged once per app in a while. */
class TouchAttributionTest {
    @Test
    fun tapsDuringAndJustAfterACommandAreTheAis() {
        var now = 100_000L
        val t = TouchAttribution { now }
        t.aiStarted()
        assertFalse(t.isOwners("com.whatsapp"))
        t.aiFinished()
        now += TouchAttribution.AI_SETTLE_MS - 1
        assertFalse(t.isOwners("com.whatsapp"))
        now += 2
        assertTrue(t.isOwners("com.whatsapp"))
        // Not again for the same app soon after; another app is logged.
        now += 1_000
        assertFalse(t.isOwners("com.whatsapp"))
        assertTrue(t.isOwners("com.android.chrome"))
        now += TouchAttribution.OWNER_LOG_GAP_MS
        assertTrue(t.isOwners("com.whatsapp"))
    }

    @Test
    fun whileTheAiWaitsForTheOwnerTheirTapsAreLogged() {
        var now = 100_000L
        val t = TouchAttribution { now }
        val logged = mutableListOf<String>()
        t.aiStarted()
        t.tapped("com.bank")
        assertTrue("no session listener: nothing logged", logged.isEmpty())
        t.onOwnerTap = { logged += it }
        t.tapped("com.bank")
        assertTrue(logged.isEmpty())
        t.aiPaused()
        t.tapped("com.bank")
        assertEquals(listOf("com.bank"), logged)
    }
}
