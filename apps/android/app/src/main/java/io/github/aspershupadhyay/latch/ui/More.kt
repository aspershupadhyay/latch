// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.foundation.layout.width
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
import io.github.aspershupadhyay.latch.data.ActivityActor
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.TaskEnd
import io.github.aspershupadhyay.latch.data.TaskRun
import io.github.aspershupadhyay.latch.data.taskRuns
import io.github.aspershupadhyay.latch.data.tenths
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.report.ReportPeriod
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
    Capability.CONTROL_SOUND to CapabilityCopy(
        "Sound", "Volumes, media, ringer, Do Not Disturb.",
        "Nothing.",
        "Set the media, ring, notification, or alarm volume; play, pause, or skip in the app that is playing; set the ringer to ring, vibrate, or silent; and turn Do Not Disturb on or off (Android asks you once for Do Not Disturb access).",
        "low", LatchIcons.Play,
    ),
    Capability.CONTROL_BRIGHTNESS to CapabilityCopy(
        "Screen", "Brightness, rotation, timeout.",
        "Nothing.",
        "Set the screen brightness (adaptive brightness turns off), auto-rotate or a fixed portrait or landscape, and how long the screen stays on. Android asks you once to let Latch change system settings.",
        "low", LatchIcons.Sliders,
    ),
    Capability.CONTROL_FLASHLIGHT to CapabilityCopy(
        "Flashlight", "Turn it on or off.",
        "Nothing.",
        "Turn the flashlight on or off. It never uses the camera for anything else.",
        "low", LatchIcons.Sparkle,
    ),
    Capability.CONTROL_PANELS to CapabilityCopy(
        "Settings panels and pages", "Opens them; taps inside follow your rules.",
        "What the page shows, like any screen.",
        "Open the Wi-Fi, internet, Bluetooth, volume, or NFC panel, or the airplane mode, location, display, battery, sound, notification, storage, or date and time settings. What it taps there is an ordinary action: the Settings app must be switched on, and risky buttons ask you. The notification shade itself stays off limits.",
        "medium", LatchIcons.Cloud,
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
internal enum class ActivityFilter(val label: String, val kinds: Set<ActivityKind>?, val by: ActivityActor? = null) {
    ALL("All", null),
    BY_AI("By AI", null, ActivityActor.AI),
    BY_YOU("By you", null, ActivityActor.OWNER),
    /** Not a filter: the AI's tasks as timelines (roadmap R14). */
    BY_TASK("By task", null),
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

    fun matches(e: ActivityEntry) = (kinds == null || e.kind in kinds) && (by == null || e.by == by)
}

/** Who did it: "AI" (through Latch), "You" (your own finger or answer), or "Latch". */
@Composable
private fun ActorTag(by: ActivityActor) {
    val signal = LocalSignal.current
    val color = when (by) {
        ActivityActor.AI -> signal.accent
        ActivityActor.OWNER -> signal.success
        ActivityActor.LATCH -> signal.text2
    }
    Text(
        by.label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "Done by ${if (by == ActivityActor.OWNER) "you" else by.label}" },
    )
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
fun ActivityScreen(entries: List<ActivityEntry>, onClear: () -> Unit, onExport: (ReportPeriod) -> Unit = {}) {
    val signal = LocalSignal.current
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    var choosePeriod by remember { mutableStateOf(false) }
    val runs = remember(entries) { taskRuns(entries) }
    val counts = remember(entries, runs) {
        ActivityFilter.entries.associateWith { f -> if (f == ActivityFilter.BY_TASK) runs.size else entries.count(f::matches) }
    }
    val shown = remember(entries, filter) { if (filter == ActivityFilter.BY_TASK) emptyList() else entries.filter(filter::matches) }
    val now = remember(entries) { System.currentTimeMillis() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Activity", "What the AI did, what you did, and what Latch did. Kept on this phone for 7 days. Never screen text or typed text.") }
        if (entries.isNotEmpty()) {
            item {
                Card {
                    ListRow(
                        LatchIcons.Share,
                        "Export a PDF report",
                        "Every step the AI took, what it asked, and what you answered, for a task or a period. Saved to Downloads.",
                        tint = signal.accent,
                        onClick = { choosePeriod = true },
                        onClickLabel = "Choose what the report covers",
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ActivityFilter.entries.filter { it == ActivityFilter.ALL || counts[it] != 0 || it == filter }, key = { it.name }) { f ->
                    val selected = f == filter
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (selected) signal.ink else Color.Transparent)
                            .border(1.dp, if (selected) signal.ink else signal.border2, RoundedCornerShape(6.dp))
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
        if (filter == ActivityFilter.BY_TASK) {
            taskTimelines(runs.take(SHOWN_TASKS), format)
        } else if (shown.isEmpty()) {
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
                        trailing = { ActorTag(e.by) },
                    )
                }
            }
        }
        if (entries.isNotEmpty()) {
            item { TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear activity", color = signal.danger) } }
        }
    }
    if (choosePeriod) {
        AlertDialog(
            onDismissRequest = { choosePeriod = false },
            title = { Text("What should the report cover?") },
            text = {
                Column {
                    Text(
                        "The PDF lists every line of Latch's log for that time, oldest first, with a summary and the AI's tasks. " +
                            "It never holds screen text, typed text, passwords, or the clipboard.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = signal.text2,
                    )
                    ReportPeriod.entries.forEach { period ->
                        TextButton(
                            onClick = {
                                choosePeriod = false
                                onExport(period)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(period.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosePeriod = false }) { Text("Cancel") } },
        )
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

/** Tasks drawn at once in "By task". */
private const val SHOWN_TASKS = 30

/**
 * "By task": each AI task as a card with how it ended, how long it took, and
 * what was decided, then its steps with seconds from the task's start.
 */
private fun LazyListScope.taskTimelines(runs: List<TaskRun>, format: DateFormat) {
    if (runs.isEmpty()) {
        item { Card { ListRow(LatchIcons.Pulse, "No AI task yet", "A task starts when the AI acts and ends when it says it is done.") } }
        return
    }
    runs.forEachIndexed { t, run ->
        item(key = "task-${run.startMs}-$t") {
            val signal = LocalSignal.current
            val ended = when (run.end) {
                TaskEnd.FINISHED -> "Finished"
                TaskEnd.SESSION_ENDED -> "Cut short when the session ended"
                TaskEnd.OPEN -> "Still going"
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "$ended · ${tenths(run.durationMs)} · started ${format.format(Date(run.startMs))}",
                        style = MaterialTheme.typography.titleSmall,
                        color = signal.text,
                    )
                    Text(
                        "Actions ${run.count(ActivityKind.ACTION)} · approvals ${run.count(ActivityKind.APPROVAL)} · refused ${run.count(ActivityKind.REFUSAL)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.text2,
                    )
                    run.entries.forEach { e ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "+${tenths(e.atMs - run.startMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = signal.text2,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                listOfNotNull(e.summary, e.app?.let { "· $it" }).joinToString(" "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (e.kind == ActivityKind.REFUSAL) signal.danger else signal.text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Where the owner-key rotation in Settings stands (ADR-038). */
sealed interface KeyRotation {
    data object Idle : KeyRotation
    data object Busy : KeyRotation
    /** [saved] is false when the gateway changed the key but this phone could not store it. */
    data class Done(val key: String, val saved: Boolean) : KeyRotation
    data class Failed(val message: String) : KeyRotation
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
    onOpenSetup: () -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    checkUpdates: Boolean = true,
    onCheckUpdates: (Boolean) -> Unit = {},
    readButtons: Boolean = true,
    onReadButtons: (Boolean) -> Unit = {},
    onCheckNow: () -> Unit = {},
    onInstallUpdate: (UpdateInfo) -> Unit = {},
    reducedMotion: Boolean = false,
    onOpenGuide: () -> Unit = {},
    onOpenSource: () -> Unit = {},
    keyRotation: KeyRotation = KeyRotation.Idle,
    onRotateKey: () -> Unit = {},
    onCopyNewKey: (String) -> Unit = {},
    onDismissRotation: () -> Unit = {},
) {
    val signal = LocalSignal.current
    var confirmForget by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
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
            ListRow(LatchIcons.Lock, "Privacy", "What's on your screen goes only to your own relay, only while a session runs; screen content is never saved. Activity (what the AI did, no screen text) stays on this phone for 7 days. No tracking by Latch.", tint = signal.text2)
            RowDivider()
            ListRow(
                LatchIcons.Eye,
                "Read unlabeled buttons",
                "Before the AI taps a button with no label, Latch reads the words on it here on the phone, so an icon-only Send or Pay still asks you. Uses Google ML Kit: the picture and words never leave the phone, but ML Kit sends Google usage statistics (phone model, app version, speed).",
                tint = signal.accent,
                trailing = {
                    LatchSwitch(
                        checked = readButtons,
                        onCheckedChange = onReadButtons,
                        modifier = Modifier.semantics { contentDescription = "Read unlabeled buttons" },
                    )
                },
            )
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
            // AGPL-3.0 section 5(d): the app shows its license and where its source is.
            ListRow(
                LatchIcons.Info,
                "Open source",
                "Free software under the GNU AGPL-3.0. Tap for the source code.",
                tint = signal.text2,
                onClick = onOpenSource,
                onClickLabel = "Open the source code",
            )
        }
        if (isOwner) {
            Card {
                ListRow(
                    LatchIcons.Key,
                    "Rotate owner key",
                    if (keyRotation == KeyRotation.Busy) "Changing the key\u2026" else "Makes a new secret key for your relay and saves it on this phone. The old key stops working at once.",
                    tint = signal.accent2,
                    onClick = { if (keyRotation != KeyRotation.Busy) confirmRotate = true },
                    onClickLabel = "Rotate the owner key",
                )
            }
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
                "latch v$version",
                style = MaterialTheme.typography.labelMedium,
                color = signal.text2,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, signal.border, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("Make a new owner key?") },
            text = {
                Text(
                    "Your relay makes a new secret key and this phone saves it. The current key stops working everywhere at once, " +
                        "including the LATCH_ADMIN_TOKEN in your relay settings; leave that setting as it is. " +
                        "If you ever lose the key, set a new LATCH_ADMIN_TOKEN in your relay settings and redeploy to get back in.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmRotate = false; onRotateKey() }) { Text("Make new key") } },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
        )
    }
    when (keyRotation) {
        is KeyRotation.Done -> AlertDialog(
            onDismissRequest = onDismissRotation,
            title = { Text(if (keyRotation.saved) "New owner key saved" else "Copy your new key now") },
            text = {
                Text(
                    if (keyRotation.saved) {
                        "This phone uses the new key from now on, and the old one no longer works. You can copy it to keep a backup somewhere safe; it is not shown again."
                    } else {
                        "Your relay changed the key, but this phone could not save it. Copy it now and keep it safe: it is not shown again. " +
                            "If it is lost, set a new LATCH_ADMIN_TOKEN in your relay settings and redeploy."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { onCopyNewKey(keyRotation.key) }) { Text("Copy key") } },
            dismissButton = { TextButton(onClick = onDismissRotation) { Text("Done") } },
        )
        is KeyRotation.Failed -> AlertDialog(
            onDismissRequest = onDismissRotation,
            title = { Text("The key was not changed") },
            text = { Text(keyRotation.message) },
            confirmButton = { TextButton(onClick = onDismissRotation) { Text("OK") } },
        )
        else -> Unit
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
