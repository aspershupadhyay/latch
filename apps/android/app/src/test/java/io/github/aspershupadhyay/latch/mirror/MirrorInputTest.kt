// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import io.github.aspershupadhyay.latch.mirror.StrokePlanner.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorInputTest {
    @Test fun aQuickClickIsOneTap() {
        val p = StrokePlanner()
        p.down(100, 200, 0)
        assertEquals(listOf(Segment(listOf(100 to 200), 60, continues = false, continuation = false)), p.up(100, 200, 60))
        assertFalse(p.active)
    }

    @Test fun aFlickKeepsItsPathAndSpeed() {
        val p = StrokePlanner()
        p.down(500, 1500, 0)
        assertEquals(emptyList<Segment>(), p.move(500, 1200, 30))
        assertEquals(emptyList<Segment>(), p.move(500, 800, 60))
        val s = p.up(500, 700, 80)
        assertEquals(listOf(Segment(listOf(500 to 1500, 500 to 1200, 500 to 800, 500 to 700), 80, continues = false, continuation = false)), s)
    }

    @Test fun aLongDragGoesLiveAndContinuesPieceByPiece() {
        val p = StrokePlanner()
        p.down(100, 100, 0)
        p.move(110, 100, 50)
        // Past the threshold: what arrived so far, finger kept down.
        val start = p.move(120, 100, 130)
        assertEquals(listOf(Segment(listOf(100 to 100, 110 to 100, 120 to 100), 130, continues = true, continuation = false)), start)
        // While that runs, moves wait; when it finishes they go as one continuation, paced by arrival.
        assertEquals(emptyList<Segment>(), p.move(130, 100, 150))
        assertEquals(emptyList<Segment>(), p.move(140, 100, 170))
        assertEquals(listOf(Segment(listOf(120 to 100, 130 to 100, 140 to 100), 40, continues = true, continuation = true)), p.done())
        // Nothing new: nothing to send; the finger stays down.
        assertEquals(emptyList<Segment>(), p.done())
        // A move now goes at once, then the lift ends the stroke.
        assertEquals(listOf(Segment(listOf(140 to 100, 150 to 100), 30, continues = true, continuation = true)), p.move(150, 100, 200))
        assertEquals(emptyList<Segment>(), p.up(150, 100, 220))
        assertEquals(listOf(Segment(listOf(150 to 100), 20, continues = false, continuation = true)), p.done())
        assertFalse(p.active)
    }

    @Test fun aHeldPressGoesLiveOnTheTimerForALongPress() {
        val p = StrokePlanner()
        p.down(300, 300, 0)
        assertEquals(emptyList<Segment>(), p.tick(100))
        assertEquals(listOf(Segment(listOf(300 to 300), 120, continues = true, continuation = false)), p.tick(120))
        assertEquals(emptyList<Segment>(), p.done())
        assertEquals(listOf(Segment(listOf(300 to 300), 680, continues = false, continuation = true)), p.up(300, 300, 800))
    }

    @Test fun cancelLiftsTheFingerAndFailureForgetsTheStroke() {
        val p = StrokePlanner()
        p.down(1, 1, 0)
        p.tick(200)
        assertEquals(emptyList<Segment>(), p.cancel(210))
        assertEquals(listOf(Segment(listOf(1 to 1), 10, continues = false, continuation = true)), p.done())
        p.down(1, 1, 300)
        p.tick(500)
        p.failed()
        assertFalse(p.active)
        assertEquals(emptyList<Segment>(), p.move(5, 5, 510))
        // A pending press is just dropped.
        p.down(1, 1, 600)
        assertEquals(emptyList<Segment>(), p.cancel(610))
        assertFalse(p.active)
    }

    @Test fun longPathsAreSampledAndDurationsBounded() {
        val p = StrokePlanner()
        p.down(0, 0, 0)
        for (i in 1..99) p.move(i, 0, i.toLong())
        val s = p.up(100, 0, 100).single()
        assertEquals(StrokePlanner.MAX_POINTS, s.points.size)
        assertEquals(0 to 0, s.points.first())
        assertEquals(100 to 0, s.points.last())
        val q = StrokePlanner(liveAfterMs = 1_000_000)
        q.down(0, 0, 0)
        assertEquals(StrokePlanner.MAX_SEGMENT_MS, q.up(0, 0, 60_000).single().durationMs)
    }

    @Test fun theWheelScrollsByTheDistanceAndEndsStill() {
        val s = WheelGesture.plan(540, 1200, 0, 400, 1080, 2400)
        assertEquals(2, s.size)
        assertEquals(listOf(540 to 1200, 540 to 800), s[0].points)
        assertTrue(s[0].continues)
        assertEquals(listOf(540 to 800), s[1].points)
        assertFalse(s[1].continues)
        assertTrue(s[1].continuation)
        assertEquals(listOf(540 to 1200, 540 to 2399), WheelGesture.plan(540, 1200, 0, -5000, 1080, 2400)[0].points)
        assertEquals(emptyList<Segment>(), WheelGesture.plan(540, 0, 0, 100, 1080, 2400))
    }

    @Test fun typingEditsAtTheCursor() {
        assertEquals(TextEdit.Result("hello", 5), TextEdit.insert("hell", 4, 4, "o"))
        assertEquals(TextEdit.Result("hXllo", 2), TextEdit.insert("hello", 1, 2, "X"))
        assertEquals(TextEdit.Result("ab", 2), TextEdit.insert("a", -1, -1, "b"))
        assertEquals(TextEdit.Result("ac", 1), TextEdit.backspace("abc", 2, 2))
        assertEquals(TextEdit.Result("a", 1), TextEdit.backspace("a😀", 3, 3))
        assertNull(TextEdit.backspace("abc", 0, 0))
        assertEquals(TextEdit.Result("c", 0), TextEdit.backspace("abc", 0, 2))
        assertEquals(TextEdit.Result("ab", 2), TextEdit.delete("ab😀", 2, 2))
        assertNull(TextEdit.delete("ab", 2, 2))
        assertEquals(1, TextEdit.move("a😀b", 3, 3, MirrorKey.LEFT))
        assertEquals(3, TextEdit.move("a😀b", 1, 1, MirrorKey.RIGHT))
        assertEquals(0, TextEdit.move("abc", 2, 2, MirrorKey.UP))
        assertEquals(3, TextEdit.move("abc", 0, 0, MirrorKey.DOWN))
    }

    @Test fun wordsAndLinesMoveTheCursorFurther() {
        assertEquals(TextEdit.Result("hello ", 6), TextEdit.wordBackspace("hello world", 11, 11))
        assertEquals(TextEdit.Result("world", 0), TextEdit.wordBackspace("hello  world", 7, 7))
        assertNull(TextEdit.wordBackspace("abc", 0, 0))
        assertEquals(6, TextEdit.move("hello world", 9, 9, MirrorKey.WORD_LEFT))
        assertEquals(11, TextEdit.move("hello world", 5, 5, MirrorKey.WORD_RIGHT))
        assertEquals(4, TextEdit.move("one\ntwo", 6, 6, MirrorKey.HOME))
        assertEquals(3, TextEdit.move("one\ntwo", 1, 1, MirrorKey.END))
        assertEquals(7, TextEdit.move("one\ntwo", 5, 5, MirrorKey.END))
    }

    @Test fun aHintShownAsTextIsAnEmptyField() {
        // WhatsApp's "Message": typing must not land after it, and Backspace must not eat it.
        assertEquals("", TextEdit.realText("Message", showingHint = true, hint = "Message", placeholder = null))
        assertEquals("", TextEdit.realText("Message", showingHint = false, hint = "Message", placeholder = null))
        assertEquals("", TextEdit.realText("Message", showingHint = false, hint = null, placeholder = "Message"))
        assertEquals("Messages", TextEdit.realText("Messages", showingHint = false, hint = "Message", placeholder = "Message"))
        assertEquals("hi", TextEdit.realText("hi", showingHint = false, hint = null, placeholder = null))
        assertEquals(TextEdit.Result("h", 1), TextEdit.insert(TextEdit.realText("Message", false, "Message", null), -1, -1, "h"))
    }

    @Test fun theEncoderSizeFitsAndAligns() {
        assertEquals(864 to 1920, EncoderSizing.size(1080, 2400, 1920))
        assertEquals(1072 to 2400, EncoderSizing.size(1080, 2400, 4000))
        assertEquals(1920 to 864, EncoderSizing.size(2400, 1080, 1920))
        assertEquals(576 to 1280, EncoderSizing.size(1080, 2400, 1280))
        assertEquals(16 to 16, EncoderSizing.size(1, 1, 1920))
    }

    @Test fun theBitrateBacksOffOnDropsAndRecoversSlowly() {
        val g = BitrateGovernor(startBps = 6_000_000)
        assertNull(g.dropped(10_000))
        assertEquals(4_200_000, g.dropped(10_100))
        assertNull(g.dropped(10_200))
        assertNull("at most one step down a second", g.dropped(10_300))
        assertEquals(2_940_000, g.dropped(11_200))
        assertNull(g.clean(12_000))
        assertEquals(3_381_000, g.clean(16_300))
        repeat(100) { g.clean(20_000L + it * 6_000) }
        assertEquals(12_000_000, g.bps)
        repeat(100) { g.dropped(2_000_000L + it * 1_100) }
        assertEquals(1_000_000, g.bps)
    }

    @Test fun keyFramesCarryTheirParameterSets() {
        val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x01)
        val idr = java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65, 0x11))
        assertEquals(5, AnnexB.firstNalType(idr))
        val joined = AnnexB.withConfig(config, idr, key = true)
        val bytes = ByteArray(joined.remaining()).also { joined.get(it) }
        assertEquals((config + byteArrayOf(0, 0, 0, 1, 0x65, 0x11)).toList(), bytes.toList())
        assertEquals("a delta frame is sent as is", idr, AnnexB.withConfig(config, idr, key = false))
        val already = java.nio.ByteBuffer.wrap(config + byteArrayOf(0, 0, 1, 0x65))
        assertEquals(7, AnnexB.firstNalType(already))
        assertEquals("a key frame that has its SPS is sent as is", already, AnnexB.withConfig(config, already, key = true))
        assertEquals(-1, AnnexB.firstNalType(java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
    }

    @Test fun anH265KeyFrameGetsItsVpsSpsAndPpsOnce() {
        // R40: H.265 NAL types are 6 bits after the forbidden bit: 0x40 is a VPS (32), 0x26 an IDR (19).
        val config = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0c, 0, 0, 0, 1, 0x42, 0x01, 0x01)
        val idr = java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x11))
        assertEquals(19, AnnexB.firstNalType(idr, hevc = true))
        val joined = AnnexB.withConfig(config, idr, key = true, hevc = true)
        assertEquals(config.size + 7, joined.remaining())
        val already = java.nio.ByteBuffer.wrap(config + byteArrayOf(0, 0, 1, 0x26, 0x01))
        assertEquals(32, AnnexB.firstNalType(already, hevc = true))
        assertEquals(already, AnnexB.withConfig(config, already, key = true, hevc = true))
        assertEquals(600_000, MirrorCapture.encoderBps(1_000_000, hevc = true))
        assertEquals(1_000_000, MirrorCapture.encoderBps(1_000_000, hevc = false))
    }

    @Test fun aPinchFromTheComputerIsTwoFingersOnTheScreen() {
        // A 1080x2400 phone: the fingers start a quarter of the short side apart.
        assertEquals(listOf(405, 1200, 675, 1200), PinchFingers.at(540, 1200, 1.0, 0.0, 1080, 2400).toList())
        assertEquals(listOf(270, 1200, 810, 1200), PinchFingers.at(540, 1200, 2.0, 0.0, 1080, 2400).toList())
        // Turned a quarter: one above the other.
        assertEquals(listOf(540, 1065, 540, 1335), PinchFingers.at(540, 1200, 1.0, 90.0, 1080, 2400).toList())
        // Never off the screen, never on top of each other.
        val edge = PinchFingers.at(0, 0, 20.0, 0.0, 1080, 2400)
        assertTrue(edge.all { it >= 0 } && edge[2] <= 1079)
        val close = PinchFingers.at(540, 1200, 0.05, 0.0, 1080, 2400)
        assertEquals(48, close[2] - close[0])
        assertEquals("pd 1 2 3 4", ShellInputCommands.pinch(PinchPhase.START, intArrayOf(1, 2, 3, 4)))
        assertEquals("pu 1 2 3 4", ShellInputCommands.pinch(PinchPhase.END, intArrayOf(1, 2, 3, 4)))
        assertEquals(
            MirrorIn.Pinch(PinchPhase.MOVE, 540, 1200, 1.8, -30.5),
            MirrorMessages.parse("""{"t":"pinch","phase":"move","x":540,"y":1200,"scale":1.8,"angle":-30.5}"""),
        )
        assertNull(MirrorMessages.parse("""{"t":"pinch","phase":"move","x":540,"y":1200,"scale":50,"angle":0}"""))
    }
}
