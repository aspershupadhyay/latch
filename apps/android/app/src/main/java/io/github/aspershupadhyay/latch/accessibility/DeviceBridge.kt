// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands the running accessibility service to the session, and tells the UI
 * whether the owner has the service switched on.
 */
class DeviceBridge {
    /** Tells the owner's own taps from the AI's (Activity "You" / "AI"). */
    val touches = TouchAttribution()

    private val _service = MutableStateFlow<LatchAccessibilityService?>(null)
    val service: StateFlow<LatchAccessibilityService?> = _service.asStateFlow()

    fun attach(service: LatchAccessibilityService) {
        _service.value = service
    }

    fun detach(service: LatchAccessibilityService) {
        _service.compareAndSet(service, null)
    }
}

/**
 * Decides whether a tap Android reports was the owner's own finger. While a
 * command runs, and for [AI_SETTLE_MS] after it, taps count as the AI's (its
 * own clicks are reported the same way); [aiPaused] hands the screen back to
 * the owner while the AI waits for them (ask_owner). Only the app name is
 * logged, at most once per app every [OWNER_LOG_GAP_MS]; never what was tapped.
 */
class TouchAttribution(private val now: () -> Long = android.os.SystemClock::uptimeMillis) {
    @Volatile private var aiRunning = false
    @Volatile private var aiQuietAtMs = 0L
    private val lastLogged = HashMap<String, Long>()

    /** Set while a session runs; null otherwise, so nothing is logged outside a session. */
    @Volatile var onOwnerTap: ((String) -> Unit)? = null

    fun aiStarted() {
        aiRunning = true
    }

    fun aiFinished() {
        aiRunning = false
        aiQuietAtMs = now() + AI_SETTLE_MS
    }

    /** The AI waits for the owner: taps from now on are theirs. */
    fun aiPaused() {
        aiRunning = false
        aiQuietAtMs = 0
    }

    /** Android reported a tap in [packageName]; logs it as the owner's when it was. */
    fun tapped(packageName: String) {
        val listener = onOwnerTap ?: return
        if (isOwners(packageName)) listener(packageName)
    }

    @Synchronized
    internal fun isOwners(packageName: String): Boolean {
        val t = now()
        if (aiRunning || t < aiQuietAtMs) return false
        val last = lastLogged[packageName]
        if (last != null && t - last < OWNER_LOG_GAP_MS) return false
        lastLogged[packageName] = t
        return true
    }

    companion object {
        const val AI_SETTLE_MS = 1_500L
        const val OWNER_LOG_GAP_MS = 30_000L
    }
}
