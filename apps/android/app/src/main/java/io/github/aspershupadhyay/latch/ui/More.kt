package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.saveable.rememberSaveable
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
    Capability.ACTIVITY_READ to CapabilityCopy(
        "Read the activity log", "What happened, when you ask in the chat.",
        "Latch's own log: apps used, actions, your answers, files. Never screen or typed text.",
        "Let the AI read Latch's activity log, so it can tell you what it did and what you approved or refused.",
        "low", LatchIcons.Pulse,
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
    ActivityKind.CONNECTION -> LatchIcons.Cloud
    ActivityKind.SCREEN -> LatchIcons.Eye
    ActivityKind.ACTION -> LatchIcons.Tap
    ActivityKind.APP -> LatchIcons.Apps
    ActivityKind.APPROVAL -> LatchIcons.ShieldCheck
    ActivityKind.REFUSAL -> LatchIcons.Warning
    ActivityKind.FILE -> LatchIcons.Copy
    ActivityKind.FOLDER -> LatchIcons.Folder
    ActivityKind.TASK -> LatchIcons.Check
}

/** The Activity filters, in the order the owner looks for things. Session also covers the connection. */
internal enum class ActivityFilter(val label: String, val kinds: Set<ActivityKind>?) {
    ALL("All", null),
    ACTIONS("Actions", setOf(ActivityKind.ACTION)),
    APPS("App access", setOf(ActivityKind.APP)),
    APPROVALS("Approvals", setOf(ActivityKind.APPROVAL)),
    REFUSED("Refused", setOf(ActivityKind.REFUSAL)),
    FILES("Files", setOf(ActivityKind.FILE)),
    FOLDERS("Folders", setOf(ActivityKind.FOLDER)),
    SCREEN("Screen reads", setOf(ActivityKind.SCREEN)),
    TASKS("Tasks", setOf(ActivityKind.TASK)),
    SESSION("Session", setOf(ActivityKind.SESSION, ActivityKind.CONNECTION)),
    ;

    fun matches(e: ActivityEntry) = kinds == null || e.kind in kinds
}

/** "Today", "Yesterday", or the date, for the day headers. */
private fun dayLabel(atMs: Long, nowMs: Long): String {
    val zone = java.util.TimeZone.getDefault()
    fun day(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
    return when (day(nowMs) - day(atMs)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(atMs))
    }
}

/**
 * Everything the AI did, asked, and was refused, newest first, filterable by
 * kind. Kept on this phone for a week; never screen or typed text.
 */
@Composable
fun ActivityScreen(entries: List<ActivityEntry>, onClear: () -> Unit) {
    val signal = LocalSignal.current
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val counts = remember(entries) { ActivityFilter.entries.associateWith { f -> entries.count(f::matches) } }
    val shown = remember(entries, filter) { entries.filter(filter::matches) }
    val now = remember(entries) { System.currentTimeMillis() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Activity", "Every app, action, approval, refusal, and file. Kept on this phone for 7 days; never screen or typed text.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ActivityFilter.entries.filter { it == ActivityFilter.ALL || counts[it] != 0 || it == filter }, key = { it.name }) { f ->
                    val selected = f == filter
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (selected) signal.ink else signal.text2.copy(alpha = 0.12f))
                            .selectable(selected = selected, role = Role.Tab) { filter = f }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${f.label} ${counts[f] ?: 0}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) signal.canvas else signal.text,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Card {
                    ListRow(
                        LatchIcons.Pulse,
                        if (entries.isEmpty()) "Nothing yet" else "Nothing in ${filter.label}",
                        if (entries.isEmpty()) "Start a session and connect an AI app." else "Pick another filter above.",
                        tint = signal.text2,
                    )
                }
            }
        }
        var lastDay: String? = null
        shown.take(SHOWN_ENTRIES).forEachIndexed { i, e ->
            val day = dayLabel(e.atMs, now)
            if (day != lastDay) {
                lastDay = day
                item(key = "day-$day-$i") { SectionCaption(day) }
            }
            item(key = "e-${e.atMs}-$i") {
                val tint = when (e.kind) {
                    ActivityKind.REFUSAL -> signal.danger
                    ActivityKind.APPROVAL, ActivityKind.APP -> signal.warning
                    ActivityKind.ACTION, ActivityKind.TASK -> signal.accent
                    ActivityKind.FILE, ActivityKind.FOLDER -> signal.accent2
                    else -> signal.text2
                }
                Card {
                    ListRow(
                        iconFor(e.kind),
                        e.summary,
                        listOfNotNull(format.format(Date(e.atMs)), e.kind.label, e.app).joinToString(" · "),
                        tint = tint,
                    )
                }
            }
        }
        if (entries.isNotEmpty()) {
            item { TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear activity", color = signal.danger) } }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all activity?") },
            text = { Text("Every line is deleted from this phone. The AI's past actions cannot be listed again afterwards.") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Lines drawn at once; the log keeps more, and the AI can read all of them. */
private const val SHOWN_ENTRIES = 500

@Composable
fun SettingsScreen(
    gatewayUrl: String,
    deviceId: String,
    phoneName: String,
    isOwner: Boolean,
    version: String,
    onForget: () -> Unit,
    onOpenAccessibility: () -> Unit,
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
                trailing = { InfoButton(HelpTopic.RELAY) },
            )
            RowDivider()
            ListRow(LatchIcons.Phone, "This phone", phoneName, tint = signal.text2)
        }
        Card {
            ListRow(LatchIcons.Info, "How to set up Latch", "Every step in plain words", tint = signal.accent, onClick = onOpenGuide, onClickLabel = "Open the setup guide")
            RowDivider()
            ListRow(LatchIcons.ShieldCheck, "Permissions", "Notifications, screen access, battery", tint = signal.success, onClick = onOpenSetup, onClickLabel = "Open permissions")
            RowDivider()
            ListRow(LatchIcons.Person, "Screen access", "Turn it off in Android's settings at any time", tint = signal.accent2, onClick = onOpenAccessibility, onClickLabel = "Open accessibility settings")
            RowDivider()
            ListRow(LatchIcons.Lock, "Privacy", "What's on your screen goes only to your own relay, only while a session runs; screen content is never saved. Activity (what the AI did, no screen text) stays on this phone for 7 days. No tracking.", tint = signal.text2)
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
