// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSocketsTest {
    @Test fun mirrorSocketsSendAtOnceAndAskForTheVideoQueue() {
        LowLatencySockets().createSocket().use { s ->
            assertTrue(s.tcpNoDelay)
            // R54: DSCP AF41, the Wi-Fi video queue.
            assertEquals(LowLatencySockets.INTERACTIVE_TOS, s.trafficClass)
        }
    }
}
