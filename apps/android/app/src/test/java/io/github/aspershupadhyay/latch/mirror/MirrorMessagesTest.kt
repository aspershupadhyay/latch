// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** The phone reads the same fixtures as the gateway (`packages/schemas/v1/mirror`). */
class MirrorMessagesTest {
    private fun fixture(name: String): List<String> {
        val url = javaClass.classLoader!!.getResource("mirror/$name") ?: error("mirror/$name is not on the test classpath")
        return (Json.parseToJsonElement(File(url.toURI()).readText()) as JsonArray).map { it.toString() }
    }

    @Test fun everyValidViewerMessageParses() {
        val all = fixture("viewer-valid.json")
        assertTrue(all.size >= 15)
        for (raw in all) assertNotNull("rejected: $raw", MirrorMessages.parse(raw))
    }

    @Test fun everyInvalidViewerMessageIsIgnored() {
        for (raw in fixture("viewer-invalid.json")) assertNull("accepted: $raw", MirrorMessages.parse(raw))
    }

    @Test fun gatewayMessagesParse() {
        assertEquals(MirrorIn.Challenge(1, "m_ab"), MirrorMessages.parse("""{"t":"challenge","version":1,"connection":"m_ab"}"""))
        assertEquals(
            MirrorIn.Ready("123456", listOf("http://127.0.0.1:8787/mirror")),
            MirrorMessages.parse("""{"t":"ready","code":"123456","code_expires_ms":5,"urls":["http://127.0.0.1:8787/mirror"]}"""),
        )
        assertEquals(MirrorIn.Ready(null, emptyList()), MirrorMessages.parse("""{"t":"ready","code":null,"urls":[]}"""))
        assertEquals(MirrorIn.Viewer(true, Codec.JPEG), MirrorMessages.parse("""{"t":"viewer","on":true,"codec":"jpeg"}"""))
        assertEquals(MirrorIn.Viewer(false, Codec.H264), MirrorMessages.parse("""{"t":"viewer","on":false}"""))
        assertEquals(MirrorIn.Code(null), MirrorMessages.parse("""{"t":"code","code":null}"""))
        assertEquals(MirrorIn.Touch(Phase.DOWN, 10, 20), MirrorMessages.parse("""{"t":"touch","phase":"down","x":10,"y":20}"""))
        assertEquals(MirrorIn.Text("héllo 😀"), MirrorMessages.parse("""{"t":"text","text":"héllo 😀"}"""))
        assertNull(MirrorMessages.parse("""{"t":"viewer","on":"yes"}"""))
        assertNull(MirrorMessages.parse("not json"))
    }

    @Test fun textCountsCharactersNotUnits() {
        assertTrue(MirrorMessages.validText("😀".repeat(256)))
        assertTrue(!MirrorMessages.validText("😀".repeat(257)))
        assertTrue(!MirrorMessages.validText("a\u0000"))
    }

    @Test fun framesHaveTheTenByteHeader() {
        val f = MirrorMessages.frame(MirrorMessages.KIND_H264, true, 0x0102030405060708, ByteBuffer.wrap(byteArrayOf(9, 8)))
        assertArrayEquals(byteArrayOf(1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 8), f)
    }

    @Test fun outgoingMessagesAreJson() {
        val ai = Json.parseToJsonElement(MirrorMessages.ai("active", owner = false, working = true, listOf(MirrorMessages.Step(5, "ai", "Tapped “Search”", "YouTube"))))
        assertEquals("""{"t":"ai","session":"active","owner":false,"working":true,"steps":[{"at":5,"by":"ai","text":"Tapped “Search”","app":"YouTube"}]}""", ai.toString())
        assertEquals("""{"t":"start","version":1,"key":"k"}""", MirrorMessages.start("k"))
        assertEquals("""{"t":"screen","w":1080,"h":2400,"rotation":90}""", MirrorMessages.screen(1080, 2400, 90))
    }
}
