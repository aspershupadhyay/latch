// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import io.github.aspershupadhyay.latch.protocol.Rect
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.UiNode

/**
 * Words drawn as pixels, added to an observation (R67). Pure, so it is tested
 * on the JVM: [needed] decides from the element list whether reading the
 * picture is worth its time, and [nodes] turns the lines read into elements.
 */
object PixelText {
    /** A wordless area at least this share of the screen is read. */
    private const val AREA_SHARE = 0.12
    /** Fewer elements with words than this: the app draws its own screen (Flutter, a game). */
    private const val FEW_WORDS = 3
    private val DRAWN_ROLES = setOf("SurfaceView", "TextureView", "GLSurfaceView")
    /** At most this many lines are added, so a page of print does not crowd out the app's elements. */
    const val MAX_LINES = 60
    /** Turbo fix 5 (R68): lines the reader is less sure of than this are noise ("Lauy's"). */
    private const val MIN_CONFIDENCE = 0.6f
    /** Lines shorter than this (after cleaning) are fragments of icons and logos. */
    private const val MIN_CHARS = 3
    /** A card or row with words of its own is at most this share of the screen; lines inside one repeat or garble them. */
    private const val LABELLED_SHARE = 0.25

    private fun words(n: UiNode) = !n.sensitive && (!n.text.isNullOrBlank() || !n.description.isNullOrBlank() || !n.label.isNullOrBlank())

    private fun area(r: Rect) = if (r.isEmpty) 0L else (r.right - r.left).toLong() * (r.bottom - r.top)

    /**
     * True when the screen likely shows words the element list lacks: almost
     * no element has words, or a large element (a picture, a map, a canvas)
     * has no text of its own and nothing with words inside it.
     */
    fun needed(nodes: Collection<UiNode>, screen: ScreenInfo): Boolean {
        if (screen.width <= 0 || screen.height <= 0) return false
        return nodes.count(::words) < FEW_WORDS || drawnArea(nodes, screen)
    }

    /**
     * True when part of the screen is drawn as pixels the element list does not
     * describe: a video, camera, or game surface, or a large element (a map, a
     * picture) with no text of its own and nothing with words inside it. An
     * action there can change the screen without changing any element.
     */
    fun drawnArea(nodes: Collection<UiNode>, screen: ScreenInfo): Boolean {
        if (screen.width <= 0 || screen.height <= 0) return false
        if (nodes.any { it.role in DRAWN_ROLES }) return true
        val byId = nodes.associateBy { it.id }
        val holdsWords = HashSet<String>()
        for (n in nodes) {
            if (!words(n)) continue
            var parent = n.parent
            var hops = 0
            while (parent != null && hops++ < 64 && holdsWords.add(parent)) parent = byId[parent]?.parent
        }
        val big = screen.width.toLong() * screen.height * AREA_SHARE
        return nodes.any { n ->
            !n.sensitive && n.text.isNullOrBlank() && n.id !in holdsWords && area(n.bounds) >= big
        }
    }

