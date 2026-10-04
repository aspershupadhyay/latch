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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import io.github.aspershupadhyay.latch.data.ThemeChoice
import io.github.aspershupadhyay.latch.data.FolderGrant
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.aspershupadhyay.latch.session.GatewaySetup
import io.github.aspershupadhyay.latch.session.McpClient
import io.github.aspershupadhyay.latch.session.NewMcpClient
import io.github.aspershupadhyay.latch.session.OwnerClient
import io.github.aspershupadhyay.latch.session.Paired
import io.github.aspershupadhyay.latch.session.Pairing
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.session.SetupException
import io.github.aspershupadhyay.latch.session.SignInRequest
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = LatchApp.get(this)
        val reducedMotion = AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val showIntro = savedInstanceState == null
        setContent {
            val prefs by app.settings.preferences.collectAsStateWithLifecycle()
            val dark = when (prefs.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            // Status and navigation bar icons follow the chosen look, not only the phone's.
            DisposableEffect(dark) {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            // The opening animation plays once per launch, over the app that is already loading.
            var intro by rememberSaveable { mutableStateOf(showIntro) }
            LatchTheme(dark = dark) {
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

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", LatchIcons.Home),
    CAPABILITIES("Access", LatchIcons.ShieldCheck),
    CONNECT("Connect", LatchIcons.Plug),
    ACTIVITY("Activity", LatchIcons.Pulse),
    SETTINGS("Settings", LatchIcons.Sliders),
}

private enum class Onboarding { WELCOME, CREATE, JOIN, GUIDE }

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

private fun Paired.save(app: LatchApp) = app.settings.savePairing(SavedPairing(gatewayUrl, deviceId, name), deviceToken)

@Composable
fun LatchRoot(app: LatchApp, reducedMotion: Boolean) {
    val state by app.session.state.collectAsStateWithLifecycle()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    if (pairing == null) {
        OnboardingFlow(app, (state as? SessionState.Revoked)?.reason)
        return
    }
    MainTabs(app, reducedMotion)
}

@Composable
private fun OnboardingFlow(app: LatchApp, notice: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(Onboarding.WELCOME) }
    var ownerKey by rememberSaveable { mutableStateOf(GatewaySetup.newOwnerKey()) }
    var keyCopied by rememberSaveable { mutableStateOf(false) }
    var deployOpened by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(JoinMode.OWNER_KEY) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = step != Onboarding.WELCOME) { step = Onboarding.WELCOME; error = null }

    fun connect(asOwner: Boolean, key: String) {
        val url = Pairing.normalize(address, BuildConfig.ALLOW_CLEARTEXT)
        if (url == null) {
            error = if (address.trim().startsWith("http://")) "Use an https:// address." else "That does not look like a web address."
            return
        }
        busy = true
        error = null
        app.session.clearFailure()
        scope.launch {
            try {
                val model = deviceDescriptor().model
                val paired = if (asOwner) app.setup.pairAsOwner(url, key.trim(), model, model) else app.setup.pairWithCode(url, key, model)
                if (asOwner) app.settings.saveOwnerKey(key.trim())
                paired.save(app)
            } catch (e: SetupException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    when (step) {
        Onboarding.WELCOME -> WelcomeScreen(
            onCreateGateway = { step = Onboarding.CREATE }, onHaveGateway = { step = Onboarding.JOIN }, notice = notice,
            onGuide = { step = Onboarding.GUIDE },
        )
        Onboarding.GUIDE -> GuideScreen(onBack = { step = Onboarding.WELCOME }, onStart = { step = Onboarding.CREATE })
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
                back = { step = Onboarding.WELCOME; error = null },
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
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    val signal = LocalSignal.current
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    // AI apps waiting for approval, checked every few seconds while Latch is on screen.
    var requestsTick by remember { mutableIntStateOf(0) }
    val requestsFlow = remember(pairing, isOwner, requestsTick) {
        val owner = pairing?.let { p -> app.settings.ownerKey()?.let { OwnerClient(app.http, p.gatewayUrl, it) } }
        if (owner == null) {
            flowOf(emptyList<SignInRequest>())
        } else {
            flow {
                while (true) {
                    emit(runCatching { owner.signInRequests() }.getOrDefault(emptyList()))
                    delay(3_000)
                }
            }
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

    Scaffold(
        containerColor = signal.canvas,
        bottomBar = { LatchTabBar(tab, { tab = it }, signInRequests.size) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 30 }) togetherWith fadeOut(tween(120))
                    }
                },
                label = "tabs",
            ) { current ->
                when (current) {
                    Tab.HOME -> HomeRoute(app, reducedMotion, signInRequests, go = { tab = it }, openSetup = { setupOpen = true })
                    Tab.CAPABILITIES -> CapabilitiesRoute(app) { setupOpen = true }
                    Tab.CONNECT -> ConnectRoute(app, signInRequests) { requestsTick++ }
                    Tab.ACTIVITY -> {
                        val entries by app.log.entries.collectAsStateWithLifecycle()
                        ActivityScreen(entries, app.log::clear)
                    }
                    Tab.SETTINGS -> SettingsRoute(app, reducedMotion, openGuide = { guideOpen = true }) { setupOpen = true }
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
private fun HomeRoute(app: LatchApp, reducedMotion: Boolean, signInRequests: List<SignInRequest>, go: (Tab) -> Unit, openSetup: () -> Unit) {
    val context = LocalContext.current
    val state by app.session.state.collectAsStateWithLifecycle()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    val pending by app.approvals.pending.collectAsStateWithLifecycle()
    val entries by app.log.entries.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    val outdatedGateway by app.session.outdatedGateway.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
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
            else -> Triple(Phase.ACTIVE, "Your AI can work now", "Only in the apps you allowed. Stop ends everything at once.")
        }
        is SessionState.Revoked -> Triple(Phase.REVOKED, "Revoked", s.reason)
        is SessionState.Failed -> Triple(Phase.FAILED, "Stopped", s.message)
    }
    val minutesLeft = when (val s = state) {
        is SessionState.Active -> ((s.expiresAtMs - now) / 60_000).coerceAtLeast(0) + 1
        is SessionState.Reconnecting -> ((s.expiresAtMs - now) / 60_000).coerceAtLeast(0) + 1
        else -> null
    }
    val statuses = remember(prefs, service, now) { app.session.capabilityStatuses() }

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
            lastActivity = entries.firstOrNull(),
            activityCount = entries.size,
            isOwner = isOwner,
            reducedMotion = reducedMotion,
            signInRequests = signInRequests,
            outdatedGateway = outdatedGateway,
            update = update,
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
            setPaused = app.session::setPaused,
            setMinutes = { m -> app.settings.update { it.copy(sessionMinutes = m) } },
            answer = app.approvals::answer,
            openAccessibilitySettings = { openAccessibilityFor(context) },
            openAppInfo = { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
            goCapabilities = { go(Tab.CAPABILITIES) },
            goConnect = { go(Tab.CONNECT) },
            goActivity = { go(Tab.ACTIVITY) },
            openSetup = openSetup,
            reviewSignIns = { go(Tab.CONNECT) },
            openGatewayUpdateHelp = { context.startActivity(Intent(Intent.ACTION_VIEW, GATEWAY_UPDATE_HELP.toUri())) },
            installUpdate = { info -> installUpdate(app, context, info) },
        ),
    )
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
    var created by remember { mutableStateOf<NewMcpClient?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val p = pairing ?: return
    val owner = remember(p, isOwner) { app.settings.ownerKey()?.let { OwnerClient(app.http, p.gatewayUrl, it) } }

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
    LaunchedEffect(owner, requests.size) { run { clients = it.clients() } }

    ConnectScreen(
        ConnectState("${p.gatewayUrl}/mcp", isOwner, clients, loading, error, created, requests),
        ConnectActions(
            create = { name -> run { created = it.createClient(name); clients = it.clients() } },
            revoke = { id -> run { it.revokeClient(id); clients = it.clients() } },
            dismissCreated = { created = null },
            copy = { label, value, sensitive -> copyToClipboard(context, label, value, sensitive) },
            refresh = { run { clients = it.clients() } },
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
private fun SettingsRoute(app: LatchApp, reducedMotion: Boolean, openGuide: () -> Unit, openSetup: () -> Unit) {
    val context = LocalContext.current
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
    val p = pairing ?: return
    SettingsScreen(
        gatewayUrl = p.gatewayUrl,
        deviceId = p.deviceId,
        phoneName = p.name,
        isOwner = isOwner,
        version = BuildConfig.VERSION_NAME,
        onForget = { app.session.forget() },
        onOpenAccessibility = { openAccessibilityFor(context) },
        onOpenSetup = openSetup,
        update = update,
        checkUpdates = prefs.checkUpdates,
        onCheckUpdates = { v -> app.settings.update { it.copy(checkUpdates = v) } },
        onCheckNow = { app.updater.check(manual = true) },
        onInstallUpdate = { info -> installUpdate(app, context, info) },
        reducedMotion = reducedMotion,
        onOpenGuide = openGuide,
        onOpenSource = { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE.toUri())) },
        theme = prefs.theme,
        onTheme = { t -> app.settings.update { it.copy(theme = t) } },
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
private const val GATEWAY_UPDATE_HELP = "https://github.com/aspershupadhyay/latch#fix-update-your-gateway"
private const val SOURCE_CODE = "https://github.com/aspershupadhyay/latch"
