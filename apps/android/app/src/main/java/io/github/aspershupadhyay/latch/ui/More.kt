package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import io.github.aspershupadhyay.latch.update.UpdateInfo
import io.github.aspershupadhyay.latch.update.UpdateState
import java.text.DateFormat
import java.util.Date

data class CapabilityCopy(val title: String, val short: String, val sees: String, val does: String, val risk: String, val icon: ImageVector)

val catalog = mapOf(
    Capability.DEVICE_INFO to CapabilityCopy(
        "Device info", "Model, Android version, screen size. Always on.",
        "Phone model, Android version, screen size, the app in front.", "Nothing.", "low", LatchIcons.Phone,
    ),
    Capability.UI_OBSERVE to CapabilityCopy(
        "Read the screen", "Text and buttons. Passwords stay hidden.",
        "Text, buttons, and layout of the app in front. Password, PIN, code, and payment fields are hidden.", "Nothing by itself.", "medium", LatchIcons.Eye,
    ),
    Capability.SCREEN_CAPTURE to CapabilityCopy(
        "Screenshots", "A picture of the screen.",
        "A picture of the screen, including images. Apps that block screenshots stay blocked.", "Nothing by itself.", "high", LatchIcons.Camera,
    ),
    Capability.INPUT_GESTURE to CapabilityCopy(
        "Tap and swipe", "Risky buttons ask you first.",
        "Nothing extra.", "Press buttons and scroll. Send, buy, delete, and similar buttons ask you first.", "medium", LatchIcons.Tap,
    ),
    Capability.INPUT_TEXT to CapabilityCopy(
        "Type text", "Never in password fields. Never presses send.",
        "Nothing extra.", "Replace text in a normal field. Never secret fields. Never presses send.", "high", LatchIcons.Keyboard,
    ),
    Capability.NAV_GLOBAL to CapabilityCopy(
        "Back, Home, Recents", "The system navigation buttons.",
        "Nothing extra.", "Use the system navigation buttons.", "low", LatchIcons.Back,
    ),
    Capability.APP_LAUNCH to CapabilityCopy(
        "Open apps", "See and open your installed apps.",
        "Names of apps you can open.", "Open an installed app. Never Latch itself.", "medium", LatchIcons.Apps,
    ),
)

