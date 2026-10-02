package io.github.aspershupadhyay.latch.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.LatchApp
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
import io.github.aspershupadhyay.latch.session.deviceDescriptor
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.delay
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

enum class Tab(val label: String) { HOME("Home"), CAPABILITIES("Allow"), CONNECT("Connect"), ACTIVITY("Activity"), SETTINGS("Settings") }

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
    val signal = LocalSignal.current
    Scaffold(
        containerColor = signal.canvas,
        bottomBar = {
            NavigationBar(containerColor = signal.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Box(Modifier.size(if (tab == t) 10.dp else 7.dp).clip(CircleShape).background(if (tab == t) signal.accent else signal.disabled)) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeRoute(app, reducedMotion) { tab = it }
                Tab.CAPABILITIES -> CapabilitiesRoute(app)
                Tab.CONNECT -> ConnectRoute(app)
                Tab.ACTIVITY -> {
                    val entries by app.log.entries.collectAsStateWithLifecycle()
                    ActivityScreen(entries, app.log::clear)
                }
                Tab.SETTINGS -> SettingsRoute(app)
            }
        }
    }
}

@Composable
private fun HomeRoute(app: LatchApp, reducedMotion: Boolean, go: (Tab) -> Unit) {
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
        ),
        HomeActions(
            start = {
                app.session.clearFailure()
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                app.session.start()
            },
            stop = { app.session.stop() },
            setPaused = app.session::setPaused,
            setMinutes = { m -> app.settings.update { it.copy(sessionMinutes = m) } },
            answer = app.approvals::answer,
            openAccessibilitySettings = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
            openAppInfo = { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
            goCapabilities = { go(Tab.CAPABILITIES) },
            goConnect = { go(Tab.CONNECT) },
            goActivity = { go(Tab.ACTIVITY) },
        ),
    )
}

@Composable
private fun CapabilitiesRoute(app: LatchApp) {
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    CapabilitiesScreen(
        enabled = prefs.enabled,
        accessibilityOn = service != null,
        approveEveryAction = prefs.approveEveryAction,
        onToggle = { c, on -> app.settings.update { p -> p.copy(enabled = if (on) p.enabled + c else p.enabled - c) } },
        onApproveEveryAction = { v -> app.settings.update { it.copy(approveEveryAction = v) } },
    )
}

@Composable
private fun ConnectRoute(app: LatchApp) {
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
    LaunchedEffect(owner) { run { clients = it.clients() } }

    ConnectScreen(
        ConnectState("${p.gatewayUrl}/mcp", isOwner, clients, loading, error, created),
        ConnectActions(
            create = { name -> run { created = it.createClient(name); clients = it.clients() } },
            revoke = { id -> run { it.revokeClient(id); clients = it.clients() } },
            dismissCreated = { created = null },
            copy = { label, value, sensitive -> copyToClipboard(context, label, value, sensitive) },
            refresh = { run { clients = it.clients() } },
        ),
    )
}

@Composable
private fun SettingsRoute(app: LatchApp) {
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
        onOpenAccessibility = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
        onOpenConsole = { context.startActivity(Intent(Intent.ACTION_VIEW, p.gatewayUrl.toUri())) },
    )
}
