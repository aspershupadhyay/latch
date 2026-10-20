// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R45: sound goes out only while the page plays it, and not through long silence. */
class MirrorAudioTest {
    private fun frame(sample: Int): ByteArray = ByteArray(MirrorAudio.FRAME_BYTES).also {
        for (i in it.indices step 2) {
            it[i] = (sample and 0xff).toByte()
            it[i + 1] = (sample shr 8).toByte()
        }
    }

    @Test
    fun silenceIsNearZeroOnEverySample() {
        assertTrue(MirrorAudio.isSilent(frame(0), MirrorAudio.FRAME_BYTES))
        assertTrue(MirrorAudio.isSilent(frame(-3), MirrorAudio.FRAME_BYTES))
        assertFalse(MirrorAudio.isSilent(frame(200), MirrorAudio.FRAME_BYTES))
        assertFalse(MirrorAudio.isSilent(frame(-200), MirrorAudio.FRAME_BYTES))
        // One loud sample anywhere is sound.
        val quiet = frame(0).also { it[MirrorAudio.FRAME_BYTES - 1] = 0x10 }
        assertFalse(MirrorAudio.isSilent(quiet, MirrorAudio.FRAME_BYTES))
    }

    @Test
    fun framesGoOutOnlyToAListenerAndBrieflyAfterSound() {
        assertTrue(MirrorAudio.shouldSend(listening = true, sinceSoundMs = 0, sinceSentMs = 20))
        assertTrue(MirrorAudio.shouldSend(listening = true, sinceSoundMs = MirrorAudio.SILENT_TAIL_MS, sinceSentMs = 20))
        assertFalse(MirrorAudio.shouldSend(listening = true, sinceSoundMs = MirrorAudio.SILENT_TAIL_MS + 1, sinceSentMs = 20))
        // One keep-alive a second through silence.
        assertTrue(MirrorAudio.shouldSend(listening = true, sinceSoundMs = 60_000, sinceSentMs = MirrorAudio.KEEP_ALIVE_MS))
        assertFalse(MirrorAudio.shouldSend(listening = false, sinceSoundMs = 0, sinceSentMs = 60_000))
    }
}
