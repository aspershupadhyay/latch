// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.accessibility.ButtonReader
import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService
import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.ApprovalGrants
import io.github.aspershupadhyay.latch.data.Autonomy
import io.github.aspershupadhyay.latch.files.PhoneFiles
import io.github.aspershupadhyay.latch.files.PhoneTransfers
import io.github.aspershupadhyay.latch.data.Settings
import io.github.aspershupadhyay.latch.policy.Consequences
import io.github.aspershupadhyay.latch.policy.LabelReader
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityState
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.HelloMessage
import io.github.aspershupadhyay.latch.protocol.Outgoing
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.StateMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
    private val appName: (String) -> String? = { null },
    private val autonomy: Autonomy? = null,
    private val files: PhoneFiles? = null,
    private val transfers: PhoneTransfers? = null,
    /** Protocol 1.13: whether Android's battery optimisation applies to Latch; null when unknown. */
    private val batteryRestricted: () -> Boolean? = { null },
    /** R27 (ADR-046): this phone's ADB identity, present once the device build wires it; null disables full-access ADB. */
    private val adbKey: io.github.aspershupadhyay.latch.session.adb.AdbKey? = null,
    /** R27 (ADR-046): cable-free wireless-debugging pairing; null where discovery is unavailable. */
    private val adbWireless: io.github.aspershupadhyay.latch.session.adb.AdbWireless? = null,
    /** Protocol 1.19 (ADR-048): command ids already run, kept across sessions and restarts. */
    private val recentCommands: RecentCommands = RecentCommands(),
    /** Protocol 1.20 (ADR-049): where background ADB jobs write their output until it is collected. */
    private val adbJobDir: java.io.File? = null,
) {
    private val _state = MutableStateFlow<SessionState>(if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * The gateway's protocol when it is older than this app's, so the owner can be
     * told to update it (an old gateway hides newer tools from the AI). Null when current.
     */
    private val _outdatedGateway = MutableStateFlow<String?>(null)
    val outdatedGateway: StateFlow<String?> = _outdatedGateway.asStateFlow()

    /** Minor protocol version of the connected gateway; 1.4 relays approvals and waits for them. */
    @Volatile private var gatewayMinor = 0

    /**
     * R27 (ADR-046): full-access ADB runs each command over a fresh connection
     * to the device's own adbd, reading the wireless-debugging endpoint fresh
     * each time so re-pairing takes effect without a restart. Android moves
     * wireless debugging to a new port after a reboot or toggle; when the saved
     * port refuses, the bridge finds the new one over mDNS and saves it, so
     * reconnecting needs neither a cable nor a new pairing. Null (no key or no
     * endpoint) leaves adb.shell refused as unsupported.
     */
    private val adbBridge: AdbShellBridge? = adbKey?.let { key ->
        io.github.aspershupadhyay.latch.session.adb.AdbShellExecutor(adbJobDir) {
            val saved = settings.preferences.value.adbEndpoint
            try {
                io.github.aspershupadhyay.latch.session.adb.SocketAdbTransport.connect(saved, key)
            } catch (e: java.io.IOException) {
                val fresh = try {
                    adbWireless?.connectEndpoint()?.takeIf { it != saved }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
                when {
                    fresh != null -> {
                        settings.update { it.copy(adbEndpoint = fresh) }
                        io.github.aspershupadhyay.latch.session.adb.SocketAdbTransport.connect(fresh, key)
                    }
                    // R29 (b): without Wi-Fi, wireless debugging is off; the port the owner had
                    // Latch open stays up until the phone restarts.
                    settings.preferences.value.adbFixedPort ->
                        io.github.aspershupadhyay.latch.session.adb.SocketAdbTransport.connect("127.0.0.1:${io.github.aspershupadhyay.latch.data.ADB_FIXED_PORT}", key)
                    else -> throw e
                }
            }
        }
    }

    private val executor = CommandExecutor(
        bridge, approvals, log, grants, consequences, appName, autonomy, { gatewayMinor >= 4 }, files, transfers, ::labelReader,
        status = { bridge.service.value?.let { runCatching { PhoneControls.status(it) }.getOrNull() } },
        adbShell = adbBridge,
    )
    private var reader: Pair<LatchAccessibilityService, ButtonReader>? = null
    private var link: DeviceLink? = null
    @Volatile private var wanted = false
    @Volatile private var paused = false
    @Volatile private var expiresAtMs = 0L
    private var expiryJob: Job? = null
    @Volatile private var runningCommandId: String? = null
    /** Running `adb.shell` commands by id (they run in parallel), so a gateway cancel can stop one. */
    private val runningAdb = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** ADR-032: reads unlabeled buttons only while the owner's switch is on; one reader per service. */
    private fun labelReader(): LabelReader? {
        if (!settings.preferences.value.readButtons) return null
        val service = bridge.service.value ?: return null
        reader?.takeIf { it.first === service }?.let { return it.second }
        // The accessibility service restarted: the old reader's ML Kit resources go with it.
        reader?.second?.close()
        return ButtonReader(service, service::captureRegion).also { reader = service to it }
    }

    init {
        approvals.onRequested = ::offerApproval
        scope.launch {
            combine(settings.preferences, bridge.service) { _, _ -> Unit }.collect {
                // Turning full ADB off ends its background jobs, as Stop does (ADR-049).
                if (!adbActive()) adbBridge?.stopJobs()
                // Past the timer only full ADB kept the link up; once it is off, end it.
                if (wanted && expiresAtMs > 0 && System.currentTimeMillis() >= expiresAtMs && !adbActive()) {
                    stop("Session time ended")
                } else {
                    pushState()
                }
            }
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
                // ADR-046: full-access ADB does not use the accessibility service; it
                // needs a wireless-debugging endpoint from pairing instead.
                capability == Capability.ADB_SHELL ->
                    if (adbBridge != null && prefs.adbEndpoint != null) CapabilityStatus.ENABLED else CapabilityStatus.NEEDS_PERMISSION
                !serviceOn -> CapabilityStatus.NEEDS_PERMISSION
                // Brightness needs Android's "Modify system settings"; some phones have no flashlight.
                else -> bridge.service.value?.let { PhoneControls.limit(it, capability) } ?: CapabilityStatus.ENABLED
            }
        }
    }

    private fun sessionInfo() = SessionInfo(
        expiresAtMs, settings.preferences.value.approveEveryAction, paused, settings.preferences.value.remoteApprovals,
    )

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
        transfers?.cancelAll()
        files?.reset()
        // "This session" app answers from an earlier session are gone already (stop clears them);
        // Auto mode chosen "for this session" just before starting must survive the start.
        expiresAtMs = System.currentTimeMillis() + settings.preferences.value.sessionMinutes * 60_000L
        log.add(ActivityKind.SESSION, "Session started for ${settings.preferences.value.sessionMinutes} minutes", by = ActivityActor.OWNER)
        // The owner's own taps while the session runs are logged as theirs.
        bridge.touches.onOwnerTap = { pkg -> log.add(ActivityKind.ACTION, "You tapped the screen", appName(pkg) ?: pkg, ActivityActor.OWNER) }
        startExpiryTimer()
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
                    batteryRestricted = batteryRestricted(),
                )
            },
            onCommand = ::runCommand,
            events = linkEvents,
            // Protocol 1.12 (ADR-037): a keystore key for this pairing signs every message.
            signer = KeystoreSigner.forPairing(pairing.deviceId),
            recent = recentCommands,
        ).also { it.start() }
    }

    /** Whether full-access ADB is switched on and paired, so `adb.shell` can run. */
    private fun adbActive(): Boolean = capabilityStatuses()[Capability.ADB_SHELL] == CapabilityStatus.ENABLED

    /**
     * The session timer ran out. Ordinary tools end with it. ADR-046: full-access
     * ADB has no Latch-imposed expiry, so while it is on the link stays up for
     * `adb.shell` alone (the executor refuses everything else past the timer)
     * until the owner presses Stop or turns full ADB off.
     */
    private fun sessionTimeEnded() {
        if (!adbActive()) {
            stop("Session time ended")
            return
        }
        approvals.cancel()
        grants.endSession()
        autonomy?.endSession()
        transfers?.cancelAll()
        files?.reset()
        log.add(ActivityKind.SESSION, "Session time ended. Full ADB access stays connected until you press Stop", by = ActivityActor.LATCH)
        pushState()
    }

    /** Whether a screen command runs right now (the mirror page shows "AI is working"). */
    val busy: Boolean get() = runningCommandId != null

    /** Called when the owner presses the emergency Stop, so it ends live mirroring too (R30). */
    @Volatile var onOwnerStop: (() -> Unit)? = null

    /** The emergency stop: ends the session, drops queued work, and denies any pending approval. */
    fun stop(reason: String = OWNER_STOP) {
        if (reason == OWNER_STOP) onOwnerStop?.invoke()
        if (!wanted && link == null) return
        wanted = false
        paused = false
        bridge.touches.onOwnerTap = null
        expiryJob?.cancel()
        approvals.cancel()
        grants.endSession()
        autonomy?.endSession()
        // File ids live for one session.
        transfers?.cancelAll()
        files?.reset()
        // Stop ends full-access ADB, background jobs included (ADR-046, ADR-049).
        adbBridge?.stopJobs()
        val closing = link
        link = null
        // Tell the gateway right away so tool calls fail fast instead of timing out.
        scope.launch { closing?.stop(sayBye = true) }
        log.add(ActivityKind.SESSION, reason, by = if (reason.startsWith("You ") || reason == FORGOT) ActivityActor.OWNER else ActivityActor.LATCH)
        _state.value = if (settings.pairing.value == null) SessionState.Unpaired else SessionState.Idle
    }

    /** Whether cable-free ADB pairing is available on this build. */
    fun adbWirelessAvailable(): Boolean = adbWireless != null

    /**
     * R27 (ADR-046): pair with this phone's own adbd over Wi-Fi using the code
     * from Developer options → Wireless debugging, then store the connect
     * endpoint so full-access ADB can run. Returns a user-facing message.
     */
    suspend fun pairAdb(code: String, pairingHost: String?, pairingPort: Int?): String {
        val wireless = adbWireless ?: return "Full ADB pairing is not available on this build."
        return when (val result = wireless.pair(code.trim(), pairingHost?.trim()?.ifEmpty { null }, pairingPort)) {
            is io.github.aspershupadhyay.latch.session.adb.AdbWireless.Result.Paired -> {
                settings.update { it.copy(adbEndpoint = result.connectEndpoint) }
                log.add(ActivityKind.SESSION, "You paired full ADB access over Wi-Fi", by = ActivityActor.OWNER)
                "Paired. Full ADB access is ready over Wi-Fi."
            }
            is io.github.aspershupadhyay.latch.session.adb.AdbWireless.Result.Failed -> result.reason
        }
    }

    /**
     * R29 (b): asks adbd to also listen on [ADB_FIXED_PORT] (what `adb tcpip`
     * does), so full ADB keeps working on mobile data until the phone restarts;
     * or, when [on] is false, to close it again (`adb usb`). Needs a working
     * wireless-debugging connection to ask. Returns a message for the owner.
     */
    suspend fun setAdbFixedPort(on: Boolean): String = withContext(Dispatchers.IO) {
        val key = adbKey ?: return@withContext "Full ADB is not available on this build."
        val endpoint = if (on) {
            settings.preferences.value.adbEndpoint ?: return@withContext "Pair full ADB access first."
        } else {
            "127.0.0.1:${io.github.aspershupadhyay.latch.data.ADB_FIXED_PORT}"
        }
        val reply = try {
            val connection = try {
                io.github.aspershupadhyay.latch.session.adb.SocketAdbTransport.connect(endpoint, key)
            } catch (e: java.io.IOException) {
                // Closing: the port may be gone already (a restart); then there is nothing to close.
                if (!on) null else throw e
            }
            connection?.use {
                it.connect()
                it.service(if (on) "tcpip:${io.github.aspershupadhyay.latch.data.ADB_FIXED_PORT}" else "usb:")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext if (on) {
                "Could not open the port: ${e.message ?: e.javaClass.simpleName}. Keep Wi-Fi and wireless debugging on and try again."
            } else {
                settings.update { it.copy(adbFixedPort = false) }
                "Latch no longer uses the port. It closes when the phone restarts."
            }
        }
        settings.update { it.copy(adbFixedPort = on) }
        log.add(
            ActivityKind.SESSION,
            if (on) "You let full ADB work on mobile data (port ${io.github.aspershupadhyay.latch.data.ADB_FIXED_PORT}, until restart)" else "You closed full ADB's mobile-data port",
            by = ActivityActor.OWNER,
        )
        if (on) {
            if (reply?.contains("restarting", ignoreCase = true) == true || reply?.contains("${io.github.aspershupadhyay.latch.data.ADB_FIXED_PORT}") == true) {
                "Full ADB now also works on mobile data, until this phone restarts."
            } else {
                "adbd answered: ${reply?.take(120)?.trim().orEmpty()}"
            }
        } else {
            "The mobile-data port is closed."
        }
    }

    /** Turns full ADB access off and forgets the paired endpoint. */
    fun unpairAdb() {
        if (settings.preferences.value.adbFixedPort) scope.launch { setAdbFixedPort(false) }
        settings.update { it.copy(adbEndpoint = null, enabled = it.enabled - Capability.ADB_SHELL) }
        log.add(ActivityKind.SESSION, "You turned off full ADB access", by = ActivityActor.OWNER)
    }

    /** Ends the session at [expiresAtMs], however often that moves. */
    private fun startExpiryTimer() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            // delay() counts only awake time; look at the wall clock at least every
            // 30 s, so a phone that slept does not run the session past its end.
            while (true) {
                val left = expiresAtMs - System.currentTimeMillis()
                if (left <= 0) break
                delay(minOf(left, 30_000L))
            }
            sessionTimeEnded()
        }
    }

    /**
     * The owner's one-tap Extend: adds the chosen session length to what is
     * left (or to now, once the timer has run out), at most [MAX_SESSION_MINUTES]
     * from now. The session still ends by itself; only the owner extends it.
     */
    fun extend() {
        if (!wanted) return
        val now = System.currentTimeMillis()
        val minutes = settings.preferences.value.sessionMinutes
        val wasOver = now >= expiresAtMs
        expiresAtMs = minOf(maxOf(expiresAtMs, now) + minutes * 60_000L, now + MAX_SESSION_MINUTES * 60_000L)
        if (wasOver) startExpiryTimer()
        log.add(ActivityKind.SESSION, "You extended the session by $minutes minutes", by = ActivityActor.OWNER)
        pushState()
        when (val current = _state.value) {
            is SessionState.Active -> _state.value = current.copy(expiresAtMs = expiresAtMs)
            is SessionState.Reconnecting -> _state.value = current.copy(expiresAtMs = expiresAtMs)
            else -> Unit
        }
    }

    fun setPaused(value: Boolean, reason: String? = null) {
        if (!wanted || paused == value) return
        paused = value
        if (value) approvals.cancel()
        log.add(ActivityKind.SESSION, reason ?: if (value) "You paused the session" else "You resumed the session", by = ActivityActor.OWNER)
        pushState()
        (state.value as? SessionState.Active)?.let { _state.value = it.copy(paused = value) }
    }

    fun forget() {
        stop(FORGOT)
        val pairing = settings.pairing.value
        // The relay would keep this phone's entry, still counted as connected for
        // a short while, so pairing again at once left duplicates in list_devices.
        // An owner's phone removes its own entry; otherwise the next pairing of
        // this phone replaces it.
        settings.ownerKey()?.let { key ->
            if (pairing != null) {
                scope.launch {
                    runCatching { OwnerClient(http, pairing.gatewayUrl, key).removePhone(pairing.deviceId) }
                }
            }
        }
        pairing?.let { KeystoreSigner.delete(it.deviceId) }
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

        override fun gatewayProtocol(version: String) {
            gatewayMinor = Protocol.minorOf(version) ?: 0
            bridge.gatewayMinor = gatewayMinor
            val behind = Protocol.minorOf(version)?.let { it < Protocol.MINOR } ?: false
            scope.launch { _outdatedGateway.value = if (behind) version else null }
        }

        override fun cancel(commandId: String) {
            scope.launch {
                // A full-access ADB command may run indefinitely (ADR-046); the
                // gateway's cancel is how its caller stops it.
                runningAdb[commandId]?.let { it.cancel(); return@launch }
                if (runningCommandId != commandId) return@launch
                approvals.cancel()
            }
        }

        override fun approvalAnswer(nonce: String, choice: String) {
            scope.launch {
                // Only when the owner allowed it; a gateway cannot answer on its own otherwise.
                if (!settings.preferences.value.remoteApprovals) return@launch
                // Relayed by the AI app: Latch cannot tell who typed it there.
                if (approvals.answerRemote(nonce, choice)) log.add(ActivityKind.APPROVAL, "Answered in the AI app: $choice", by = ActivityActor.AI)
            }
        }
    }

    /** Tells a 1.4 gateway the phone waits for the owner; it may ask in the AI app too. */
    private fun offerApproval(pending: PendingApproval) {
        val id = pending.commandId ?: return
        if (gatewayMinor < 4) return
        // A step handed to the owner is answered on the phone only, never in the AI app.
        if (pending.kind == ApprovalKind.OWNER_TASK) return
        link?.send(
            Outgoing.approvalRequest(
                id, pending.nonce, pending.title, pending.detail, pending.kind == ApprovalKind.APP, pending.choices,
                settings.preferences.value.remoteApprovals, pending.expiresAtMs,
            ),
        )
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

    /**
     * Runs off the main thread: reading a big screen or a folder blocks on the
     * system for a while, and a blocked main thread is what makes Android close
     * an app ("isn't responding") and switch its accessibility service off.
     * Nothing a command does may crash the app either: every failure becomes an
     * error answer for the AI and a line in Activity.
     */
    private suspend fun runCommand(envelope: CommandEnvelope): String = withContext(Dispatchers.Default) {
        // ADB commands run beside the one screen command; only that one is "running".
        val isAdb = envelope.command is Command.AdbShell
        if (!isAdb) runningCommandId = envelope.id
        try {
            val command = envelope.command
            val data = if (command is Command.AdbShell) {
                // ADR-046: no Latch-imposed time limit. The gateway's deadline is the
                // caller's timeout_ms, its own platform limit, or none (u32 max);
                // that, a gateway cancel, or the owner's Stop ends the command.
                val limit = envelope.deadlineMs.takeIf { it in 1 until NO_DEADLINE_MS }
                    ?: command.timeoutMs.takeIf { it > 0 }?.toLong()
                // A supervisor scope, so a failed or cancelled command answers
                // with an error instead of tearing down the command loop.
                supervisorScope {
                    val job = async {
                        val run = suspend { executor.execute(envelope, sessionInfo(), capabilityStatuses()) { sessionInfo() to capabilityStatuses() } }
                        if (limit != null) withTimeout(limit) { run() } else run()
                    }
                    runningAdb[envelope.id] = job
                    try {
                        job.await()
                    } catch (e: CancellationException) {
                        ensureActive()
                        if (e is TimeoutCancellationException) throw e
                        throw ProtocolException(ErrorCode.CANCELLED, "the ADB command was cancelled")
                    } finally {
                        runningAdb.remove(envelope.id)
                    }
                }
            } else {
                // A 1.4 gateway keeps waiting while the owner is asked, so the command may too.
                val limit = envelope.deadlineMs.coerceIn(1_000, 180_000) + if (gatewayMinor >= 4) APPROVAL_GRACE_MS else 0
                withTimeout(limit) {
                    executor.execute(envelope, sessionInfo(), capabilityStatuses()) { sessionInfo() to capabilityStatuses() }
                }
            }
            Outgoing.ok(envelope.id, data)
        } catch (e: ProtocolException) {
            if (e.code != ErrorCode.USER_DENIED && e.code != ErrorCode.CONFIRMATION_EXPIRED) {
                log.add(ActivityKind.REFUSAL, "Refused ${envelope.command.name}: ${e.code.wire}")
            }
            Outgoing.error(envelope.id, e.code, e.message ?: e.code.wire)
        } catch (e: TimeoutCancellationException) {
            log.add(ActivityKind.REFUSAL, "Ran out of time: ${envelope.command.name}")
            Outgoing.error(envelope.id, ErrorCode.DEADLINE_EXCEEDED, "the phone ran out of time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The kind of failure only, never a path or screen text.
            Crashes.record(e)
            log.add(ActivityKind.REFUSAL, "Could not finish ${envelope.command.name}: ${e.javaClass.simpleName}")
            Outgoing.error(envelope.id, ErrorCode.INTERNAL, "the phone could not finish this (${e.javaClass.simpleName}); observe and try again")
        } finally {
            if (!isAdb) runningCommandId = null
        }
    }
}

/** The longest session the owner can choose or extend to, from now. */
const val MAX_SESSION_MINUTES = 8 * 60

/** Extra time a command may take while the owner answers an approval (protocol 1.4 gateways). */
private const val APPROVAL_GRACE_MS = 120_000L

/** The `deadline_ms` a gateway sends for an `adb.shell` without any deadline (u32 max, ADR-046). */
private const val NO_DEADLINE_MS = 4_294_967_295L

/** Gateway address handling, shared by pairing and the session. */
object Pairing {
    /** A gateway address and, for a gateway on the owner's own Wi-Fi (R24), the pin of its certificate. */
    data class Address(val url: String, val pin: String?)

    /**
     * Reads what the owner typed or pasted: an address, optionally with
     * `#pin=<base64url SHA-256>` as `latch-gateway` prints it for phones on the
     * same Wi-Fi. Returns null for anything unusable; a pin needs https.
     */
    fun parse(input: String, allowCleartext: Boolean): Address? {
        val trimmed = input.trim()
        val fragment = trimmed.substringAfter('#', "")
        val pin = if ('#' in trimmed) fragment.removePrefix("pin=").takeIf { fragment.startsWith("pin=") && GatewayPins.isPin(it) } ?: return null else null
        val url = normalize(trimmed.substringBefore('#'), allowCleartext) ?: return null
        if (pin != null && !url.startsWith("https://")) return null
        return Address(url, pin)
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

    fun client(): OkHttpClient = GatewayPins.install(OkHttpClient.Builder())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

private const val FORGOT = "This phone forgot the gateway"

/** The reason the owner's own Stop gives; it ends live mirroring too. */
const val OWNER_STOP = "You stopped the session"
