// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A key or a private link is never shown whole on screen. */
class MaskSecretTest {
    private val token = "lmt_" + "a1b2c3d4".repeat(8)

    @Test
    fun privateLinkShowsTheAddressAndFourCharactersOfTheKey() {
        val shown = maskSecret("https://relay.example/mcp/$token")
        assertEquals("https://relay.example/mcp/lmt_" + "•".repeat(14), shown)
        assertFalse(shown.contains(token.drop(4).take(8)))
    }

    @Test
    fun keyShowsOnlyItsStart() {
        assertEquals("Bearer lmt" + "•".repeat(14), maskSecret("Bearer $token"))
    }
}
