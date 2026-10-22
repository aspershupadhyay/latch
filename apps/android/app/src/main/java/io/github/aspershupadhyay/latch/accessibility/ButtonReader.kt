// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.content.Context
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.aspershupadhyay.latch.policy.LabelReader
import io.github.aspershupadhyay.latch.protocol.Rect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the words on an unlabeled control with Google ML Kit's on-device text
 * recognition (ADR-032). The picture and the words stay on the phone. ML Kit
 * is started only on first use, so with the owner's switch off it never runs
 * and reports nothing to Google.
 */
class ButtonReader(
    private val context: Context,
    private val capture: suspend (Rect) -> android.graphics.Bitmap?,
) : LabelReader, ScreenTextReader {
    private var recognizer: TextRecognizer? = null
    private var devanagari: TextRecognizer? = null

    @Synchronized
    private fun recognizer(): TextRecognizer = recognizer ?: run {
        // The manifest removes ML Kit's own start-up provider; start it here instead.
        runCatching { MlKit.initialize(context.applicationContext) }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    }

    override suspend fun read(region: Rect): String? {
        val bitmap = capture(region) ?: return null
        return try {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    recognizer().process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { cont.resume(it.text.take(MAX_CHARS).ifBlank { null }) }
                        .addOnFailureListener { cont.resume(null) }
                        .addOnCanceledListener { cont.resume(null) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure leaves the judgement as it was without reading: never less strict.
            null
        }
    }

    /** R67: Hindi and other Devanagari text; its model comes from Google Play services when the phone has them. */
    @Synchronized
    private fun devanagari(): TextRecognizer = devanagari ?: run {
        recognizer()
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()).also { devanagari = it }
    }

    /**
     * The lines in [bitmap]: Latin script first, then Devanagari lines (Hindi
     * labels on Indian apps and maps), which the Latin model cannot read. On a
     * phone without Play services, or before its model downloaded, only Latin.
     */
    override suspend fun lines(bitmap: android.graphics.Bitmap): List<TextLine> {
        val latin = linesWith(recognizer(), bitmap)
        val hindi = linesWith(devanagari(), bitmap).filter { line -> line.text.any { it in '\u0900'..'\u097F' } }
        return latin + hindi
    }

    private suspend fun linesWith(reader: TextRecognizer, bitmap: android.graphics.Bitmap): List<TextLine> = try {
        withTimeoutOrNull(SCREEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                reader.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        cont.resume(
                            text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                val box = line.boundingBox ?: return@mapNotNull null
                                val words = line.text.trim().take(MAX_LINE_CHARS)
                                if (words.isEmpty()) null else TextLine(words, Rect(box.left, box.top, box.right, box.bottom), line.confidence)
                            },
                        )
                    }
                    .addOnFailureListener { cont.resume(emptyList()) }
                    .addOnCanceledListener { cont.resume(emptyList()) }
            }
        } ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Frees ML Kit's native resources; a later [read] starts it again. */
    @Synchronized
    fun close() {
        recognizer?.close()
        recognizer = null
        devanagari?.close()
        devanagari = null
    }

    private companion object {
        const val READ_TIMEOUT_MS = 1_500L
        const val MAX_CHARS = 200
        /** A whole screen takes longer than one button. */
        const val SCREEN_TIMEOUT_MS = 2_500L
        const val MAX_LINE_CHARS = 200
    }
}
