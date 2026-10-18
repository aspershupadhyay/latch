// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aspershupadhyay.latch.data.ConnectionKind
import io.github.aspershupadhyay.latch.data.Pairing
import io.github.aspershupadhyay.latch.data.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Switching between the hosted relay and the computer on Wi-Fi keeps both,
 * with their keys sealed in the real Android Keystore, so switching back
 * asks for nothing.
 */
@RunWith(AndroidJUnit4::class)
class SavedConnectionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hosted = Pairing("https://latch-gateway-ada.vercel.app", "d_hosted", "Pixel")
    private val wifi = Pairing("https://192.168.1.5:8788", "d_wifi", "Pixel", pin = "A".repeat(43))

    private fun clean() = Settings(context).run {
        forgetPairing()
        ConnectionKind.entries.forEach { dropSaved(it) }
    }

    @Before fun before() = clean()
    @After fun after() = clean()

    @Test fun bothConnectionsAreKeptWithTheirKeysAcrossSwitchesAndRestarts() {
        val settings = Settings(context)
        settings.savePairing(hosted, "dt_hosted")
        settings.saveOwnerKey("lok_hosted")
        assertEquals(ConnectionKind.HOSTED, settings.pairing.value?.kind)

        // Set up Wi-Fi: the hosted relay is kept, the phone is unpaired meanwhile.
        settings.keepActive()
        assertNull(settings.pairing.value)
        assertFalse(settings.isOwner.value)
        assertEquals(hosted, settings.saved.value[ConnectionKind.HOSTED])
        settings.savePairing(wifi, "dt_wifi")
        settings.saveOwnerKey("lgt_wifi")

        // Back to the hosted relay: its own keys return, Wi-Fi is kept.
        assertTrue(settings.restore(ConnectionKind.HOSTED))
        assertEquals(hosted, settings.pairing.value)
        assertEquals("dt_hosted", settings.token())
        assertEquals("lok_hosted", settings.ownerKey())
        assertEquals(wifi, settings.saved.value[ConnectionKind.WIFI])
        assertNull(settings.saved.value[ConnectionKind.HOSTED])

        // After an app restart, and switching once more.
        val again = Settings(context)
        assertEquals(hosted, again.pairing.value)
        assertEquals(wifi, again.saved.value[ConnectionKind.WIFI])
        assertTrue(again.restore(ConnectionKind.WIFI))
        assertEquals(wifi, again.pairing.value)
        assertEquals("dt_wifi", again.token())
        assertEquals("lgt_wifi", again.ownerKey())
        assertEquals(hosted, again.saved.value[ConnectionKind.HOSTED])
    }

    @Test fun aGatewayWithANewAddressKeepsItsKeysAndPin() {
        val settings = Settings(context)
        settings.savePairing(wifi, "dt_wifi")
        settings.moveGateway("https://192.168.1.23:8788")
        val moved = Settings(context).pairing.value
        assertEquals("https://192.168.1.23:8788", moved?.gatewayUrl)
        assertEquals(wifi.pin, moved?.pin)
        assertEquals("dt_wifi", settings.token())
    }

    @Test fun disconnectingForgetsOnlyTheConnectionInUse() {
        val settings = Settings(context)
        settings.savePairing(hosted, "dt_hosted")
        settings.keepActive()
        settings.savePairing(wifi, "dt_wifi")
        settings.forgetPairing()
        assertNull(settings.pairing.value)
        assertEquals(hosted, settings.saved.value[ConnectionKind.HOSTED])
        assertFalse(settings.restore(ConnectionKind.WIFI))
    }
}
