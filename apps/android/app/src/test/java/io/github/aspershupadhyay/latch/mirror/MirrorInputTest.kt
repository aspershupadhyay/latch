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
        // 400 px of content needs 400 px plus the slop the app spends before it scrolls.
        val s = WheelGesture.plan(540, 1200, 0, 400, 1080, 2400, slop = 24)
        assertEquals(2, s.size)
        assertEquals(listOf(540 to 1200, 540 to 776), s[0].points)
        assertTrue(s[0].continues)
        assertEquals(listOf(540 to 776), s[1].points)
        assertFalse(s[1].continues)
        assertTrue(s[1].continuation)
        // A long scroll stays out of the top and bottom 6 % (the shade and the home gesture).
        assertEquals(listOf(540 to 144, 540 to 2255), WheelGesture.plan(540, 1200, 0, -5000, 1080, 2400, slop = 24)[0].points)
    }

    @Test fun aTrackpadsTinyStepsNeverBecomeATap() {
        // Owner report 2026-10-20: scrolling with the trackpad clicked things.
        assertEquals(emptyList<Segment>(), WheelGesture.plan(540, 1200, 0, 3, 1080, 2400, slop = 24))
        assertEquals(emptyList<Segment>(), WheelGesture.plan(540, 1200, 5, -23, 1080, 2400, slop = 24))
        assertFalse(WheelGesture.enough(5, 23, slop = 24))
        assertTrue(WheelGesture.enough(0, -24, slop = 24))
        // Sideways jitter during a vertical scroll does not swipe a pager.
        val down = WheelGesture.plan(540, 1200, 7, 60, 1080, 2400, slop = 24)[0].points
        assertEquals(540, down[1].first)
        // Every played drag is longer than the slop, wherever the pointer is, even at an edge.
        for (y in listOf(0, 10, 1200, 2390, 2399)) for (dy in listOf(24, -24, 300, -300)) {
            val p = WheelGesture.plan(540, y, 0, dy, 1080, 2400, slop = 24)[0].points
            assertTrue("y=$y dy=$dy", kotlin.math.abs(p[1].second - p[0].second) > 24)
            assertTrue("y=$y dy=$dy", p.all { it.second in 144..2255 })
        }
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
        // With 8-aligned sides a 1080-wide phone is sent at its own size: nothing is resampled.
        assertEquals(1080 to 2400, EncoderSizing.size(1080, 2400, 2560, align = 8))
        assertEquals(1440 to 3200, EncoderSizing.size(1440, 3200, 4000, align = 8))
        assertEquals(1152 to 2560, EncoderSizing.size(1440, 3200, 2560, align = 8))
        assertEquals(864 to 1920, EncoderSizing.size(1080, 2400, 1920))
        assertEquals(1072 to 2400, EncoderSizing.size(1080, 2400, 4000))
        assertEquals(1920 to 864, EncoderSizing.size(2400, 1080, 1920))
        assertEquals(576 to 1280, EncoderSizing.size(1080, 2400, 1280))
        assertEquals(16 to 16, EncoderSizing.size(1, 1, 1920))
    }

    @Test fun theBitrateBacksOffOnDropsAndRecovers() {
        val g = BitrateGovernor(startBps = 6_000_000)
        assertNull(g.dropped(10_000))
        assertNull("one busy moment is not a slow network", g.dropped(10_100))
        assertEquals(4_500_000, g.dropped(10_200))
        assertNull(g.dropped(10_300))
        assertNull(g.dropped(10_400))
        assertNull("at most one step down a second", g.dropped(10_500))
        assertEquals(3_375_000, g.dropped(11_300))
        assertNull(g.clean(12_000))
        assertEquals(4_050_000, g.clean(14_600))
        repeat(100) { g.clean(20_000L + it * 4_000) }
        assertEquals(12_000_000, g.bps)
        repeat(300) { g.dropped(2_000_000L + it * 400) }
        assertEquals(1_500_000, g.bps)
        assertTrue(g.atFloor)
    }

    @Test fun theBudgetFollowsThePictureAndANewViewerStartsSharp() {
        // A 1080×2400 phone at 60 fps: about 28 Mbit/s for motion, starting at three quarters.
        assertEquals(27_993_600, VideoBudget.maxBps(1080, 2400, 60))
        assertEquals(20_995_200, VideoBudget.startBps(1080, 2400, 60))
        assertEquals("a small picture still gets a usable ceiling", 4_000_000, VideoBudget.maxBps(432, 960, 30))
        assertEquals("never past what a Wi-Fi carries well", 40_000_000, VideoBudget.maxBps(2560, 2560, 120))
        val g = BitrateGovernor()
        g.fitTo(VideoBudget.maxBps(1080, 2400, 60), VideoBudget.startBps(1080, 2400, 60), restart = true)
        assertEquals(20_995_200, g.bps)
        repeat(30) { g.clean(10_000L + it * 4_000) }
        assertEquals(27_993_600, g.bps)
        // A smaller picture for the same viewer keeps what the network showed, under its own ceiling.
        g.fitTo(VideoBudget.maxBps(576, 1280, 30), VideoBudget.startBps(576, 1280, 30), restart = false)
        assertEquals(4_000_000, g.bps)
    }

    @Test fun busyFramesAtASharpBitrateAreNotDroppedAsIfTheNetworkWereSlow() {
        // Before: 96 KiB, less than one frame of a video at full size.
        assertEquals(96L * 1024, MirrorLink.queueLimit(key = false, bps = 4_000_000))
        assertEquals(315_000L, MirrorLink.queueLimit(key = false, bps = 21_000_000))
        assertEquals(630_000L, MirrorLink.queueLimit(key = true, bps = 21_000_000))
        assertEquals(512L * 1024, MirrorLink.queueLimit(key = true, bps = 0))
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

    @Test fun theClipboardFollowsOnlyWhileTheOwnerSwitchedItOn() {
        // R44: the page's switch and the helper's command.
        assertEquals(MirrorIn.ClipboardFollow(true), MirrorMessages.parse("""{"t":"clipboard_follow","on":true}"""))
        assertNull(MirrorMessages.parse("""{"t":"clipboard_follow","on":true,"text":"x"}"""))
        assertEquals("cw 1", ShellInputCommands.watchClipboard(true))
        assertEquals("cw 0", ShellInputCommands.watchClipboard(false))
    }
}
