package io.github.aspershupadhyay.latch.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ActivityKind { SESSION, OBSERVE, ACTION, APPROVAL, REFUSAL, CONNECTION }

/** One redacted line in the on-phone activity timeline. Never holds screen text or typed text. */
data class ActivityEntry(val atMs: Long, val kind: ActivityKind, val summary: String)

/** In-memory only: the log disappears with the process, by design. */
class ActivityLog {
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    fun add(kind: ActivityKind, summary: String) {
        _entries.update { (listOf(ActivityEntry(System.currentTimeMillis(), kind, summary)) + it).take(CAPACITY) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private companion object {
        const val CAPACITY = 300
    }
}
