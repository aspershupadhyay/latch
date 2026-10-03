package io.github.aspershupadhyay.latch.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the AI may use an app right now. */
enum class AppDecision { ALLOWED, ASK }

/** Auto mode (ADR-024): off, until the session ends, or until the owner turns it off. */
enum class AutoMode { OFF, SESSION, ALWAYS }

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
    /** Auto mode: every app, every action, no questions (the owner's explicit consent, ADR-024). */
    val auto: AutoMode = AutoMode.OFF,
) {
    val autoOn: Boolean get() = auto != AutoMode.OFF

    /** Payments, installs, permissions, and account deletion run without asking. */
    val trustsCritical: Boolean get() = trustCritical || autoOn
}

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
        /** Only "always" is kept; "this session" never outlives the process. */
        fun loadAutoAlways(): Boolean = false
        fun saveAutoAlways(value: Boolean) = Unit
    }

    private val _state = MutableStateFlow(
        AutonomyState(
            allowed = store.load(),
            trustCritical = store.loadTrustCritical(),
            auto = if (store.loadAutoAlways()) AutoMode.ALWAYS else AutoMode.OFF,
        ),
    )
    val state: StateFlow<AutonomyState> = _state.asStateFlow()

    fun decide(packageName: String): AppDecision {
        val s = _state.value
        return if (s.autoOn || packageName in s.allowed || packageName in s.sessionApps) AppDecision.ALLOWED else AppDecision.ASK
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

    /** Turns Auto mode on or off. The screens ask for the owner's consent before calling this. */
    fun setAuto(mode: AutoMode) {
        if (_state.value.auto == mode) return
        store.saveAutoAlways(mode == AutoMode.ALWAYS)
        _state.value = _state.value.copy(auto = mode)
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
        store.saveAutoAlways(false)
        _state.value = AutonomyState()
    }

    /** "This session" answers and session Auto mode end with the session (stop or expiry). */
    fun endSession() {
        _state.value = _state.value.let { it.copy(sessionApps = emptySet(), auto = if (it.auto == AutoMode.SESSION) AutoMode.OFF else it.auto) }
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

    override fun loadAutoAlways(): Boolean = prefs.getBoolean("auto_always", false)

    override fun saveAutoAlways(value: Boolean) {
        prefs.edit { putBoolean("auto_always", value) }
    }
}
