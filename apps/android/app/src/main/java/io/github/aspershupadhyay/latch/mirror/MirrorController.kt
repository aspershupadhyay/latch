// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.R
import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.Settings
import io.github.aspershupadhyay.latch.session.KeystoreSigner
import io.github.aspershupadhyay.latch.session.SessionController
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.Base64

/** What the Home screen shows about mirroring. */
data class MirrorStatus(
    val phase: Phase = Phase.OFF,
    /** The 6-digit code to type on the computer, until it is used. */
    val code: String? = null,
    /** Where to open the page, best first (the gateway's own computer when it is on the Wi-Fi). */
    val urls: List<String> = emptyList(),
    /** The page with the viewer key, for opening without the code. */
    val link: String? = null,
    val message: String? = null,
) {
    enum class Phase { OFF, STARTING, WAITING, WATCHING, RECONNECTING, FAILED }
}

/**
 * Live mirror on the phone (R30, ADR-051): Android's capture consent, a
 * `mediaProjection` foreground service ([MirrorService]), the stream to the
 * gateway ([MirrorLink], [MirrorCapture]), and the owner's input from the page.
 *
 * Input is the owner's own: it is performed through the accessibility service
 * and logged as theirs. The first input while an AI session runs pauses the
 * AI ("You're in control") until the owner lets it continue. Latch's own
 * windows and password fields are refused, so they stay on the phone.
 */
