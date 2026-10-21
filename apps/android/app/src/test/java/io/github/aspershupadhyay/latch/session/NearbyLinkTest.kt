// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ADR-060: the phone computes the same commitment and number as the gateway (`packages/schemas/v1/nearby`). */
class NearbyLinkTest {
    @Test fun theNumberMatchesTheGatewaysFixture() {
        val url = javaClass.classLoader!!.getResource("nearby/number.json") ?: error("nearby/number.json is not on the test classpath")
        val cases = (Json.parseToJsonElement(File(url.toURI()).readText()) as JsonObject)["cases"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (case in cases.map { it.jsonObject }) {
            val p = NearbyLink.unhex(case["phone_nonce"]!!.jsonPrimitive.content)!!
            val g = NearbyLink.unhex(case["gateway_nonce"]!!.jsonPrimitive.content)!!
            assertEquals(case["commit"]!!.jsonPrimitive.content, NearbyLink.hex(NearbyLink.commitment(g)))
            assertEquals(case["number"]!!.jsonPrimitive.content, NearbyLink.shown(NearbyLink.number(case["pin"]!!.jsonPrimitive.content, p, g)))
        }
    }

    @Test fun hexAndShown() {
        assertEquals("000 007", NearbyLink.shown(7))
        assertEquals("999 999", NearbyLink.shown(999_999))
        assertArrayEquals(byteArrayOf(0, 15, -1), NearbyLink.unhex("000fff"))
        assertNull(NearbyLink.unhex("0g"))
        assertNull(NearbyLink.unhex("abc"))
    }

    @Test fun aFoundComputerGivesTheSameLinkAsItsQrCode() {
        val found = NearbyLink.Found("https://192.168.1.5:8788", "A".repeat(43), "MacBook")
        val link = found.setupLink("K7QXM2PD")
        val setup = Pairing.splitSetup(link)
        assertEquals("https://192.168.1.5:8788#pin=${"A".repeat(43)}", setup.address)
        assertEquals("K7QXM2PD", setup.code)
    }
}
