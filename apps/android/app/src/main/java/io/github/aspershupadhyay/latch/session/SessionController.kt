package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.Settings
import io.github.aspershupadhyay.latch.protocol.ByeMessage
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityState
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.GatewayMessage
import io.github.aspershupadhyay.latch.protocol.GatewayParser
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.StateMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** What the owner sees. Every state has a plain-language explanation in the UI. */
sealed interface SessionState {
    data object Unpaired : SessionState
    data object Idle : SessionState
    data class Connecting(val attempt: Int) : SessionState
    data class Active(val expiresAtMs: Long, val paused: Boolean) : SessionState
    data class Reconnecting(val expiresAtMs: Long, val attempt: Int) : SessionState
    data class Revoked(val reason: String) : SessionState
    data class Failed(val message: String) : SessionState
}

/**
 * Owns the one connection to the gateway. All state changes happen on the
 * main thread; OkHttp callbacks are marshalled onto [scope].
 */
class SessionController(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val bridge: DeviceBridge,
    private val approvals: ApprovalBroker,
    private val log: ActivityLog,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<SessionState>(if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val executor = CommandExecutor(bridge, approvals, log)
    private var socket: WebSocket? = null
    /** Identifies the current socket so callbacks from a replaced one are ignored. */
    private var generation = 0
    private var wanted = false
    private var paused = false
    private var expiresAtMs = 0L
    private var expiryJob: Job? = null
    private var reconnectJob: Job? = null
    private var commandJob: Job? = null
    private var commands = Channel<CommandEnvelope>(Channel.UNLIMITED)
    private var runningCommandId: String? = null

    init {
        scope.launch {
            combine(settings.preferences, bridge.service) { _, _ -> Unit }.collect { pushState() }
        }
        scope.launch {
            settings.pairing.collect { pairing ->
                val current = _state.value
                // Keep revocation and failure messages on screen until the owner acts on them.
                val explaining = current is SessionState.Revoked || current is SessionState.Failed
                if (pairing == null && !wanted && !explaining) _state.value = SessionState.Unpaired
                else if (pairing != null && current == SessionState.Unpaired) _state.value = SessionState.Idle
            }
        }
    }

    fun capabilityStatuses(): Map<Capability, CapabilityStatus> {
        val prefs = settings.preferences.value
        val serviceOn = bridge.service.value != null
        return Capability.entries.associateWith { capability ->
            when {
                capability == Capability.DEVICE_INFO -> CapabilityStatus.ENABLED
                capability !in prefs.enabled -> CapabilityStatus.DISABLED
                !serviceOn -> CapabilityStatus.NEEDS_PERMISSION
                else -> CapabilityStatus.ENABLED
            }
        }
    }

    private fun sessionInfo() = SessionInfo(expiresAtMs, settings.preferences.value.approveEveryAction, paused)

    private fun wireCapabilities() = capabilityStatuses().map { (c, s) -> CapabilityState(c.wire, s.wire) }

    // ---- Owner actions ----

    fun start() {
        if (settings.pairing.value == null || wanted) return
        wanted = true
        paused = false
        expiresAtMs = System.currentTimeMillis() + settings.preferences.value.sessionMinutes * 60_000L
        log.add(ActivityKind.SESSION, "Session started for ${settings.preferences.value.sessionMinutes} minutes")
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(expiresAtMs - System.currentTimeMillis())
            stop("Session time ended")
        }
        startCommandLoop()
        connect(attempt = 0)
    }

    /** The emergency stop: closes the channel, drops queued work, and denies any pending approval. */
    fun stop(reason: String = "You stopped the session") {
        if (!wanted && socket == null) return
        wanted = false
        paused = false
        expiryJob?.cancel()
        reconnectJob?.cancel()
        approvals.cancel()
        commandJob?.cancel()
        commands.close()
        socket?.let {
            it.send(Protocol.json.encodeToString(ByeMessage.serializer(), ByeMessage("user_stopped")))
            it.close(1000, "stopped")
        }
        socket = null
        generation++
        log.add(ActivityKind.SESSION, reason)
        _state.value = if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle
    }

    fun setPaused(value: Boolean) {
        if (!wanted || paused == value) return
        paused = value
        if (value) approvals.cancel()
        log.add(ActivityKind.SESSION, if (value) "Session paused" else "Session resumed")
        pushState()
        (state.value as? SessionState.Active)?.let { _state.value = it.copy(paused = value) }
    }

    fun forget() {
        stop("This phone forgot the gateway")
        settings.forgetPairing()
        _state.value = SessionState.Unpaired
    }

    fun clearFailure() {
        if (_state.value is SessionState.Failed || _state.value is SessionState.Revoked) {
            _state.value = if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle
        }
    }

    // ---- Connection ----

    private fun connect(attempt: Int) {
        val pairing = settings.pairing.value
        val token = settings.token()
        if (pairing == null || token == null) {
            fail("This phone has no saved pairing. Pair it again.")
            return
        }
        _state.value = if (attempt == 0) SessionState.Connecting(0) else SessionState.Reconnecting(expiresAtMs, attempt)
        val gen = ++generation
        val request = Request.Builder()
            .url(Pairing.deviceSocketUrl(pairing.gatewayUrl))
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { if (gen == generation) onOpen(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (gen == generation) onMessage(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (gen == generation) onDisconnected(code, null) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (gen == generation) onDisconnected(null, response?.code) }
            }
        })
    }

    private fun onOpen(webSocket: WebSocket) {
        val hello = HelloMessage(
            protocol = Protocol.VERSION,
            device = deviceDescriptor(),
            capabilities = wireCapabilities(),
            session = sessionInfo(),
            deviceTimeMs = System.currentTimeMillis(),
        )
        webSocket.send(Protocol.json.encodeToString(HelloMessage.serializer(), hello))
    }

    private fun onMessage(text: String) {
        val message = try {
            GatewayParser.parse(text)
        } catch (e: ProtocolException) {
            // Answer commands we cannot parse so the gateway does not wait; drop anything else.
            GatewayParser.commandId(text)?.let { socket?.send(Outgoing.error(it, e.code, e.message ?: "invalid command")) }
            return
        }
        when (message) {
            is GatewayMessage.Welcome -> {
                if (!Protocol.isCompatible(message.protocol)) {
                    fail("The gateway speaks protocol ${message.protocol.take(8)}; this app needs ${Protocol.VERSION}. Update one of them.")
                    return
                }
                log.add(ActivityKind.CONNECTION, "Connected to the gateway")
                _state.value = SessionState.Active(expiresAtMs, paused)
            }
            is GatewayMessage.CommandMessage -> commands.trySend(message.envelope)
            is GatewayMessage.Cancel -> if (runningCommandId == message.id) approvals.cancel()
            is GatewayMessage.Revoked -> {
                stop("The gateway owner revoked this phone")
                settings.forgetPairing()
                _state.value = SessionState.Revoked("The gateway owner revoked this phone. Pair again to reconnect.")
            }
        }
    }

    private fun onDisconnected(closeCode: Int?, httpStatus: Int?) {
        socket = null
        approvals.cancel()
        if (!wanted) return
        when {
            httpStatus == 401 -> fail("The gateway no longer recognises this phone. It may have been revoked; pair again.")
            closeCode == 4003 -> {
                stop("The gateway owner revoked this phone")
                settings.forgetPairing()
                _state.value = SessionState.Revoked("The gateway owner revoked this phone. Pair again to reconnect.")
            }
            closeCode == 4002 -> fail("The gateway rejected this app version. Update Latch or the gateway.")
            else -> {
                val attempt = ((_state.value as? SessionState.Reconnecting)?.attempt ?: 0) + 1
                log.add(ActivityKind.CONNECTION, "Connection lost; retrying")
                _state.value = SessionState.Reconnecting(expiresAtMs, attempt)
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(min(30_000L, 1_000L shl min(attempt, 5)))
                    if (wanted) connect(attempt)
                }
            }
        }
    }

    private fun fail(message: String) {
        stop(message)
        _state.value = SessionState.Failed(message)
    }

    private fun pushState() {
        val ws = socket ?: return
        if (_state.value !is SessionState.Active) return
        val message = StateMessage(wireCapabilities(), sessionInfo(), System.currentTimeMillis())
        ws.send(Protocol.json.encodeToString(StateMessage.serializer(), message))
    }

    // ---- Commands: strictly one at a time ----

    private fun startCommandLoop() {
        commands = Channel(Channel.UNLIMITED)
        commandJob = scope.launch {
            for (envelope in commands) {
                runningCommandId = envelope.id
                val reply = try {
                    val data = withTimeout(envelope.deadlineMs.coerceIn(1_000, 180_000)) {
                        executor.execute(envelope, sessionInfo(), capabilityStatuses())
                    }
                    Outgoing.ok(envelope.id, data)
                } catch (e: ProtocolException) {
                    if (e.code != ErrorCode.USER_DENIED && e.code != ErrorCode.CONFIRMATION_EXPIRED) {
                        log.add(ActivityKind.REFUSAL, "Refused ${envelope.command.name}: ${e.code.wire}")
                    }
                    Outgoing.error(envelope.id, e.code, e.message ?: e.code.wire)
                } catch (e: TimeoutCancellationException) {
                    Outgoing.error(envelope.id, ErrorCode.DEADLINE_EXCEEDED, "the phone ran out of time")
                }
                runningCommandId = null
                socket?.send(reply)
            }
        }
    }
}

/** Gateway address handling, shared by pairing and the session. */
object Pairing {
    fun deviceSocketUrl(gatewayUrl: String): String {
        val base = gatewayUrl.trimEnd('/')
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        } + "/v1/device"
    }

    /** Adds https:// when the owner typed a bare host. Returns null for anything unusable. */
    fun normalize(input: String, allowCleartext: Boolean): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val url = if ("://" in trimmed) trimmed else "https://$trimmed"
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") && allowCleartext -> url
            else -> null
        }
    }

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
