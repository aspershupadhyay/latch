// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.data

import io.github.aspershupadhyay.latch.protocol.ActivityItem
import io.github.aspershupadhyay.latch.protocol.ActivityList
import io.github.aspershupadhyay.latch.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

/** What a line is about; [wire] is the protocol 1.8 name, [label] the Activity filter's. */
enum class ActivityKind(val wire: String, val label: String) {
    SESSION("session", "Session"),
    CONNECTION("connection", "Connection"),
    SCREEN("screen", "Screen reads"),
    ACTION("action", "Actions"),
    APP("app", "App access"),
    APPROVAL("approval", "Approvals"),
    REFUSAL("refusal", "Refused"),
    FILE("file", "Files"),
    FOLDER("folder", "Folders"),
    TASK("task", "Tasks"),
    ;

    companion object {
        fun fromWire(wire: String): ActivityKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Who did it (`by` on the wire): the AI through Latch, the owner's own hand,
 * or Latch itself (a timer, a rule, the connection). [label] is what Activity shows.
 */
enum class ActivityActor(val wire: String, val label: String) {
    AI("ai", "AI"),
    OWNER("owner", "You"),
    LATCH("latch", "Latch"),
    ;

    companion object {
        fun fromWire(wire: String?): ActivityActor? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One line in the activity timeline: what happened, who did it, in which app,
 * and when. Never holds screen text or typed text; file and app names only.
 */
data class ActivityEntry(
    val atMs: Long,
    val kind: ActivityKind,
    val summary: String,
    val app: String? = null,
    val by: ActivityActor = ActivityActor.LATCH,
)

/**
 * Every AI action, question to the owner, answer, refusal, and file the AI
 * touched, newest first. Kept on this phone only (app-private storage, never
 * backed up) for [RETENTION_MS], so a restart or a crash does not erase what
 * the owner needs to check; "Clear activity" deletes it. With no [file] (tests)
 * it lives in memory only. Writes happen on one background thread.
 */
class ActivityLog(private val file: File? = null, private val now: () -> Long = System::currentTimeMillis) {
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()
    private val writer = file?.let { Executors.newSingleThreadExecutor { r -> Thread(r, "latch-activity").apply { isDaemon = true } } }
    private var appendsSinceCompact = 0

    init {
        writer?.execute(::load)
    }

    fun add(kind: ActivityKind, summary: String, app: String? = null, by: ActivityActor = ActivityActor.LATCH) {
        val entry = ActivityEntry(now(), kind, summary.take(MAX_SUMMARY_CHARS), app?.take(MAX_APP_CHARS), by)
        _entries.update { (listOf(entry) + it).take(CAPACITY) }
        writer?.execute {
            runCatching {
                file!!.appendText(encode(entry) + "\n")
                if (++appendsSinceCompact >= COMPACT_EVERY) compact()
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        writer?.execute { runCatching { file!!.delete() } }
    }

    /** Waits until every pending write reached the file (tests). */
    internal fun flush() {
        writer?.submit {}?.get()
    }

    /** Protocol 1.8 `activity.list`: newest first, optionally only [kinds] and entries since [sinceMs]. */
    fun query(limit: Int, kinds: Collection<String>, sinceMs: Long?): ActivityList {
        val matching = _entries.value.filter { e ->
            (kinds.isEmpty() || e.kind.wire in kinds) && (sinceMs == null || e.atMs >= sinceMs)
        }
        return ActivityList(matching.take(limit).map(::item), matching.size)
    }

    private fun item(e: ActivityEntry) = ActivityItem(e.atMs, e.kind.wire, e.summary, e.app, e.by.wire)

    private fun encode(e: ActivityEntry) = Protocol.json.encodeToString(ActivityItem.serializer(), item(e))

    private fun load() {
        val f = file ?: return
        val cutoff = now() - RETENTION_MS
        val saved = runCatching {
            if (!f.exists()) return@runCatching emptyList()
            f.readLines().asReversed().asSequence().mapNotNull { line ->
                runCatching { Protocol.json.decodeFromString(ActivityItem.serializer(), line) }.getOrNull()
                    ?.let { item ->
                        // Lines saved by older versions have no `by`: an AI action line is the AI's; the rest are Latch's.
                        ActivityKind.fromWire(item.kind)?.let {
                            ActivityEntry(item.atMs, it, item.summary, item.app, ActivityActor.fromWire(item.by) ?: legacyActor(it, item.summary))
                        }
                    }
            }.filter { it.atMs >= cutoff }.take(CAPACITY).toList()
        }.getOrDefault(emptyList())
        // Lines added while loading come first; both lists are newest first.
        _entries.update { (it + saved).sortedByDescending(ActivityEntry::atMs).take(CAPACITY) }
        compact()
    }

    /** Rewrites the file with only what is kept, so it never grows past [CAPACITY] lines. */
    private fun compact() {
        val f = file ?: return
        appendsSinceCompact = 0
        val cutoff = now() - RETENTION_MS
        val kept = _entries.value.filter { it.atMs >= cutoff }.asReversed()
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(kept.joinToString("") { encode(it) + "\n" })
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    companion object {
        /** Best guess for a line saved before `by` existed. */
        fun legacyActor(kind: ActivityKind, summary: String): ActivityActor = when {
            summary.startsWith("You ") -> ActivityActor.OWNER
            kind in setOf(ActivityKind.SCREEN, ActivityKind.ACTION, ActivityKind.FILE, ActivityKind.FOLDER, ActivityKind.TASK) -> ActivityActor.AI
            else -> ActivityActor.LATCH
        }

        const val CAPACITY = 2_000
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val COMPACT_EVERY = 500
        private const val MAX_SUMMARY_CHARS = 300
        private const val MAX_APP_CHARS = 80
    }
}
