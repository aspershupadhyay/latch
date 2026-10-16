// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.report

import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.TaskEnd
import io.github.aspershupadhyay.latch.data.TaskRun
import io.github.aspershupadhyay.latch.data.taskRuns
import io.github.aspershupadhyay.latch.data.tenths
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Which part of the activity log a report covers. */
enum class ReportPeriod(val label: String) {
    LATEST_TASK("The latest AI task"),
    TODAY("Today"),
    LAST_24_HOURS("The last 24 hours"),
    LAST_7_DAYS("The last 7 days (everything kept)"),
}

/** One line of the report's full log, oldest first. */
data class ReportRow(val atMs: Long, val time: String, val by: String, val kind: String, val app: String?, val what: String)

/** One AI task in the report's task list. */
data class ReportTask(val started: String, val duration: String, val end: String, val steps: Int, val approvals: Int, val refusals: Int, val apps: List<String>)

/**
 * Everything the owner's PDF report says (roadmap R25), built only from the
 * phone's activity log, which never holds screen text, typed text, or the
 * clipboard. [fingerprint] chains a SHA-256 over every row in order, so two
 * copies of a report can be compared and a changed, added, or removed line
 * shows as a different fingerprint.
 */
data class ActivityReport(
    val title: String,
    val period: String,
    val generated: String,
    val device: String,
    val counts: List<Pair<String, Int>>,
    val apps: List<Pair<String, Int>>,
    val tasks: List<ReportTask>,
    val rows: List<ReportRow>,
    val fingerprint: String,
) {
    val isEmpty get() = rows.isEmpty()

    companion object {
        /** Rows in one report; the log itself keeps at most 2,000. */
        const val MAX_ROWS = 2_000

        /**
         * Builds the report for [period] from a newest-first log. [device]
         * names the phone and Latch version; [zone] sets the printed times.
         */
        fun build(
            newestFirst: List<ActivityEntry>,
            period: ReportPeriod,
            nowMs: Long,
            device: String,
            zone: TimeZone = TimeZone.getDefault(),
            locale: Locale = Locale.getDefault(),
        ): ActivityReport {
            val chosen: List<ActivityEntry> = when (period) {
                ReportPeriod.LATEST_TASK -> taskRuns(newestFirst).firstOrNull()?.entries ?: emptyList()
                ReportPeriod.TODAY -> {
                    val start = startOfDay(nowMs, zone)
                    newestFirst.filter { it.atMs >= start }.asReversed()
                }
                ReportPeriod.LAST_24_HOURS -> newestFirst.filter { it.atMs >= nowMs - DAY_MS }.asReversed()
                ReportPeriod.LAST_7_DAYS -> newestFirst.asReversed()
            }.takeLast(MAX_ROWS)

            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).apply { timeZone = zone }
            val rows = chosen.map {
                ReportRow(it.atMs, stamp.format(Date(it.atMs)), it.by.label, it.kind.label, it.app, it.summary)
            }
            val span = if (chosen.isEmpty()) {
                "Nothing logged"
            } else {
                "${stamp.format(Date(chosen.first().atMs))} to ${stamp.format(Date(chosen.last().atMs))} (${zone.getDisplayName(false, TimeZone.SHORT, locale)})"
            }
            val counts = listOf(
                "Lines" to chosen.size,
                "By the AI" to chosen.count { it.by == ActivityActor.AI },
                "By you" to chosen.count { it.by == ActivityActor.OWNER },
                "By Latch" to chosen.count { it.by == ActivityActor.LATCH },
                "AI actions" to chosen.count { it.kind == ActivityKind.ACTION },
                "Questions to you" to chosen.count { it.kind == ActivityKind.APPROVAL && it.summary.startsWith("Asked you") },
                "You approved" to chosen.count { it.kind == ActivityKind.APPROVAL && it.summary.startsWith("You ") },
                "Done without asking" to chosen.count { it.kind == ActivityKind.APPROVAL && it.summary.startsWith("Done without asking") },
                "Refused or denied" to chosen.count { it.kind == ActivityKind.REFUSAL },
                "Files and folders" to chosen.count { it.kind == ActivityKind.FILE || it.kind == ActivityKind.FOLDER },
                "Screen reads" to chosen.count { it.kind == ActivityKind.SCREEN },
            )
            val apps = chosen.mapNotNull { it.app }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
            val tasks = taskRuns(chosen.asReversed()).asReversed().map { task(it, stamp) }
            return ActivityReport(
                title = "Latch activity report",
                period = "${period.label}: $span",
                generated = stamp.format(Date(nowMs)),
                device = device,
                counts = counts,
                apps = apps,
                tasks = tasks,
                rows = rows,
                fingerprint = fingerprint(rows),
            )
        }

        private fun task(run: TaskRun, stamp: SimpleDateFormat) = ReportTask(
            started = stamp.format(Date(run.startMs)),
            duration = tenths(run.durationMs),
            end = when (run.end) {
                TaskEnd.FINISHED -> "finished"
                TaskEnd.SESSION_ENDED -> "session ended"
                TaskEnd.OPEN -> "still open"
            },
            steps = run.entries.count { it.by == ActivityActor.AI },
            approvals = run.count(ActivityKind.APPROVAL),
            refusals = run.count(ActivityKind.REFUSAL),
            apps = run.entries.mapNotNull { it.app }.distinct(),
        )

        /**
         * SHA-256 chained over the rows in order: start from the hash of
         * "latch-report-v1", then hash the previous value with each row's
         * time in milliseconds, actor, kind, app, and text.
         */
        fun fingerprint(rows: List<ReportRow>): String {
            var chain = sha256("latch-report-v1".toByteArray())
            for (r in rows) {
                val line = listOf(r.atMs.toString(), r.by, r.kind, r.app ?: "", r.what).joinToString("\u001f")
                chain = sha256(chain + line.toByteArray())
            }
            return chain.joinToString("") { "%02x".format(it) }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun startOfDay(nowMs: Long, zone: TimeZone): Long {
            val local = nowMs + zone.getOffset(nowMs)
            val day = Math.floorDiv(local, DAY_MS) * DAY_MS
            return day - zone.getOffset(day)
        }

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
