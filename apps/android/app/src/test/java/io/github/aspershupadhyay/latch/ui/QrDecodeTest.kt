// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aTapBecomesAFocusAreaOnTheTurnedSensor() {
        // Portrait phone, sensor turned 90°: a tap at the top centre is the sensor's left middle.
        assertArrayEquals(intArrayOf(-1000, -100, -800, 100), QrDecode.focusArea(500f, 0f, 1000, 2000, 90))
        // Not turned: the centre stays the centre.
        assertArrayEquals(intArrayOf(-100, -100, 100, 100), QrDecode.focusArea(500f, 1000f, 1000, 2000, 0))
        // Always on the sensor.
        QrDecode.focusArea(-50f, 99999f, 1000, 2000, 270).forEach { assertTrue(it in -1000..1000) }
    }
}
