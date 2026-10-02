package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.ApprovalGrants
import io.github.aspershupadhyay.latch.data.Settings
import io.github.aspershupadhyay.latch.policy.Consequences
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityState
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.StateMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
 * Owns the session: its lifetime, pause, the one [DeviceLink] to the gateway,
 * and the device-side execution of commands. All state changes happen on the
 * main thread; link callbacks are marshalled onto [scope].
 */
class SessionController(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val bridge: DeviceBridge,
    private val approvals: ApprovalBroker,
    private val log: ActivityLog,
    private val http: OkHttpClient,
    private val grants: ApprovalGrants,
    consequences: Consequences,
    appName: (String) -> String? = { null },
) {
    private val _state = MutableStateFlow<SessionState>(if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val executor = CommandExecutor(bridge, approvals, log, grants, consequences, appName)
    private var link: DeviceLink? = null
    private var wanted = false
    private var paused = false
    private var expiresAtMs = 0L
    private var expiryJob: Job? = null
    @Volatile private var runningCommandId: String? = null

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
        val pairing = settings.pairing.value ?: return
        if (wanted) return
        val token = settings.token()
        if (token == null) {
            _state.value = SessionState.Failed("This phone has no saved credential. Pair it again.")
            return
        }
        wanted = true
        paused = false
        grants.endSession()
        expiresAtMs = System.currentTimeMillis() + settings.preferences.value.sessionMinutes * 60_000L
        log.add(ActivityKind.SESSION, "Session started for ${settings.preferences.value.sessionMinutes} minutes")
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(expiresAtMs - System.currentTimeMillis())
            stop("Session time ended")
        }
        _state.value = SessionState.Connecting(0)
        link = DeviceLink(
            http = http,
            gatewayUrl = pairing.gatewayUrl,
            token = token,
            scope = scope,
            hello = {
                HelloMessage(
                    protocol = Protocol.VERSION,
                    device = deviceDescriptor(),
                    capabilities = wireCapabilities(),
                    session = sessionInfo(),
                    deviceTimeMs = System.currentTimeMillis(),
                )
            },
            onCommand = ::runCommand,
            events = linkEvents,
        ).also { it.start() }
    }

    /** The emergency stop: ends the session, drops queued work, and denies any pending approval. */
    fun stop(reason: String = "You stopped the session") {
        if (!wanted && link == null) return
        wanted = false
        paused = false
        expiryJob?.cancel()
        approvals.cancel()
        grants.endSession()
        val closing = link
        link = null
        // Tell the gateway right away so tool calls fail fast instead of timing out.
        scope.launch { closing?.stop(sayBye = true) }
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
        grants.clearAll()
        _state.value = SessionState.Unpaired
    }

    fun clearFailure() {
        if (_state.value is SessionState.Failed || _state.value is SessionState.Revoked) {
            _state.value = if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle
        }
    }

    // ---- Link events (arrive on a background thread) ----

    private val linkEvents = object : DeviceLink.Events {
        override fun connected() {
            scope.launch {
                if (!wanted) return@launch
                log.add(ActivityKind.CONNECTION, "Connected to the gateway")
                _state.value = SessionState.Active(expiresAtMs, paused)
            }
        }

        override fun retrying(attempt: Int) {
            scope.launch {
                if (!wanted) return@launch
                approvals.cancel()
                if (attempt == 1) log.add(ActivityKind.CONNECTION, "Connection lost; retrying")
                _state.value = SessionState.Reconnecting(expiresAtMs, attempt)
            }
        }

        override fun rejected(revoked: Boolean) {
            scope.launch {
                if (revoked) {
                    stop("The gateway owner revoked this phone")
                    settings.forgetPairing()
                    _state.value = SessionState.Revoked("The gateway owner revoked this phone. Pair again to reconnect.")
                } else {
                    fail("The gateway does not recognise this phone. It may have been revoked or the gateway was reset; pair again.")
                }
            }
        }

        override fun incompatible(gatewayVersion: String) {
            scope.launch { fail("The gateway speaks protocol $gatewayVersion; this app needs ${Protocol.VERSION}. Update one of them.") }
        }

        override fun cancel(commandId: String) {
            scope.launch { if (runningCommandId == commandId) approvals.cancel() }
        }
    }

    private fun fail(message: String) {
        stop(message)
        _state.value = SessionState.Failed(message)
    }

    private fun pushState() {
        val current = link ?: return
        if (_state.value !is SessionState.Active) return
        current.pushState(StateMessage(wireCapabilities(), sessionInfo(), System.currentTimeMillis()))
    }

    // ---- Commands: strictly one at a time (DeviceLink serialises them) ----

    private suspend fun runCommand(envelope: CommandEnvelope): String = withContext(Dispatchers.Main.immediate) {
        runningCommandId = envelope.id
        try {
            val data = withTimeout(envelope.deadlineMs.coerceIn(1_000, 180_000)) {
                executor.execute(envelope, sessionInfo(), capabilityStatuses()) { sessionInfo() to capabilityStatuses() }
            }
            Outgoing.ok(envelope.id, data)
        } catch (e: ProtocolException) {
            if (e.code != ErrorCode.USER_DENIED && e.code != ErrorCode.CONFIRMATION_EXPIRED) {
                log.add(ActivityKind.REFUSAL, "Refused ${envelope.command.name}: ${e.code.wire}")
            }
            Outgoing.error(envelope.id, e.code, e.message ?: e.code.wire)
        } catch (e: TimeoutCancellationException) {
            Outgoing.error(envelope.id, ErrorCode.DEADLINE_EXCEEDED, "the phone ran out of time")
        } finally {
            runningCommandId = null
        }
    }
}

/** Gateway address handling, shared by pairing and the session. */
object Pairing {
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
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}
