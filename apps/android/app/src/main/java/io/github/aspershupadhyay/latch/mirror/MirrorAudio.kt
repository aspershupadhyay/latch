// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.annotation.SuppressLint
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
 */
class MirrorAudio(
    private val projection: MediaProjection,
    private val sink: (kind: Byte, key: Boolean, timestampUs: Long, payload: ByteBuffer) -> Boolean,
) {
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
                    sink(MirrorMessages.KIND_PCM, false, SystemClock.elapsedRealtimeNanos() / 1000, ByteBuffer.wrap(buffer, 0, filled))
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
        thread?.join(500)
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        /** 20 ms of 16-bit stereo. */
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 * 2
    }
}
