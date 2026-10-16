// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.report.ActivityReport
import io.github.aspershupadhyay.latch.report.ReportPdf
import io.github.aspershupadhyay.latch.report.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Roadmap R25 on a real Android: the activity report becomes a PDF that
 * Android's own PDF reader opens, with as many pages as the layout planned.
 */
@RunWith(AndroidJUnit4::class)
class ReportPdfTest {
    @Test
    fun theReportOpensAsAPdfWithEveryPage() {
        val now = System.currentTimeMillis()
        val log = (0 until 400).map { i ->
            ActivityEntry(now - i * 1_000L, ActivityKind.ACTION, "Tap “Item $i” in a long list", "com.example.shop", ActivityActor.AI)
        } + ActivityEntry(now - 500_000L, ActivityKind.SESSION, "Session started", null, ActivityActor.OWNER)
        val report = ActivityReport.build(log, ReportPeriod.LAST_7_DAYS, now, "Emulator · Latch test")
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "report-test.pdf")
        val pages = file.outputStream().use { ReportPdf.write(report, it) }
        assertTrue(pages > 1)
        assertEquals("%PDF-", String(file.readBytes(), 0, 5, Charsets.US_ASCII))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { assertEquals(pages, it.pageCount) }
        }
        file.delete()
    }
}
