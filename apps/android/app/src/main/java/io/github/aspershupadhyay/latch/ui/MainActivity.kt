// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.PowerManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import io.github.aspershupadhyay.latch.data.ConnectionKind
import io.github.aspershupadhyay.latch.data.FolderGrant
import io.github.aspershupadhyay.latch.data.AutoMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.data.SavedApproval
import io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService
import io.github.aspershupadhyay.latch.data.Pairing as SavedPairing
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.session.GatewayPins
import io.github.aspershupadhyay.latch.session.GatewaySetup
import io.github.aspershupadhyay.latch.session.McpClient
import io.github.aspershupadhyay.latch.session.RelayPhone
import io.github.aspershupadhyay.latch.session.NewMcpClient
import io.github.aspershupadhyay.latch.session.OwnerClient
import io.github.aspershupadhyay.latch.session.Paired
import io.github.aspershupadhyay.latch.session.Pairing
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.session.SetupException
import io.github.aspershupadhyay.latch.session.SignInRequest
import io.github.aspershupadhyay.latch.report.ActivityReport
import io.github.aspershupadhyay.latch.report.ReportSaver
import io.github.aspershupadhyay.latch.session.deviceDescriptor
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.update.UpdateInfo
import io.github.aspershupadhyay.latch.update.Updater
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = LatchApp.get(this)
        val reducedMotion = AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val showIntro = savedInstanceState == null
        setContent {
            // Latch follows the phone's light or dark setting; the bar icons follow it too.
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            DisposableEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            // The opening animation plays once per launch, over the app that is already loading.
            var intro by rememberSaveable { mutableStateOf(showIntro) }
            LatchTheme {
                Box {
                    LatchRoot(app, reducedMotion)
                    if (intro) LatchIntro(reducedMotion) { intro = false }
                }
            }
        }
        // Only a fresh launch carries a new installer answer; a recreated activity would repeat an old one.
        if (savedInstanceState == null) handleInstallStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    override fun onStart() {
        super.onStart()
        val app = LatchApp.get(this)
        if (app.settings.preferences.value.checkUpdates) app.updater.checkIfDue()
    }

    override fun onResume() {
        super.onResume()
        LatchApp.get(this).ownScreenShown = true
    }

    override fun onPause() {
        LatchApp.get(this).ownScreenShown = false
        super.onPause()
    }

    /** The installer's answer to an in-app update; it may need the owner's tap on Android's Update screen. */
    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        LatchApp.get(this).updater.onInstallStatus(intent)?.let { confirm ->
            runCatching { startActivity(confirm) }
        }
    }
}

/** Three tabs, the most a child holds in mind; AI apps and Settings open from the gear on Home. */
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", LatchIcons.Home),
    CAPABILITIES("Access", LatchIcons.ShieldCheck),
    ACTIVITY("History", LatchIcons.Pulse),
}

/** A page over the tabs, reached from Home's gear: Settings, and AI apps inside it. */
enum class Page { SETTINGS, CONNECT }

private enum class Onboarding { WELCOME, CHOOSE, CREATE, WIFI, JOIN, GUIDE, NEARBY }

