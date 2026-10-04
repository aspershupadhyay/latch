package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ThemeChoice
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
    Capability.CLIPBOARD_WRITE to CapabilityCopy(
        "Copy to clipboard", "Puts text there to paste. Never reads it.",
        "Nothing: Latch never reads your clipboard.",
        "Put text on the clipboard, for example a caption to paste into Instagram. It replaces what you copied before.",
        "low", LatchIcons.Clipboard,
    ),
)

@Composable
internal fun riskColor(risk: String) = when (risk) {
    "high" -> LocalSignal.current.danger
    "medium" -> LocalSignal.current.warning
    else -> LocalSignal.current.success
}

@Composable
internal fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

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
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {},
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
        SectionCaption("Appearance")
        ThemePicker(theme, onTheme)
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

/** Light, dark, or the phone's own setting, as one segmented control. */
@Composable
private fun ThemePicker(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit) {
    val signal = LocalSignal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(signal.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeChoice.entries.forEach { choice ->
            val selected = choice == theme
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (selected) signal.ink else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { onTheme(choice) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(choice.label, style = MaterialTheme.typography.labelLarge, color = if (selected) signal.onInk else signal.text2, maxLines = 1)
            }
        }
    }
}
