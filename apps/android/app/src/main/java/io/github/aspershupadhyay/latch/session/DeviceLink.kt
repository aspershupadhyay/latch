package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.ByeMessage
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.GatewayMessage
import io.github.aspershupadhyay.latch.protocol.GatewayParser
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
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

    fun start() {
        if (loop != null) return
        worker = scope.launch {
            for (envelope in commands) {
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
        }
        loop = scope.launch(Dispatchers.IO) { run() }
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
                runCatching { send("/v1/device/messages?connection=$conn", Protocol.json.encodeToString(ByeMessage.serializer(), ByeMessage("user_stopped"))) }
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
        val conn = connection ?: return@withContext
        // Results matter: retry briefly through a flaky network.
        repeat(3) { attempt ->
            val status = runCatching { send("/v1/device/messages?connection=$conn", body) }.getOrNull()
            if (status != null && status != 500 && status != 502 && status != 503) return@withContext
            delay(500L * (attempt + 1))
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

    private fun sayHello(): HelloResult {
        val body = Protocol.json.encodeToString(HelloMessage.serializer(), hello())
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
    }
}
