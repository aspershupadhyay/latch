// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.ByeMessage
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.GatewayMessage
import io.github.aspershupadhyay.latch.protocol.GatewayParser
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.MessageSigner
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.Signing
import io.github.aspershupadhyay.latch.protocol.StateMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The phone's connection to a gateway over the HTTP long-poll binding
 * (protocol 1.1): `hello`, then a loop of `poll`, with results and state
 * posted to `messages`. Works with any gateway, including serverless ones.
 *
 * Plain Kotlin (no Android APIs) so it is tested on the JVM against a real
 * gateway. Commands are handed to [onCommand] strictly one at a time.
 */
class DeviceLink(
    private val http: OkHttpClient,
    gatewayUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val hello: () -> HelloMessage,
    private val onCommand: suspend (CommandEnvelope) -> String,
    private val events: Events,
    /** Protocol 1.12: signs every message with this phone's device key, if the gateway speaks 1.12. */
    private val signer: MessageSigner? = null,
) {
    interface Events {
        /** Connected (also after an automatic reconnect). */
        fun connected()
        /** Lost the connection; retrying on its own. [attempt] starts at 1. */
        fun retrying(attempt: Int)
        /** The gateway no longer accepts this phone's credential. Stops the link. */
        fun rejected(revoked: Boolean)
        /** The gateway speaks an incompatible protocol. Stops the link. */
        fun incompatible(gatewayVersion: String)
        /** The gateway asked to cancel a command (best effort). */
        fun cancel(commandId: String)
        /** The protocol version the gateway answered `hello` with, on every (re)connect. */
        fun gatewayProtocol(version: String) = Unit
        /** Since 1.4: the owner answered an approval in the AI app. */
        fun approvalAnswer(nonce: String, choice: String) = Unit
    }

    private val base = gatewayUrl.trimEnd('/')
    private val json = "application/json".toMediaType()
    // Long polls wait up to 25 s on the gateway; leave room for slow networks.
    private val pollClient = http.newBuilder().readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private var loop: Job? = null
    private var worker: Job? = null
    private val commands = Channel<CommandEnvelope>(Channel.UNLIMITED)
    @Volatile private var connection: String? = null
    @Volatile private var activeCall: Call? = null
    /** When the last command arrived; polls say `hot=1` for a while after, so gateways check sooner. */
    @Volatile private var lastCommandAtMs = 0L
    /** Whether this gateway takes signed messages (1.12+); learned before the first hello. */
    @Volatile private var signing: Boolean? = null

    /** A message as this phone sends it on [conn]: signed when the gateway takes signatures. */
    private fun sealed(conn: String, body: String): String =
        if (signing == true && signer != null) Signing.seal(signer, conn, body) else body

    fun start() {
        if (loop != null) return
        worker = scope.launch {
            for (envelope in commands) {
                // Screen commands run one at a time. Full-access ADB never touches the
                // screen, so it runs beside them instead of making them wait (ADR-046).
                if (envelope.command is io.github.aspershupadhyay.latch.protocol.Command.AdbShell) {
                    launch { answer(envelope) }
                } else {
                    answer(envelope)
                }
            }
        }
        loop = scope.launch(Dispatchers.IO) { run() }
    }

    /** Runs one command and posts its answer. */
    private suspend fun answer(envelope: CommandEnvelope) {
        // onCommand answers every failure itself; this only guards the link against a bug there.
        val reply = try {
            onCommand(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outgoing.error(envelope.id, io.github.aspershupadhyay.latch.protocol.ErrorCode.INTERNAL, "the phone could not finish this")
        }
        post(reply)
    }

    /** Stops polling. With [sayBye], tells the gateway first so tools fail fast. */
    suspend fun stop(sayBye: Boolean) {
        val conn = connection
        loop?.cancel()
        activeCall?.cancel()
        worker?.cancel()
        commands.close()
        loop = null
        if (sayBye && conn != null) {
            withContext(Dispatchers.IO) {
                runCatching { send("/v1/device/messages?connection=$conn", sealed(conn, Protocol.json.encodeToString(ByeMessage.serializer(), ByeMessage("user_stopped")))) }
            }
        }
        connection = null
    }

    /** Sends a protocol message (e.g. an approval request); ignored while disconnected. */
    fun send(body: String) {
        scope.launch(Dispatchers.IO) { post(body) }
    }

    /** Sends the owner's current switches and session; ignored while disconnected. */
    fun pushState(state: StateMessage) {
        val body = Protocol.json.encodeToString(StateMessage.serializer(), state)
        scope.launch(Dispatchers.IO) { post(body) }
    }

    private suspend fun post(body: String) = withContext(Dispatchers.IO) {
        // Results matter: an action that already happened must reach the AI, or it may
        // repeat it. Through a reconnect, wait for the new connection; through a flaky
        // network, retry briefly.
        val until = System.currentTimeMillis() + RECONNECT_WAIT_MS
        var attempt = 0
        while (attempt < 3) {
            val conn = connection
            if (conn == null) {
                if (System.currentTimeMillis() >= until || loop?.isActive != true) return@withContext
                delay(200)
                continue
            }
            val status = runCatching { send("/v1/device/messages?connection=$conn", sealed(conn, body)) }.getOrNull()
            when {
                status == 409 && connection != conn -> continue // replaced meanwhile: send on the new one
                status != null && status != 500 && status != 502 && status != 503 -> return@withContext
            }
            delay(500L * (attempt + 1))
            attempt++
        }
    }

    private fun send(path: String, body: String): Int {
        val request = Request.Builder().url(base + path).header("Authorization", "Bearer $token").post(body.toRequestBody(json)).build()
        return http.newCall(request).execute().use { it.code }
    }

    private suspend fun run() {
        var failures = 0
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            try {
                val conn = connection ?: when (val r = sayHello()) {
                    is HelloResult.Ok -> {
                        // Stop was pressed while the hello was on its way: leave at once
                        // instead of showing the phone as connected.
                        if (!currentCoroutineContext().isActive) {
                            runCatching {
                                send(
                                    "/v1/device/messages?connection=${r.connection}",
                                    sealed(r.connection, Protocol.json.encodeToString(ByeMessage.serializer(), ByeMessage("user_stopped"))),
                                )
                            }
                            return
                        }
                        failures = 0
                        everConnected = true
                        connection = r.connection
                        events.gatewayProtocol(r.protocol)
                        events.connected()
                        r.connection
                    }
                    is HelloResult.Rejected -> {
                        events.rejected(revoked = everConnected)
                        return
                    }
                    is HelloResult.Incompatible -> {
                        events.incompatible(r.version)
                        return
                    }
                }
                when (val p = pollOnce(conn)) {
                    is Poll.Message -> handle(p.text)
                    Poll.Empty -> Unit
                    Poll.Replaced -> connection = null
                    Poll.Rejected -> {
                        events.rejected(revoked = true)
                        return
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException is the usual case; anything else (a malformed answer, a bad
                // address) is retried the same way instead of ending the session silently.
                failures++
                connection = null
                events.retrying(failures)
                delay(min(30_000L, 1_000L shl min(failures, 5)))
            }
        }
    }

    private sealed interface HelloResult {
        data class Ok(val connection: String, val protocol: String) : HelloResult
        data object Rejected : HelloResult
        data class Incompatible(val version: String) : HelloResult
    }

    /** Whether the gateway speaks 1.12 (signed messages), from its public `/v1/info`. */
    private fun gatewaySigns(): Boolean {
        val request = Request.Builder().url("$base/v1/info").get().build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) throw IOException("info failed with HTTP ${response.code}")
            val protocol = runCatching {
                (Protocol.json.parseToJsonElement(response.body.string()) as kotlinx.serialization.json.JsonObject)["protocol"]
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            }.getOrNull() ?: return false
            return (Protocol.minorOf(protocol) ?: 0) >= SIGNING_MINOR
        }
    }

    private fun sayHello(): HelloResult {
        if (signer != null && signing == null) signing = gatewaySigns()
        val plain = hello().let { if (signing == true && signer != null) it.copy(deviceKey = signer.publicKey) else it }
        val body = sealed("", Protocol.json.encodeToString(HelloMessage.serializer(), plain))
        val request = Request.Builder().url("$base/v1/device/hello").header("Authorization", "Bearer $token").post(body.toRequestBody(json)).build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val welcome = try {
                        GatewayParser.parse(response.body.string()) as? GatewayMessage.Welcome
                    } catch (e: ProtocolException) {
                        null
                    } ?: throw IOException("gateway sent an unexpected answer to hello")
                    if (!Protocol.isCompatible(welcome.protocol)) return HelloResult.Incompatible(welcome.protocol.take(8))
                    val conn = welcome.connection ?: return HelloResult.Incompatible(welcome.protocol.take(8))
                    return HelloResult.Ok(conn, welcome.protocol.take(16))
                }
                401 -> return HelloResult.Rejected
                422 -> return HelloResult.Incompatible("?")
                else -> throw IOException("hello failed with HTTP ${response.code}")
            }
        }
    }

    private sealed interface Poll {
        data class Message(val text: String) : Poll
        data object Empty : Poll
        data object Replaced : Poll
        data object Rejected : Poll
    }

    private fun pollOnce(conn: String): Poll {
        val hot = if (System.currentTimeMillis() - lastCommandAtMs < HOT_WINDOW_MS) "&hot=1" else ""
        val request = Request.Builder()
            .url("$base/v1/device/poll?connection=$conn&wait=25$hot")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val call = pollClient.newCall(request)
        activeCall = call
        call.execute().use { response ->
            return when (response.code) {
                200 -> Poll.Message(response.body.string())
                204 -> Poll.Empty
                409 -> Poll.Replaced
                401 -> Poll.Rejected
                else -> throw IOException("poll failed with HTTP ${response.code}")
            }
        }
    }

    private suspend fun handle(text: String) {
        val message = try {
            GatewayParser.parse(text)
        } catch (e: ProtocolException) {
            // Answer commands we cannot parse so the gateway does not wait; drop anything else.
            GatewayParser.commandId(text)?.let { post(Outgoing.error(it, e.code, e.message ?: "invalid command")) }
            return
        }
        when (message) {
            is GatewayMessage.CommandMessage -> {
                lastCommandAtMs = System.currentTimeMillis()
                commands.send(message.envelope)
            }
            is GatewayMessage.Cancel -> events.cancel(message.id)
            is GatewayMessage.ApprovalAnswer -> events.approvalAnswer(message.nonce, message.choice)
            is GatewayMessage.Revoked -> {
                events.rejected(revoked = true)
                loop?.cancel()
            }
            is GatewayMessage.Welcome -> Unit
        }
    }

    private companion object {
        /** An agent loop sends its next command within seconds of reading a result. */
        const val HOT_WINDOW_MS = 60_000L

        /** How long a result waits for the link to come back before it is dropped. */
        const val RECONNECT_WAIT_MS = 20_000L

        /** The first protocol minor that takes signed messages. */
        const val SIGNING_MINOR = 12
    }
}
