// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-044: names for icons, the apps' own actions, slider values, and covered elements. */
class TreeReadingTest {
    @Test fun aTooltipOrTheLabellingElementNamesAnIconFirst() {
        assertEquals("Like", TreeReading.guessLabel("Like", "Favourite", "com.example:id/heart"))
        assertEquals("Search people", TreeReading.guessLabel(null, "  Search\n people ", "com.example:id/q"))
    }

    @Test fun aResourceIdIsSplitIntoWords() {
        assertEquals("row feed button like", TreeReading.guessLabel(null, null, "com.instagram.android:id/row_feed_button_like"))
        assertEquals("btn sign out", TreeReading.guessLabel(null, null, "com.example:id/btnSignOut"))
        assertEquals("send", TreeReading.guessLabel(null, null, "send"))
    }

    @Test fun genericOrObfuscatedIdsGiveNoName() {
        assertNull(TreeReading.guessLabel(null, null, "com.example:id/image_view"))
        assertNull(TreeReading.guessLabel(null, null, "com.example:id/a1"))
        assertNull(TreeReading.guessLabel(null, null, "com.example:id/xq7"))
        assertNull(TreeReading.guessLabel(null, null, "com.example:id/"))
        assertNull(TreeReading.guessLabel("  ", "", null))
    }

    @Test fun longNamesAreShortened() {
        assertEquals(60, TreeReading.guessLabel("x".repeat(200), null, null)!!.length)
    }

    @Test fun onlyTheAppsOwnActionsAreKept() {
        val actions = listOf(
            16 to null, // click
            0x20 to "Double tap to like", // long click relabelled: still Android's
            0x01020036 to "Show on screen", // android.R.id.accessibilityActionShowOnScreen
            0x7f0a0001 to "Like",
            0x7f0a0002 to " Reply\n",
            0x7f0a0003 to "Like", // duplicate
            0x7f0a0004 to "", // no name
            0x7f0a0005 to null,
        )
        assertEquals(listOf("Like", "Reply"), TreeReading.ownActions(actions))
    }

    @Test fun atMostSixteenActionsAreKept() {
        val many = (1..40).map { 0x7f0a0000 + it to "Action $it" }
        assertEquals(TreeReading.MAX_ACTIONS, TreeReading.ownActions(many).size)
    }

    @Test fun rangesBecomePercentages() {
        assertEquals(70, TreeReading.percentOf(0f, 100f, 70f))
        assertEquals(50, TreeReading.percentOf(0f, 1f, 0.5f))
        assertEquals(25, TreeReading.percentOf(-10f, 10f, -5f))
        assertEquals(100, TreeReading.percentOf(0f, 10f, 12f))
        assertEquals(0, TreeReading.percentOf(0f, 10f, -3f))
        assertNull(TreeReading.percentOf(5f, 5f, 5f))
        assertNull(TreeReading.percentOf(0f, 10f, Float.NaN))
    }

    @Test fun anElementIsCoveredWhenItsCentreIsUnderAWindowAbove() {
        val menu = Rect(600, 300, 1040, 700)
        assertTrue(TreeReading.covered(Rect(500, 400, 900, 500), listOf(menu)))
        assertFalse(TreeReading.covered(Rect(0, 400, 500, 500), listOf(menu)))
        assertFalse(TreeReading.covered(Rect(500, 400, 900, 500), emptyList()))
    }
}
