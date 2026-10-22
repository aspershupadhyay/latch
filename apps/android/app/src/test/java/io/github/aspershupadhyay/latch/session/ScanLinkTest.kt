// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-069: one scanner for the Wi-Fi gateway, the hosted relay, and the mirror page. */
class ScanLinkTest {
    private val pin = "A".repeat(43)
    private val key = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO-_"

    @Test
    fun theWifiGatewaysCodeIsASetupLink() {
        val text = "https://192.168.1.5:8788#pin=$pin&code=K7QXM2PD"
        assertEquals(ScanLink.WifiSetup(text), ScanLink.parse(" $text ", allowCleartext = false))
        // Without its code or pin it is not one.
        assertNull(ScanLink.parse("https://192.168.1.5:8788#pin=$pin", allowCleartext = false))
    }

    @Test
    fun theHostedRelaysPageIsAHostedLink() {
        assertEquals(ScanLink.Hosted("https://latch-gateway.vercel.app"), ScanLink.parse("https://latch-gateway.vercel.app/#connect", false))
        assertEquals(ScanLink.Hosted("https://relay.example"), ScanLink.parse("https://relay.example#connect", false))
        // Plain http only where the build allows it; paths and other fragments are not the relay's code.
        assertNull(ScanLink.parse("http://relay.example#connect", false))
        assertNull(ScanLink.parse("https://relay.example/page#connect", false))
        assertNull(ScanLink.parse("https://relay.example#other", false))
        assertNull(ScanLink.parse("https://relay.example", false))
    }

    @Test
    fun theMirrorPagesCodeCarriesItsKeyAndPin() {
        assertEquals(
            ScanLink.Mirror("http://127.0.0.1:8788", key, pin),
            ScanLink.parse("http://127.0.0.1:8788/mirror#k=$key&pin=$pin", false),
        )
        assertEquals(
            ScanLink.Mirror("https://relay.example", key, null),
            ScanLink.parse("https://relay.example/mirror#k=$key", false),
        )
        assertNull(ScanLink.parse("https://relay.example/mirror#k=short", false))
        assertNull(ScanLink.parse("https://relay.example/mirror#k=$key&pin=bad", false))
        assertNull(ScanLink.parse("https://relay.example/x/mirror#k=$key", false))
        assertNull(ScanLink.parse("WIFI:S:home;T:WPA;P:secret;;", false))
        assertNull(ScanLink.parse("https://shop.example/item", false))
    }

    @Test
    fun aMirrorPageMustBeThePhonesOwnGateway() {
        val local = ScanLink.Mirror("http://127.0.0.1:8788", key, pin)
        assertTrue(local.isFor("https://192.168.1.5:8788", pin))
        assertFalse(local.isFor("https://192.168.1.5:8788", "B".repeat(43)))
        val public = ScanLink.Mirror("https://relay.example", key, null)
        assertTrue(public.isFor("https://relay.example/", null))
        assertFalse(public.isFor("https://other.example", null))
        // A page without the pin on a computer the phone reaches by pin: compared by address.
        assertFalse(ScanLink.Mirror("http://127.0.0.1:8788", key, null).isFor("https://192.168.1.5:8788", pin))
    }
}
