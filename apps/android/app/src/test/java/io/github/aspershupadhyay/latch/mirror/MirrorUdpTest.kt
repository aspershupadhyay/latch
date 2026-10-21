// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R56 (ADR-061): the phone's side of the mirror's UDP packets, held to the gateway's bytes. */
class MirrorUdpTest {
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun packetsMatchTheSharedFixture() {
        val url = javaClass.classLoader!!.getResource("mirror/udp.json") ?: error("mirror/udp.json is not on the test classpath")
        val fixture = Json.parseToJsonElement(File(url.toURI()).readText()) as JsonObject
        val key = unhex(fixture["key"]!!.jsonPrimitive.content)
        val id = unhex(fixture["id"]!!.jsonPrimitive.content)
        val packets = fixture["packets"]!!.jsonArray
        assertTrue(packets.size >= 5)
        for (case in packets.map { it.jsonObject }) {
            val keys = MirrorUdp.Keys(key, id)
            val dir = case["direction"]!!.jsonPrimitive.int.toByte()
            val counter = case["counter"]!!.jsonPrimitive.long
            val plain = unhex(case["plaintext"]!!.jsonPrimitive.content)
            val sealed = keys.seal(dir, counter, plain)
            assertEquals(case["packet"]!!.jsonPrimitive.content, MirrorUdp.hex(sealed))
            val (c, opened) = keys.open(dir, sealed)!!
            assertEquals(counter, c)
            assertArrayEquals(plain, opened)
        }
    }

    @Test fun aPacketOpensOnlyWithItsKeyDirectionAndUnchangedBytes() {
        val keys = MirrorUdp.Keys(ByteArray(32) { 1 }, ByteArray(8) { 7 })
        val packet = keys.seal(MirrorUdp.DIR_GATEWAY, 5, "hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), keys.open(MirrorUdp.DIR_GATEWAY, packet)!!.second)
        assertNull(keys.open(MirrorUdp.DIR_PHONE, packet))
        assertNull(MirrorUdp.Keys(ByteArray(32) { 2 }, ByteArray(8) { 7 }).open(MirrorUdp.DIR_GATEWAY, packet))
        assertNull(MirrorUdp.Keys(ByteArray(32) { 1 }, ByteArray(8) { 8 }).open(MirrorUdp.DIR_GATEWAY, packet))
        for (i in packet.indices) {
            val bad = packet.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i changed", keys.open(MirrorUdp.DIR_GATEWAY, bad))
        }
        // Only the first [length] bytes of a receive buffer count.
        val buffer = packet + ByteArray(100)
        assertArrayEquals("hello".toByteArray(), keys.open(MirrorUdp.DIR_GATEWAY, buffer, packet.size)!!.second)
    }

    @Test fun theReplayWindowTakesEachCounterOnce() {
        val w = MirrorUdp.ReplayWindow()
        assertTrue(w.accept(10))
        assertFalse(w.accept(10))
        assertTrue(w.accept(12))
        assertTrue(w.accept(11))
        assertFalse(w.accept(11))
        assertTrue(w.accept(100))
        assertFalse(w.accept(36))
        assertTrue(w.accept(37))
        assertFalse(w.accept(-1))
    }

    @Test fun aFrameIsCutIntoPiecesThatPutItBackTogether() {
        val frame = ByteArray(3000) { it.toByte() }
        val pieces = MirrorUdp.fragments(42, frame)
        assertEquals(3, pieces.size)
        pieces.forEachIndexed { i, p ->
            assertEquals(MirrorUdp.T_FRAGMENT, p[0])
            val b = java.nio.ByteBuffer.wrap(p, 1, 8)
            assertEquals(42, b.int)
            assertEquals(i, b.short.toInt())
            assertEquals(3, b.short.toInt())
            assertTrue(p.size <= MirrorUdp.FRAGMENT_HEADER_BYTES + MirrorUdp.MAX_FRAGMENT_BYTES)
        }
        val back = pieces.fold(ByteArray(0)) { acc, p -> acc + p.copyOfRange(MirrorUdp.FRAGMENT_HEADER_BYTES, p.size) }
        assertArrayEquals(frame, back)
        assertEquals(1, MirrorUdp.fragments(1, ByteArray(10)).size)
    }

    @Test fun requestsAndReportsFromTheGatewayAreRead() {
        assertEquals(MirrorUdp.Nack(7, listOf(1, 3)), MirrorUdp.parseNack(byteArrayOf(4, 0, 0, 0, 7, 0, 2, 0, 1, 0, 3)))
        assertEquals(MirrorUdp.Nack(7, emptyList()), MirrorUdp.parseNack(byteArrayOf(4, 0, 0, 0, 7, 0, 0)))
        assertNull("count and length disagree", MirrorUdp.parseNack(byteArrayOf(4, 0, 0, 0, 7, 0, 2, 0, 1)))
        val report = MirrorUdp.parseReport(byteArrayOf(5, 0, 0, 0, 240.toByte(), 0, 0, 0, 2, 0, 0, 0, 0))!!
        assertEquals(MirrorUdp.Report(240, 2, 0), report)
        assertFalse("under 2 % is fine", report.lossy)
        assertTrue(MirrorUdp.Report(90, 10, 0).lossy)
        assertTrue("a skipped frame is lossy", MirrorUdp.Report(100, 0, 1).lossy)
        assertNull(MirrorUdp.parseReport(byteArrayOf(5, 0)))
    }

    @Test fun theResendRingKeepsTheNewestFramesWithinItsLimits() {
        val ring = MirrorUdp.ResendRing(maxFrames = 3, maxBytes = 10_000)
        for (seq in 0 until 5) ring.put(seq, MirrorUdp.fragments(seq, ByteArray(1500)))
        assertTrue("the oldest went", ring.get(MirrorUdp.Nack(0, emptyList())).isEmpty())
        assertEquals(2, ring.get(MirrorUdp.Nack(4, emptyList())).size)
        assertEquals(1, ring.get(MirrorUdp.Nack(4, listOf(1, 9))).size)
        val small = MirrorUdp.ResendRing(maxFrames = 100, maxBytes = 2_000)
        small.put(1, MirrorUdp.fragments(1, ByteArray(1500)))
        small.put(2, MirrorUdp.fragments(2, ByteArray(1500)))
        assertTrue("past the byte limit the oldest went", small.get(MirrorUdp.Nack(1, emptyList())).isEmpty())
    }

    @Test fun theOfferAndTheAnswerAreReadAsTheGatewayWritesThem() {
        val (id, key) = MirrorUdp.newSession()
        val offer = Json.parseToJsonElement(MirrorUdp.offer(id, key)).jsonObject
        assertEquals("udp", offer["t"]!!.jsonPrimitive.content)
        assertEquals(16, offer["id"]!!.jsonPrimitive.content.length)
        assertEquals(64, offer["key"]!!.jsonPrimitive.content.length)
        assertEquals(MirrorIn.Udp(8788), MirrorMessages.parse("""{"t":"udp","port":8788}"""))
        assertEquals(MirrorIn.Udp(null), MirrorMessages.parse("""{"t":"udp","port":null}"""))
        assertNull(MirrorMessages.parse("""{"t":"udp","port":70000}"""))
        assertNull(MirrorMessages.parse("""{"t":"udp","port":"8788"}"""))
    }
}
