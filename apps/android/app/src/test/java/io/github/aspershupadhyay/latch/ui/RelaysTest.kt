// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.data.ConnectionKind
import io.github.aspershupadhyay.latch.data.Pairing
import io.github.aspershupadhyay.latch.session.UsageLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-075: one slot per relay, and each relay's use in the owner's words. */
class RelaysTest {
    @Test
    fun eachRelayHasItsOwnSlot() {
        assertEquals(ConnectionKind.WIFI, Pairing("https://192.168.1.5:8788", "d_1", "Phone", pin = "p").kind)
        assertEquals(ConnectionKind.CLOUDFLARE, Pairing("https://latch-relay.ada.workers.dev", "d_2", "Phone", relay = "cloudflare").kind)
        assertEquals(ConnectionKind.HOSTED, Pairing("https://latch-gateway-ada.vercel.app", "d_3", "Phone", relay = "vercel").kind)
        // Pairings saved before ADR-075 have no relay: they stay in the Vercel slot.
        assertEquals(ConnectionKind.HOSTED, Pairing("https://latch-gateway-ada.vercel.app", "d_4", "Phone").kind)
        // A pinned gateway is the Wi-Fi one whatever it reports.
        assertEquals(ConnectionKind.WIFI, Pairing("https://192.168.1.5:8788", "d_5", "Phone", pin = "p", relay = "cloudflare").kind)
        assertEquals(ConnectionKind.CLOUDFLARE, ConnectionKind.forImplementation("cloudflare"))
        assertEquals(ConnectionKind.HOSTED, ConnectionKind.forImplementation("vercel"))
        assertEquals(ConnectionKind.HOSTED, ConnectionKind.forImplementation("rust"))
        assertTrue(ConnectionKind.HOSTED.hosted && ConnectionKind.CLOUDFLARE.hosted)
        assertFalse(ConnectionKind.WIFI.hosted)
        assertEquals(listOf("Vercel", "Cloudflare", "Wi-Fi"), ConnectionKind.entries.map { it.short })
    }

    @Test
    fun theMostUsedAllowanceIsShown() {
        assertNull(relayUse(emptyList()))
        val use = relayUse(
            listOf(
                UsageLine("requests", "Vercel function calls", 120_000, 1_000_000, "month"),
                UsageLine("store_commands", "Upstash Redis commands", 420_000, 500_000, "month"),
            ),
        )!!
        assertEquals(0.84f, use.share, 0.001f)
        assertEquals("84% of this month's free use", use.text)
        assertEquals("100% of today's free use", relayUse(listOf(UsageLine("requests", "Requests", 150_000, 100_000, "day")))!!.text)
        assertEquals(0f, UsageLine("requests", "x", 5, 0, "day").share)
    }
}