@Composable
private fun riskColor(risk: String) = when (risk) {
    "high" -> LocalSignal.current.danger
    "medium" -> LocalSignal.current.warning
    else -> LocalSignal.current.success
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun CapabilitiesScreen(
    enabled: Set<Capability>,
    accessibilityOn: Boolean,
    approveEveryAction: Boolean,
    onToggle: (Capability, Boolean) -> Unit,
    onApproveEveryAction: (Boolean) -> Unit,
    onOpenSetup: () -> Unit = {},
    showCursor: Boolean = true,
    keepAwake: Boolean = true,
    onShowCursor: (Boolean) -> Unit = {},
    onKeepAwake: (Boolean) -> Unit = {},
    saved: List<SavedApprovalRow> = emptyList(),
    onRemoveSaved: (String) -> Unit = {},
    onRemoveAllSaved: () -> Unit = {},
) {
    val signal = LocalSignal.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenColumn {
        ScreenTitle("Access", "Choose what an AI may do. Changes apply at once.")

        if (!accessibilityOn) {
            Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                ListRow(
                    LatchIcons.Warning,
                    "Screen access is off",
                    "None of these work until you turn it on.",
                    tint = signal.warning,
                    onClick = onOpenSetup,
                    onClickLabel = "Turn on screen access",
                )
            }
        }

        Card {
            ListRow(
                LatchIcons.ShieldCheck,
                "Ask me before every action",
                if (approveEveryAction) "You approve every tap, swipe, and text." else "Only send, buy, delete, and similar ask you.",
                tint = signal.accent,
                trailing = {
                    Switch(
                        checked = approveEveryAction,
                        onCheckedChange = onApproveEveryAction,
                        modifier = Modifier.semantics { contentDescription = "Ask me before every action" },
                    )
                },
            )
        }

        SectionCaption("Abilities")
        Card {
            Capability.entries.forEachIndexed { index, capability ->
                val copy = catalog.getValue(capability)
                val always = capability == Capability.DEVICE_INFO
                val on = always || capability in enabled
                val open = expanded == capability.wire
                if (index > 0) RowDivider()
                ListRow(
                    icon = copy.icon,
                    title = copy.title,
                    subtitle = copy.short,
                    tint = if (on) signal.accent else signal.text2,
                    onClick = { expanded = if (open) null else capability.wire },
                    onClickLabel = if (open) "Hide details" else "Show details",
                    trailing = {
                        Switch(
                            checked = on,
                            enabled = !always,
                            onCheckedChange = { onToggle(capability, it) },
                            modifier = Modifier.semantics { contentDescription = copy.title },
                        )
                    },
                )
                AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Pill("${copy.risk.replaceFirstChar { it.uppercase() }} risk", riskColor(copy.risk))
                        Text("Sees: ${copy.sees}", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                        Text("Does: ${copy.does}", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                    }
                }
            }
        }

        SectionCaption("While the AI works")
        Card {
            ListRow(
                LatchIcons.Tap,
                "Show where the AI taps",
                "A dot moves to each tap and swipe. It cannot press anything, and the AI never sees it.",
                tint = signal.accent,
                trailing = {
                    Switch(
                        checked = showCursor,
                        onCheckedChange = onShowCursor,
                        modifier = Modifier.semantics { contentDescription = "Show where the AI taps" },
                    )
                },
            )
            RowDivider()
            ListRow(
                LatchIcons.Phone,
                "Keep the screen on",
                "During a session the screen stays on, so a task is not cut off by the lock screen. Uses more battery.",
                tint = signal.accent,
                trailing = {
                    Switch(
                        checked = keepAwake,
                        onCheckedChange = onKeepAwake,
                        modifier = Modifier.semantics { contentDescription = "Keep the screen on during a session" },
                    )
                },
            )
        }

        SectionCaption("Always allowed")
        Card {
            if (saved.isEmpty()) {
                ListRow(
                    LatchIcons.ShieldCheck,
                    "Nothing saved",
                    "When a send, post, or call asks you, choose “Always in this app” to stop being asked for that button there. Payments, installs, and permissions always ask.",
                    tint = signal.text2,
                )
            } else {
                saved.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    ListRow(
                        LatchIcons.ShieldCheck,
                        row.action,
                        "in ${row.app}",
                        tint = signal.accent,
                        trailing = {
                            TextButton(
                                onClick = { onRemoveSaved(row.key) },
                                modifier = Modifier.semantics { contentDescription = "Stop always allowing ${row.action} in ${row.app}" },
                            ) { Text("Remove") }
                        },
                    )
                }
                RowDivider()
                TextButton(onClick = onRemoveAllSaved, modifier = Modifier.padding(start = 60.dp)) { Text("Remove all") }
            }
        }
    }
}

/** One "always allow" answer, ready to show. [action] quotes untrusted app text. */
data class SavedApprovalRow(val key: String, val action: String, val app: String)

private fun iconFor(kind: ActivityKind) = when (kind) {
    ActivityKind.SESSION -> LatchIcons.Clock
    ActivityKind.OBSERVE -> LatchIcons.Eye
    ActivityKind.ACTION -> LatchIcons.Tap
    ActivityKind.APPROVAL -> LatchIcons.ShieldCheck
    ActivityKind.REFUSAL -> LatchIcons.Warning
    ActivityKind.CONNECTION -> LatchIcons.Cloud
}

