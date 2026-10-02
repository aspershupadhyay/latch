package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.text.DateFormat
import java.util.Date

data class CapabilityCopy(val title: String, val sees: String, val does: String, val risk: String)

val catalog = mapOf(
    Capability.DEVICE_INFO to CapabilityCopy("Basic device information", "Phone model, Android version, screen size, the app in front.", "Nothing.", "low"),
    Capability.UI_OBSERVE to CapabilityCopy("Read the screen", "Text, buttons, and layout of the app in front. Password, PIN, code, and payment fields are hidden.", "Nothing by itself.", "medium"),
    Capability.SCREEN_CAPTURE to CapabilityCopy("Take screenshots", "A picture of the screen, including images. Apps that block screenshots stay blocked.", "Nothing by itself.", "high"),
    Capability.INPUT_GESTURE to CapabilityCopy("Tap, long-press, and swipe", "Nothing extra.", "Press buttons and scroll. Send, buy, delete, and similar buttons ask you first.", "medium"),
    Capability.INPUT_TEXT to CapabilityCopy("Type into text fields", "Nothing extra.", "Replace text in a normal field. Never secret fields. Never presses send.", "high"),
    Capability.NAV_GLOBAL to CapabilityCopy("Back, Home, and Recents", "Nothing extra.", "Use the system navigation buttons.", "low"),
    Capability.APP_LAUNCH to CapabilityCopy("See and open apps", "Names of apps you can open.", "Open an installed app. Never Latch itself.", "medium"),
)

@Composable
private fun riskColor(risk: String) = when (risk) {
    "high" -> LocalSignal.current.danger
    "medium" -> LocalSignal.current.warning
    else -> LocalSignal.current.success
}

@Composable
fun CapabilitiesScreen(
    enabled: Set<Capability>,
    accessibilityOn: Boolean,
    approveEveryAction: Boolean,
    onToggle: (Capability, Boolean) -> Unit,
    onApproveEveryAction: (Boolean) -> Unit,
) {
    val signal = LocalSignal.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        ScreenTitle("Capabilities", "Each switch is separate and applies immediately, even mid-session.")
        Tile(brush = if (approveEveryAction) signal.attentionBrush else null, minHeight = 0.dp) {
            val onBrush = approveEveryAction
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TileLabel("Safety", if (onBrush) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f) else signal.accent)
                    Text("Ask me before every action", style = MaterialTheme.typography.titleMedium, color = if (onBrush) androidx.compose.ui.graphics.Color.White else signal.text)
                    TileNote(
                        if (approveEveryAction) "On: you approve every tap, swipe, and typed text." else "Off: you approve only actions that send, buy, delete, or publish.",
                        color = if (onBrush) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f) else signal.text2,
                    )
                }
                Switch(checked = approveEveryAction, onCheckedChange = onApproveEveryAction, modifier = Modifier.semantics { contentDescription = "Ask me before every action" })
            }
        }
        val pairs = Capability.entries.chunked(2)
        pairs.forEach { row ->
            BentoRow {
                row.forEach { capability ->
                    val copy = catalog.getValue(capability)
                    val on = capability in enabled || capability == Capability.DEVICE_INFO
                    Tile(Modifier.weight(1f), color = if (on) null else signal.surface2, minHeight = 190.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { StatusDot(riskColor(copy.risk), "${copy.risk} risk") }
                            Switch(
                                checked = on,
                                enabled = capability != Capability.DEVICE_INFO,
                                onCheckedChange = { onToggle(capability, it) },
                                modifier = Modifier.semantics { contentDescription = copy.title },
                            )
                        }
                        Text(copy.title, style = MaterialTheme.typography.titleMedium, color = signal.text)
                        TileNote("Sees: ${copy.sees}", maxLines = 4)
                        TileNote("Does: ${copy.does}", maxLines = 4)
                        if (on && !accessibilityOn && capability != Capability.DEVICE_INFO) {
                            TileNote("Needs the accessibility service", color = signal.warning)
                        }
                    }
                }
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
fun ActivityScreen(entries: List<ActivityEntry>, onClear: () -> Unit) {
    val signal = LocalSignal.current
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        ScreenTitle("Activity", "What happened on this phone. Never screen text or typed text. Kept until Latch closes.")
        BentoRow {
            listOf(
                "Actions" to entries.count { it.kind == ActivityKind.ACTION },
                "Approvals" to entries.count { it.kind == ActivityKind.APPROVAL },
                "Refused" to entries.count { it.kind == ActivityKind.REFUSAL },
            ).forEach { (label, n) ->
                Tile(Modifier.weight(1f), color = signal.surface2, minHeight = 96.dp) {
                    TileLabel(label)
                    Text("$n", style = MaterialTheme.typography.headlineSmall, color = signal.text)
                }
            }
        }
        if (entries.isEmpty()) {
            Tile(minHeight = 0.dp) { TileNote("Nothing yet. Start a session and connect an AI app.") }
        } else {
            Tile(minHeight = 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TileLabel("Timeline")
                    Column(Modifier.weight(1f)) {}
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                entries.take(100).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(format.format(Date(e.atMs)), color = signal.text2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                        Text(
                            e.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (e.kind) {
                                ActivityKind.REFUSAL -> signal.danger
                                ActivityKind.APPROVAL -> signal.warning
                                else -> signal.text
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    gatewayUrl: String,
    deviceId: String,
    phoneName: String,
    isOwner: Boolean,
    version: String,
    onForget: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val signal = LocalSignal.current
    var confirmForget by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        ScreenTitle("Settings")
        Tile(minHeight = 0.dp) {
            TileLabel("Gateway")
            Text(gatewayUrl, style = MaterialTheme.typography.titleMedium, color = signal.text)
            TileNote("This phone: “$phoneName” · $deviceId")
            TileNote(if (isOwner) "You own this gateway; its owner key is encrypted on this phone." else "Joined with a pairing code.")
            if (isOwner) SecondaryButton("Open the web console", onOpenConsole, Modifier.fillMaxWidth())
        }
        BentoRow {
            Tile(Modifier.weight(1f), color = signal.surface2) {
                TileLabel("Privacy")
                TileNote("Screen content goes only to your gateway during a session, for what you allow. Nothing is stored on the phone. No analytics.", maxLines = 7)
            }
            Tile(Modifier.weight(1f), color = signal.surface2, onClick = onOpenAccessibility, onClickLabel = "Open accessibility settings") {
                TileLabel("Accessibility")
                TileNote("Turn the service off at any time; everything that needs it stops at once.", maxLines = 5)
                Push()
                Text("Open ›", style = MaterialTheme.typography.labelLarge, color = signal.accent)
            }
        }
        Tile(minHeight = 0.dp) {
            TileLabel("Leave", signal.danger)
            TileNote("Stops the session and deletes this phone's credentials. To come back you pair again.")
            SecondaryButton("Forget this gateway", { confirmForget = true }, Modifier.fillMaxWidth(), contentColor = signal.danger)
        }
        TileNote("Latch $version · protocol 1.1 · open source, Apache-2.0")
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this gateway?") },
            text = { Text("The session stops and this phone deletes its credentials. Revoke it in the gateway console too.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
