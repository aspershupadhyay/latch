// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.report

import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/** Roadmap R25: the owner's PDF report of what the AI did, built from the activity log only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ActivityReportTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_800_000_000_000L // 2027-01-15 08:00:00 UTC

    private fun e(minutesAgo: Long, kind: ActivityKind, summary: String, by: ActivityActor, app: String? = null) =
        ActivityEntry(now - minutesAgo * 60_000, kind, summary, app, by)

    /** Newest first, as the log keeps them: one old task yesterday, one finished task today. */
    private val log = listOf(
        e(1, ActivityKind.TASK, "The AI finished: Sent the photo", ActivityActor.AI),
        e(2, ActivityKind.APPROVAL, "You approved: Tap “Send” in com.whatsapp", ActivityActor.OWNER, "com.whatsapp"),
        e(3, ActivityKind.APPROVAL, "Asked you: Tap “Send” in com.whatsapp", ActivityActor.LATCH, "com.whatsapp"),
        e(4, ActivityKind.ACTION, "Type 12 characters", ActivityActor.AI, "com.whatsapp"),
        e(5, ActivityKind.ACTION, "Open com.whatsapp", ActivityActor.AI, "com.whatsapp"),
        e(6, ActivityKind.SESSION, "Session started", ActivityActor.OWNER),
        e(26 * 60, ActivityKind.REFUSAL, "Refused: a password field", ActivityActor.LATCH, "com.bank"),
        e(26 * 60 + 1, ActivityKind.ACTION, "Open com.bank", ActivityActor.AI, "com.bank"),
    )

    private fun build(period: ReportPeriod, entries: List<ActivityEntry> = log) =
        ActivityReport.build(entries, period, now, "Pixel 9 · Android 16 · Latch 0.1.0", utc, Locale.US)

    @Test
    fun theLatestTaskHoldsItsStepsOldestFirst() {
        val r = build(ReportPeriod.LATEST_TASK)
        assertEquals(listOf("Open com.whatsapp", "Type 12 characters", "Asked you: Tap “Send” in com.whatsapp", "You approved: Tap “Send” in com.whatsapp", "The AI finished: Sent the photo"), r.rows.map { it.what })
        assertEquals("2027-01-15 07:55:00", r.rows.first().time)
        assertEquals(listOf("AI", "AI", "Latch", "You", "AI"), r.rows.map { it.by })
        assertEquals(1, r.tasks.size)
        assertEquals("finished", r.tasks[0].end)
        assertEquals(listOf("com.whatsapp"), r.tasks[0].apps)
        val counts = r.counts.toMap()
        assertEquals(1, counts["Questions to you"])
        assertEquals(1, counts["You approved"])
        assertEquals(2, counts["AI actions"])
    }

    @Test
    fun periodsCutTheLogByTime() {
        assertEquals(6, build(ReportPeriod.TODAY).rows.size)
        assertEquals(6, build(ReportPeriod.LAST_24_HOURS).rows.size)
        val week = build(ReportPeriod.LAST_7_DAYS)
        assertEquals(8, week.rows.size)
        assertEquals("Open com.bank", week.rows.first().what)
        assertEquals(1, week.counts.toMap()["Refused or denied"])
        assertEquals(listOf("com.whatsapp" to 4, "com.bank" to 2), week.apps)
        assertTrue(build(ReportPeriod.TODAY, emptyList()).isEmpty)
    }

    @Test
    fun anyChangedLineChangesTheFingerprint() {
        val r = build(ReportPeriod.LAST_7_DAYS)
        assertEquals(64, r.fingerprint.length)
        assertEquals("the same lines give the same fingerprint", r.fingerprint, build(ReportPeriod.LAST_7_DAYS).fingerprint)
        val edited = log.toMutableList().also { it[3] = it[3].copy(summary = "Type 13 characters") }
        assertNotEquals(r.fingerprint, build(ReportPeriod.LAST_7_DAYS, edited).fingerprint)
        assertNotEquals(r.fingerprint, build(ReportPeriod.LAST_7_DAYS, log.drop(1)).fingerprint)
        val reordered = log.toMutableList().also { val t = it[1]; it[1] = it[2].copy(atMs = t.atMs); it[2] = t.copy(atMs = log[2].atMs) }
        assertNotEquals(r.fingerprint, build(ReportPeriod.LAST_7_DAYS, reordered).fingerprint)
    }

    @Test
    fun everyLineGetsAPage() {
        // The 2,000 lines the log keeps at most, some long enough to wrap.
        // (Android's PdfDocument itself runs in the emulator test, ReportPdfTest.)
        val many = (0 until 2_000).map { i ->
            ActivityEntry(now - i * 1_000L, ActivityKind.ACTION, "Tap “Item $i” in a long list " + "word ".repeat(i % 30), "com.example.shop", ActivityActor.AI)
        }
        val pages = ReportPdf.pageCount(build(ReportPeriod.LAST_7_DAYS, many))
        assertTrue("$pages pages for 2,000 lines", pages in 20..200)
        assertEquals(1, ReportPdf.pageCount(build(ReportPeriod.LATEST_TASK)))
    }

    @Test
    fun longWordsAreCutToTheLine() {
        val paint = android.graphics.Paint().apply { textSize = 8f }
        val lines = ReportPdf.wrap("open https://example.com/" + "a".repeat(400) + " then done", paint, 200f)
        assertTrue(lines.size > 2)
        assertTrue(lines.all { paint.measureText(it) <= 200f })
        assertEquals("open", lines.first())
        assertTrue(lines.last().endsWith("then done"))
    }
}
