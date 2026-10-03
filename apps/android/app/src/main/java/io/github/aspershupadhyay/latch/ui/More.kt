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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.AutoMode
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
    Capability.FILE_READ to CapabilityCopy(
        "See files", "Photos you allow, Downloads, your Latch folder.",
        "Names and contents of photos and videos you allow, of files Latch saved in Downloads, and of the folder you pick below. Nothing else on the phone.",
        "Copy those files to your AI, for example to your computer.", "high", LatchIcons.Folder,
    ),
    Capability.FILE_WRITE to CapabilityCopy(
        "Save and change files", "Replacing and deleting ask you first.",
        "Nothing extra.",
        "Save new files (in your photos, Downloads, or your Latch folder), make folders, rename. Replacing or deleting a file asks you, unless Auto mode is on.",
        "high", LatchIcons.Folder,
    ),
    Capability.APP_SHARE to CapabilityCopy(
        "Share to apps", "Like the Share button: Instagram, YouTube, X, ….",
        "Nothing extra.",
        "Open an app's share screen with files, ready to post or send. Only apps you switched on. Posting and sending follow your app rules.",
        "medium", LatchIcons.Share,
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
    appsOn: Int = 0,
    onOpenApps: () -> Unit = {},
    remoteApprovals: Boolean = false,
    onRemoteApprovals: (Boolean) -> Unit = {},
    auto: AutoMode = AutoMode.OFF,
    onAuto: (AutoMode) -> Unit = {},
    folderName: String? = null,
    photosAllowed: Boolean = false,
    onPickFolder: () -> Unit = {},
    onForgetFolder: () -> Unit = {},
    onAllowPhotos: () -> Unit = {},
) {
    val signal = LocalSignal.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenColumn {
        ScreenTitle("Access", "Choose what your AI may do. Changes work right away.")

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

        AutoModeCard(auto, onAuto)

        SectionCaption("How the AI works")
        Card {
            ListRow(
                LatchIcons.Apps,
                "Apps the AI can use",
                when {
                    auto != AutoMode.OFF -> "Auto mode is on: every app, no questions."
                    appsOn == 0 -> "None yet. Latch asks you the first time the AI needs an app."
                    else -> "$appsOn on. Others ask you the first time."
                },
                tint = signal.accent,
                onClick = onOpenApps,
                onClickLabel = "Choose apps the AI can use",
            )
            RowDivider()
            ListRow(
                LatchIcons.ShieldCheck,
                "Ask me before every action",
                if (approveEveryAction) "On: you OK every tap and every word the AI types." else "Off: in apps you allowed, the AI works without asking.",
                tint = Color(0xFFFF9F0A),
                trailing = {
                    LatchSwitch(
                        checked = approveEveryAction,
                        onCheckedChange = onApproveEveryAction,
                        modifier = Modifier.semantics { contentDescription = "Ask me before every action" },
                    )
                },
            )
            RowDivider()
            ListRow(
                LatchIcons.Sparkle,
                "Answer from your AI chat too",
                if (remoteApprovals) {
                    "On: when Latch asks you something, you can answer on this phone or in your AI chat."
                } else {
                    "Off: only this phone can answer Latch's questions."
                },
                tint = Color(0xFF5E5CE6),
                trailing = {
                    LatchSwitch(
                        checked = remoteApprovals,
                        onCheckedChange = onRemoteApprovals,
                        modifier = Modifier.semantics { contentDescription = "Answer Latch's questions from your AI chat too" },
                    )
                },
            )
        }

        SectionCaption("What the AI can do")
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
                        LatchSwitch(
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

        SectionCaption("Files")
        Card {
            ListRow(
                LatchIcons.Folder,
                if (folderName != null) "Your Latch folder: $folderName" else "Pick a folder for the AI",
                if (folderName != null) {
                    "The AI may read, save, rename, and delete files in this folder only."
                } else {
                    "One folder the AI may use, for example Documents/AI. It never sees other folders."
                },
                tint = if (folderName != null) signal.accent else signal.text2,
                onClick = onPickFolder,
                onClickLabel = if (folderName != null) "Pick a different folder" else "Pick a folder",
                trailing = {
                    if (folderName != null) {
                        TextButton(
                            onClick = onForgetFolder,
                            modifier = Modifier.semantics { contentDescription = "Stop sharing the folder $folderName with the AI" },
                        ) { Text("Remove") }
                    }
                },
            )
            RowDivider()
            ListRow(
                LatchIcons.Photo,
                if (photosAllowed) "Photos: allowed" else "Photos: not allowed",
                if (photosAllowed) {
                    "The AI can see the photos and videos you allowed. Tap to change which."
                } else {
                    "Allow all photos, or only the ones you choose. Saving new pictures works without this."
                },
                tint = if (photosAllowed) signal.accent else signal.text2,
                onClick = onAllowPhotos,
                onClickLabel = "Choose which photos the AI can see",
            )
        }

        SectionCaption("While the AI works")
        Card {
            ListRow(
                LatchIcons.Tap,
                "Show where the AI taps",
                "A dot moves to each tap and swipe. It cannot press anything, and the AI never sees it.",
                tint = signal.accent,
                trailing = {
                    LatchSwitch(
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
                    LatchSwitch(
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
    onOpenGuide: () -> Unit = {},
) {
    val signal = LocalSignal.current
    var confirmForget by remember { mutableStateOf(false) }
    ScreenColumn {
        ScreenTitle("Settings")
        Card {
            ListRow(
                LatchIcons.Cloud,
                "Relay",
                gatewayUrl.removePrefix("https://") + if (isOwner) " \u00b7 yours" else " \u00b7 joined with a code",
                tint = signal.accent,
                onClick = if (isOwner) onOpenConsole else null,
                onClickLabel = "Open your relay's web page",
                trailing = { InfoButton(HelpTopic.RELAY) },
            )
            RowDivider()
            ListRow(LatchIcons.Phone, "This phone", phoneName, tint = Color(0xFF8E8E93))
        }
        Card {
            ListRow(LatchIcons.Info, "How to set up Latch", "Every step in plain words", tint = signal.accent, onClick = onOpenGuide, onClickLabel = "Open the setup guide")
            RowDivider()
            ListRow(LatchIcons.ShieldCheck, "Permissions", "Notifications, screen access, battery", tint = Color(0xFF34C759), onClick = onOpenSetup, onClickLabel = "Open permissions")
            RowDivider()
            ListRow(LatchIcons.Person, "Screen access", "Turn it off in Android's settings at any time", tint = signal.accent2, onClick = onOpenAccessibility, onClickLabel = "Open accessibility settings")
            RowDivider()
            ListRow(LatchIcons.Lock, "Privacy", "What's on your screen goes only to your own relay, only while a session runs. Nothing is saved. No tracking.", tint = Color(0xFF8E8E93))
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
                "Checks GitHub for a newer Latch. Sends nothing about you.",
                tint = signal.accent,
                trailing = {
                    LatchSwitch(
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
                "Disconnect from this relay",
                "Stops everything and removes this phone's keys",
                tint = signal.danger,
                titleColor = signal.danger,
                onClick = { confirmForget = true },
                onClickLabel = "Disconnect from this relay",
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Latch $version",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(signal.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Disconnect this phone?") },
            text = { Text("Everything stops and this phone forgets its keys. To connect again, you'll need your secret key or a new join code.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Disconnect", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Auto mode (ADR-024): every app and every action without questions. It
 * only turns on after the owner reads what it means and chooses how long.
 */
@Composable
private fun AutoModeCard(auto: AutoMode, onAuto: (AutoMode) -> Unit) {
    val signal = LocalSignal.current
    var asking by remember { mutableStateOf(false) }
    val on = auto != AutoMode.OFF
    Card(color = if (on) signal.accent.copy(alpha = if (signal.dark) 0.22f else 0.10f) else null) {
        ListRow(
            LatchIcons.Sparkle,
            "Auto mode",
            when (auto) {
                AutoMode.OFF -> "Let the AI use every app and finish tasks without asking you. Off."
                AutoMode.SESSION -> "On until this session ends. The AI uses every app without asking."
                AutoMode.ALWAYS -> "On until you turn it off. The AI uses every app without asking."
            },
            tint = signal.accent,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoButton(HelpTopic.AUTO)
                    LatchSwitch(
                        checked = on,
                        onCheckedChange = { want -> if (want) asking = true else onAuto(AutoMode.OFF) },
                        modifier = Modifier.semantics { contentDescription = "Auto mode" },
                    )
                }
            },
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Turn on Auto mode?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The AI will be able to use every app on this phone and do everything in them without asking you, including:")
                    Text("\u2022 send messages, post, and delete\n\u2022 pay, buy, and transfer money\n\u2022 install apps and answer Android's permission pop-ups")
                    Text("Latch still never types passwords, PINs, or one-time codes. The red Stop button ends everything at once, and every action is listed in Activity.")
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onAuto(AutoMode.SESSION); asking = false }) { Text("Yes, for this session") }
                    TextButton(onClick = { onAuto(AutoMode.ALWAYS); asking = false }) { Text("Yes, until I turn it off", color = signal.danger) }
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
