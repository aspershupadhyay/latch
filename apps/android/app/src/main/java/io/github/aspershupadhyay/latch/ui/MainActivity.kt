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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = LatchApp.get(this)
        val reducedMotion = AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            LatchTheme {
                LatchRoot(app, reducedMotion)
            }
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

private enum class Onboarding { WELCOME, CREATE, JOIN }

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
        Onboarding.WELCOME -> WelcomeScreen(onCreateGateway = { step = Onboarding.CREATE }, onHaveGateway = { step = Onboarding.JOIN }, notice = notice)
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

    Scaffold(
        containerColor = signal.canvas,
        bottomBar = {
            NavigationBar(containerColor = signal.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.CONNECT && signInRequests.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${signInRequests.size}") } }) {
                                    Icon(t.icon, contentDescription = "${signInRequests.size} waiting", modifier = Modifier.size(24.dp))
                                }
                            } else {
                                Icon(t.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = signal.accent,
                            selectedTextColor = signal.accent,
                            indicatorColor = signal.accent.copy(alpha = 0.14f),
                            unselectedIconColor = signal.text2,
                            unselectedTextColor = signal.text2,
                        ),
                    )
                }
            }
        },
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
                    Tab.SETTINGS -> SettingsRoute(app) { setupOpen = true }
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
        SessionState.Unpaired, SessionState.Idle -> Triple(Phase.IDLE, "Ready. Nothing is shared.", "Start a session when you want an AI to work with this phone.")
        is SessionState.Connecting -> Triple(Phase.CONNECTING, "Connecting…", "No commands run until your gateway confirms.")
        is SessionState.Reconnecting -> Triple(Phase.RECONNECTING, "Reconnecting", "Nothing can run while offline. Stop ends the session.")
        is SessionState.Active -> when {
            pending != null -> Triple(Phase.ACTIVE, "Waiting for you", "Nothing happens until you answer.")
            s.paused -> Triple(Phase.PAUSED, "Paused", "The AI can neither see nor act until you resume.")
            else -> Triple(Phase.ACTIVE, "Session active", "An AI can use what you allowed. Stop ends everything at once.")
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
        ),
    )
}

@Composable
private fun CapabilitiesRoute(app: LatchApp, openSetup: () -> Unit) {
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val always by app.grants.always.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    CapabilitiesScreen(
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
private fun SettingsRoute(app: LatchApp, openSetup: () -> Unit) {
    val context = LocalContext.current
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val isOwner by app.settings.isOwner.collectAsStateWithLifecycle()
    val p = pairing ?: return
    SettingsScreen(
        gatewayUrl = p.gatewayUrl,
        deviceId = p.deviceId,
        phoneName = p.name,
        isOwner = isOwner,
        version = BuildConfig.VERSION_NAME,
        onForget = { app.session.forget() },
        onOpenAccessibility = { openAccessibilityFor(context) },
        onOpenConsole = { context.startActivity(Intent(Intent.ACTION_VIEW, p.gatewayUrl.toUri())) },
        onOpenSetup = openSetup,
    )
}
