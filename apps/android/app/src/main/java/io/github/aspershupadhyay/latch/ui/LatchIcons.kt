// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Latch's own line icons on a 24-unit grid, drawn in code so the app needs no
 * icon library. Strokes are black and tinted by `Icon`.
 */
object LatchIcons {
    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.dot(x: Float, y: Float) {
        moveTo(x, y)
        lineTo(x + 0.01f, y)
    }

    /** The Latch mark (see Logo.kt): a hook that has caught a pin. */
    val Mark: ImageVector = ImageVector.Builder("mark", 24.dp, 24.dp, 24f, 24f)
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6.4f, 18.4f); lineTo(6.4f, 10.4f)
            arcTo(5f, 5f, 0f, false, true, 16.4f, 10.4f)
            lineTo(16.4f, 12.6f)
        }
        .path(fill = SolidColor(Color.Black)) { circle(16.4f, 17.2f, 2.5f) }
        .build()

    val Clipboard = icon("clipboard") {
        moveTo(9f, 4f); lineTo(6.5f, 4f); arcToRelative(1.5f, 1.5f, 0f, false, false, -1.5f, 1.5f); lineTo(5f, 19.5f)
        arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, 1.5f); lineTo(17.5f, 21f); arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, -1.5f)
        lineTo(19f, 5.5f); arcToRelative(1.5f, 1.5f, 0f, false, false, -1.5f, -1.5f); lineTo(15f, 4f)
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f); lineTo(9f, 6f); close()
        moveTo(8.5f, 11f); lineTo(15.5f, 11f)
        moveTo(8.5f, 15f); lineTo(13f, 15f)
    }

    val Home = icon("home") {
        moveTo(3f, 10.5f); lineTo(12f, 3.5f); lineTo(21f, 10.5f)
        moveTo(5f, 9f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 9f)
        moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f)
    }

    val Shield = icon("shield") {
        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 11f)
        curveTo(20f, 16f, 16.5f, 19.5f, 12f, 21f)
        curveTo(7.5f, 19.5f, 4f, 16f, 4f, 11f)
        lineTo(4f, 6f); close()
    }

    val ShieldCheck = icon("shield-check") {
        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 11f)
        curveTo(20f, 16f, 16.5f, 19.5f, 12f, 21f)
        curveTo(7.5f, 19.5f, 4f, 16f, 4f, 11f)
        lineTo(4f, 6f); close()
        moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(15.5f, 9.5f)
    }

    val Plug = icon("plug") {
        moveTo(9f, 3f); lineTo(9f, 7f)
        moveTo(15f, 3f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(18f, 7f); lineTo(18f, 11f)
        curveTo(18f, 14.3f, 15.3f, 17f, 12f, 17f)
        curveTo(8.7f, 17f, 6f, 14.3f, 6f, 11f); close()
        moveTo(12f, 17f); lineTo(12f, 21f)
    }

    val Pulse = icon("pulse") {
        moveTo(3f, 12f); lineTo(7f, 12f); lineTo(10f, 5f); lineTo(14f, 19f); lineTo(17f, 12f); lineTo(21f, 12f)
    }

    val Sliders = icon("sliders") {
        moveTo(4f, 6f); lineTo(13f, 6f); moveTo(19f, 6f); lineTo(20f, 6f); circle(16f, 6f, 2.2f)
        moveTo(4f, 12f); lineTo(5f, 12f); moveTo(11f, 12f); lineTo(20f, 12f); circle(8f, 12f, 2.2f)
        moveTo(4f, 18f); lineTo(11f, 18f); moveTo(17f, 18f); lineTo(20f, 18f); circle(14f, 18f, 2.2f)
    }

    val Eye = icon("eye") {
        moveTo(2.5f, 12f)
        curveTo(4.8f, 7.5f, 8.2f, 5f, 12f, 5f)
        curveTo(15.8f, 5f, 19.2f, 7.5f, 21.5f, 12f)
        curveTo(19.2f, 16.5f, 15.8f, 19f, 12f, 19f)
        curveTo(8.2f, 19f, 4.8f, 16.5f, 2.5f, 12f); close()
        circle(12f, 12f, 3f)
    }

    val Camera = icon("camera") {
        moveTo(4f, 8f); lineTo(7.5f, 8f); lineTo(9f, 5.5f); lineTo(15f, 5.5f); lineTo(16.5f, 8f); lineTo(20f, 8f)
        lineTo(20f, 19f); lineTo(4f, 19f); close()
        circle(12f, 13f, 3.5f)
    }

    val Tap = icon("tap") {
        circle(12f, 9f, 2f)
        moveTo(7.2f, 12.6f)
        arcToRelative(6f, 6f, 0f, true, true, 9.6f, 0f)
        moveTo(12f, 13f); lineTo(12f, 21f)
        moveTo(9f, 18f); lineTo(12f, 21f); lineTo(15f, 18f)
    }

    val Keyboard = icon("keyboard") {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f); lineTo(20f, 17.5f); lineTo(4f, 17.5f); close()
        dot(8f, 10f); dot(12f, 10f); dot(16f, 10f)
        moveTo(8f, 14f); lineTo(16f, 14f)
    }

    val Back = icon("back") {
        moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f)
        moveTo(4f, 12f); lineTo(20f, 12f)
    }

    val Apps = icon("apps") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }

    val Phone = icon("phone") {
        moveTo(7f, 3f); lineTo(17f, 3f); lineTo(17f, 21f); lineTo(7f, 21f); close()
        moveTo(11f, 18f); lineTo(13f, 18f)
    }

    val Bell = icon("bell") {
        moveTo(6f, 16f); lineTo(6f, 11f)
        curveTo(6f, 7.7f, 8.7f, 5f, 12f, 5f)
        curveTo(15.3f, 5f, 18f, 7.7f, 18f, 11f)
        lineTo(18f, 16f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close()
        moveTo(10f, 21f); lineTo(14f, 21f)
    }

    val Person = icon("person") {
        circle(12f, 5f, 1.8f)
        moveTo(5f, 9f); lineTo(19f, 9f)
        moveTo(12f, 9f); lineTo(12f, 14.5f)
        moveTo(12f, 14.5f); lineTo(8.5f, 21f)
        moveTo(12f, 14.5f); lineTo(15.5f, 21f)
    }

    val Battery = icon("battery") {
        moveTo(3f, 8f); lineTo(18f, 8f); lineTo(18f, 16f); lineTo(3f, 16f); close()
        moveTo(21f, 11f); lineTo(21f, 13f)
        moveTo(11.5f, 9.5f); lineTo(9f, 12f); lineTo(12f, 12f); lineTo(9.5f, 14.5f)
    }

    val Check = icon("check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }

    val ChevronRight = icon("chevron-right") {
        moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
    }

    val Copy = icon("copy") {
        moveTo(8f, 8f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(8f, 20f); close()
        moveTo(4f, 16f); lineTo(4f, 4f); lineTo(16f, 4f)
    }

    val Stop = icon("stop", filled = true) {
        moveTo(7f, 6f); lineTo(17f, 6f)
        arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
        lineTo(18f, 17f)
        arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
        lineTo(7f, 18f)
        arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
        lineTo(6f, 7f)
        arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
        close()
    }

    val Pause = icon("pause") {
        moveTo(9f, 6f); lineTo(9f, 18f)
        moveTo(15f, 6f); lineTo(15f, 18f)
    }

    val Play = icon("play", filled = true) {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
    }

    val Key = icon("key") {
        circle(8f, 15f, 4f)
        moveTo(11f, 12f); lineTo(20f, 3f)
        moveTo(17f, 6f); lineTo(20f, 9f)
    }

    val Lock = icon("lock") {
        moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(8.5f, 11f); lineTo(8.5f, 8f)
        curveTo(8.5f, 6f, 10f, 4.5f, 12f, 4.5f)
        curveTo(14f, 4.5f, 15.5f, 6f, 15.5f, 8f)
        lineTo(15.5f, 11f)
    }

    val Cloud = icon("cloud") {
        moveTo(7f, 18f); lineTo(17f, 18f)
        curveTo(19.2f, 18f, 21f, 16.2f, 21f, 14f)
        curveTo(21f, 11.9f, 19.4f, 10.2f, 17.3f, 10f)
        curveTo(16.6f, 7.2f, 14.5f, 5f, 11.5f, 5f)
        curveTo(8.5f, 5f, 6f, 7.4f, 6f, 10.4f)
        curveTo(4.3f, 10.9f, 3f, 12.4f, 3f, 14.2f)
        curveTo(3f, 16.3f, 4.8f, 18f, 7f, 18f); close()
    }

    val Folder = icon("folder") {
        moveTo(3f, 7f); lineTo(3f, 18f); lineTo(21f, 18f); lineTo(21f, 9f); lineTo(12f, 9f)
        lineTo(10f, 6f); lineTo(4f, 6f); close()
    }

    val Photo = icon("photo") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20f, 17f)
        circle(15.5f, 8.8f, 1.3f)
    }

    val Share = icon("share") {
        circle(6f, 12f, 2.4f)
        circle(18f, 6f, 2.4f)
        circle(18f, 18f, 2.4f)
        moveTo(8.2f, 10.9f); lineTo(15.8f, 7.1f)
        moveTo(8.2f, 13.1f); lineTo(15.8f, 16.9f)
    }

    val Trash = icon("trash") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
    }

    val Leave = icon("leave") {
        moveTo(15f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(15f, 20f)
        moveTo(10f, 8f); lineTo(6f, 12f); lineTo(10f, 16f)
        moveTo(6f, 12f); lineTo(15f, 12f)
    }

    val Sparkle = icon("sparkle") {
        moveTo(12f, 3f); lineTo(13.8f, 9.2f); lineTo(20f, 11f); lineTo(13.8f, 12.8f)
        lineTo(12f, 19f); lineTo(10.2f, 12.8f); lineTo(4f, 11f); lineTo(10.2f, 9.2f); close()
    }

    val Warning = icon("warning") {
        moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
        moveTo(12f, 9.5f); lineTo(12f, 14f)
        dot(12f, 17f)
    }

    val Clock = icon("clock") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }

    val Plus = icon("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val External = icon("external") {
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
        moveTo(20f, 4f); lineTo(11f, 13f)
        moveTo(18f, 14f); lineTo(18f, 20f); lineTo(4f, 20f); lineTo(4f, 6f); lineTo(10f, 6f)
    }

    val Info = icon("info") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 11f); lineTo(12f, 16f)
        dot(12f, 8f)
    }
}
