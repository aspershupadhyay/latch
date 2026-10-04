package io.github.aspershupadhyay.latch.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One saved "always" answer, split for display. Keys look like `tap|com.instagram.android|share`. */
data class SavedApproval(val key: String) {
    private val parts = key.split('|', limit = 3)
    val verb: String get() = parts.getOrElse(0) { "" }
    val packageName: String get() = parts.getOrElse(1) { "?" }
    val label: String get() = parts.getOrElse(2) { "" }

    /** "Tap “share”", "Swipe" — the label is untrusted app text, shown quoted. */
    val action: String
        get() {
            val v = when (verb) {
                "long-press" -> "Long-press"
                "swipe" -> "Swipe"
                else -> "Tap"
            }
            return if (label.isEmpty()) v else "$v “$label”"
        }
}

/**
 * The owner's saved approval answers: "for this session" (memory only) and
 * "always in this app" (kept on the phone, never sent anywhere). Only
 * consequential actions can be saved; critical ones are asked every time,
 * which the caller enforces by never passing their key here.
 */
class ApprovalGrants(private val store: Store) {
    interface Store {
        fun load(): Set<String>
        fun save(keys: Set<String>)
    }

    private val _always = MutableStateFlow(store.load())
    val always: StateFlow<Set<String>> = _always.asStateFlow()
    // Read and written from the command thread and the UI thread.
    private val session: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun allows(key: String): Boolean = key in session || key in _always.value

    fun allowForSession(key: String) {
        session += key
    }

    fun allowAlways(key: String) {
        if (key in _always.value) return
        // A bounded list the owner can still read through; the oldest answers go first.
        val next = (_always.value.toList() + key).takeLast(MAX_ALWAYS).toSet()
        store.save(next)
        _always.value = next
    }

    fun remove(key: String) {
        session -= key
        val next = _always.value - key
        store.save(next)
        _always.value = next
    }

    fun clearAll() {
        session.clear()
        store.save(emptySet())
        _always.value = emptySet()
    }

    /** Session answers end with the session (stop, expiry, or a new start). */
    fun endSession() {
        session.clear()
    }

    companion object {
        const val MAX_ALWAYS = 200
    }
}

/** SharedPreferences keeps insertion order only by our own list; store it as one string. */
class PrefsGrantStore(context: Context) : ApprovalGrants.Store {
    private val prefs = context.getSharedPreferences("latch_approvals", Context.MODE_PRIVATE)

    override fun load(): Set<String> =
        prefs.getString("always", null)?.split('\n')?.filter { it.isNotBlank() }?.toCollection(LinkedHashSet()) ?: emptySet()

    override fun save(keys: Set<String>) {
        // Keys never contain newlines (the protocol forbids control characters).
        prefs.edit { putString("always", keys.joinToString("\n")) }
    }
}
