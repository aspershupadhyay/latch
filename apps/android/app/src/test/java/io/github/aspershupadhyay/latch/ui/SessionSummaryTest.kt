// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSummaryTest {
    private val start = 1_000_000L
    private val end = start + 14 * 60_000L

    @Test
    fun countsOnlyTheSessionsOwnLines() {
        val log = listOf(
            ActivityEntry(start - 1, ActivityKind.ACTION, "Before", app = "Chrome", by = ActivityActor.AI),
            ActivityEntry(start + 10, ActivityKind.ACTION, "Tap", app = "Chrome", by = ActivityActor.AI),
            ActivityEntry(start + 20, ActivityKind.ACTION, "Type", app = "WhatsApp", by = ActivityActor.AI),
            ActivityEntry(start + 30, ActivityKind.ACTION, "Tap again", app = "Chrome", by = ActivityActor.AI),
            ActivityEntry(start + 40, ActivityKind.ACTION, "Owner tapped", app = "Chrome", by = ActivityActor.OWNER),
            ActivityEntry(start + 50, ActivityKind.APPROVAL, "Allowed Send", app = "WhatsApp", by = ActivityActor.OWNER),
            ActivityEntry(start + 60, ActivityKind.REFUSAL, "Password field", app = "Bank"),
            ActivityEntry(end + 1, ActivityKind.ACTION, "After", app = "Maps", by = ActivityActor.AI),
        )
        assertEquals(SessionSummary(minutes = 14, actions = 3, apps = 2, approvals = 1, refused = 1), summarizeSession(log, start, end))
    }

    @Test
    fun anEmptySessionStillHasALength() {
        assertEquals(SessionSummary(minutes = 1, actions = 0, apps = 0, approvals = 0, refused = 0), summarizeSession(emptyList(), start, start + 5_000))
    }
}