@Composable
fun ActivityScreen(entries: List<ActivityEntry>, onClear: () -> Unit) {
    val signal = LocalSignal.current
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    ScreenColumn {
        ScreenTitle("Activity", "Only on this phone. Never screen or typed text.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("${entries.count { it.kind == ActivityKind.ACTION }} actions", signal.accent, LatchIcons.Tap)
            Pill("${entries.count { it.kind == ActivityKind.APPROVAL }} approvals", signal.warning, LatchIcons.ShieldCheck)
            Pill("${entries.count { it.kind == ActivityKind.REFUSAL }} refused", signal.danger, LatchIcons.Warning)
        }
        if (entries.isEmpty()) {
            Card {
                ListRow(LatchIcons.Pulse, "Nothing yet", "Start a session and connect an AI app.", tint = signal.text2)
            }
        } else {
            Card {
                entries.take(100).forEachIndexed { index, e ->
                    if (index > 0) RowDivider()
                    val tint = when (e.kind) {
                        ActivityKind.REFUSAL -> signal.danger
                        ActivityKind.APPROVAL -> signal.warning
                        ActivityKind.ACTION -> signal.accent
                        else -> signal.text2
                    }
                    ListRow(iconFor(e.kind), e.summary, format.format(Date(e.atMs)), tint = tint)
                }
            }
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear activity") }
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
    onOpenSetup: () -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    checkUpdates: Boolean = true,
    onCheckUpdates: (Boolean) -> Unit = {},
    onCheckNow: () -> Unit = {},
    onInstallUpdate: (UpdateInfo) -> Unit = {},
    reducedMotion: Boolean = false,
) {
    val signal = LocalSignal.current
    var confirmForget by remember { mutableStateOf(false) }
    ScreenColumn {
        ScreenTitle("Settings")
        Card {
            ListRow(
                LatchIcons.Cloud,
                "Gateway",
                gatewayUrl.removePrefix("https://") + if (isOwner) " · you own it" else " · joined with a code",
                tint = signal.accent,
                onClick = if (isOwner) onOpenConsole else null,
                onClickLabel = "Open the web console",
            )
            RowDivider()
            ListRow(LatchIcons.Phone, "This phone", "$phoneName · $deviceId", tint = signal.text2)
        }
        Card {
            ListRow(LatchIcons.ShieldCheck, "Permissions and setup", "Notifications, screen access, battery", tint = signal.accent, onClick = onOpenSetup, onClickLabel = "Open setup")
            RowDivider()
            ListRow(LatchIcons.Person, "Accessibility settings", "Turn screen access off at any time", tint = signal.accent2, onClick = onOpenAccessibility, onClickLabel = "Open accessibility settings")
            RowDivider()
            ListRow(LatchIcons.Lock, "Privacy", "Screen content goes only to your gateway, only during a session. Nothing is stored on the phone. No analytics.", tint = signal.success)
        }
        SectionCaption("Updates")
        if (update.shownOnHome()) UpdateCard(update, reducedMotion, onInstallUpdate)
        Card {
            ListRow(
                LatchIcons.Sparkle,
                "Check for updates",
                when (update) {
                    UpdateState.Checking -> "Checking\u2026"
                    UpdateState.UpToDate -> "You have the newest build ($version)"
                    is UpdateState.Failed -> if (update.info == null) update.message else "Installed: $version"
                    else -> "Installed: $version"
                },
                tint = signal.accent,
                onClick = onCheckNow,
                onClickLabel = "Check for updates now",
            )
            RowDivider()
            ListRow(
                LatchIcons.Clock,
                "Check when Latch opens",
                "Asks Latch's GitHub releases for the newest version number. Nothing about this phone or its screen is sent.",
                tint = signal.accent,
                trailing = {
                    Switch(
                        checked = checkUpdates,
                        onCheckedChange = onCheckUpdates,
                        modifier = Modifier.semantics { contentDescription = "Check for updates when Latch opens" },
                    )
                },
            )
        }
        Card {
            ListRow(
                LatchIcons.Leave,
                "Forget this gateway",
                "Stops the session and deletes this phone's keys",
                tint = signal.danger,
                titleColor = signal.danger,
                onClick = { confirmForget = true },
                onClickLabel = "Forget this gateway",
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Latch $version · protocol 1.1 · Apache-2.0",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(signal.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this gateway?") },
            text = { Text("The session stops and this phone deletes its keys. Revoke it in the gateway console too.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
