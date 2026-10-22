// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.UiNode
import kotlin.math.abs
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

    /** A card or row is at most this share of the screen; a bigger container is the page itself. */
    private const val CARD_SHARE = 0.4
    private const val MAX_CARD_HOPS = 6
    private const val MAX_CARD_CHARS = 60

    /**
     * Names for tappable controls without words (Turbo fix 1, R68): each takes
     * the words of the card or row it sits in, after its own guessed name or
     * what it is: "+ · Mother Dairy Cow Milk 500 ml". The card is the nearest
     * container no bigger than [CARD_SHARE] of the screen that shows words
     * other than the control's own; its name is its longest such text. In a
     * quantity stepper (two unnamed controls on one row around a number) the
     * left one is "−" and the right one "+". Sensitive elements are never
     * named or used as names. Returns id → label for the controls named.
     */
    fun cardNames(nodes: Collection<UiNode>, screen: ScreenInfo): Map<String, String> {
        val byId = nodes.associateBy { it.id }
        val children = HashMap<String, MutableList<UiNode>>()
        for (n in nodes) n.parent?.let { children.getOrPut(it) { ArrayList() }.add(n) }
        val unnamed = nodes.filter(::unnamedControl)
        if (unnamed.isEmpty()) return emptyMap()
        val maxCard = screen.width.toLong() * screen.height * CARD_SHARE
        val out = HashMap<String, String>()
        for (control in unnamed) {
            var card: UiNode? = null
            var cardWords: String? = null
            var parent = control.parent?.let(byId::get)
            var hops = 0
            while (parent != null && hops++ < MAX_CARD_HOPS && area(parent.bounds) <= maxCard) {
                val words = wordsUnder(parent, control.id, children)
                if (words != null) {
                    card = parent
                    cardWords = words
                    break
                }
                parent = parent.parent?.let(byId::get)
            }
            if (card == null || cardWords == null) continue
            val head = stepperSide(control, card, children) ?: control.label ?: if (control.role.contains("Image")) "icon" else "button"
            out[control.id] = "$head · $cardWords"
        }
        return out
    }

    private fun unnamedControl(n: UiNode) =
        (n.clickable || n.longClickable) && !n.sensitive && !n.editable && n.text.isNullOrBlank() && n.description.isNullOrBlank()

    private fun words(n: UiNode): String? = if (n.sensitive || n.ocr == true) null else (n.text ?: n.description)?.takeIf { it.isNotBlank() }

    /** The longest words shown inside [container], outside [skip]'s own subtree; null when there are none. */
    private fun wordsUnder(container: UiNode, skip: String, children: Map<String, List<UiNode>>): String? {
        var best: String? = null
        var bestLetters = 0
        val queue = ArrayDeque(listOf(container))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 200) {
            val n = queue.removeFirst()
            if (n.id == skip) continue
            words(n)?.let { w ->
                val letters = w.count { it.isLetter() }
                if (letters >= 3 && letters > bestLetters) {
                    best = w
                    bestLetters = letters
                }
            }
            children[n.id]?.let(queue::addAll)
        }
        return best?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")?.take(MAX_CARD_CHARS)
    }

    /** "−" or "+" when [control] is one end of a quantity stepper in [card]: two unnamed controls on one row around a number. */
    private fun stepperSide(control: UiNode, card: UiNode, children: Map<String, List<UiNode>>): String? {
        val inCard = ArrayList<UiNode>()
        val queue = ArrayDeque(listOf(card))
        while (queue.isNotEmpty() && inCard.size < 200) {
            val n = queue.removeFirst()
            inCard += n
            children[n.id]?.let(queue::addAll)
        }
        val row = control.bounds.centerY
        val half = (control.bounds.bottom - control.bounds.top) / 2
        fun sameRow(n: UiNode) = abs(n.bounds.centerY - row) <= maxOf(half, 1)
        val ends = inCard.filter { unnamedControl(it) && sameRow(it) }
        if (ends.size != 2 || control !in ends) return null
        val (left, right) = ends.sortedBy { it.bounds.centerX }
        val number = inCard.any { n ->
            sameRow(n) && n.text?.trim()?.matches(Regex("\\d{1,3}")) == true &&
                n.bounds.centerX > left.bounds.centerX && n.bounds.centerX < right.bounds.centerX
        }
        if (!number) return null
        return if (control == left) "−" else "+"
    }

    private fun area(r: Rect) = if (r.isEmpty) 0L else (r.right - r.left).toLong() * (r.bottom - r.top)

    /** Whether an element is under one of the windows drawn above its own: its centre lies inside one. */
    fun covered(element: Rect, windowsAbove: List<Rect>): Boolean {
        val x = element.left + (element.right - element.left) / 2
        val y = element.top + (element.bottom - element.top) / 2
        return windowsAbove.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
    }
}
