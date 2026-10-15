// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

/**
 * Roadmap R7: how quickly each app's screen reacts to an action, so the phone
 * stops waiting for a change sooner in apps that always answer fast.
 *
 * Deliberately cautious, because stopping too early makes a slow app look
 * unchanged and an agent might repeat the action:
 * - only changes actually seen count, and only after [MIN_SAMPLES] of them;
 * - the wait is 1.5 times the slowest change seen, never below [FLOOR_MS] and
 *   never above the caller's default (today's fixed wait);
 * - a shortened wait that sees no change forgets that app's samples, so the
 *   next action waits the full default again.
 * Kept in memory only; nothing about the screen is stored, only durations.
 */
class SettleStats(private val maxKeys: Int = 64, private val window: Int = 16) {
    private val samples = object : LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>?) = size > maxKeys
    }

    /** How long to wait for a change after this kind of action in this app. */
    @Synchronized
    fun expectChange(key: String, defaultMs: Long): Long {
        val seen = samples[key] ?: return defaultMs
        if (seen.size < MIN_SAMPLES) return defaultMs
        return (seen.max() * 3 / 2).coerceIn(minOf(FLOOR_MS, defaultMs), defaultMs)
    }

    /** The screen changed [changeAfterMs] after the action. */
    @Synchronized
    fun changed(key: String, changeAfterMs: Long) {
        val seen = samples.getOrPut(key) { ArrayDeque() }
        seen.addLast(changeAfterMs.coerceAtLeast(0))
        while (seen.size > window) seen.removeFirst()
    }

    /** No change was seen within a shortened wait: start over with the full default. */
    @Synchronized
    fun missed(key: String) {
        samples.remove(key)
    }

    companion object {
        const val MIN_SAMPLES = 8
        const val FLOOR_MS = 200L
    }
}
