// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.data

/** How an AI task in the log ended. */
enum class TaskEnd { FINISHED, SESSION_ENDED, OPEN }

/** One AI task cut from the activity log, oldest entry first (roadmap R14). */
data class TaskRun(val entries: List<ActivityEntry>, val end: TaskEnd) {
    val startMs get() = entries.first().atMs
    val durationMs get() = entries.last().atMs - entries.first().atMs
    fun count(kind: ActivityKind) = entries.count { it.kind == kind }
}

/**
 * Cuts the AI's tasks out of a newest-first log, newest task first, the same
 * way both gateways do for `get_activity {task: "latest"}`
 * (servers/mcp/src/mcp/replay.rs): a task starts at the first AI entry after a
 * session line or a finished task, takes in everything logged until the AI
 * says it is done, and is cut short by a session line. Nothing extra is stored.
 */
fun taskRuns(newestFirst: List<ActivityEntry>): List<TaskRun> {
    val runs = ArrayList<TaskRun>()
    var open: MutableList<ActivityEntry>? = null
    for (e in newestFirst.asReversed()) {
        if (e.kind == ActivityKind.SESSION) {
            open?.let { runs += TaskRun(it, TaskEnd.SESSION_ENDED) }
            open = null
            continue
        }
        if (open == null) {
            if (e.by != ActivityActor.AI) continue
            open = ArrayList()
        }
        open += e
        if (e.kind == ActivityKind.TASK) {
            runs += TaskRun(open, TaskEnd.FINISHED)
            open = null
        }
    }
    open?.let { runs += TaskRun(it, TaskEnd.OPEN) }
    return runs.asReversed()
}

/** `12.3 s` from milliseconds, rounded down to a tenth, as the gateways write it. */
fun tenths(ms: Long): String = "${ms / 1000}.${ms % 1000 / 100} s"
