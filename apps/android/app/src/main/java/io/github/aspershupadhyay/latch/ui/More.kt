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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import io.github.aspershupadhyay.latch.ui.theme.GeistMono
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
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
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
    Capability.ADB_SHELL to CapabilityCopy(
        "Full ADB access", "Run any ADB shell command. Off by default.",
        "Anything ADB can read on this phone — far more than the screen.",
        "Run arbitrary ADB shell commands over wireless debugging, with the same power as a computer plugged in with ADB. There is no per-command check, allowlist, or filter once you turn it on and authorize Android's wireless debugging: only turn it on for a client you fully trust, and use Stop or turn it off to end it.",
        "high", LatchIcons.Key,
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
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
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
/** One step on the History timeline: a round glyph joined to the next step by a line, the words, and the time. */
@Composable
private fun TimelineRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    titleColor: Color,
    time: String,
    detail: String,
    by: ActivityActor,
    last: Boolean,
) {
    val signal = LocalSignal.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 4.dp)) {
        Column(Modifier.width(40.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = if (signal.dark) 0.14f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
            if (!last) Box(Modifier.padding(top = 4.dp).width(2.dp).weight(1f).background(signal.border))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp, bottom = if (last) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.bodySmall, fontFamily = GeistMono, color = signal.text3, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = signal.text2, modifier = Modifier.weight(1f))
                ActorTag(by)
            }
        }
    }
}

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
        item {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { ScreenTitle("History", "Everything your AI did, in plain words.") }
                if (entries.isNotEmpty()) {
                    // The PDF report: every step, what the AI asked, and what you answered.
                    Row(
                        Modifier.padding(top = 10.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                            .background(signal.surface).border(1.dp, signal.border, RoundedCornerShape(14.dp))
                            .clickable(onClickLabel = "Save a PDF report", role = Role.Button) { choosePeriod = true }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(LatchIcons.Share, contentDescription = null, tint = signal.text2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save PDF", style = MaterialTheme.typography.labelLarge, color = signal.text)
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ActivityFilter.entries.filter { it == ActivityFilter.ALL || counts[it] != 0 || it == filter }, key = { it.name }) { f ->
                    val selected = f == filter
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                            .background(if (selected) signal.ink else signal.surface)
                            .border(1.dp, if (selected) signal.ink else signal.border2, RoundedCornerShape(50))
                            .selectable(selected = selected, role = Role.Tab) { filter = f }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${f.label} ${counts[f] ?: 0}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) signal.onInk else signal.text,
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
        val listed = shown.take(SHOWN_ENTRIES)
        var lastDay: String? = null
        listed.forEachIndexed { i, e ->
            val day = dayLabel(e.atMs, now)
            if (day != lastDay) {
                lastDay = day
                item(key = "day-$day-$i") { SectionCaption(day) }
            }
            // The line to the next step stops at the end of the day.
            val last = i == listed.lastIndex || dayLabel(listed[i + 1].atMs, now) != day
            item(key = "e-${e.atMs}-$i") {
                val tint = when (e.kind) {
                    ActivityKind.REFUSAL -> signal.danger
                    ActivityKind.APPROVAL, ActivityKind.APP -> signal.warning
                    ActivityKind.ACTION, ActivityKind.TASK -> signal.live
                    ActivityKind.FILE, ActivityKind.FOLDER -> signal.live
                    else -> signal.text2
                }
                TimelineRow(
                    iconFor(e.kind), tint, e.summary,
                    if (e.kind == ActivityKind.REFUSAL) signal.danger else signal.text,
                    format.format(Date(e.atMs)),
                    listOfNotNull(e.kind.label, e.app).joinToString(" \u00b7 "),
                    e.by, last,
                )
            }
        }
        item {
            // The privacy promise, where the list ends.
            Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LatchIcons.Lock, contentDescription = null, tint = signal.success, modifier = Modifier.size(16.dp))
                Text("Kept on this phone for 7 days. Never screen text or typed text.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
            }
        }
        if (entries.isNotEmpty()) {
            item { TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clear activity", color = signal.danger) } }
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
    /** Disconnects but keeps the keys, so reconnecting is one tap with no QR code or key. */
    onDisconnectKeep: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    /** AI apps: connect one, answer its sign-in, switch between Wi-Fi and the internet. */
    onOpenConnect: () -> Unit = {},
    aiWaiting: Int = 0,
    connection: io.github.aspershupadhyay.latch.data.ConnectionKind = io.github.aspershupadhyay.latch.data.ConnectionKind.HOSTED,
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
        Card(color = if (aiWaiting > 0) signal.accent.copy(alpha = if (signal.dark) 0.14f else 0.08f) else null) {
            ListRow(
                LatchIcons.Plug,
                "Your AI apps",
                if (aiWaiting > 0) {
                    "$aiWaiting waiting for your OK. Tap to answer."
                } else {
                    "Connect Claude or another AI \u00b7 ${connection.title}"
                },
                tint = signal.accent,
                onClick = onOpenConnect,
                onClickLabel = "Open your AI apps",
            )
        }
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
            // One home for Android permissions, screen access included; the turn-off switch is one tap further.
            ListRow(LatchIcons.ShieldCheck, "Permissions", "Screen access, notifications, battery", tint = signal.success, onClick = onOpenSetup, onClickLabel = "Open permissions")
            RowDivider()
            ListRow(LatchIcons.Person, "Turn off screen access", "In Android's settings, any time", tint = signal.text2, onClick = onOpenAccessibility, onClickLabel = "Open accessibility settings")
            RowDivider()
            ListRow(LatchIcons.Lock, "Privacy", "Screen content is never saved. No tracking.", tint = signal.text2, trailing = { InfoButton(HelpTopic.PRIVACY) })
            RowDivider()
            ListRow(
                LatchIcons.Eye,
                "Read unlabeled buttons",
                "So an icon-only Send or Pay still asks you. Uses Google ML Kit.",
                tint = signal.accent,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InfoButton(HelpTopic.READ_BUTTONS)
                        LatchSwitch(
                            checked = readButtons,
                            onCheckedChange = onReadButtons,
                            modifier = Modifier.semantics { contentDescription = "Read unlabeled buttons" },
                        )
                    }
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
                    tint = signal.live,
                    onClick = { if (keyRotation != KeyRotation.Busy) confirmRotate = true },
                    onClickLabel = "Rotate the owner key",
                )
            }
        }
        Card {
            ListRow(
                LatchIcons.Leave,
                "Disconnect from this relay",
                "Stops everything. Reconnect later in one tap, or forget the keys",
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
            text = {
                Text(
                    "Everything stops. Disconnect keeps this connection on the phone, so reconnecting is one tap on the first screen, with no QR code or key.\n\n" +
                        "Forget keys removes them for good: to connect again you'll need a new QR code (run latch-gateway pair on your computer), your secret key, or a join code.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmForget = false; onDisconnectKeep() }) { Text("Disconnect") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget keys", color = signal.danger) }
                    TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
                }
            },
        )
    }
}
