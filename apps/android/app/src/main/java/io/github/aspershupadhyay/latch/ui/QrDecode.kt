// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * The scanner's plain parts (ADR-054, R32), with no Android types so they are
 * tested on the JVM: ZXing's Java reader for a still picture or a camera frame,
 * normal or light on dark (a terminal draws either), and the auto-zoom rule.
 * The camera itself reads with zxing-cpp (ScanActivity), which keeps reading
 * small, noisy codes where this reader gives up; this one is the fallback for
 * phones where the native reader does not load, and for photos it misses.
 */
object QrDecode {
    /** Hints for camera frames and photos: QR only, and look hard. */
    val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * The text of the first QR code in a picture of [width] × [height] ARGB
     * [pixels], or null. Tries the picture as it is and inverted, then smaller
     * copies: a photo of a screen is often too big and noisy at full size.
     */
    fun fromPixels(pixels: IntArray, width: Int, height: Int): String? {
        var w = width
        var h = height
        var px = pixels
        repeat(3) {
            val source = RGBLuminanceSource(w, h, px)
            read(source)?.let { return it }
            read(InvertedLuminanceSource(source))?.let { return it }
            if (maxOf(w, h) <= MIN_EDGE) return null
            val half = halve(px, w, h)
            px = half
            w /= 2
            h /= 2
        }
        return null
    }

    private fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: NotFoundException) {
        null
    } catch (e: ReaderException) {
        null
    }

    /** Every second pixel in both directions. */
    private fun halve(px: IntArray, w: Int, h: Int): IntArray {
        val nw = w / 2
        val nh = h / 2
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) out[y * nw + x] = px[(y * 2) * w + x * 2]
        return out
    }

    /**
     * The text of a QR code in a camera frame's luminance plane ([y], [rowStride]
     * bytes a row), normal or inverted, or null.
     */
    fun fromLuminance(y: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width || y.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        return read(source) ?: read(source.invert())
    }

    /**
     * Auto-zoom: the zoom ratio at which a code seen [side] pixels across, in a
     * frame whose short edge is [edge] pixels at zoom [current], fills about
     * [ZOOM_TARGET] of that edge, at most [max] (and [MAX_AUTO_ZOOM]). Null when
     * the code is big enough already or zooming would barely change it. Digital
     * zoom crops every camera output, so the frame is measured at [current].
     */
    fun zoomFor(side: Float, edge: Int, current: Float, max: Float): Float? {
        if (side <= 0f || edge <= 0 || current <= 0f) return null
        val fraction = side / edge
        if (fraction >= ZOOM_SMALL) return null
        val next = (current * ZOOM_TARGET / fraction).coerceAtMost(minOf(max, MAX_AUTO_ZOOM))
        return next.takeIf { it > current * 1.15f }
    }

    /** A code narrower than this share of the frame's short edge is zoomed in on. */
    const val ZOOM_SMALL = 0.22f

    /** The share of the frame's short edge a zoomed-in code fills. */
    const val ZOOM_TARGET = 0.45f

    /** Auto-zoom stops here: more digital zoom only blurs. */
    const val MAX_AUTO_ZOOM = 3f

    private const val MIN_EDGE = 400
}