    /**
     * The [lines] read from the screen as elements, numbered from [firstIndex].
     * Left out: lines in the status bar (above [topInset]) or off the screen,
     * lines touching a sensitive element (its redaction holds for the picture
     * too), and lines an element already says where they were drawn. Each
     * hangs on the smallest element it was drawn on.
     */
    fun nodes(lines: List<TextLine>, existing: Collection<UiNode>, screen: ScreenInfo, topInset: Int, firstIndex: Int, limit: Int): List<UiNode> {
        val out = ArrayList<UiNode>()
        val labelled = labelledAreas(existing, screen)
        for (line in lines) {
            if (out.size >= minOf(limit, MAX_LINES)) break
            if (line.confidence < MIN_CONFIDENCE) continue
            val b = Rect(
                line.bounds.left.coerceIn(0, screen.width), line.bounds.top.coerceIn(0, screen.height),
                line.bounds.right.coerceIn(0, screen.width), line.bounds.bottom.coerceIn(0, screen.height),
            )
            val text = clean(line.text)
            if (b.isEmpty || text.count { it.isLetterOrDigit() } < 2 || text.count { !it.isWhitespace() } < MIN_CHARS || b.centerY < topInset) continue
            if (existing.any { it.sensitive && overlaps(it.bounds, b) }) continue
            // Inside a card or row that already has words: the tree has it, the picture only garbles it.
            if (labelled.any { mostlyInside(b, it) }) continue
            val folded = fold(text)
            // Words an element on the same spot already says ("Food & Drink…" read off its own chip).
            val said = existing.any { n ->
                words(n) && (n.bounds.contains(b.centerX, b.centerY) || mostlyInside(b, n.bounds)) &&
                    listOfNotNull(n.text, n.description, n.label).map(::fold).any { it.contains(folded) || (it.length >= 3 && folded.contains(it)) }
            }
            if (said) continue
            if (out.any { fold(it.text.orEmpty()) == folded && overlaps(it.bounds, b) }) continue
            val under = existing.filter { it.bounds.contains(b.centerX, b.centerY) }.minByOrNull { area(it.bounds) }
            out += UiNode(
                id = "n${firstIndex + out.size}",
                parent = under?.id,
                role = "Text",
                text = text,
                bounds = b,
                ocr = true,
            )
        }
        return out
    }

    /**
     * Bounds of the small elements that have words, or hold an element that has
     * them: a product card, a list row. Big ones (a map, a page) are left out,
     * so their drawn words are still read.
     */
    private fun labelledAreas(nodes: Collection<UiNode>, screen: ScreenInfo): List<Rect> {
        val byId = nodes.associateBy { it.id }
        val holds = HashSet<String>()
        for (n in nodes) {
            if (!words(n) || n.ocr == true) continue
            holds += n.id
            var parent = n.parent
            var hops = 0
            while (parent != null && hops++ < 64 && holds.add(parent)) parent = byId[parent]?.parent
        }
        val small = (screen.width.toLong() * screen.height * LABELLED_SHARE).toLong()
        return nodes.filter { it.id in holds && area(it.bounds) in 1..small }.map { it.bounds }
    }

    /** Letters and digits only, lower case: "Food & Drink…" and "food drink" fold alike. */
    internal fun fold(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    /** Single capital letters the Latin model reads off an icon before a label: "H Tickets", "K Share". */
    private val ICON_LETTER = Regex("^[B-HJ-Z]\\s+(?=\\p{Lu}\\p{Ll})")

    /**
     * A recognised line without what icons turn into: rating stars ("4.6 **ttt",
     * "46t*tt*"), a letter for an icon before a label, and dashes or bullets
     * around a map label ("-Amar Jawan Jyoti Rd-").
     */
    internal fun clean(raw: String): String {
        val kept = raw.trim().split(Regex("\\s+"))
            .filterNot { word -> word.any { it in STAR_CHARS } }
            .joinToString(" ") { rupee(it) }
        return kept.replace(ICON_LETTER, "").trim { it.isWhitespace() || it in EDGE_CHARS }
    }

    /** A rupee sign the Latin model misreads, before an amount ("F36", "Z120", "स49"). */
    private val MISREAD_RUPEE = Regex("^[FZस३](\\d+(?:[.,]\\d{1,2})?)$")

    /** Turbo fix 6 (R68): "F36" → "₹36". A 7 before digits stays: "736" may be a real number. */
    internal fun rupee(word: String): String = MISREAD_RUPEE.matchEntire(word)?.let { "₹" + it.groupValues[1] } ?: word

    private const val STAR_CHARS = "*★☆✩✪✫✬✭✮✯"
    private const val EDGE_CHARS = "-–—_|•·~"

    /** Most of [inner] (a line) lies inside [outer] (an element). */
    private fun mostlyInside(inner: Rect, outer: Rect): Boolean {
        val w = minOf(inner.right, outer.right) - maxOf(inner.left, outer.left)
        val h = minOf(inner.bottom, outer.bottom) - maxOf(inner.top, outer.top)
        return w > 0 && h > 0 && w.toLong() * h * 2 >= area(inner)
    }

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
