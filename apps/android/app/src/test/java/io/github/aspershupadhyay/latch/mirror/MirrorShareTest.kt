// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-069: names of files sent to the computer are ones the gateway accepts. */
class MirrorShareTest {
    @Test
    fun namesStayInOneFolderAndFitTheGateway() {
        assertEquals("photo.jpg", MirrorShare.safeName("DCIM/Camera/photo.jpg"))
        assertEquals("b.txt", MirrorShare.safeName("a\\b.txt"))
        assertEquals("file", MirrorShare.safeName(".."))
        assertEquals("file", MirrorShare.safeName("  "))
        assertEquals("bell_.txt", MirrorShare.safeName("bell\u0007.txt"))
        assertEquals("отчёт.pdf", MirrorShare.safeName("отчёт.pdf"))
        val long = MirrorShare.safeName("x".repeat(300) + ".mp4")
        assertEquals(200, long.length)
        assertTrue(long.endsWith(".mp4"))
    }
}
