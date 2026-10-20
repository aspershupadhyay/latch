// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * The phone's sound for the mirror (ADR-055): what apps play (videos, music,
 * games), captured with Android's playback capture under the same screen-share
 * consent, sent as raw 48 kHz stereo 16-bit PCM in 20 ms frames. Raw PCM needs
 * no encoder and no decoder, so it adds no delay; at 1.5 Mbit/s it suits the
 * home Wi-Fi the self-hosted gateway runs on.
 *
 * Android never lets ordinary apps capture calls or the microphone of a call,
 * and apps that opt out of capture (some streaming apps) stay silent.
 *
 * While the viewer plays the sound, the phone's own media volume goes to zero
 * so it plays on the computer only ([quietPhone]); it comes back when the
 * viewer turns sound off or leaves. The capture is a copy taken before the
 * speaker's volume on most phones; where a phone silences the copy too, the
 * volume comes back by itself and the owner is told.
 */
class MirrorAudio(
    context: Context,
    private val projection: MediaProjection,
    private val sink: (kind: Byte, key: Boolean, timestampUs: Long, payload: ByteBuffer) -> Boolean,
    private val problem: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** The media volume before the phone went quiet; null while it plays normally. */
    @Volatile private var savedVolume: Int? = null
    @Volatile private var quietSinceMs = 0L
    @Volatile private var loudBeforeQuiet = false
    @Volatile private var lastLoudMs = 0L

    /**
     * R45: whether the page plays the sound. Frames go out only then, and not
     * through long silence, so a muted page or a quiet phone costs no
     * bandwidth (sound is ~1.5 Mbit/s of raw PCM).
     */
    @Volatile private var listening = false
    /** When the last frame with sound in it was captured, and when the last frame went out. */
    @Volatile private var lastSoundMs = 0L
    @Volatile private var lastSentMs = 0L

    /** [on]: the computer plays the sound, so the phone's speaker goes quiet; off: it plays again. */
    fun quietPhone(on: Boolean) {
        listening = on
        if (on && running && savedVolume == null) {
            val now = SystemClock.uptimeMillis()
            val volume = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
            if (volume == 0) return
            loudBeforeQuiet = now - lastLoudMs < 500
            // An index, not ADJUST_MUTE: muting a stream silences every copy of it.
            if (runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }.isSuccess) {
                savedVolume = volume
                quietSinceMs = now
            }
        } else if (!on) {
            restoreVolume()
        }
    }

    private fun restoreVolume() {
        val volume = savedVolume ?: return
        savedVolume = null
        // The owner may have turned it up on the phone meanwhile: keep their choice.
        runCatching {
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            }
        }
    }

    /** Sound was playing, the phone went quiet, and the copy fell silent at once: this phone mutes it too. */
    private fun checkCopyAfterQuiet(loud: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (loud) lastLoudMs = now
        if (savedVolume == null || !loudBeforeQuiet) return
        val since = now - quietSinceMs
        if (since > COPY_CHECK_MS) {
            loudBeforeQuiet = false
        } else if (since > 1_200 && now - lastLoudMs > 1_200) {
            loudBeforeQuiet = false
            restoreVolume()
            problem("This phone also silences the copy when its volume is zero, so the sound plays on both.")
        }
    }
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts capturing; false when the phone refused (no RECORD_AUDIO permission, or no support). */
    @SuppressLint("MissingPermission") // checked by the caller; a refusal throws and is caught
    fun start(): Boolean {
        if (running) return true
        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread({
            val buffer = ByteArray(FRAME_BYTES)
            try {
                record.startRecording()
                while (running) {
                    var filled = 0
                    while (running && filled < FRAME_BYTES) {
                        val n = record.read(buffer, filled, FRAME_BYTES - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled <= 0) continue
                    checkCopyAfterQuiet(isLoud(buffer, filled))
                    val now = SystemClock.uptimeMillis()
                    if (!isSilent(buffer, filled)) lastSoundMs = now
                    // A short tail of silence lets the page's queue drain softly, then nothing.
                    if (shouldSend(listening, now - lastSoundMs, now - lastSentMs)) {
                        lastSentMs = now
                        sink(MirrorMessages.KIND_PCM, false, SystemClock.elapsedRealtimeNanos() / 1000, ByteBuffer.wrap(buffer, 0, filled))
                    }
                }
            } catch (_: Exception) {
                // The projection ended or the recorder failed: the picture carries on without sound.
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "latch-mirror-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        // The next viewer says again whether it plays the sound.
        listening = false
        thread?.join(500)
        thread = null
        restoreVolume()
    }

    /** Any 16-bit sample above a whisper. */
    private fun isLoud(buffer: ByteArray, length: Int): Boolean {
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
            if (sample > LOUD || sample < -LOUD) return true
            i += 8 // every other stereo frame is plenty
        }
        return false
    }

    companion object {
        /**
         * Whether a frame goes out: someone listens, and there was sound within
         * [SILENT_TAIL_MS], or one keep-alive every [KEEP_ALIVE_MS] through silence.
         */
        fun shouldSend(listening: Boolean, sinceSoundMs: Long, sinceSentMs: Long): Boolean =
            listening && (sinceSoundMs <= SILENT_TAIL_MS || sinceSentMs >= KEEP_ALIVE_MS)

        const val KEEP_ALIVE_MS = 1_000L

        /** Digital silence, or close to it: every left and right sample within a few steps of zero. */
        fun isSilent(buffer: ByteArray, length: Int): Boolean {
            var i = 0
            while (i + 1 < length) {
                val sample = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
                if (sample > SILENT || sample < -SILENT) return false
                i += 2
            }
            return true
        }

        /** Silence sent after the last sound, so the page's queue ends without a click. */
        const val SILENT_TAIL_MS = 300L
        private const val SILENT = 8
        const val SAMPLE_RATE = 48_000
        private const val LOUD = 64
        private const val COPY_CHECK_MS = 3_000L
        /** 20 ms of 16-bit stereo. */
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 * 2
    }
}
