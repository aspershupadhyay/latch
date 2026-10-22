// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.UiNode
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

    // Turbo fix 1 (R68): unnamed controls take their card's words.
    private val screen = ScreenInfo(1080, 2400)

    private fun n(id: String, parent: String?, bounds: Rect, text: String? = null, clickable: Boolean = false, role: String = "View", label: String? = null, sensitive: Boolean = false) =
        UiNode(id = id, parent = parent, role = role, text = text, bounds = bounds, clickable = clickable, label = label, sensitive = sensitive)

    private fun card(base: Int, top: Int, name: String) = listOf(
        n("n$base", "n0", Rect(0, top, 1080, top + 300)),
        n("n${base + 1}", "n$base", Rect(40, top + 20, 700, top + 80), name),
        n("n${base + 2}", "n$base", Rect(40, top + 90, 300, top + 140), "₹36"),
        n("n${base + 3}", "n$base", Rect(700, top + 180, 1040, top + 260)),
        n("n${base + 4}", "n${base + 3}", Rect(700, top + 180, 800, top + 260), clickable = true, role = "ImageView"),
        n("n${base + 5}", "n${base + 3}", Rect(820, top + 180, 920, top + 260), "1"),
        n("n${base + 6}", "n${base + 3}", Rect(940, top + 180, 1040, top + 260), clickable = true, role = "ImageView"),
    )

    @Test fun aStepperInACardIsNamedMinusAndPlusAfterTheCard() {
        val nodes = listOf(n("n0", null, Rect(0, 0, 1080, 2400))) + card(1, 400, "Mother Dairy Cow Milk 1 L") + card(8, 720, "Mother Dairy Cow Milk 500 ml")
        val names = TreeReading.cardNames(nodes, screen)
        assertEquals("− · Mother Dairy Cow Milk 1 L", names["n5"])
        assertEquals("+ · Mother Dairy Cow Milk 1 L", names["n7"])
        assertEquals("+ · Mother Dairy Cow Milk 500 ml", names["n14"])
        assertEquals(4, names.size)
    }

    @Test fun aLoneIconKeepsItsOwnGuessAndGainsTheRow() {
        val nodes = listOf(
            n("n0", null, Rect(0, 0, 1080, 2400)),
            n("n1", "n0", Rect(0, 300, 1080, 450)),
            n("n2", "n1", Rect(40, 320, 800, 420), "Wi-Fi"),
            n("n3", "n1", Rect(900, 320, 1040, 420), clickable = true, role = "ImageButton", label = "settings gear"),
            n("n4", "n0", Rect(900, 600, 1040, 700), clickable = true, role = "ImageButton"),
        )
        val names = TreeReading.cardNames(nodes, screen)
        assertEquals("settings gear · Wi-Fi", names["n3"])
        // Its only container is the whole page: no card, no name.
        assertNull(names["n4"])
    }

    @Test fun sensitiveWordsNeverNameAControl() {
        val nodes = listOf(
            n("n0", null, Rect(0, 0, 1080, 2400)),
            n("n1", "n0", Rect(0, 300, 1080, 450)),
            n("n2", "n1", Rect(40, 320, 800, 420), sensitive = true),
            n("n3", "n1", Rect(900, 320, 1040, 420), clickable = true, role = "ImageButton"),
        )
        assertTrue(TreeReading.cardNames(nodes, screen).isEmpty())
    }
}
