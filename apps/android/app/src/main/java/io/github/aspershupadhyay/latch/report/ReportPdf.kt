// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Draws an [ActivityReport] as an A4 PDF with Android's own [PdfDocument]
 * (no library): a summary, the AI's tasks, and every line of the log, with
 * the page number and the report's fingerprint on each page.
 */
object ReportPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val FOOTER_Y = PAGE_H - 24f

    private class Op(val text: String, val x: Float, val y: Float, val paint: Paint)

    private val title = paint(18f, bold = true)
    private val heading = paint(12f, bold = true)
    private val body = paint(9f)
    private val small = paint(7.5f)
    private val mono = paint(7.5f).apply { typeface = Typeface.MONOSPACE }
    private val muted = paint(7.5f).apply { color = Color.rgb(96, 96, 96) }
    private val columnHead = paint(7.5f, bold = true)

    private fun paint(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.rgb(20, 20, 20)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Writes the PDF to [out]; returns the number of pages. */
    fun write(report: ActivityReport, out: OutputStream): Int {
        val pages = layout(report)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, ops ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                val canvas = page.canvas
                for (op in ops) canvas.drawText(op.text, op.x, op.y, op.paint)
                val footer = "Latch activity report · page ${index + 1} of ${pages.size} · fingerprint ${report.fingerprint.take(16)}…"
                canvas.drawText(footer, MARGIN, FOOTER_Y, muted)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return pages.size
    }

    /** How many pages [report] takes. */
    internal fun pageCount(report: ActivityReport): Int = layout(report).size

    /** Splits the report into pages of text, top to bottom. */
    private fun layout(report: ActivityReport): List<List<Op>> {
        val pages = ArrayList<MutableList<Op>>()
        var ops = ArrayList<Op>().also { pages += it }
        var y = MARGIN + 10f
        val bottom = FOOTER_Y - 18f
        val width = PAGE_W - 2 * MARGIN

        fun newPage() {
            ops = ArrayList<Op>().also { pages += it }
            y = MARGIN + 10f
        }
        fun line(text: String, paint: Paint, x: Float = MARGIN, gap: Float = 3f) {
            val height = paint.textSize + gap
            if (y + height > bottom) newPage()
            ops += Op(text, x, y + paint.textSize, paint)
            y += height
        }
        fun para(text: String, paint: Paint, x: Float = MARGIN) = wrap(text, paint, width - (x - MARGIN)).forEach { line(it, paint, x) }
        fun space(h: Float) {
            y += h
        }

        line(report.title, title, gap = 8f)
        para("Phone: ${report.device}", body)
        para("Period: ${report.period}", body)
        para("Made on the phone: ${report.generated}", body)
        space(4f)
        para(
            "What this holds: every line of Latch's activity log for this period: what the AI did, what it asked you and how you answered, " +
                "what was refused, the apps and files it touched, and what you and Latch did. It never holds screen text, typed text, " +
                "passwords, codes, or the clipboard; typed text appears only as a number of characters.",
            muted,
        )
        space(8f)

        line("Summary", heading, gap = 5f)
        report.counts.forEach { (label, n) ->
            if (y + body.textSize + 3f > bottom) newPage()
            ops += Op(label, MARGIN, y + body.textSize, body)
            ops += Op(n.toString(), MARGIN + 160f, y + body.textSize, body)
            y += body.textSize + 3f
        }
        if (report.apps.isNotEmpty()) {
            space(4f)
            para("Apps: " + report.apps.joinToString(", ") { (app, n) -> "$app ($n)" }, body)
        }
        space(8f)

        line("AI tasks (${report.tasks.size})", heading, gap = 5f)
        if (report.tasks.isEmpty()) para("No AI task in this period.", body)
        report.tasks.forEachIndexed { i, t ->
            para(
                "${i + 1}. ${t.started} · ${t.duration} · ${t.end} · ${t.steps} AI steps · ${t.approvals} approval lines · ${t.refusals} refused" +
                    if (t.apps.isEmpty()) "" else " · ${t.apps.joinToString(", ")}",
                body,
            )
        }
        space(8f)

        line("Every line, oldest first (${report.rows.size})", heading, gap = 5f)
        val cols = floatArrayOf(MARGIN, MARGIN + 82f, MARGIN + 112f, MARGIN + 168f)
        val whatX = MARGIN + 168f
        fun header() {
            if (y + small.textSize + 4f > bottom) newPage()
            listOf("Time", "Who", "Kind", "App · what happened").forEachIndexed { i, h -> ops += Op(h, cols[i], y + small.textSize, columnHead) }
            y += small.textSize + 4f
        }
        header()
        if (report.rows.isEmpty()) para("Nothing logged in this period.", body)
        for (r in report.rows) {
            val what = wrap(listOfNotNull(r.app?.let { "[$it]" }, r.what).joinToString(" "), small, width - (whatX - MARGIN))
            val height = what.size * (small.textSize + 2f) + 2f
            if (y + height > bottom) {
                newPage()
                header()
            }
            ops += Op(r.time.substring(5), cols[0], y + small.textSize, mono)
            ops += Op(r.by, cols[1], y + small.textSize, small)
            ops += Op(r.kind, cols[2], y + small.textSize, small)
            what.forEachIndexed { i, text -> ops += Op(text, whatX, y + small.textSize + i * (small.textSize + 2f), small) }
            y += height
        }
        space(10f)
        line("Fingerprint (SHA-256 chained over every line above)", heading, gap = 4f)
        line(report.fingerprint, mono)
        para("A report made again from the same lines has the same fingerprint; any changed, added, or removed line changes it.", muted)
        return pages
    }

    /** Breaks [text] into lines no wider than [max] points, splitting long words. */
    internal fun wrap(text: String, paint: Paint, max: Float): List<String> {
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in text.replace('\n', ' ').split(' ')) {
            var w = word
            while (paint.measureText(w) > max) {
                // A word wider than the line (a long link or id): cut it where it fits.
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                val n = paint.breakText(w, true, max, null).coerceAtLeast(1)
                lines += w.substring(0, n)
                w = w.substring(n)
            }
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(candidate) <= max) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) lines += current.toString()
        return lines
    }
}
