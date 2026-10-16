// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import kotlin.math.roundToInt

/**
 * Pure rules for reading more of the accessibility tree (ADR-044, roadmap R26),
 * kept apart from Android types so they are unit-tested on the JVM.
 */
object TreeReading {
    /** At most this many of an element's own actions are sent (the protocol's limit). */
    const val MAX_ACTIONS = 16
    private const val MAX_ACTION_CHARS = 80
    private const val MAX_LABEL_CHARS = 60

    /** Words that alone say nothing about what a control does. */
    private val GENERIC = setOf(
        "button", "btn", "image", "img", "icon", "ic", "view", "layout", "container", "content", "root", "frame",
        "item", "row", "cell", "wrapper", "holder", "main", "action", "text", "txt", "label", "iv", "tv", "fl", "ll",
        "rl", "cl", "id", "widget", "touch", "area", "parent", "child", "inner", "outer", "bg", "background",
    )

    /**
     * A name for an element that has no text or description, from what the app
     * set up for it: its tooltip, the text of the element that labels it, or
     * its resource id split into words (`row_feed_button_like` →
     * "row feed button like"). Null when none of them says anything: an id of
     * only generic or obfuscated parts (`a1`, `image_view`) gives nothing.
     */
    fun guessLabel(tooltip: String?, labelledBy: String?, resourceId: String?): String? {
        clean(tooltip)?.let { return it }
        clean(labelledBy)?.let { return it }
        val entry = resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        val words = entry
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(Regex("[^A-Za-z]+"))
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
        val meaningful = words.filter { it.length >= 3 && it !in GENERIC && it.any { c -> c in "aeiouy" } }
        if (meaningful.isEmpty()) return null
        return clean(words.joinToString(" "))
    }

    private fun clean(value: String?): String? {
        val single = value?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: return null
        if (single.isEmpty()) return null
        return if (single.length > MAX_LABEL_CHARS) single.take(MAX_LABEL_CHARS) else single
    }

    /**
     * The app's own actions on an element, from (id, label) pairs: the ones
     * Android defines (click, scroll, copy …) are left out, since the AI has
     * tools for those. Standard actions use the legacy bit ids (below 2^22) or
     * the framework's `android.R.id.accessibilityAction*` range; an app's own
     * actions use other ids. Labels are trimmed, duplicates dropped.
     */
    fun ownActions(actions: List<Pair<Int, CharSequence?>>): List<String> {
        val out = LinkedHashSet<String>()
        for ((id, label) in actions) {
            if (isStandard(id)) continue
            val name = label?.toString()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: continue
            if (name.isEmpty()) continue
            out += name.take(MAX_ACTION_CHARS)
            if (out.size == MAX_ACTIONS) break
        }
        return out.toList()
    }

    private fun isStandard(id: Int): Boolean =
        (id > 0 && id < (1 shl 22) && Integer.bitCount(id) == 1) || (id ushr 16) == 0x0102

    /** A range's current value as a whole percentage of the range, or null when the range is empty. */
    fun percentOf(min: Float, max: Float, current: Float): Int? {
        if (!(max > min) || current.isNaN()) return null
        return ((current - min) / (max - min) * 100f).roundToInt().coerceIn(0, 100)
    }

    /** Whether an element is under one of the windows drawn above its own: its centre lies inside one. */
    fun covered(element: Rect, windowsAbove: List<Rect>): Boolean {
        val x = element.left + (element.right - element.left) / 2
        val y = element.top + (element.bottom - element.top) / 2
        return windowsAbove.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
    }
}
