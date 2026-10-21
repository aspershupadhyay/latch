// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The phone's screen as video (R30, ADR-051). One `VirtualDisplay` mirrors the
 * screen for the whole projection (Android 14 allows only one per consent);
 * while someone watches, it renders straight into the hardware H.264
 * encoder's input `Surface`, so pixels never pass through the CPU. Without a
 * viewer it renders nowhere and nothing is encoded. Browsers without WebCodecs
 * get JPEG stills a few times a second instead.
 *
 * Everything runs on [handler]'s thread. Frames go to [sink], which returns
 * false when it had to drop one; the capture then asks for a key frame and
 * lowers the bitrate while drops repeat.
 */
class MirrorCapture(
    projection: MediaProjection,
    private val handler: Handler,
    private val screen: () -> Screen,
    private val sink: (kind: Byte, key: Boolean, timestampUs: Long, payload: ByteBuffer) -> Boolean,
    private val problem: (String) -> Unit,
    /** R38: the picture got smaller to save bandwidth ([QualityLadder.saving]), or came back. */
    private val quality: (saving: Boolean, width: Int, height: Int, fps: Int) -> Unit = { _, _, _, _ -> },
) {
    data class Screen(val width: Int, val height: Int, val dpi: Int, val rotation: Int)

    /** The phone's sound, while someone watches (ADR-055); null without permission. */
    var audio: MirrorAudio? = null

    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var reader: ImageReader? = null
    private var config: ByteArray? = null
    private var codec: Codec? = null
    /** What is being sent now: "h264", "jpeg", or "off". */
    val codecName: String get() = codec?.wire ?: "off"
    private var size = 0 to 0
    private val governor = BitrateGovernor()
    private val ladder = QualityLadder()
    /** R40: the encoder running now is H.265. */
    private var hevc = false
    /** R37: the encoder heals a lost frame by refreshing stripes, so a drop needs no key frame. */
    private var intraRefresh = false
    /** R35: what the encoder did since the page last asked. */
    val stats = StreamStats()
    /** The bitrate the encoder aims for now, and the encoded picture's size. */
    val targetBps: Int get() = governor.bps
    val encodedSize: Pair<Int, Int> get() = size
    /** The next encoder serves a new viewer: its bitrate starts high again. */
    private var freshViewer = true
    private var lastKeyRequestMs = 0L
    private var lastStillMs = 0L

    init {
        val s = screen()
        display = projection.createVirtualDisplay(
            "Latch mirror", s.width, s.height, s.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler,
        )
    }

    /** Starts sending [kind] frames (a viewer joined, or switched to stills). */
    fun start(kind: Codec) {
        freshViewer = true
        restart(kind)
    }

    /** [start] for the same viewer (an error, a smaller picture): the bitrate keeps what the network showed. */
    private fun restart(kind: Codec) {
        audio?.start()
        stopOutput()
        codec = kind
        val s = screen()
        try {
            when (kind) {
                Codec.H264 -> startVideo(s, hevc = false)
                // R40: H.265 only on a hardware encoder; otherwise H.264, which every viewer decodes.
                Codec.H265 -> try {
                    startVideo(s, hevc = true)
                } catch (e: Exception) {
                    stopOutput()
                    codec = Codec.H264
                    startVideo(s, hevc = false)
                }
                Codec.JPEG -> startStills(s)
            }
        } catch (e: Exception) {
            stopOutput()
            if (kind != Codec.JPEG) {
                problem("This phone's video encoder could not start; sending still pictures instead.")
                restart(Codec.JPEG)
            } else {
                problem("The screen could not be captured (${e.javaClass.simpleName}).")
            }
        }
    }

    /** No one watches: stop encoding. The projection and display stay for the next viewer. */
    fun stop() {
        audio?.stop()
        stopOutput()
        codec = null
    }

    /** The screen turned or changed size: start again at the new size if running. */
    fun screenChanged() {
        val running = codec ?: run {
            val s = screen()
            display?.resize(s.width, s.height, s.dpi)
            return
        }
        restart(running)
    }

    /** The browser needs a key frame (joined late, or dropped frames). */
    fun requestKeyFrame() {
        val e = encoder ?: run {
            // Stills: the screen may be still, so no new picture comes; send the last one again.
            handler.post {
                if (reader != null && stillOut.size() > 0) {
                    sink(MirrorMessages.KIND_JPEG, true, System.nanoTime() / 1000, stillOut.view())
                }
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyRequestMs < KEY_REQUEST_GAP_MS) return
        lastKeyRequestMs = now
        runCatching { e.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        audio?.stop()
        stopOutput()
        display?.release()
        display = null
    }

    // ---- H.264 ----

    private fun startVideo(s: Screen, hevc: Boolean) {
        val mime = if (hevc) MIME_HEVC else MIME
        val info = pickEncoder(mime, hardwareOnly = hevc) ?: throw IllegalStateException("no ${if (hevc) "H.265" else "H.264"} encoder")
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities ?: throw IllegalStateException("not a video encoder")
        val level = ladder.level
        // The screen's own size when the encoder takes it (sides a multiple of 8), else 16-aligned, else smaller.
        val align = maxOf(8, video.widthAlignment, video.heightAlignment)
        val sizes = listOf(level.maxEdge, 1920, 1280, 960).filter { it <= level.maxEdge }.distinct()
            .flatMap { listOf(EncoderSizing.size(s.width, s.height, it, align), EncoderSizing.size(s.width, s.height, it)) }
        // Prefer a size the encoder keeps up with at the full frame rate; any size it takes otherwise.
        val (w, h) = sizes.firstOrNull { (w, h) -> runCatching { video.areSizeAndRateSupported(w, h, level.fps.toDouble()) }.getOrDefault(false) }
            ?: sizes.firstOrNull { (w, h) -> video.isSizeSupported(w, h) } ?: throw IllegalStateException("no supported size")
        // The budget follows the picture: more pixels and frames get more bits (VideoBudget).
        governor.fitTo(VideoBudget.maxBps(w, h, level.fps), VideoBudget.startBps(w, h, level.fps), restart = freshViewer)
        freshViewer = false
        val codec = MediaCodec.createByCodecName(info.name)
        try {
            intraRefresh = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh)
            try {
                codec.configure(format(w, h, caps, lowLatency = true, fps = level.fps, hevc = hevc), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // Some vendors refuse keys they do not know; the plain format works everywhere.
                codec.reset()
                intraRefresh = false
                codec.configure(format(w, h, caps, lowLatency = false, fps = level.fps, hevc = hevc), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            codec.setCallback(callback, handler)
            codec.start()
            encoder = codec
            this.hevc = hevc
            size = w to h
            config = null
            display?.resize(w, h, s.dpi)
            display?.surface = surface
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    private fun format(w: Int, h: Int, caps: MediaCodecInfo.CodecCapabilities, lowLatency: Boolean, fps: Int, hevc: Boolean) =
        MediaFormat.createVideoFormat(if (hevc) MIME_HEVC else MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, encoderBps(governor.bps, hevc))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // R38: fewer frames a second at a lower level; the encoder drops the rest.
            if (fps < FPS) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            // Key frames come on request (a viewer joins or falls behind), not on a timer;
            // with intra refresh (R37) only on join, rotation, and the gateway's request.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (lowLatency && intraRefresh) INTRA_KEY_FRAME_SECONDS else KEY_FRAME_SECONDS)
            // A still screen still sends a frame now and then (R46: twice a second), so the
            // picture never goes stale and a lost frame is replaced soon.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            if (!lowLatency) return@apply
            if (intraRefresh) setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, maxOf(1, fps / 2))
            // High profile (CABAC, 8×8 transforms) gives a visibly cleaner picture than Baseline at the
            // same bits; with no B-frames it adds no delay, and every browser decodes it.
            val profile = when {
                hevc -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh } ->
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            }
            setInteger(MediaFormat.KEY_PROFILE, profile)
            caps.profileLevels.filter { it.profile == profile }.maxOfOrNull { it.level }
                ?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            // Variable bitrate: a busy frame (a scene cut, a game explosion) may take more bits than a
            // calm one instead of turning to blocks; the governor still caps the average.
            if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }

    /** A hardware encoder for [mime] when there is one, else any (unless [hardwareOnly]). */
    private fun pickEncoder(mime: String, hardwareOnly: Boolean = false): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        return all.firstOrNull { it.isHardwareAccelerated } ?: if (hardwareOnly) null else all.firstOrNull()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== encoder) return
            try {
                val buffer = codec.getOutputBuffer(index) ?: return
                buffer.position(info.offset).limit(info.offset + info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = ByteArray(info.size).also { buffer.get(it) }
                    return
                }
                if (info.size == 0) return
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val payload = AnnexB.withConfig(config, buffer, key, hevc)
                val now = SystemClock.uptimeMillis()
                val size = payload.remaining()
                val encodeUs = System.nanoTime() / 1000 - info.presentationTimeUs
                val sent = sink(if (hevc) MirrorMessages.KIND_H265 else MirrorMessages.KIND_H264, key, info.presentationTimeUs, payload)
                stats.frame(size, key, sent, encodeUs)
                if (sent) {
                    governor.clean(now)?.let(::setBitrate)
                    ladder.clean(now)?.let { changeLevel() }
                } else {
                    governor.dropped(now)?.let(::setBitrate)
                    if (ladder.dropped(now, governor.atFloor) != null) {
                        changeLevel()
                    } else if (!intraRefresh) {
                        // R37: with intra refresh the next frames heal the picture; no key frame spike.
                        requestKeyFrame()
                    }
                }
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== encoder) return
            // A recoverable error gets a fresh encoder; anything else falls back to stills.
            when {
                e.isRecoverable || e.isTransient -> restart(this@MirrorCapture.codec ?: Codec.H264)
                // R40: an H.265 encoder that fails gives way to H.264, not to stills.
                hevc -> restart(Codec.H264)
                else -> restart(Codec.JPEG)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    /** R38: start the encoder again at the ladder's new size and frame rate, and tell the page. */
    private fun changeLevel() {
        handler.post {
            val running = codec?.takeIf { it != Codec.JPEG } ?: return@post
            restart(running)
            val level = ladder.level
            quality(ladder.saving, size.first, size.second, level.fps)
        }
    }

    private fun setBitrate(bps: Int) {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, encoderBps(bps, hevc)) }) }
    }

    // ---- Stills ----

    private fun startStills(s: Screen) {
        val (w, h) = EncoderSizing.size(s.width, s.height, STILL_EDGE)
        @Suppress("WrongConstant")
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        size = w to h
        display?.resize(w, h, s.dpi)
        display?.surface = r.surface
        lastStillMs = 0
        // R46: woken by a new picture instead of polling; a still screen costs nothing.
        r.setOnImageAvailableListener({ scheduleStill() }, handler)
    }

    /** A new picture is ready: send it now, or when [STILL_EVERY_MS] has passed since the last. */
    private fun scheduleStill() {
        if (handler.hasCallbacks(stillTick)) return
        val wait = STILL_EVERY_MS - (SystemClock.uptimeMillis() - lastStillMs)
        if (wait <= 0) handler.post(stillTick) else handler.postDelayed(stillTick, wait)
    }

    private var stillFull: Bitmap? = null
    private var stillCropped: Bitmap? = null
    private val stillOut = StillBuffer()

    /** A JPEG buffer whose bytes are lent to the sink without a copy (the sink copies them once). */
    private class StillBuffer : ByteArrayOutputStream(64 * 1024) {
        fun view(): ByteBuffer = ByteBuffer.wrap(buf, 0, count)
    }

    /** Sends the newest picture at most [STILL_EVERY_MS] apart; a still screen sends nothing new. */
    private val stillTick = object : Runnable {
        override fun run() {
            val r = reader ?: return
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val stride = plane.rowStride / plane.pixelStride
                    // R46: the same bitmaps and buffer every tick instead of new ones five times a second.
                    val full = stillFull?.takeIf { it.width == stride && it.height == image.height }
                        ?: createBitmap(stride, image.height).also { stillFull?.recycle(); stillFull = it }
                    full.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = if (stride == image.width) {
                        full
                    } else {
                        val cropped = stillCropped?.takeIf { it.width == image.width && it.height == image.height }
                            ?: createBitmap(image.width, image.height).also { stillCropped?.recycle(); stillCropped = it }
                        val area = android.graphics.Rect(0, 0, image.width, image.height)
                        android.graphics.Canvas(cropped).drawBitmap(full, area, area, null)
                        cropped
                    }
                    stillOut.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, STILL_QUALITY, stillOut)
                    val started = System.nanoTime() / 1000
                    val sent = sink(MirrorMessages.KIND_JPEG, true, started, stillOut.view())
                    stats.frame(stillOut.size(), true, sent, null)
                    lastStillMs = SystemClock.uptimeMillis()
                } catch (e: Exception) {
                    problem("A still picture could not be made (${e.javaClass.simpleName}).")
                } finally {
                    image.close()
                }
            }
        }
    }

    private fun stopOutput() {
        handler.removeCallbacks(stillTick)
        display?.surface = null
        encoder?.let { e ->
            encoder = null
            runCatching { e.stop() }
            runCatching { e.release() }
        }
        reader?.let { r ->
            reader = null
            runCatching { r.close() }
        }
        stillFull?.recycle()
        stillCropped?.recycle()
        stillFull = null
        stillCropped = null
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

        /** R40: H.265 shows the same picture with fewer bits, so it is given 60 % of H.264's. */
        fun encoderBps(bps: Int, hevc: Boolean): Int = if (hevc) (bps * 0.6).toInt() else bps
        /** The longest side sent: a 1440p phone's own size fits; larger screens are scaled. */
        const val MAX_EDGE = 2560
        const val FPS = 60
        private const val KEY_FRAME_SECONDS = 10
        private const val REPEAT_AFTER_US = 500_000L
        /** With intra refresh, a key frame is only asked for; this keeps the encoder from adding its own. */
        private const val INTRA_KEY_FRAME_SECONDS = 3_600
        private const val KEY_REQUEST_GAP_MS = 200L
        private const val STILL_EDGE = 1280
        private const val STILL_EVERY_MS = 200L
        private const val STILL_QUALITY = 60

        /** Whether this Android lets an app ask for the whole screen only (Android 14+). */
        val fullScreenOnly: Boolean get() = Build.VERSION.SDK_INT >= 34
    }
}
