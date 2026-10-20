// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

/**
 * Turns the owner's pointer on the mirror page into accessibility gestures
 * (R30). Android performs a gesture only once it is fully described, so:
 *
 * - A quick press (released within [liveAfterMs]) becomes one stroke with the
 *   real path and duration: a tap, a long press, or a flick with its speed.
 * - A longer press goes live: what arrived so far is dispatched with the
 *   finger kept down, and each following piece continues that stroke
 *   (`StrokeDescription.continueStroke`) as soon as the previous one finished,
 *   paced by when the points arrived, so drags follow the mouse.
 *
 * Plain Kotlin and single-threaded: the caller dispatches each [Segment],
 * reports [done] or [failed], and calls [tick] when [liveAfterMs] passes.
 */
class StrokePlanner(private val liveAfterMs: Long = LIVE_AFTER_MS) {
    data class Point(val x: Int, val y: Int, val atMs: Long)

    /**
     * One dispatch: a path through [points] taking [durationMs]. [continues]
     * keeps the finger down afterwards; [continuation] continues the stroke
     * the previous segment left down.
     */
    data class Segment(val points: List<Pair<Int, Int>>, val durationMs: Long, val continues: Boolean, val continuation: Boolean)

    private sealed interface State
    private data object Idle : State
    private class Pending(val points: MutableList<Point>) : State
    private class Live(var last: Point, val buffered: MutableList<Point> = mutableListOf(), var inFlight: Boolean = true, var lift: Point? = null) : State

    private var state: State = Idle

    /** Whether a finger is down (pending or live). */
    val active: Boolean get() = state !is Idle

    /**
     * A press. One still down (its lift was lost) is simply forgotten: Android
     * cancels a stroke left down when the next new gesture is dispatched.
     */
    fun down(x: Int, y: Int, now: Long) {
        state = Pending(mutableListOf(Point(x, y, now)))
    }

    fun move(x: Int, y: Int, now: Long): List<Segment> {
        when (val s = state) {
            is Pending -> {
                s.points.add(Point(x, y, now))
                return if (now - s.points.first().atMs >= liveAfterMs) goLive(s, now) else emptyList()
            }
            is Live -> {
                if (s.lift != null) return emptyList()
                s.buffered.add(Point(x, y, now))
                return if (s.inFlight) emptyList() else flush(s)
            }
            Idle -> return emptyList()
        }
    }

    fun up(x: Int, y: Int, now: Long): List<Segment> {
        when (val s = state) {
            is Pending -> {
                s.points.add(Point(x, y, now))
                state = Idle
                val first = s.points.first()
                return listOf(segment(s.points, now - first.atMs, continues = false, continuation = false))
            }
            is Live -> {
                s.lift = Point(x, y, now)
                return if (s.inFlight) emptyList() else flush(s)
            }
            Idle -> return emptyList()
        }
    }

    /** The page lost the pointer: lift the finger where it is, without the last move. */
    fun cancel(now: Long): List<Segment> {
        val s = state
        state = Idle
        return when (s) {
            is Live -> if (s.inFlight) {
                // The running piece leaves the finger down; lift it once it finishes.
                state = Live(s.last, inFlight = true, lift = s.last.copy(atMs = now))
                emptyList()
            } else {
                listOf(segment(listOf(s.last), 1, continues = false, continuation = true))
            }
            else -> emptyList()
        }
    }

    /** Time passed: a press held this long goes live even without moving (a long press, the start of a drag). */
    fun tick(now: Long): List<Segment> {
        val s = state as? Pending ?: return emptyList()
        return if (now - s.points.first().atMs >= liveAfterMs) goLive(s, now) else emptyList()
    }

    /** The last segment finished; returns the next one, if anything arrived meanwhile. */
    fun done(): List<Segment> {
        val s = state as? Live ?: return emptyList()
        s.inFlight = false
        return flush(s)
    }

    /** Android cancelled the gesture (the window changed under it): the finger is up. */
    fun failed() {
        state = Idle
    }

    private fun goLive(s: Pending, now: Long): List<Segment> {
        val first = s.points.first()
        val last = s.points.last()
        state = Live(last.copy(atMs = maxOf(last.atMs, now)))
        return listOf(segment(s.points, maxOf(last.atMs, now) - first.atMs, continues = true, continuation = false))
    }

