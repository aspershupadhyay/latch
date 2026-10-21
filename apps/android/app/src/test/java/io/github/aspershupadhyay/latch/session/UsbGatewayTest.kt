// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R57: the gateway over a USB cable is the phone's own loopback at the Wi-Fi port. */
class UsbGatewayTest {
    @Test fun theCableAddressIsTheLoopbackAtTheGatewaysPort() {
        assertEquals("https://127.0.0.1:8788", UsbGateway.url(8788))
        assertTrue(UsbGateway.isUsb("https://127.0.0.1:8788"))
        assertFalse(UsbGateway.isUsb("https://192.168.1.5:8788"))
        assertFalse(UsbGateway.isUsb("not a url"))
        assertFalse("a plain loopback gateway (development) is not the cable", UsbGateway.isUsb("http://127.0.0.1:8787"))
    }
}
