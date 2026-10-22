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
        for (line in lines) {
            if (out.size >= minOf(limit, MAX_LINES)) break
            val b = Rect(
                line.bounds.left.coerceIn(0, screen.width), line.bounds.top.coerceIn(0, screen.height),
                line.bounds.right.coerceIn(0, screen.width), line.bounds.bottom.coerceIn(0, screen.height),
            )
            val text = line.text.trim()
            if (b.isEmpty || text.length < 2 || b.centerY < topInset) continue
            if (existing.any { it.sensitive && overlaps(it.bounds, b) }) continue
            val folded = text.lowercase()
            val said = existing.any { n ->
                words(n) && n.bounds.contains(b.centerX, b.centerY) &&
                    listOfNotNull(n.text, n.description, n.label).any { it.lowercase().contains(folded) }
            }
            if (said) continue
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

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