    private fun flush(s: Live): List<Segment> {
        val lift = s.lift
        if (s.buffered.isEmpty() && lift == null) return emptyList()
        val points = buildList {
            add(s.last)
            addAll(s.buffered)
            if (lift != null) add(lift)
        }
        val newest = points.last()
        s.buffered.clear()
        if (lift != null) {
            state = Idle
            return listOf(segment(points, newest.atMs - s.last.atMs, continues = false, continuation = true))
        }
        val from = s.last
        s.last = newest
        s.inFlight = true
        return listOf(segment(points, newest.atMs - from.atMs, continues = true, continuation = true))
    }

    private fun segment(points: List<Point>, durationMs: Long, continues: Boolean, continuation: Boolean): Segment {
        // Repeated points add nothing to a path; long paths keep their ends and an even sample between.
        val distinct = points.map { it.x to it.y }.fold(mutableListOf<Pair<Int, Int>>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        val path = if (distinct.size <= MAX_POINTS) distinct else List(MAX_POINTS) { i -> distinct[i * (distinct.size - 1) / (MAX_POINTS - 1)] }
        return Segment(path, durationMs.coerceIn(1, MAX_SEGMENT_MS), continues, continuation)
    }

    companion object {
        /** A press longer than this is followed live instead of replayed whole. */
        const val LIVE_AFTER_MS = 120L
        const val MAX_SEGMENT_MS = 10_000L
        const val MAX_POINTS = 64
    }
}

/**
 * The mouse wheel as a short drag that ends still, so the app scrolls by the
 * distance without flinging on: a move with the finger kept down, then a
 * pause before lifting.
 *
 * A trackpad sends many tiny steps. A drag shorter than Android's touch slop
 * is a tap to the app (owner report 2026-10-20: "whenever I scroll it clicks
 * things"), so steps below [slop] are not played (the caller keeps adding
 * them up), the drag is lengthened by the slop the app spends before it
 * starts scrolling, an axis that moved less than the slop does not move, and
 * the finger stays out of the screen's edges, where a drag opens the
 * notification shade or goes home.
 */
object WheelGesture {
    const val MOVE_MS = 120L
    const val SETTLE_MS = 60L
    /** Android's touch slop on a typical phone (8 dp at 3×), for callers without a context. */
    const val DEFAULT_SLOP = 24

    /** The two segments for scrolling [dx], [dy] phone pixels at [x], [y] on a [width]×[height] screen; empty when too little to scroll. */
    fun plan(x: Int, y: Int, dx: Int, dy: Int, width: Int, height: Int, slop: Int = DEFAULT_SLOP): List<StrokePlanner.Segment> {
        val min = maxOf(1, slop)
        if (kotlin.math.abs(dx) < min && kotlin.math.abs(dy) < min) return emptyList()
        val (fromX, toX) = axis(x, if (kotlin.math.abs(dx) < min) 0 else dx, width, min)
        val (fromY, toY) = axis(y, if (kotlin.math.abs(dy) < min) 0 else dy, height, min)
        if (kotlin.math.abs(toX - fromX) < min && kotlin.math.abs(toY - fromY) < min) return emptyList()
        return listOf(
            StrokePlanner.Segment(listOf(fromX to fromY, toX to toY), MOVE_MS, continues = true, continuation = false),
            StrokePlanner.Segment(listOf(toX to toY), SETTLE_MS, continues = false, continuation = true),
        )
    }

    /** Whether [dx], [dy] is enough to scroll on at least one axis (more than a tap). */
    fun enough(dx: Int, dy: Int, slop: Int = DEFAULT_SLOP): Boolean =
        kotlin.math.abs(dx) >= maxOf(1, slop) || kotlin.math.abs(dy) >= maxOf(1, slop)

    /** Start and end on one axis: the content follows the finger, so scrolling down moves it up. */
    private fun axis(at: Int, d: Int, size: Int, slop: Int): Pair<Int, Int> {
        val edge = minOf(maxOf(2 * slop, size * EDGE_SHARE / 100), (size - 1) / 2)
        val low = edge
        val high = size - 1 - edge
        var from = at.coerceIn(low, high)
        if (d == 0) return from to from
        val travel = minOf(high - low, kotlin.math.abs(d) + slop)
        var to = if (d > 0) from - travel else from + travel
        // Slide the whole drag back inside instead of cutting it short.
        if (to < low) { from += low - to; to = low }
        if (to > high) { from -= to - high; to = high }
        return from to to
    }

    /** Kept free at each edge: the status bar and the gesture bar. */
    private const val EDGE_SHARE = 6
}
