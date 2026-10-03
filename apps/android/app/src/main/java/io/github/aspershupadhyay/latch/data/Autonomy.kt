package io.github.aspershupadhyay.latch.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the AI may use an app right now. */
enum class AppDecision { ALLOWED, ASK }

/** The owner's app switches, as one snapshot for the screens. */
data class AutonomyState(
    /** Apps switched on in Access → Apps (kept on the phone). */
    val allowed: Set<String> = emptySet(),
    /** Apps allowed until the session ends ("This session" on the app question). */
    val sessionApps: Set<String> = emptySet(),
    /**
     * The owner's opt-in (ADR-022): in switched-on apps, payments, installs,
     * Android permission prompts, and account deletion run without asking too.
     */
    val trustCritical: Boolean = false,
)

/**
 * The owner's app switches (ADR-021).
 *
 * The AI works only in apps the owner switched on. In those apps it acts
 * without asking, including sending, posting, deleting, and calling: the
 * owner approved the app, not every tap in it. The first time the AI needs an
 * app that is off, the phone asks once ("This session" or "Always", which
 * switches it on). Critical actions (payments, installs, Android permission
 * prompts, account deletion) still ask on the phone every time; the
 * executor enforces that.
 *
 * Kept on the phone only. "This session" answers end with the session.
 */
class Autonomy(private val store: Store) {
    interface Store {
        fun load(): Set<String>
        fun save(allowed: Set<String>)
        fun loadTrustCritical(): Boolean = false
        fun saveTrustCritical(value: Boolean) = Unit
    }

    private val _state = MutableStateFlow(AutonomyState(allowed = store.load(), trustCritical = store.loadTrustCritical()))
    val state: StateFlow<AutonomyState> = _state.asStateFlow()

    fun decide(packageName: String): AppDecision {
        val s = _state.value
        return if (packageName in s.allowed || packageName in s.sessionApps) AppDecision.ALLOWED else AppDecision.ASK
    }

    fun setAllowed(packageName: String, on: Boolean) {
        val s = _state.value
        val allowed = when {
            on && s.allowed.size >= MAX_APPS -> return
            on -> s.allowed + packageName
            else -> s.allowed - packageName
        }
        // Switching an app off also ends a "this session" answer for it.
        val next = s.copy(allowed = allowed, sessionApps = if (on) s.sessionApps else s.sessionApps - packageName)
        if (next == s) return
        if (next.allowed != s.allowed) store.save(next.allowed)
        _state.value = next
    }

    fun allowForSession(packageName: String) {
        _state.value = _state.value.let { it.copy(sessionApps = it.sessionApps + packageName) }
    }

    fun setTrustCritical(value: Boolean) {
        if (_state.value.trustCritical == value) return
        store.saveTrustCritical(value)
        _state.value = _state.value.copy(trustCritical = value)
    }

    /** Every app off, and payments and the like ask again. */
    fun clearAll() {
        store.save(emptySet())
        store.saveTrustCritical(false)
        _state.value = AutonomyState()
    }

    /** "This session" answers end with the session (stop, expiry, or a new start). */
    fun endSession() {
        _state.value = _state.value.copy(sessionApps = emptySet())
    }

    companion object {
        const val MAX_APPS = 500
    }
}

/** SharedPreferences store. Session answers are never written. */
class PrefsAutonomyStore(context: Context) : Autonomy.Store {
    private val prefs = context.getSharedPreferences("latch_autonomy", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet("allowed", emptySet())?.toSet() ?: emptySet()

    override fun save(allowed: Set<String>) {
        prefs.edit { putStringSet("allowed", allowed) }
    }

    override fun loadTrustCritical(): Boolean = prefs.getBoolean("trust_critical", false)

    override fun saveTrustCritical(value: Boolean) {
        prefs.edit { putBoolean("trust_critical", value) }
    }
}
