// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * The scanner's plain parts (ADR-054, R32), with no Android types so they are
 * tested on the JVM: reading a QR code from a still picture, normal or light on
 * dark (a terminal draws either), and the camera's focus-area arithmetic.
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
     * A tap at ([x], [y]) on a [width] × [height] preview as a focus area in the
     * camera's own coordinates (-1000…1000 on each axis), [sensorDegrees] being
     * how far the sensor is turned from the screen. Clamped so the area stays on
     * the sensor.
     */
    fun focusArea(x: Float, y: Float, width: Int, height: Int, sensorDegrees: Int, half: Int = 100): IntArray {
        val nx = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val ny = (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        // The point on the sensor before its rotation is undone.
        val (sx, sy) = when (((sensorDegrees % 360) + 360) % 360) {
            90 -> ny to 1f - nx
            180 -> 1f - nx to 1f - ny
            270 -> 1f - ny to nx
            else -> nx to ny
        }
        val cx = (sx * 2000 - 1000).toInt().coerceIn(-1000 + half, 1000 - half)
        val cy = (sy * 2000 - 1000).toInt().coerceIn(-1000 + half, 1000 - half)
        return intArrayOf(cx - half, cy - half, cx + half, cy + half)
    }

    private const val MIN_EDGE = 400
}
