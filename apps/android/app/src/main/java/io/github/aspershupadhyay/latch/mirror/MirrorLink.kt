// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import io.github.aspershupadhyay.latch.protocol.MessageSigner
import io.github.aspershupadhyay.latch.protocol.Signing
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The phone's mirror socket to its gateway (R30, `GET /v1/mirror/phone`).
 * Proves the device key on the gateway's challenge, then carries frames out
 * and the viewer's messages in. Reconnects by itself with the same viewer
 * key, so a short network drop does not end the owner's view. Plain Kotlin
 * (OkHttp only), so it is tested on the JVM against a real gateway.
 */
class MirrorLink(
    http: OkHttpClient,
    gatewayUrl: String,
    private val token: String,
    private val key: String,
    private val signer: MessageSigner?,
    private val events: Events,
) {
    interface Events {
        /** The gateway took the start: the code (null once used) and where to open the page. */
        fun ready(code: String?, urls: List<String>)
        fun message(message: MirrorIn)
        /** Lost the connection; trying again on its own. */
        fun reconnecting()
        /** The gateway refused for good (credential, mirror missing on this gateway, bad start). */
        fun refused(reason: String)
    }

    private val client = http.newBuilder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val url = gatewayUrl.trimEnd('/') + "/v1/mirror/phone"
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "latch-mirror-link").apply { isDaemon = true } }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var started = false
    private var attempt = 0
    private var retry: ScheduledFuture<*>? = null

    fun start() {
        wanted = true
        connect()
    }

    /** Ends the mirror: tells the gateway (so the viewer hears it at once) and closes. */
    fun stop() {
        wanted = false
        retry?.cancel(false)
        socket?.let { s ->
            s.send(MirrorMessages.end())
            s.close(1000, "mirroring ended")
        }
        socket = null
        timer.shutdown()
    }

    fun sendText(text: String) {
        if (started) socket?.send(text)
    }

    /**
     * Sends one frame unless the socket is already behind; returns false when
     * the frame was dropped, so the encoder sends a key frame next. Video is
     * never queued behind a slow network.
     */
    fun sendFrame(frame: ByteArray): Boolean {
        val s = socket ?: return false
        if (!started || s.queueSize() > MAX_QUEUED_BYTES) return false
        return s.send(frame.toByteString())
    }

    private fun connect() {
        if (!wanted) return
        started = false
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            when (val m = MirrorMessages.parse(text)) {
                is MirrorIn.Challenge -> {
                    if (m.version != MirrorMessages.VERSION) {
                        giveUp(webSocket, "the gateway speaks a different mirror version; update the gateway or the app")
                        return
                    }
                    val start = MirrorMessages.start(key)
                    webSocket.send(if (signer != null) Signing.seal(signer, m.connection, start) else start)
                }
                is MirrorIn.Ready -> {
                    started = true
                    attempt = 0
                    events.ready(m.code, m.urls)
                }
                null -> Unit
                else -> if (started) events.message(m)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket !== socket) return
            when (code) {
                REFUSED -> giveUp(webSocket, reason.ifBlank { "the gateway refused the mirror" })
                REPLACED -> giveUp(webSocket, "mirroring started again from this phone")
                1000 -> giveUp(webSocket, reason.ifBlank { "the gateway ended mirroring" })
                else -> lost(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            when (response?.code) {
                401 -> giveUp(webSocket, "the gateway no longer knows this phone; pair it again")
                404 -> giveUp(webSocket, "this relay cannot mirror; mirroring needs the self-hosted latch-gateway (docs/setup.md)")
                else -> lost(webSocket)
            }
        }
    }

    private fun giveUp(webSocket: WebSocket, reason: String) {
        if (webSocket !== socket) return
        wanted = false
        socket = null
        events.refused(reason)
    }

    private fun lost(webSocket: WebSocket) {
        if (webSocket !== socket || !wanted) return
        socket = null
        started = false
        events.reconnecting()
        val wait = minOf(MAX_RETRY_MS, RETRY_START_MS shl minOf(attempt, 5))
        attempt++
        retry = runCatching { timer.schedule({ connect() }, wait, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    companion object {
        /** Bytes waiting in OkHttp's queue before frames are dropped (OkHttp itself fails at 16 MiB). */
        const val MAX_QUEUED_BYTES = 512L * 1024
        const val REFUSED = 4401
        const val REPLACED = 4409
        private const val RETRY_START_MS = 250L
        private const val MAX_RETRY_MS = 4_000L
    }
}
