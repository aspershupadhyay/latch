// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.report

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves a report PDF to Downloads/Latch reports (no permission needed) and shares it. */
object ReportSaver {
    const val FOLDER = "Latch reports"

    /** Writes [report] to Downloads and returns its address, or throws when the phone could not save it. */
    fun save(context: Context, report: ActivityReport, nowMs: Long = System.currentTimeMillis()): Saved {
        val name = "latch-activity-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(nowMs)) + ".pdf"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Android did not make the file")
        try {
            val pages = resolver.openOutputStream(uri)?.use { ReportPdf.write(report, it) } ?: error("Android did not open the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return Saved(uri, name, pages)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Android's share sheet for a saved report. */
    fun shareIntent(saved: Saved): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, saved.uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Latch activity report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Share the report",
    )

    data class Saved(val uri: Uri, val name: String, val pages: Int)
}
