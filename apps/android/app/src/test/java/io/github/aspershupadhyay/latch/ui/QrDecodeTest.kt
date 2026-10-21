// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecodeTest {
    private val link = "https://192.168.1.5:8788#pin=${"A".repeat(43)}&code=K7QXM2PD"

    /** The link drawn as a QR code, [scale] pixels a module, on a quiet zone of [margin] modules. */
    private fun drawn(scale: Int, inverted: Boolean = false, margin: Int = 2, canvas: Int = 0): Triple<IntArray, Int, Int> {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to margin))
        val side = matrix.width * scale
        val size = maxOf(side, canvas)
        val offset = (size - side) / 2
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // Outside the code: the terminal's own background, dark when the code is light on dark.
        val pixels = IntArray(size * size) { if (inverted) black else white }
        for (y in 0 until side) for (x in 0 until side) {
            val dark = matrix[x / scale, y / scale]
            pixels[(y + offset) * size + x + offset] = if (dark != inverted) black else white
        }
        return Triple(pixels, size, size)
    }

    @Test
    fun readsADarkOnLightCode() {
        val (px, w, h) = drawn(scale = 6)
        assertEquals(link, QrDecode.fromPixels(px, w, h))
    }

    @Test
    fun readsALightOnDarkCodeAsATerminalWithoutColourDrawsIt() {
        val (px, w, h) = drawn(scale = 6, inverted = true)
        assertEquals(link, QrDecode.fromPixels(px, w, h))
    }

    @Test
    fun readsASmallCodeInABigPhoto() {
        val (px, w, h) = drawn(scale = 5, canvas = 1800)
        assertEquals(link, QrDecode.fromPixels(px, w, h))
    }

    @Test
    fun aPictureWithoutACodeReadsAsNothing() {
        val gray = IntArray(500 * 500) { 0xFF808080.toInt() }
        assertNull(QrDecode.fromPixels(gray, 500, 500))
    }

    @Test
    fun readsACameraFrameFromItsLuminancePlane() {
        val (px, w, h) = drawn(scale = 4, canvas = 400)
        // A camera plane: one byte a pixel, rows padded to a wider stride.
        val stride = w + 64
        val y = ByteArray(stride * h)
        for (row in 0 until h) for (x in 0 until w) y[row * stride + x] = (px[row * w + x] and 0xFF).toByte()
        assertEquals(link, QrDecode.fromLuminance(y, w, h, stride))
        // Light on dark reads as well.
        for (i in y.indices) y[i] = (255 - (y[i].toInt() and 0xFF)).toByte()
        assertEquals(link, QrDecode.fromLuminance(y, w, h, stride))
        // A plane shorter than it claims is refused, not read past its end.
        assertNull(QrDecode.fromLuminance(ByteArray(10), w, h, stride))
    }

    @Test
    fun aSmallCodeIsZoomedInOnAndABigOneIsLeftAlone() {
        // 10 % of the frame at 1x: zoom until it fills about 45 %, capped by the auto-zoom limit.
        assertEquals(QrDecode.MAX_AUTO_ZOOM, QrDecode.zoomFor(108f, 1080, 1f, 8f)!!, 0.001f)
        // 15 % at 1x: 3x would be the cap too; with a 2x camera, 2x.
        assertEquals(2f, QrDecode.zoomFor(162f, 1080, 1f, 2f)!!, 0.001f)
        // 20 % at 1x: 2.25x.
        assertEquals(2.25f, QrDecode.zoomFor(216f, 1080, 1f, 8f)!!, 0.001f)
        // Big enough already, or already at the limit: no change.
        assertNull(QrDecode.zoomFor(400f, 1080, 1f, 8f))
        assertNull(QrDecode.zoomFor(150f, 1080, 3f, 8f))
        // Nonsense in, nothing out.
        assertNull(QrDecode.zoomFor(0f, 1080, 1f, 8f))
        assertNull(QrDecode.zoomFor(100f, 0, 1f, 8f))
    }
}