class MirrorController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val session: SessionController,
    private val log: ActivityLog,
    private val bridge: DeviceBridge,
) {
    private val _status = MutableStateFlow(MirrorStatus())
    val status: StateFlow<MirrorStatus> = _status.asStateFlow()

    private var service: Service? = null
    private var projection: MediaProjection? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var capture: MirrorCapture? = null
    @Volatile private var link: MirrorLink? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var key: String? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var aiJob: Job? = null
    private var inputJob: Job? = null
    @Volatile private var watching = false
    @Volatile private var ownerControl = false
    private var lastScreen: MirrorCapture.Screen? = null

    init {
        // The phone's emergency Stop ends mirroring as well.
        session.onOwnerStop = { scope.launch { stop("Mirroring ended with Stop") } }
    }

    /** Android's "Share your screen?" dialog, offering the entire screen only where Android allows. */
    fun consentIntent(): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return if (MirrorCapture.fullScreenOnly) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /**
     * The gateway to mirror through: only the self-hosted gateway relays video, so a
     * kept computer-on-Wi-Fi connection is used even while the hosted relay is in use (ADR-053).
     */
    private fun target(): Pair<io.github.aspershupadhyay.latch.data.Pairing, String>? {
        val wifi = io.github.aspershupadhyay.latch.data.ConnectionKind.WIFI
        val active = settings.pairing.value
        if (active != null && active.kind != wifi) {
            val kept = settings.saved.value[wifi]
            val token = settings.keptToken(wifi)
            if (kept != null && token != null) {
                io.github.aspershupadhyay.latch.session.GatewayPins.set(kept.gatewayUrl, kept.pin)
                return kept to token
            }
        }
        return active?.let { a -> settings.token()?.let { a to it } }
    }

    /** Why mirroring cannot start now, or null when it can. */
    fun unavailableReason(): String? = when {
        settings.pairing.value == null -> "Pair this phone with your relay first."
        settings.token() == null -> "This phone has no saved credential. Pair it again."
        bridge.service.value == null -> "Turn on Latch's screen access first; the mirror uses it for your clicks and typing."
        else -> null
    }

    /** The owner allowed the capture: start the foreground service with Android's answer. */
    fun allowed(resultCode: Int, data: Intent) {
        _status.value = MirrorStatus(MirrorStatus.Phase.STARTING)
        val intent = Intent(context, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_RESULT, resultCode)
            .putExtra(MirrorService.EXTRA_DATA, data)
        context.startForegroundService(intent)
    }

    /** The owner said no in Android's dialog. */
    fun declined() {
        _status.value = MirrorStatus()
    }

    /** Called by [MirrorService] once it is in the foreground (Android requires that order). */
    fun begin(service: Service, resultCode: Int, data: Intent) {
        stopParts()
        this.service = service
        val (pairing, token) = target() ?: run {
            fail("This phone is not paired with a relay.")
            return
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        } ?: run {
            fail("Android did not allow the screen capture. Try again.")
            return
        }
        this.projection = projection
        val thread = HandlerThread("latch-mirror").also { it.start() }
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        // Android 14+ requires the callback before the display; it also tells us when
        // the owner ends sharing from the status bar.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stop("Android ended screen sharing") }
            }
        }
        projection.registerCallback(callback, handler)
        projectionCallback = callback
        capture = try {
            MirrorCapture(projection, handler, ::screen, ::sendFrame) { problem -> scope.launch { notice(problem) } }.also {
                // The phone's sound too, when the owner allowed audio recording (ADR-055).
                val audioAllowed = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (audioAllowed) it.audio = MirrorAudio(projection, ::sendFrame)
            }
        } catch (e: Exception) {
            fail("The screen could not be captured (${e.javaClass.simpleName}).")
            return
        }
        watchDisplay(handler)
        val viewerKey = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        key = viewerKey
        link = MirrorLink(http, pairing.gatewayUrl, token, viewerKey, KeystoreSigner.forPairing(pairing.deviceId), linkEvents).also { it.start() }
        startInput()
        log.add(ActivityKind.SESSION, "You started mirroring to your computer", by = ActivityActor.OWNER)
    }

    /** Ends mirroring: the page hears it at once, the capture and the service stop. */
    fun stop(reason: String = "You ended mirroring") {
        if (_status.value.phase == MirrorStatus.Phase.OFF && service == null) return
        val wasOn = service != null
        stopParts()
        service?.let { s ->
            service = null
            s.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            s.stopSelf()
        }
        if (wasOn) log.add(ActivityKind.SESSION, reason, by = if (reason.startsWith("You")) ActivityActor.OWNER else ActivityActor.LATCH)
        _status.value = MirrorStatus()
    }

    /** The service went away on its own (Android stopped it): clean up. */
    fun serviceGone() {
        if (service != null) {
            service = null
            stop("Mirroring ended")
        }
    }

    private fun fail(message: String) {
        stopParts()
        service?.let { s ->
            service = null
            s.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            s.stopSelf()
        }
        _status.value = MirrorStatus(MirrorStatus.Phase.FAILED, message = message)
    }

    private fun stopParts() {
        watching = false
        ownerControl = false
        aiJob?.cancel()
        inputJob?.cancel()
        aiJob = null
        inputJob = null
        input.close()
        link?.stop()
        link = null
        displayListener?.let { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        val c = capture
        val h = handler
        capture = null
        if (c != null && h != null) h.post { c.release() }
        projection?.let { p ->
            // Our own stop must not come back as "Android ended screen sharing".
            projectionCallback?.let { runCatching { p.unregisterCallback(it) } }
            runCatching { p.stop() }
        }
        projection = null
        projectionCallback = null
        thread?.quitSafely()
        thread = null
        handler = null
        key = null
    }

    // ---- The screen ----

    private fun screen(): MirrorCapture.Screen {
        val dpi = context.resources.displayMetrics.densityDpi
        // The display itself, not a window's metrics: a service's window metrics can stay
        // portrait after the phone turns, which squeezed landscape video into a tall frame.
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        return MirrorCapture.Screen(metrics.widthPixels, metrics.heightPixels, dpi, display.rotation * 90)
    }

    private fun watchDisplay(handler: Handler) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val now = screen()
                if (now == lastScreen) return
                lastScreen = now
                capture?.screenChanged()
                link?.sendText(MirrorMessages.screen(now.width, now.height, now.rotation))
            }
        }
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, handler)
        displayListener = listener
    }

    private fun sendFrame(kind: Byte, keyFrame: Boolean, timestampUs: Long, payload: java.nio.ByteBuffer): Boolean {
        val l = link ?: return false
        if (!watching) return true
        return l.sendFrame(MirrorMessages.frame(kind, keyFrame, timestampUs, payload))
    }

    // ---- The gateway ----

    private val linkEvents = object : MirrorLink.Events {
        override fun ready(code: String?, urls: List<String>) {
            scope.launch {
                val k = key ?: return@launch
                val current = _status.value
                _status.value = current.copy(
                    phase = if (watching) MirrorStatus.Phase.WATCHING else MirrorStatus.Phase.WAITING,
                    // A reconnect gets no new code; the old one (if still unused) keeps working.
                    code = code ?: current.code.takeIf { current.phase == MirrorStatus.Phase.RECONNECTING },
                    urls = urls,
                    link = urls.firstOrNull()?.let { "$it#k=$k" },
                    message = null,
                )
                updateNotification()
            }
        }

        override fun message(message: MirrorIn) {
            when (message) {
                is MirrorIn.Viewer -> scope.launch { viewer(message.on, message.codec) }
                is MirrorIn.Code -> scope.launch {
                    _status.value = _status.value.copy(code = message.code)
                    updateNotification()
                }
                is MirrorIn.Sync -> handler?.post { capture?.requestKeyFrame() }
                is MirrorIn.CodecChoice -> handler?.post { capture?.start(message.codec) }
                is MirrorIn.Ping -> link?.sendText(MirrorMessages.pong(message.at))
                else -> input.trySend(Input.Message(message, SystemClock.uptimeMillis()))
            }
        }

        override fun reconnecting() {
            scope.launch {
                if (_status.value.phase != MirrorStatus.Phase.OFF) {
                    _status.value = _status.value.copy(phase = MirrorStatus.Phase.RECONNECTING)
                    updateNotification()
                }
            }
        }

        override fun refused(reason: String) {
            scope.launch { fail(reason.replaceFirstChar { it.uppercase() }.trimEnd('.') + ".") }
        }
    }

    private fun viewer(on: Boolean, codec: Codec) {
        watching = on
        val h = handler ?: return
        if (on) {
            val s = screen()
            lastScreen = s
            link?.sendText(MirrorMessages.screen(s.width, s.height, s.rotation))
            h.post { capture?.start(codec) }
            startAi()
            log.add(ActivityKind.CONNECTION, "Your computer is watching the screen", by = ActivityActor.OWNER)
            _status.value = _status.value.copy(phase = MirrorStatus.Phase.WATCHING)
        } else {
            h.post { capture?.stop() }
            aiJob?.cancel()
            _status.value = _status.value.copy(phase = MirrorStatus.Phase.WAITING)
        }
        updateNotification()
    }

    private fun notice(text: String) {
        link?.sendText(MirrorMessages.notice(text))
    }

    // ---- What the AI is doing, for the page ----

    private fun startAi() {
        aiJob?.cancel()
        aiJob = scope.launch {
            var last = ""
            while (true) {
                val message = aiMessage()
                if (message != last) {
                    link?.sendText(message)
                    last = message
                }
                delay(AI_REFRESH_MS)
            }
        }
    }

    private fun aiMessage(): String {
        val state = session.state.value
        val live = state is SessionState.Active || state is SessionState.Connecting || state is SessionState.Reconnecting
        val paused = (state as? SessionState.Active)?.paused == true
        if (!paused) ownerControl = false
        val since = System.currentTimeMillis() - STEPS_WINDOW_MS
        val steps = log.entries.value.asSequence()
            .filter { it.atMs >= since && it.kind != ActivityKind.SCREEN }
            .take(STEPS)
            .map { MirrorMessages.Step(it.atMs, it.by.wire, it.summary, it.app) }
            .toList()
        return MirrorMessages.ai(
            session = if (!live) "off" else if (paused) "paused" else "active",
            owner = ownerControl,
            working = live && !paused && session.busy,
            steps = steps,
        )
    }

    /** The owner's first input while the AI works hands control to them. */
    private fun ownerInput() {
        val state = session.state.value as? SessionState.Active ?: return
        if (state.paused) return
        session.setPaused(true, "You took over from the mirror")
        ownerControl = true
        notice("You're in control. The AI waits until you let it continue.")
    }

    // ---- The owner's input, one at a time on the main thread ----

    private sealed interface Input {
        data class Message(val message: MirrorIn, val atMs: Long) : Input
        data object Tick : Input
        data class Done(val ok: Boolean) : Input
    }

    /** A new channel per mirror, so nothing from an ended one is replayed into the next. */
    private var input = Channel<Input>(Channel.UNLIMITED)

    private fun startInput() {
        inputJob?.cancel()
        input.close()
        val input = Channel<Input>(Channel.UNLIMITED)
        this.input = input
        inputJob = scope.launch(Dispatchers.Main) {
            val planner = StrokePlanner()
            val queue = ArrayDeque<Pair<StrokePlanner.Segment, Boolean>>()
            var inFlight: Boolean? = null // null: nothing running; true: a planner piece; false: a wheel piece
            var ignoring = false
            var tick: Job? = null
            var wheelAt: Pair<Int, Int>? = null
            var wheelDx = 0
            var wheelDy = 0

            fun pump() {
                if (inFlight != null) return
                val (segment, fromPlanner) = queue.removeFirstOrNull() ?: return
                val service = bridge.service.value ?: run {
                    queue.clear()
                    if (fromPlanner) planner.failed()
                    return
                }
                inFlight = fromPlanner
                service.mirrorSegment(segment) { ok -> input.trySend(Input.Done(ok)) }
            }

            fun planned(segments: List<StrokePlanner.Segment>) {
                segments.forEach { queue.addLast(it to true) }
                pump()
            }

            fun flushWheel() {
                val at = wheelAt ?: return
                val service = bridge.service.value ?: return
                if (planner.active || inFlight != null || queue.isNotEmpty()) return
                val s = service.screen()
                WheelGesture.plan(at.first, at.second, wheelDx, wheelDy, s.width, s.height).forEach { queue.addLast(it to false) }
                wheelAt = null
                wheelDx = 0
                wheelDy = 0
                pump()
            }

            for (event in input) {
                when (event) {
                    is Input.Done -> {
                        val fromPlanner = inFlight
                        inFlight = null
                        if (fromPlanner == true) {
                            if (event.ok) planned(planner.done()) else planner.failed()
                        } else if (!event.ok) {
                            queue.clear()
                        }
                        pump()
                        flushWheel()
                    }
                    Input.Tick -> planned(planner.tick(SystemClock.uptimeMillis()))
                    is Input.Message -> {
                        val service = bridge.service.value
                        if (service == null) {
                            notice("Latch's screen access is off on the phone, so clicks and typing can't reach it.")
                            continue
                        }
                        val now = event.atMs
                        when (val m = event.message) {
                            is MirrorIn.Touch -> when (m.phase) {
                                Phase.DOWN -> {
                                    val refusal = service.mirrorRefusal(m.x, m.y)
                                    ignoring = refusal != null
                                    if (refusal != null) {
                                        notice(refusal)
                                    } else {
                                        ownerInput()
                                        // Whatever was left of an earlier stroke is dropped.
                                        queue.removeAll { it.second }
                                        planner.down(m.x, m.y, now)
                                        tick?.cancel()
                                        tick = launch {
                                            delay(StrokePlanner.LIVE_AFTER_MS)
                                            input.trySend(Input.Tick)
                                        }
                                    }
                                }
                                Phase.MOVE -> if (!ignoring) planned(planner.move(m.x, m.y, now))
                                Phase.UP -> {
                                    tick?.cancel()
                                    if (!ignoring) planned(planner.up(m.x, m.y, now))
                                    ignoring = false
                                }
                                Phase.CANCEL -> {
                                    tick?.cancel()
                                    if (!ignoring) planned(planner.cancel(now))
                                    ignoring = false
                                }
                            }
                            is MirrorIn.Wheel -> {
                                val refusal = service.mirrorRefusal(m.x, m.y)
                                if (refusal != null) {
                                    notice(refusal)
                                } else {
                                    ownerInput()
                                    wheelAt = m.x to m.y
                                    wheelDx += m.dx
                                    wheelDy += m.dy
                                    flushWheel()
                                }
                            }
                            is MirrorIn.Text -> {
                                ownerInput()
                                service.mirrorEdit { text, start, end -> TextEdit.insert(text, start, end, m.text) }?.let(::notice)
                            }
                            is MirrorIn.Key -> {
                                ownerInput()
                                val problem = when (m.key) {
                                    MirrorKey.BACKSPACE -> service.mirrorEdit { text, start, end -> TextEdit.backspace(text, start, end) }
                                    MirrorKey.DELETE -> service.mirrorEdit { text, start, end -> TextEdit.delete(text, start, end) }
                                    MirrorKey.ENTER -> service.mirrorEnter()
                                    MirrorKey.TAB -> null
                                    else -> service.mirrorCursor { text, start, end -> TextEdit.move(text, start, end, m.key) }
                                }
                                problem?.let(::notice)
                            }
                            is MirrorIn.Button -> {
                                ownerInput()
                                val action = when (m.name) {
                                    MirrorButton.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                                    MirrorButton.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                                    MirrorButton.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                }
                                if (!service.mirrorButton(action)) notice("The phone did not take that button.")
                            }
                            is MirrorIn.Control -> when (m.action) {
                                MirrorAction.PAUSE -> {
                                    if ((session.state.value as? SessionState.Active)?.paused == false) {
                                        session.setPaused(true, "You paused the AI from the mirror")
                                        ownerControl = true
                                    }
                                }
                                MirrorAction.RESUME -> {
                                    ownerControl = false
                                    session.setPaused(false, "You let the AI continue from the mirror")
                                }
                                MirrorAction.STOP -> session.stop("You stopped the session from the mirror")
                                MirrorAction.END -> stop("You ended mirroring from your computer")
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    // ---- The ongoing notification ----

    /** The foreground notification: what to open on the computer, and Stop. */
    fun notification(): Notification {
        val s = _status.value
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            context, 5, Intent(context, MirrorService::class.java).setAction(MirrorService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (s.phase) {
            MirrorStatus.Phase.WATCHING -> "Your computer is watching and can use this phone."
            MirrorStatus.Phase.RECONNECTING -> "Reconnecting to your relay…"
            else -> s.code?.let { "Code ${formatCode(it)} · open ${s.urls.firstOrNull() ?: "the mirror page on your relay"}" }
                ?: "Waiting for your computer"
        }
        return NotificationCompat.Builder(context, LatchApp.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle("Mirroring to your computer")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun updateNotification() {
        if (service == null) return
        try {
            NotificationManagerCompat.from(context).notify(LatchApp.NOTIFICATION_MIRROR, notification())
        } catch (e: SecurityException) {
            // Notifications switched off; Android's own capture indicator still shows.
        }
    }

    companion object {
        private const val AI_REFRESH_MS = 400L
        private const val STEPS = 6
        private const val STEPS_WINDOW_MS = 30 * 60_000L

        /** "123456" as "123 456", easier to read across a room. */
        fun formatCode(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code
    }
}
