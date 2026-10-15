// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.UiNode

/**
 * Decides which elements of one observation only repeat an element already listed, so the
 * agent sees each thing once (roadmap R12). Apps like Google Maps nest an element in a
 * container that says and does the same, put a label inside a button that repeats the
 * button's words, draw a plain label over a button, or stack two identical controls.
 * A dropped element's children hang on the kept one. Sensitive elements are never dropped.
 */
class Repeats {
    private val controls = HashSet<UiNode>()
    private val labels = HashSet<Triple<Rect, String?, String?>>()

    /** Whether [candidate] (a child of the kept [parent], if any) adds nothing already listed. */
    fun repeats(candidate: UiNode, parent: UiNode?): Boolean {
        if (candidate.sensitive) return false
        if (parent != null && !parent.sensitive && (normal(candidate) == normal(parent) || insideAndSaysSame(parent, candidate))) return true
        return if (interactive(candidate)) {
            normal(candidate) in controls
        } else {
            labelKey(candidate)?.let { it in labels } == true
        }
    }

    /** Records a listed element so later repeats of it are dropped. */
    fun keep(node: UiNode) {
        if (node.sensitive || !interactive(node)) return
        controls += normal(node)
        labelKey(node)?.let { labels += it }
    }

    private fun insideAndSaysSame(parent: UiNode, child: UiNode): Boolean {
        val words = words(child)
        if (words.isEmpty() || !words(parent).containsAll(words)) return false
        val p = parent.bounds
        val c = child.bounds
        if (c.left < p.left || c.top < p.top || c.right > p.right || c.bottom > p.bottom) return false
        // The child may not do anything the parent doesn't: tapping the parent must be enough.
        return (!child.clickable || parent.clickable) && (!child.longClickable || parent.longClickable) &&
            (!child.scrollable || parent.scrollable) && child.editable == parent.editable &&
            (child.checked == null || child.checked == parent.checked) && child.enabled == parent.enabled
    }

    private fun words(n: UiNode) = setOfNotNull(n.text?.trim()?.lowercase(), n.description?.trim()?.lowercase())

    private fun labelKey(n: UiNode) = if (n.text != null || n.description != null) Triple(n.bounds, n.text, n.description) else null

    private fun normal(n: UiNode) = n.copy(id = "", parent = null, role = "", resourceId = null, focused = false)

    companion object {
        fun interactive(n: UiNode) = n.clickable || n.longClickable || n.editable || n.scrollable || n.checked != null
    }
}
