// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R67: words drawn as pixels (a photo, a map, a Flutter app) become elements the AI can find. */
class PixelTextTest {
    private val screen = ScreenInfo(1080, 2400)

    private fun node(id: String, bounds: Rect, text: String? = null, parent: String? = null, desc: String? = null, sensitive: Boolean = false) =
        UiNode(id = id, parent = parent, role = "View", text = text, description = desc, bounds = bounds, sensitive = sensitive)

    private val full = Rect(0, 0, 1080, 2400)

    private fun wordy() = listOf(
        node("n0", full),
        node("n1", Rect(0, 100, 1080, 200), "Title", "n0"),
        node("n2", Rect(0, 200, 1080, 300), "Settings", "n0"),
        node("n3", Rect(0, 300, 1080, 400), "Battery", "n0"),
    )

    @Test fun aScreenOfWordsNeedsNoReading() {
        assertFalse(PixelText.needed(wordy(), screen))
    }

    @Test fun aScreenWithAlmostNoWordsIsRead() {
        assertTrue(PixelText.needed(listOf(node("n0", full), node("n1", Rect(0, 0, 100, 100), "X", "n0")), screen))
    }

    @Test fun aLargePictureWithoutWordsIsRead() {
        val photo = node("n4", Rect(0, 400, 1080, 2000), desc = "Photo", parent = "n0")
        assertTrue(PixelText.needed(wordy() + photo, screen))
    }

    @Test fun linesBecomeTappableTextOnTheElementTheyAreDrawnOn() {
        val photo = node("n4", Rect(0, 400, 1080, 2000), desc = "Photo", parent = "n0")
        val lines = listOf(TextLine("QX7-PLM-42", Rect(100, 900, 500, 960)))
        val added = PixelText.nodes(lines, wordy() + photo, screen, topInset = 80, firstIndex = 5, limit = 100)
        assertEquals(1, added.size)
        assertEquals("n5", added[0].id)
        assertEquals("n4", added[0].parent)
        assertEquals("QX7-PLM-42", added[0].text)
        assertEquals(true, added[0].ocr)
    }

    @Test fun theStatusBarSensitiveFieldsAndWordsAlreadyListedAreLeftOut() {
        val password = node("n4", Rect(0, 1000, 1080, 1100), sensitive = true, parent = "n0")
        val lines = listOf(
            TextLine("18:45", Rect(20, 10, 120, 50)),
            TextLine("hunter2", Rect(50, 1020, 300, 1080)),
            TextLine("Battery", Rect(10, 310, 300, 390)),
            TextLine("Amar Jawan Jyoti Rd", Rect(200, 1500, 700, 1550)),
            TextLine("x", Rect(200, 1600, 220, 1620)),
        )
        val added = PixelText.nodes(lines, wordy() + password, screen, topInset = 80, firstIndex = 5, limit = 100)
        assertEquals(listOf("Amar Jawan Jyoti Rd"), added.map { it.text })
    }

    @Test fun boundsAreKeptOnTheScreenAndTheLimitHolds() {
        val lines = (0 until 5).map { TextLine("line $it", Rect(-20, 500 + it * 60, 1200, 550 + it * 60)) }
        val added = PixelText.nodes(lines, wordy(), screen, topInset = 80, firstIndex = 5, limit = 2)
        assertEquals(2, added.size)
        assertEquals(Rect(0, 500, 1080, 550), added[0].bounds)
    }
}