/** Copies text; secrets are flagged so Android hides them from the clipboard preview. */
fun copyToClipboard(context: Context, label: String, value: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

/** Not in the public SDK; Settings on Android 12+ handles it, and older or other builds fall back below. */
private const val ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

/** Opens Latch's own accessibility switch where the phone supports it, else the accessibility list. */
fun openAccessibilityFor(context: Context) {
    val opened = Build.VERSION.SDK_INT >= 31 && runCatching {
        val service = ComponentName(context, LatchAccessibilityService::class.java).flattenToString()
        context.startActivity(Intent(ACCESSIBILITY_DETAILS).putExtra(Intent.EXTRA_COMPONENT_NAME, service))
    }.isSuccess
    if (!opened) context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun Paired.save(app: LatchApp, pin: String?) = app.settings.savePairing(SavedPairing(gatewayUrl, deviceId, name, pin), deviceToken)

@Composable
fun LatchRoot(app: LatchApp, reducedMotion: Boolean) {
    val state by app.session.state.collectAsStateWithLifecycle()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val pendingSetup by app.pendingSetup.collectAsStateWithLifecycle()
    if (pairing == null) {
        OnboardingFlow(app, (state as? SessionState.Revoked)?.reason, pendingSetup)
        return
    }
    MainTabs(app, reducedMotion)
}

@Composable
private fun OnboardingFlow(app: LatchApp, notice: String?, setUp: ConnectionKind?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by app.settings.saved.collectAsStateWithLifecycle()
    // From the AI apps page's switch: open straight at the chosen way of connecting.
    var step by rememberSaveable {
        mutableStateOf(
            when (setUp) {
                ConnectionKind.HOSTED -> Onboarding.CREATE
                ConnectionKind.WIFI -> Onboarding.WIFI
                null -> Onboarding.WELCOME
            },
        )
    }
    LaunchedEffect(Unit) { app.pendingSetup.value = null }
    var installCopied by rememberSaveable { mutableStateOf(false) }
    var startCopied by rememberSaveable { mutableStateOf(false) }
    var ownerKey by rememberSaveable { mutableStateOf(GatewaySetup.newOwnerKey()) }
    var keyCopied by rememberSaveable { mutableStateOf(false) }
    var deployOpened by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(JoinMode.OWNER_KEY) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // ADR-060: Find my computer.
    var nearby by remember { mutableStateOf<NearbyState>(NearbyState.Searching) }
    var nearbyJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    BackHandler(enabled = step != Onboarding.WELCOME) {
        nearbyJob?.cancel()
        step = if (step == Onboarding.CREATE || step == Onboarding.WIFI) Onboarding.CHOOSE else Onboarding.WELCOME
        error = null
    }

    fun connect(asOwner: Boolean, key: String, needPin: Boolean = false, input: String = address) {
        val parsed = Pairing.parse(input, BuildConfig.ALLOW_CLEARTEXT)
        if (parsed == null) {
            error = if (input.trim().startsWith("http://")) "Use an https:// address." else "That does not look like a web address."
            return
        }
        if (needPin && parsed.pin == null) {
            error = "Enter the whole address under the QR code, including #pin=…"
            return
        }
        val url = parsed.url
        // A gateway on this Wi-Fi (R24): trust its own certificate, by the pin in the address.
        GatewayPins.set(url, parsed.pin)
        busy = true
        error = null
        app.session.clearFailure()
        scope.launch {
            try {
                val model = deviceDescriptor().model
                val paired = if (asOwner) app.setup.pairAsOwner(url, key.trim(), model, model) else app.setup.pairWithCode(url, key, model)
                // A setup code from the owner's terminal also brings the owner key (ADR-054).
                (if (asOwner) key.trim() else paired.ownerKey)?.let { app.settings.saveOwnerKey(it) }
                paired.save(app, parsed.pin)
                // A newer connection of the same kind replaces the one kept before.
                app.settings.pairing.value?.kind?.let { app.session.forgetSaved(it) }
            } catch (e: SetupException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    // ADR-054: the Wi-Fi gateway's QR code, or what the owner typed under it.
    fun connectWifi(scanned: String? = null) {
        val setup = Pairing.splitSetup(scanned ?: address)
        if (scanned != null) {
            if (Pairing.parse(setup.address, BuildConfig.ALLOW_CLEARTEXT)?.pin == null || setup.code == null) {
                error = "That QR code is not from the Latch gateway. Scan the one in your computer's terminal."
                return
            }
            address = setup.address
        }
        val key = setup.code ?: secret.trim()
        if (key.isEmpty()) {
            error = "Enter the setup code shown under the QR code."
            return
        }
        connect(asOwner = !Pairing.isCode(key), key = key, needPin = true, input = setup.address)
    }
    // ADR-060: the computer is linked once its owner answers y; the code then pairs as after a scan.
    fun linkNearby(computer: io.github.aspershupadhyay.latch.session.NearbyLink.Found) {
        nearbyJob?.cancel()
        nearbyJob = scope.launch {
            try {
                val code = io.github.aspershupadhyay.latch.session.NearbyLink.link(computer, Build.MODEL ?: "Android phone", Pairing.client()) { number ->
                    nearby = NearbyState.Number(computer.name, number)
                }
                step = Onboarding.WIFI
                connectWifi(scanned = computer.setupLink(code))
            } catch (e: io.github.aspershupadhyay.latch.session.NearbyLink.NearbyException) {
                nearby = NearbyState.Failed(e.message ?: "Linking stopped.")
            } catch (e: java.io.IOException) {
                nearby = NearbyState.Failed("Lost the computer on the Wi-Fi. Keep latch-start running and try again.")
            }
        }
    }

    fun findNearby() {
        nearbyJob?.cancel()
        nearby = NearbyState.Searching
        step = Onboarding.NEARBY
        nearbyJob = scope.launch {
            val phone = io.github.aspershupadhyay.latch.files.LocalSlots.wifiAddress() as? java.net.Inet4Address
            if (phone == null) {
                nearby = NearbyState.Failed("This phone is not on a Wi-Fi. Join the same Wi-Fi as your computer.")
                return@launch
            }
            val found = io.github.aspershupadhyay.latch.session.NearbyLink.discover(phone, Pairing.client())
            nearby = when {
                found.isEmpty() -> NearbyState.Failed(
                    "No computer found. On your computer, run latch-start (it must show “Connect your phone”), and check both are on the same Wi-Fi.",
                )
                found.size == 1 -> NearbyState.Found(found).also { linkNearby(found.first()) }
                else -> NearbyState.Found(found)
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanSetupQr()) { text ->
        // From any screen: show the Wi-Fi screen, where progress and any error appear.
        text?.let {
            step = Onboarding.WIFI
            connectWifi(scanned = it)
        }
    }

    when (step) {
        Onboarding.WELCOME -> WelcomeScreen(
            onCreateGateway = { step = Onboarding.CHOOSE }, onHaveGateway = { step = Onboarding.JOIN }, notice = notice,
            onGuide = { step = Onboarding.GUIDE },
            saved = saved.mapValues { (_, p) -> p.gatewayUrl.removePrefix("https://").removePrefix("http://") },
            onUseSaved = { kind -> app.session.switchTo(kind) },
            onScan = {
                address = ""
                secret = ""
                error = null
                scanner.launch(Unit)
            },
            onFind = ::findNearby,
        )
        Onboarding.NEARBY -> FindComputerScreen(
            nearby,
            onPick = ::linkNearby,
            onRetry = ::findNearby,
            onScanInstead = {
                nearbyJob?.cancel()
                error = null
                scanner.launch(Unit)
            },
            onBack = {
                nearbyJob?.cancel()
                step = Onboarding.WELCOME
            },
        )
        Onboarding.GUIDE -> GuideScreen(onBack = { step = Onboarding.WELCOME }, onStart = { step = Onboarding.CHOOSE })
        Onboarding.CHOOSE -> ChooseConnectionScreen(
            onChoose = { kind -> address = ""; secret = ""; error = null; step = if (kind == ConnectionKind.WIFI) Onboarding.WIFI else Onboarding.CREATE },
            onBack = { step = Onboarding.WELCOME },
        )
        Onboarding.WIFI -> WifiGatewayScreen(
            WifiGatewayState(address, secret, busy, error, installCopied, startCopied),
            WifiGatewayActions(
                copyInstall = { copyToClipboard(context, "Install command", GATEWAY_INSTALL_COMMAND, sensitive = false); installCopied = true },
                copyStart = { copyToClipboard(context, "Start command", "latch-start", sensitive = false); startCopied = true },
                setAddress = { address = it; error = null },
                setCode = { secret = it; error = null },
                scan = {
                    error = null
                    scanner.launch(Unit)
                },
                connect = { connectWifi() },
                back = { step = Onboarding.CHOOSE; error = null },
            ),
        )
        Onboarding.CREATE -> CreateGatewayScreen(
            CreateGatewayState(ownerKey, keyCopied, deployOpened, address, busy, error),
            CreateGatewayActions(
                copyKey = { copyToClipboard(context, "Owner key", ownerKey, sensitive = true); keyCopied = true },
                openDeploy = {
                    deployOpened = true
                    context.startActivity(Intent(Intent.ACTION_VIEW, GatewaySetup.vercelDeployUrl().toUri()))
                },
                setAddress = { address = it; error = null },
                connect = { connect(asOwner = true, key = ownerKey) },
                back = { step = Onboarding.CHOOSE; error = null },
            ),
        )
        Onboarding.JOIN -> JoinGatewayScreen(
            JoinState(address, mode, secret, busy, error),
            JoinActions(
                setAddress = { address = it; error = null },
                setMode = { mode = it; secret = ""; error = null },
                setSecret = { secret = it; error = null },
                connect = { connect(asOwner = mode == JoinMode.OWNER_KEY, key = secret) },
                back = { step = Onboarding.WELCOME; error = null },
            ),
        )
    }
}

@Composable
private fun MainTabs(app: LatchApp, reducedMotion: Boolean) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Settings, or AI apps over it; back closes the top one.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var connectOpen by rememberSaveable { mutableStateOf(false) }
    val page = if (connectOpen) Page.CONNECT else if (settingsOpen) Page.SETTINGS else null
    fun go(to: Tab) {
        tab = to
        settingsOpen = false
        connectOpen = false
    }
    // When a session started and ended, for the summary card on Home. Watched here, not on Home, so a
    // session that ends while another tab is open still gets its card.
    val session by app.session.state.collectAsStateWithLifecycle()
    var sessionStartMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var sessionEndMs by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(session) {
        when (session) {
            is SessionState.Active -> if (sessionStartMs == null || sessionEndMs != null) {
                sessionStartMs = System.currentTimeMillis()
                sessionEndMs = null
            }
            SessionState.Idle, is SessionState.Failed, is SessionState.Revoked -> if (sessionStartMs != null && sessionEndMs == null) {
                sessionEndMs = System.currentTimeMillis()
            }
            else -> Unit
        }
    }
    val summary = remember(sessionStartMs, sessionEndMs) {
        val start = sessionStartMs
        val end = sessionEndMs
        if (start == null || end == null) null else summarizeSession(app.log.snapshot(), start, end)
    }
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    val signal = LocalSignal.current
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    // AI apps waiting for approval, checked every few seconds while Latch is on screen.
    var requestsTick by remember { mutableIntStateOf(0) }
    val ownerKeyVersion by app.settings.ownerKeyVersion.collectAsStateWithLifecycle()
    // R49: every 3 s on Connect, where AI apps are added, and every 12 s elsewhere; a
    // waiting request keeps it quick. Switching tabs asks at once.
    val onConnect = connectOpen
    val requestsFlow = remember(pairing, isOwner, ownerKeyVersion, requestsTick, onConnect) {
        val owner = pairing?.let { p -> app.settings.ownerKey()?.let { OwnerClient(app.http, p.gatewayUrl, it) } }
        if (owner == null) {
            flowOf(emptyList<SignInRequest>())
        } else {
            flow {
                while (true) {
                    val requests = runCatching { owner.signInRequests() }.getOrDefault(emptyList())
                    emit(requests)
                    delay(if (onConnect || requests.isNotEmpty()) 3_000 else 12_000)
                }
            }.flowOn(kotlinx.coroutines.Dispatchers.IO)
        }
    }
    val signInRequests by requestsFlow.collectAsStateWithLifecycle(emptyList())

    // Right after pairing, and whenever the owner asks, walk through every permission in a row.
    if (!prefs.setupDone || setupOpen) {
        BackHandler(enabled = prefs.setupDone) { setupOpen = false }
        SetupRoute(app, reducedMotion) {
            app.settings.update { it.copy(setupDone = true) }
            setupOpen = false
        }
        return
    }

    if (guideOpen) {
        BackHandler { guideOpen = false }
        GuideScreen(onBack = { guideOpen = false })
        return
    }

    BackHandler(enabled = page != null) { if (connectOpen) connectOpen = false else settingsOpen = false }
    val pending by app.approvals.pending.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = signal.canvas,
        bottomBar = {
            Column {
                // While a session runs, Stop is one tap away on every screen, not only on Home.
                if (tab != Tab.HOME || page != null) {
                    SessionBar(session, pending != null, reducedMotion, stop = app.session::stop, open = { go(Tab.HOME) })
                }
                LatchTabBar(if (page == null) tab else null, ::go, signInRequests.size)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AnimatedContent(
                targetState = page to tab,
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 30 }) togetherWith fadeOut(tween(120))
                    }
                },
                label = "tabs",
            ) { (currentPage, current) ->
                when (currentPage) {
                    Page.CONNECT -> Column {
                        BackBar({ connectOpen = false }, if (settingsOpen) "Settings" else "Back")
                        Box(Modifier.weight(1f)) { ConnectRoute(app, signInRequests) { requestsTick++ } }
                    }
                    Page.SETTINGS -> Column {
                        BackBar({ settingsOpen = false }, "Home")
                        Box(Modifier.weight(1f)) {
                            SettingsRoute(app, reducedMotion, signInRequests.size, openConnect = { connectOpen = true }, openGuide = { guideOpen = true }) { setupOpen = true }
                        }
                    }
                    null -> when (current) {
                        Tab.HOME -> HomeRoute(
                            app, reducedMotion, signInRequests, summary,
                            dismissSummary = { sessionStartMs = null; sessionEndMs = null },
                            go = ::go,
                            openSetup = { setupOpen = true },
                            openSettings = { settingsOpen = true },
                            openConnect = { connectOpen = true },
                        )
                        Tab.CAPABILITIES -> CapabilitiesRoute(app) { setupOpen = true }
                        Tab.ACTIVITY -> {
                            val entries by app.log.entries.collectAsStateWithLifecycle(app.log.snapshot())
                            val context = LocalContext.current
                            val scope = rememberCoroutineScope()
                            ActivityScreen(entries, app.log::clear) { period ->
                                scope.launch {
                                    val saved = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val d = deviceDescriptor()
                                            val report = ActivityReport.build(
                                                app.log.snapshot(), period, System.currentTimeMillis(),
                                                "${d.model} · Android ${d.osVersion} · Latch ${d.appVersion}",
                                            )
                                            ReportSaver.save(context, report)
                                        }
                                    }
                                    saved.onSuccess {
                                        Toast.makeText(context, "Saved to Downloads/${ReportSaver.FOLDER}/${it.name}", Toast.LENGTH_LONG).show()
                                        context.startActivity(ReportSaver.shareIntent(it))
                                    }.onFailure {
                                        Toast.makeText(context, "Could not save the report: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Live permission state for the setup screen; re-read whenever the owner comes back from Settings. */
@Composable
private fun SetupRoute(app: LatchApp, reducedMotion: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val needsNotificationPermission = Build.VERSION.SDK_INT >= 33
    val notificationsOn = remember(resumes) {
        !needsNotificationPermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOn = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var notificationsSkipped by rememberSaveable { mutableStateOf(false) }
    var batterySkipped by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resumes++
        // A second refusal makes Android stop showing the dialog; send the owner to Settings instead.
        if (!granted && asked) notificationsBlocked = true
        asked = true
    }
    var preset by rememberSaveable { mutableStateOf(AccessPreset.matching(prefs.enabled)) }

    SetupScreen(
        SetupState(
            notificationsNeeded = needsNotificationPermission,
            notificationsOn = notificationsOn,
            notificationsBlocked = notificationsBlocked,
            notificationsSkipped = notificationsSkipped,
            accessibilityOn = service != null,
            batteryOn = batteryOn,
            batterySkipped = batterySkipped,
            preset = preset,
            approveEveryAction = prefs.approveEveryAction,
            reducedMotion = reducedMotion,
        ),
        SetupActions(
            allowNotifications = {
                if (needsNotificationPermission) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            openNotificationSettings = {
                context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
            },
            skipNotifications = { notificationsSkipped = true },
            openAccessibility = { openAccessibilityFor(context) },
            openAppInfo = { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
            allowBattery = {
                val request = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                runCatching { context.startActivity(request) }
                    .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
            skipBattery = { batterySkipped = true },
            choosePreset = { chosen ->
                preset = chosen
                app.settings.update { it.copy(enabled = chosen.capabilities) }
            },
            setApproveEveryAction = { v -> app.settings.update { it.copy(approveEveryAction = v) } },
            finish = onFinish,
            later = onFinish,
        ),
    )
}

@Composable
private fun HomeRoute(
    app: LatchApp,
    reducedMotion: Boolean,
    signInRequests: List<SignInRequest>,
    lastSession: SessionSummary?,
    dismissSummary: () -> Unit,
    go: (Tab) -> Unit,
    openSetup: () -> Unit,
    openSettings: () -> Unit,
    openConnect: () -> Unit,
) {
    val context = LocalContext.current
    val state by app.session.state.collectAsStateWithLifecycle()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    val pending by app.approvals.pending.collectAsStateWithLifecycle()
    // R49: Home shows the newest line and a count; it does not hold the whole log.
    val lastActivity by app.log.latest.collectAsStateWithLifecycle()
    val activityCount by app.log.count.collectAsStateWithLifecycle()
    val autonomy by app.autonomy.state.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    val outdatedGateway by app.session.outdatedGateway.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
    val ownerKeyVersion by app.settings.ownerKeyVersion.collectAsStateWithLifecycle()
    // R3: once per screen visit, the owner's relay says whether its database is far away.
    val slowRelayMs by produceState<Int?>(null, pairing, isOwner, ownerKeyVersion) {
        val p = pairing ?: return@produceState
        val key = app.settings.ownerKey() ?: return@produceState
        value = runCatching { OwnerClient(app.http, p.gatewayUrl, key).slowDatabaseMs() }.getOrNull()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val p = pairing ?: return

    val (phase, headline, detail) = when (val s = state) {
        SessionState.Unpaired, SessionState.Idle -> Triple(Phase.IDLE, "Ready", "Nothing is shared until you start. Start when you want your AI to help on this phone.")
        is SessionState.Connecting -> Triple(Phase.CONNECTING, "Connecting\u2026", "Nothing happens until your relay answers.")
        is SessionState.Reconnecting -> Triple(Phase.RECONNECTING, "Reconnecting", "The internet dropped. Nothing can happen until it's back.")
        is SessionState.Active -> when {
            pending != null -> Triple(Phase.ACTIVE, "Your answer is needed", "The AI waits until you answer below.")
            s.paused -> Triple(Phase.PAUSED, "Paused", "The AI can't see or do anything until you resume.")
            now >= s.expiresAtMs -> Triple(Phase.ACTIVE, "Only full ADB access is on", "The session time ended, so the AI can only run ADB commands. Stop ends it.")
            else -> Triple(Phase.ACTIVE, "Your AI can work now", "Only in the apps you allowed. Stop ends everything at once.")
        }
        is SessionState.Revoked -> Triple(Phase.REVOKED, "Revoked", s.reason)
        is SessionState.Failed -> Triple(Phase.FAILED, "Stopped", s.message)
    }
    val minutesLeft = when (val s = state) {
        is SessionState.Active -> if (now >= s.expiresAtMs) null else (s.expiresAtMs - now) / 60_000 + 1
        is SessionState.Reconnecting -> if (now >= s.expiresAtMs) null else (s.expiresAtMs - now) / 60_000 + 1
        else -> null
    }
    val statuses = remember(prefs, service, now) { app.session.capabilityStatuses() }
    val mirror by app.mirror.status.collectAsStateWithLifecycle()
    val mirrorRemembered by app.settings.mirrorRemembered.collectAsStateWithLifecycle()
    val mirrorConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) app.mirror.allowed(result.resultCode, data) else app.mirror.declined()
    }
    // ADR-055: the phone's sound in the mirror needs audio recording; the picture works without it.
    val mirrorAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        mirrorConsent.launch(app.mirror.consentIntent())
    }

    HomeScreen(
        HomeState(
            phase = phase,
            headline = headline,
            detail = detail,
            minutesLeft = minutesLeft,
            sessionMinutes = prefs.sessionMinutes,
            enabled = statuses.filterValues { it == CapabilityStatus.ENABLED }.keys.filter { it != Capability.DEVICE_INFO },
            accessibilityOn = service != null,
            gatewayHost = p.gatewayUrl.toUri().host ?: p.gatewayUrl,
            encrypted = p.gatewayUrl.startsWith("https://"),
            phoneName = p.name,
            pending = pending,
            lastActivity = lastActivity,
            activityCount = activityCount,
            appsOn = autonomy.allowed.size,
            autoAll = autonomy.auto != AutoMode.OFF,
            isOwner = isOwner,
            reducedMotion = reducedMotion,
            signInRequests = signInRequests,
            outdatedGateway = outdatedGateway,
            slowRelayMs = slowRelayMs,
            update = update,
            mirror = mirror,
            mirrorRemembered = mirrorRemembered,
            lastSession = lastSession,
        ),
        HomeActions(
            start = {
                if (service == null) {
                    // Without screen access a session could do nothing; finish setup instead of starting silently.
                    openSetup()
                } else {
                    app.session.clearFailure()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    app.session.start()
                }
            },
            stop = { app.session.stop() },
            dismissSummary = dismissSummary,
            setPaused = app.session::setPaused,
            extend = app.session::extend,
            setMinutes = { m -> app.settings.update { it.copy(sessionMinutes = m) } },
            answer = app.approvals::answer,
            openAccessibilitySettings = { openAccessibilityFor(context) },
            openAppInfo = { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
            goCapabilities = { go(Tab.CAPABILITIES) },
            goConnect = openConnect,
            goActivity = { go(Tab.ACTIVITY) },
            openSetup = openSetup,
            openSettings = openSettings,
            reviewSignIns = openConnect,
            openGatewayUpdateHelp = { context.startActivity(Intent(Intent.ACTION_VIEW, GATEWAY_UPDATE_HELP.toUri())) },
            openRelaySpeedHelp = { context.startActivity(Intent(Intent.ACTION_VIEW, RELAY_SPEED_HELP.toUri())) },
            installUpdate = { info -> installUpdate(app, context, info) },
            startMirror = {
                val reason = app.mirror.unavailableReason()
                if (reason != null) {
                    android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show()
                    if (service == null) openSetup()
                } else {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        mirrorConsent.launch(app.mirror.consentIntent())
                    } else {
                        mirrorAudio.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            stopMirror = { app.mirror.stop() },
            copyMirrorLink = { mirror.link?.let { copySensitive(context, "Latch mirror link", it) } },
            forgetMirrorComputers = {
                app.settings.forgetMirrorComputers()
                android.widget.Toast.makeText(context, "Done. Computers need a new code next time.", android.widget.Toast.LENGTH_LONG).show()
            },
        ),
    )
}

/** Copies a secret, marked sensitive so Android does not show it in the clipboard preview (Android 13+). */
private fun copySensitive(context: Context, label: String, value: String) {
    val clip = android.content.ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/**
 * Starts the in-app update. A running session ends first, because installing
 * restarts Latch. Without the install permission, opens Android's switch for it.
 */
private fun installUpdate(app: LatchApp, context: Context, info: UpdateInfo) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
    }
    if (app.session.state.value.let { it is SessionState.Active || it is SessionState.Reconnecting || it is SessionState.Connecting }) {
        app.session.stop()
    }
    app.updater.install(info)
}

@Composable
private fun CapabilitiesRoute(app: LatchApp, openSetup: () -> Unit) {
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val always by app.grants.always.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    val autonomy by app.autonomy.state.collectAsStateWithLifecycle()
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf<AccessGroup?>(null) }
    val context = LocalContext.current
    val folderFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    // Folders the AI may use (ADR-026): Android's own picker, kept across restarts.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (runCatching { context.contentResolver.takePersistableUriPermission(uri, folderFlags) }.isFailure) {
            Toast.makeText(context, "Android did not let Latch keep that folder. Pick another one.", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val name = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "Phone storage" } }
            .getOrDefault("Picked folder")
        app.settings.update { p ->
            if (p.folders.any { it.uri == uri.toString() }) p else p.copy(folders = (p.folders + FolderGrant(uri.toString(), name)).take(MAX_FOLDERS))
        }
    }
    var photosAllowed by remember { mutableStateOf(app.files.photosAllowed()) }
    // R29 (c): the pairing box lives in a notification.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) io.github.aspershupadhyay.latch.AdbPairingNotice.show(context)
    }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photosAllowed = app.files.photosAllowed()
        // Already answered "don't ask again": the owner changes it in Android's settings.
        if (!photosAllowed && it.values.none { granted -> granted }) {
            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }
    if (appsOpen) {
        BackHandler { appsOpen = false }
        AppsRoute(app) { appsOpen = false }
        return
    }
    CapabilitiesScreen(
        folders = prefs.folders.map { it.name },
        photosAllowed = photosAllowed,
        onPickFolder = { runCatching { pickFolder.launch(null) } },
        onForgetFolder = { index ->
            prefs.folders.getOrNull(index)?.let { gone ->
                runCatching { context.contentResolver.releasePersistableUriPermission(gone.uri.toUri(), folderFlags) }
                app.settings.update { p -> p.copy(folders = p.folders.filterNot { it.uri == gone.uri }) }
            }
        },
        photosOn = prefs.photosOn,
        onPhotosOn = { v -> app.settings.update { it.copy(photosOn = v) } },
        localTransfers = prefs.localTransfers,
        onLocalTransfers = { v -> app.settings.update { it.copy(localTransfers = v) } },
        adbEndpoint = prefs.adbEndpoint,
        adbWirelessAvailable = app.session.adbWirelessAvailable(),
        onPairAdb = { code, host, port -> app.session.pairAdb(code, host, port) },
        onUnpairAdb = app.session::unpairAdb,
        adbFixedPort = prefs.adbFixedPort,
        onAdbFixedPort = { on -> app.session.setAdbFixedPort(on) },
        onPairFromNotification = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                false
            } else if (io.github.aspershupadhyay.latch.AdbPairingNotice.show(context)) {
                // Developer options; Wireless debugging is one tap inside (it has no public intent of its own).
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                true
            } else {
                false
            }
        },
        onAllowPhotos = {
            askPhotos.launch(
                when {
                    Build.VERSION.SDK_INT >= 34 -> arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    )
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
            )
        },
        appsOn = autonomy.allowed.size,
        onOpenApps = { appsOpen = true },
        remoteApprovals = prefs.remoteApprovals,
        onRemoteApprovals = { v -> app.settings.update { it.copy(remoteApprovals = v) } },
        auto = autonomy.auto,
        onAuto = app.autonomy::setAuto,
        enabled = prefs.enabled,
        accessibilityOn = service != null,
        approveEveryAction = prefs.approveEveryAction,
        onToggle = { c, on -> app.settings.update { p -> p.copy(enabled = if (on) p.enabled + c else p.enabled - c) } },
        onApproveEveryAction = { v -> app.settings.update { it.copy(approveEveryAction = v) } },
        onOpenSetup = openSetup,
        showCursor = prefs.showCursor,
        keepAwake = prefs.keepAwake,
        onShowCursor = { v -> app.settings.update { it.copy(showCursor = v) } },
        onKeepAwake = { v -> app.settings.update { it.copy(keepAwake = v) } },
        saved = always.map(::SavedApproval).map { SavedApprovalRow(it.key, it.action, app.appLabel(it.packageName) ?: it.packageName) },
        onRemoveSaved = app.grants::remove,
        onRemoveAllSaved = app.grants::clearAll,
        openGroup = group,
        onOpenGroup = { group = it },
    )
}

@Composable
private fun ConnectRoute(app: LatchApp, requests: List<SignInRequest>, onRequestsChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    var clients by remember { mutableStateOf<List<McpClient>>(emptyList()) }
    var phones by remember { mutableStateOf<List<RelayPhone>>(emptyList()) }
    var created by remember { mutableStateOf<NewMcpClient?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val p = pairing ?: return
    val ownerKeyVersion by app.settings.ownerKeyVersion.collectAsStateWithLifecycle()
    val owner = remember(p, isOwner, ownerKeyVersion) { app.settings.ownerKey()?.let { OwnerClient(app.http, p.gatewayUrl, it) } }

    fun run(block: suspend (OwnerClient) -> Unit) {
        val o = owner ?: return
        loading = true
        error = null
        scope.launch {
            try {
                block(o)
            } catch (e: SetupException) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
    suspend fun reload(o: OwnerClient) {
        clients = o.clients()
        phones = o.phones().filter { it.id != p.deviceId }
    }
    LaunchedEffect(owner, requests.size) { run { reload(it) } }
    // R18: the phone's apps, offered when the owner limits an AI app.
    val phoneApps by produceState(app.cachedApps().orEmpty().map { PhoneApp(it.packageName, it.label) }, isOwner) {
        if (isOwner) value = withContext(Dispatchers.IO) { runCatching { app.launchableApps() }.getOrDefault(emptyList()) }.map { PhoneApp(it.packageName, it.label) }
    }

    val saved by app.settings.saved.collectAsStateWithLifecycle()
    val connectPrefs by app.settings.preferences.collectAsStateWithLifecycle()
    fun hostOf(url: String) = url.removePrefix("https://").removePrefix("http://")
    ConnectScreen(
        ConnectState(
            "${p.gatewayUrl}/mcp", isOwner, clients, loading, error, created, requests, phoneApps, phones,
            connection = p.kind, host = hostOf(p.gatewayUrl), saved = saved.mapValues { (_, s) -> hostOf(s.gatewayUrl) },
            bothConnected = connectPrefs.bothConnected,
        ),
        ConnectActions(
            switchTo = { kind -> app.session.switchTo(kind) },
            setUp = { kind -> app.pendingSetup.value = kind; app.session.keepAndSetUpAnother() },
            forgetSaved = { kind -> app.session.forgetSaved(kind) },
            setBothConnected = { on -> app.session.setBothConnected(on) },
            create = { name -> run { created = it.createClient(name); clients = it.clients() } },
            revoke = { id -> run { it.revokeClient(id); clients = it.clients() } },
            dismissCreated = { created = null },
            copy = { label, value, sensitive -> copyToClipboard(context, label, value, sensitive) },
            refresh = { run { reload(it) } },
            limit = { id, apps -> run { it.limitClient(id, apps); clients = it.clients() } },
            removePhone = { id -> run { it.removePhone(id); reload(it) } },
            answer = { id, approve ->
                run {
                    it.answerSignIn(id, approve)
                    onRequestsChanged()
                    clients = it.clients()
                }
            },
        ),
    )
}

@Composable
private fun SettingsRoute(app: LatchApp, reducedMotion: Boolean, aiWaiting: Int, openConnect: () -> Unit, openGuide: () -> Unit, openSetup: () -> Unit) {
    val context = LocalContext.current
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyRotation by remember { mutableStateOf<KeyRotation>(KeyRotation.Idle) }
    val p = pairing ?: return
    fun rotateKey() {
        val current = app.settings.ownerKey() ?: return
        keyRotation = KeyRotation.Busy
        scope.launch {
            keyRotation = try {
                val key = OwnerClient(app.http, p.gatewayUrl, current).rotateOwnerKey()
                // The old key no longer works: if saving fails, the owner must copy the key from the dialog.
                val saved = runCatching { app.settings.saveOwnerKey(key) }.isSuccess
                KeyRotation.Done(key, saved)
            } catch (e: SetupException) {
                KeyRotation.Failed(e.message ?: "The relay did not answer.")
            }
        }
    }
    SettingsScreen(
        gatewayUrl = p.gatewayUrl,
        deviceId = p.deviceId,
        phoneName = p.name,
        isOwner = isOwner,
        version = BuildConfig.VERSION_NAME,
        onForget = { app.session.forget() },
        onDisconnectKeep = { app.session.keepAndSetUpAnother() },
        onOpenAccessibility = { openAccessibilityFor(context) },
        onOpenSetup = openSetup,
        onOpenConnect = openConnect,
        aiWaiting = aiWaiting,
        connection = p.kind,
        update = update,
        checkUpdates = prefs.checkUpdates,
        onCheckUpdates = { v -> app.settings.update { it.copy(checkUpdates = v) } },
        readButtons = prefs.readButtons,
        onReadButtons = { v -> app.settings.update { it.copy(readButtons = v) } },
        onCheckNow = { app.updater.check(manual = true) },
        onInstallUpdate = { info -> installUpdate(app, context, info) },
        reducedMotion = reducedMotion,
        onOpenGuide = openGuide,
        onOpenSource = { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE.toUri())) },
        keyRotation = keyRotation,
        onRotateKey = ::rotateKey,
        onCopyNewKey = { copyToClipboard(context, "Owner key", it, sensitive = true) },
        onDismissRotation = { keyRotation = KeyRotation.Idle },
    )
}

/** Every launchable app with its switch; icons load off the main thread. */
@Composable
private fun AppsRoute(app: LatchApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val autonomy by app.autonomy.state.collectAsStateWithLifecycle()
    // The last list shows at once; a fresh read replaces it when it is ready.
    val installed by produceState(app.cachedApps()) {
        value = withContext(Dispatchers.IO) { runCatching { app.launchableApps() }.getOrDefault(value.orEmpty()) }
    }
    val rows = remember(installed, autonomy) {
        installed.orEmpty().map { a ->
            AppRow(a.packageName, a.label, app.consequences.isSensitiveApp(a.packageName, a.label), a.packageName in autonomy.allowed, a.category)
        }
    }
    val icons = remember { mutableStateMapOf<String, ImageBitmap?>() }
    AppsScreen(
        apps = rows,
        loading = installed == null,
        onToggle = { pkg, on -> app.autonomy.setAllowed(pkg, on) },
        onAllOff = { app.autonomy.clearAll() },
        onBack = onBack,
        trustCritical = autonomy.trustCritical,
        onTrustCritical = app.autonomy::setTrustCritical,
        icon = { pkg ->
            LaunchedEffect(pkg) {
                if (pkg !in icons) {
                    icons[pkg] = withContext(Dispatchers.IO) {
                        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                    }
                }
            }
            icons[pkg]
        },
    )
}

/** Folders the owner may share with the AI at once. */
private const val MAX_FOLDERS = 20

/** README steps for bringing a Vercel gateway up to date. */
private const val GATEWAY_UPDATE_HELP = "https://github.com/aspershupadhyay/latch/blob/main/docs/troubleshooting.md#fix-update-your-gateway"
private const val RELAY_SPEED_HELP = "https://github.com/aspershupadhyay/latch/blob/main/docs/operations/vercel.md#speed"
private const val SOURCE_CODE = "https://github.com/aspershupadhyay/latch"
