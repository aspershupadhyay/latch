package io.github.aspershupadhyay.latch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.session.PairingClient
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ---- Building blocks ----

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    val signal = LocalSignal.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = signal.surface),
        border = BorderStroke(1.dp, signal.border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalSignal.current.text2)
}

@Composable
private fun Badge(text: String, risk: String) {
    val signal = LocalSignal.current
    val color = when (risk) {
        "high" -> signal.danger
        "medium" -> signal.warning
        else -> signal.success
    }
    Surface(shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, color), color = signal.surface) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun minutesLeft(expiresAtMs: Long): Long = ((expiresAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1

// ---- Pairing ----

@Composable
fun PairScreen(app: LatchApp) {
    val scope = rememberCoroutineScope()
    var gateway by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val state by app.session.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = LocalSignal.current.canvas) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SignalField(FieldState.READY, "Not paired yet", reducedMotion = true, modifier = Modifier.align(Alignment.CenterHorizontally), size = 96.dp)
            Text("Let an AI you choose use this phone, only in the ways you allow.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Muted("Latch connects this phone to a Latch gateway that you or your team run. Any AI client that speaks MCP can then ask to see the screen or act, and every request passes through the switches you set here.")
            (state as? SessionState.Revoked)?.let { SectionCard { Text(it.reason, color = LocalSignal.current.danger) } }
            SectionCard {
                Heading("Pair with your gateway")
                Muted("In the gateway console, choose “Pair a phone”. Enter the address and the 8-character code it shows.")
                OutlinedTextField(
                    value = gateway,
                    onValueChange = { gateway = it; error = null },
                    label = { Text("Gateway address") },
                    placeholder = { Text("https://latch.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(12); error = null },
                    label = { Text("Pairing code") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = LocalSignal.current.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Button(
                    enabled = !busy && gateway.isNotBlank() && code.isNotBlank(),
                    onClick = {
                        busy = true
                        app.session.clearFailure()
                        scope.launch {
                            when (val result = app.pairing.pair(gateway, code)) {
                                is PairingClient.Result.Paired -> error = null
                                is PairingClient.Result.Failed -> error = result.message
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (busy) "Pairing…" else "Pair this phone") }
                Muted("If you back out now, nothing is paired and nothing is shared.")
            }
            SectionCard {
                Heading("What stays private")
                Muted("• Nothing is shared until you start a session.\n• Everything except basic device information starts switched off.\n• Password, PIN, one-time-code, and payment fields are never read or typed.\n• Latch and the notification shade are off-limits to agents.\n• Screens are not stored on the phone or the gateway.")
            }
        }
    }
}

// ---- Home ----

@Composable
fun HomeScreen(app: LatchApp, reducedMotion: Boolean, onOpenCapabilities: () -> Unit) {
    val context = LocalContext.current
    val signal = LocalSignal.current
    val state by app.session.state.collectAsStateWithLifecycle()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    val pending by app.approvals.pending.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val (field, headline, detail) = when (val s = state) {
        SessionState.Unpaired, SessionState.Idle -> Triple(FieldState.READY, "Ready. Nothing is shared.", "Start a session when you want an AI client to work with this phone.")
        is SessionState.Connecting -> Triple(FieldState.CONNECTING, "Connecting to the gateway…", "No commands run until the connection is confirmed.")
        is SessionState.Reconnecting -> Triple(FieldState.DISCONNECTED, "Connection lost. Retrying.", "No commands can run while disconnected. Stop to end the session.")
        is SessionState.Active -> when {
            pending != null -> Triple(FieldState.AWAITING_APPROVAL, "Waiting for your approval", "Nothing happens until you answer.")
            s.paused -> Triple(FieldState.DISCONNECTED, "Session paused", "The agent cannot see or do anything until you resume.")
            else -> Triple(FieldState.CONNECTED, "Session active · ${minutesLeft(s.expiresAtMs)} min left", "An AI client can use what you allowed. Stop ends everything at once.")
        }
        is SessionState.Revoked -> Triple(FieldState.STOPPED, "This phone was revoked", s.reason)
        is SessionState.Failed -> Triple(FieldState.STOPPED, "Session stopped", s.message)
    }
    val live = state is SessionState.Active || state is SessionState.Connecting || state is SessionState.Reconnecting

    Page {
        SignalField(field, headline, reducedMotion, Modifier.align(Alignment.CenterHorizontally))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Muted(detail)
        }

        pending?.let { p ->
            SectionCard {
                Badge("approval needed", p.risk)
                Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Muted(p.detail)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.approvals.answer(p.nonce, false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Deny") }
                    Button(onClick = { app.approvals.answer(p.nonce, true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Approve") }
                }
            }
        }

        if (live) {
            Button(
                onClick = { app.session.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = signal.danger),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Stop all activity", style = MaterialTheme.typography.titleMedium) }
            (state as? SessionState.Active)?.let { active ->
                OutlinedButton(onClick = { app.session.setPaused(!active.paused) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (active.paused) "Resume session" else "Pause session")
                }
            }
        } else if (pairing != null) {
            SectionCard {
                Heading("Start a session")
                Muted("Sessions end on their own. Choose how long this one may last.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        FilterChip(selected = prefs.sessionMinutes == m, onClick = { app.settings.update { it.copy(sessionMinutes = m) } }, label = { Text("$m min") })
                    }
                }
                Button(
                    onClick = {
                        app.session.clearFailure()
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        app.session.start()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Start session") }
            }
        }

        if (service == null) {
            SectionCard {
                Badge("setup needed", "medium")
                Heading("Turn on the Latch accessibility service")
                Muted("Android only lets an app read the screen and tap for you through an accessibility service. Latch uses it only during a session, only for what you allow, and never for password or payment fields.")
                Muted("If Android says the setting is restricted: open App info for Latch, tap ⋮, choose “Allow restricted settings”, then try again.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") }
                    TextButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }) { Text("App info") }
                }
            }
        }

        SectionCard {
            Heading("Right now the agent can")
            val statuses = remember(prefs, service, now) { app.session.capabilityStatuses() }
            val enabled = statuses.filterValues { it == CapabilityStatus.ENABLED }.keys - Capability.DEVICE_INFO
            if (enabled.isEmpty()) {
                Muted("Only read basic device information. Allow more under Capabilities.")
            } else {
                enabled.forEach { Text("• " + catalog.getValue(it).title) }
            }
            TextButton(onClick = onOpenCapabilities) { Text("Change what is allowed") }
        }

        pairing?.let { p ->
            SectionCard {
                Heading("Connection")
                Muted("Gateway: ${p.gatewayUrl.toUri().host ?: p.gatewayUrl}")
                Muted("This phone: ${p.name} · ${p.deviceId}")
                Muted(if (p.gatewayUrl.startsWith("https://")) "Encrypted connection (https)." else "Unencrypted debug connection. Use https outside development.")
            }
        }
    }
}

// ---- Capabilities ----

data class CapabilityCopy(val title: String, val sees: String, val does: String, val risk: String)

val catalog = mapOf(
    Capability.DEVICE_INFO to CapabilityCopy("See basic device information", "Phone model, Android version, screen size, the app in front.", "Nothing.", "low"),
    Capability.UI_OBSERVE to CapabilityCopy("Read the screen", "Text, buttons, and layout of the app in front. Password, PIN, code, and payment fields are hidden.", "Nothing by itself.", "medium"),
    Capability.SCREEN_CAPTURE to CapabilityCopy("Take screenshots", "A picture of the screen, including images and anything visible. Apps that block screenshots stay blocked.", "Nothing by itself.", "high"),
    Capability.INPUT_GESTURE to CapabilityCopy("Tap, long-press, and swipe", "Nothing extra.", "Press buttons and scroll in the app in front. Controls that send, buy, delete, or publish ask you first.", "medium"),
    Capability.INPUT_TEXT to CapabilityCopy("Type into text fields", "Nothing extra.", "Replace the text in a normal text field. Never password, PIN, code, or payment fields. Never presses send.", "high"),
    Capability.NAV_GLOBAL to CapabilityCopy("Press Back, Home, and Recents", "Nothing extra.", "Use the system navigation buttons.", "low"),
    Capability.APP_LAUNCH to CapabilityCopy("See and open apps", "The names of apps you can open from the launcher.", "Open an installed app. Never Latch itself.", "medium"),
)

@Composable
fun CapabilitiesScreen(app: LatchApp) {
    val prefs by app.settings.preferences.collectAsStateWithLifecycle()
    val service by app.bridge.service.collectAsStateWithLifecycle()
    Page {
        Text("Capabilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Muted("Each switch is separate. Changes apply immediately, even during a session.")
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Heading("Ask me before every action")
                    Muted("Off: you approve only risky actions. On: you approve every tap, swipe, and keystroke batch.")
                }
                Switch(checked = prefs.approveEveryAction, onCheckedChange = { v -> app.settings.update { it.copy(approveEveryAction = v) } })
            }
        }
        Capability.entries.forEach { capability ->
            val copy = catalog.getValue(capability)
            val on = capability in prefs.enabled
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Heading(copy.title)
                        Badge("${copy.risk} risk", copy.risk)
                    }
                    Switch(
                        checked = on,
                        enabled = capability != Capability.DEVICE_INFO,
                        onCheckedChange = { v -> app.settings.update { p -> p.copy(enabled = if (v) p.enabled + capability else p.enabled - capability) } },
                    )
                }
                Muted("Can see: ${copy.sees}")
                Muted("Can do: ${copy.does}")
                if (on && service == null && capability != Capability.DEVICE_INFO) {
                    Text("Needs the Latch accessibility service, which is off.", color = LocalSignal.current.warning)
                }
            }
        }
    }
}

// ---- Activity ----

@Composable
fun ActivityScreen(app: LatchApp) {
    val entries by app.log.entries.collectAsStateWithLifecycle()
    val signal = LocalSignal.current
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { app.log.clear() }, enabled = entries.isNotEmpty()) { Text("Clear") }
        }
        Muted("What happened on this phone. Screen text and typed text are never recorded. Kept only until Latch closes.")
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Muted("Nothing yet.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(format.format(Date(e.atMs)), color = signal.text2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                    val color = when (e.kind) {
                        ActivityKind.REFUSAL -> signal.danger
                        ActivityKind.APPROVAL -> signal.warning
                        else -> signal.text
                    }
                    Text(e.summary, color = color, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---- Settings ----

@Composable
fun SettingsScreen(app: LatchApp) {
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Page {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        pairing?.let { p ->
            SectionCard {
                Heading("Gateway")
                Text(p.gatewayUrl, fontFamily = FontFamily.Monospace)
                Muted("Device id ${p.deviceId} · named “${p.name}” by the gateway owner.")
                OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Forget this gateway") }
            }
        }
        SectionCard {
            Heading("Privacy")
            Muted("Latch sends screen content only to the gateway above, only during a session, and only for capabilities you allow. The phone stores no screens. The gateway keeps only the latest element list in memory to check actions, never screenshots, and its log holds command names and outcomes, never content.")
            Muted("Network destinations: the gateway address above. Nothing else.")
        }
        SectionCard {
            Heading("Accessibility service")
            Muted("Turn it off at any time in Android settings; every capability that needs it stops immediately.")
            TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") }
        }
        SectionCard {
            Heading("About")
            Muted("Latch ${BuildConfig.VERSION_NAME} · protocol 1.0 · open source under Apache-2.0")
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this gateway?") },
            text = { Text("The session stops and this phone deletes its credential. To connect again you need a new pairing code. The gateway owner should also revoke this phone in the console.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; app.session.forget() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
