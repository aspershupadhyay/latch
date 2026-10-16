// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatsTest {
    private val chip = Rect(0, 100, 300, 180)

    private fun node(
        id: String,
        bounds: Rect = chip,
        text: String? = null,
        description: String? = null,
        clickable: Boolean = false,
        checked: Boolean? = null,
        sensitive: Boolean = false,
        role: String = "View",
    ) = UiNode(id = id, role = role, text = text, description = description, bounds = bounds, clickable = clickable, checked = checked, sensitive = sensitive)

    /** Runs a breadth-first list through [Repeats] the way observe does; returns the kept ids. */
    private fun kept(vararg tree: Pair<UiNode, String?>): List<String> {
        val repeats = Repeats()
        val ui = LinkedHashMap<String, UiNode>()
        for ((n, parent) in tree) {
            if (repeats.repeats(n, parent?.let(ui::get))) continue
            ui[n.id] = n
            repeats.keep(n)
        }
        return ui.keys.toList()
    }

    @Test
    fun `a Maps-like tree lists each thing once`() {
        val ids = kept(
            // A chip: clickable container described "Restaurants" ...
            node("chip", description = "Restaurants", clickable = true, role = "Button") to null,
            // ... repeated exactly by a wrapper inside it ...
            node("wrap", description = "Restaurants", clickable = true, role = "FrameLayout") to "chip",
            // ... and a smaller label inside saying the same words.
            node("label", bounds = Rect(60, 120, 240, 160), text = "Restaurants") to "chip",
            // A voice-search button and a plain label drawn exactly over it.
            node("voice", bounds = Rect(400, 0, 480, 80), description = "Voice search", clickable = true) to null,
            node("voiceLabel", bounds = Rect(400, 0, 480, 80), description = "Voice search") to null,
            // Two identical controls stacked in one spot.
            node("layers", bounds = Rect(500, 0, 580, 80), description = "Layers", clickable = true) to null,
            node("layers2", bounds = Rect(500, 0, 580, 80), description = "Layers", clickable = true) to null,
        )
        assertEquals(listOf("chip", "voice", "layers"), ids)
    }

    @Test
    fun `a child that adds words, a switch, or a tap of its own is kept`() {
        val ids = kept(
            node("row", description = "Wi-Fi", clickable = true) to null,
            node("title", bounds = Rect(10, 110, 200, 140), text = "Wi-Fi") to "row",
            node("summary", bounds = Rect(10, 140, 200, 170), text = "Home network") to "row",
            node("switch", bounds = Rect(220, 110, 290, 170), description = "Wi-Fi", checked = true) to "row",
            node("card", text = "Coffee") to null,
            node("open", bounds = Rect(10, 110, 100, 170), text = "Coffee", clickable = true) to "card",
        )
        assertEquals(listOf("row", "summary", "switch", "card", "open"), ids)
    }

    @Test
    fun `a label outside its parent or sensitive is kept`() {
        val repeats = Repeats()
        val parent = node("p", description = "Pay", clickable = true)
        repeats.keep(parent)
        assertFalse(repeats.repeats(node("out", bounds = Rect(0, 90, 300, 180), text = "Pay"), parent))
        assertFalse(repeats.repeats(node("s", description = "Pay", clickable = true, sensitive = true), parent))
        assertTrue(repeats.repeats(node("in", bounds = Rect(10, 110, 290, 170), text = "pay "), parent))
    }

    @Test
    fun `different controls in the same spot are both kept`() {
        val ids = kept(
            node("a", description = "Like", clickable = true) to null,
            node("b", description = "Liked", clickable = true) to null,
        )
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `a label that carries its own state or actions is kept (protocol 1_16)`() {
        val inner = Rect(10, 110, 290, 170)
        val ids = kept(
            node("post", description = "Photo by ada", clickable = true, role = "ViewGroup") to null,
            node("like", bounds = inner, description = "Photo by ada").copy(actions = listOf("Like")) to "post",
            node("same", bounds = inner, description = "Photo by ada") to "post",
            node("liked", bounds = inner, description = "Photo by ada").copy(state = "Liked") to "post",
        )
        assertEquals(listOf("post", "like", "liked"), ids)
    }
}
