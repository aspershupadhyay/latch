// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.aspershupadhyay.latch.session.PhoneControls
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.data.AutoMode
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import io.github.aspershupadhyay.latch.ui.theme.Signal

/**
 * The Access tab in five groups instead of one long list of switches. A
 * group with capabilities has one switch for all of them (on means at least
 * one is on, so off always means none) and opens a page with every switch
 * one by one. Every capability except device info belongs to exactly one
 * group ([AccessGroupTest]).
 */
enum class AccessGroup(val title: String, val icon: ImageVector, val capabilities: List<Capability>, val intro: String) {
    AI(
        "AI access", LatchIcons.Sparkle, emptyList(),
        "Which apps the AI may use, when it has to ask you, and where you can answer.",
    ),
    SCREEN(
        "Screen & control", LatchIcons.Tap,
        listOf(
            Capability.UI_OBSERVE, Capability.SCREEN_CAPTURE, Capability.INPUT_GESTURE,
            Capability.INPUT_TEXT, Capability.NAV_GLOBAL, Capability.APP_LAUNCH,
        ),
        "With this on, the AI can see the screen and use the phone like you do. Risky buttons still ask you.",
    ),
    FILES(
        "Files & folders", LatchIcons.Folder, listOf(Capability.FILE_READ, Capability.FILE_WRITE),
        "With this on, the AI can read, save, rename, replace, and delete files, only in the places you allow. Replacing and deleting ask you unless Auto mode is on.",
    ),
    SHARING(
        "Share, clipboard & log", LatchIcons.Share, listOf(Capability.APP_SHARE, Capability.CLIPBOARD_WRITE, Capability.ACTIVITY_READ),
        "With this on, the AI can hand files to an app's share screen, put text on the clipboard, and read Latch's activity log when you ask what happened. It never reads the clipboard.",
    ),
    CONTROLS(
        "Phone controls", LatchIcons.Sliders,
        listOf(Capability.CONTROL_SOUND, Capability.CONTROL_BRIGHTNESS, Capability.CONTROL_FLASHLIGHT, Capability.CONTROL_PANELS),
        "With this on, the AI can set volumes, the ringer, and Do Not Disturb, play or pause media, change the brightness, rotation, and screen timeout, use the flashlight, open settings panels and pages, and set timers and alarms. Each group is its own switch, off until you turn it on.",
    ),
    ADB(
        "Full ADB access", LatchIcons.Key, listOf(Capability.ADB_SHELL),
        "Off by default. With this on, and after you authorize Android wireless debugging, the AI can run any ADB shell command on this phone — the same power as a computer with ADB, with no per-command check. Only turn it on for a client you fully trust, and turn it off or use Stop to end it.",
    ),
    /** ADR-065: everything that is not one of the three cards, apps, or files, on one quiet page. */
    MORE(
        "More choices", LatchIcons.Sliders, emptyList(),
        "When Latch asks you, what you see while the AI works, and full ADB. Most people never need these.",
    ),
}

/** The hue that marks a group's glyph. Only the glyph is coloured; every card sits on the same surface. */
private fun Signal.hueOf(group: AccessGroup) = when (group) {
    AccessGroup.AI -> accent
    AccessGroup.SCREEN -> mint
    AccessGroup.FILES -> warning
    AccessGroup.SHARING -> live
    AccessGroup.CONTROLS -> mint
    AccessGroup.ADB -> warning
    AccessGroup.MORE -> text2
}

/** One line per thing the group allows, for its ⓘ sheet. */
private fun featuresOf(group: AccessGroup): List<Feature> = when (group) {
    AccessGroup.AI -> listOf(
        Feature(LatchIcons.Apps, "Apps the AI can use", "Only apps you switch on; others ask you the first time."),
        Feature(LatchIcons.ShieldCheck, "Ask me before every action", "You OK every tap and every word it types."),
        Feature(LatchIcons.Sparkle, "Answer from your AI chat", "Reply to Latch's questions in the chat, not only here."),
        Feature(LatchIcons.Check, "Always allowed", "Buttons you told Latch to stop asking about, per app."),
    )
    AccessGroup.FILES -> group.capabilities.map { catalog.getValue(it).let { c -> Feature(c.icon, c.title, c.short) } } + listOf(
        Feature(LatchIcons.Folder, "Your folders", "Only the folders you add. It never sees other folders."),
        Feature(LatchIcons.Photo, "Photos", "Only when switched on, and only the photos and videos you allow."),
    )
    else -> group.capabilities.map { catalog.getValue(it).let { c -> Feature(c.icon, c.title, c.short) } }
}

@Composable
fun CapabilitiesScreen(
    enabled: Set<Capability>,
    accessibilityOn: Boolean,
    approveEveryAction: Boolean,
    onToggle: (Capability, Boolean) -> Unit,
    onApproveEveryAction: (Boolean) -> Unit,
    onOpenSetup: () -> Unit = {},
    saved: List<SavedApprovalRow> = emptyList(),
    onRemoveSaved: (String) -> Unit = {},
    onRemoveAllSaved: () -> Unit = {},
    appsOn: Int = 0,
    onOpenApps: () -> Unit = {},
    remoteApprovals: Boolean = false,
    onRemoteApprovals: (Boolean) -> Unit = {},
    auto: AutoMode = AutoMode.OFF,
    onAuto: (AutoMode) -> Unit = {},
    folders: List<String> = emptyList(),
    photosAllowed: Boolean = false,
    onPickFolder: () -> Unit = {},
    onForgetFolder: (Int) -> Unit = {},
    onAllowPhotos: () -> Unit = {},
    localTransfers: Boolean = false,
    onLocalTransfers: (Boolean) -> Unit = {},
    /** R27 (ADR-046): full-access ADB pairing. */
    adbEndpoint: String? = null,
    adbWirelessAvailable: Boolean = false,
    onPairAdb: suspend (code: String, host: String?, port: Int?) -> String = { _, _, _ -> "" },
    onUnpairAdb: () -> Unit = {},
    /** R29 (c): puts the pairing box in a notification and opens Developer options; false without notifications. */
    onPairFromNotification: () -> Boolean = { false },
    /** R29 (b): whether full ADB also works on mobile data, and the switch for it. */
    adbFixedPort: Boolean = false,
    onAdbFixedPort: suspend (Boolean) -> String = { "" },
    /** The open group page, hoisted so it survives a trip to the Apps list. */
    openGroup: AccessGroup? = null,
    onOpenGroup: ((AccessGroup?) -> Unit)? = null,
    /** ADR-064: every ordinary app is on, and the one-tap choice that sets it. */
    allApps: Boolean = false,
    onPreset: (AccessPreset) -> Unit = {},
    /** ADR-022: payments, installs, and permissions without asking, in switched-on apps. */
    trustCritical: Boolean = false,
    onTrustCritical: (Boolean) -> Unit = {},
) {
    val signal = LocalSignal.current
    var localGroup by rememberSaveable { mutableStateOf<AccessGroup?>(null) }
    val group = if (onOpenGroup != null) openGroup else localGroup
    val setGroup: (AccessGroup?) -> Unit = onOpenGroup ?: { localGroup = it }
    // Full ADB opens from More choices, so back from it returns there.
    val back: () -> Unit = { setGroup(if (group == AccessGroup.ADB) AccessGroup.MORE else null) }
    BackHandler(enabled = group != null, onBack = back)

    // Turning a group on switches on all of its capabilities; off switches all of them off.
    val toggleGroup: (AccessGroup, Boolean) -> Unit = { g, on -> g.capabilities.forEach { onToggle(it, on) } }

    AnimatedContent(
        targetState = group,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "access",
    ) { current ->
        if (current == null) {
            AccessOverview(
                enabled, accessibilityOn, auto, appsOn, allApps, approveEveryAction, folders.size, photosAllowed,
                onOpenSetup, onPreset, onOpenApps, open = setGroup,
            )
        } else {
            ScreenColumn {
                GroupHeader(current, enabled, onBack = back, onToggleGroup = { toggleGroup(current, it) })
                when (current) {
                    AccessGroup.AI -> AiAccessBody(
                        auto, onAuto, approveEveryAction, onApproveEveryAction, remoteApprovals, onRemoteApprovals,
                        saved, onRemoveSaved, onRemoveAllSaved,
                    )
                    AccessGroup.SCREEN -> CapabilityList(current, enabled, onToggle, includeDeviceInfo = true)
                    AccessGroup.FILES -> {
                        FilePlaces(folders, photosAllowed, onPickFolder, onForgetFolder, onAllowPhotos)
                        FastTransfers(localTransfers, onLocalTransfers)
                    }
                    AccessGroup.SHARING -> CapabilityList(current, enabled, onToggle)
                    AccessGroup.CONTROLS -> {
                        CapabilityList(current, enabled, onToggle)
                        BrightnessPermission(Capability.CONTROL_BRIGHTNESS in enabled)
                        DoNotDisturbPermission(Capability.CONTROL_SOUND in enabled)
                    }
                    AccessGroup.ADB -> {
                        CapabilityList(current, enabled, onToggle)
                        AdbPairing(adbEndpoint, adbWirelessAvailable, onPairAdb, onUnpairAdb, onPairFromNotification, adbFixedPort, onAdbFixedPort)
                    }
                    AccessGroup.MORE -> {
                        AiAccessBody(
                            auto, onAuto, approveEveryAction, onApproveEveryAction, remoteApprovals, onRemoteApprovals,
                            saved, onRemoveSaved, onRemoveAllSaved, trustCritical, onTrustCritical,
                        )
                        // Android's own switches some phone controls need; each shows only while it is missing.
                        BrightnessPermission(Capability.CONTROL_BRIGHTNESS in enabled)
                        DoNotDisturbPermission(Capability.CONTROL_SOUND in enabled)
                        // The largest grant in the app gets its own place, so it never reads as one more ordinary switch.
                        SectionCaption("Most access")
                        Card {
                            ListRow(
                                AccessGroup.ADB.icon,
                                AccessGroup.ADB.title,
                                if (AccessGroup.ADB.capabilities.any { it in enabled }) "On: the AI can run any ADB command" else "Off. For developers and power users.",
                                tint = signal.warning,
                                onClick = { setGroup(AccessGroup.ADB) },
                                onClickLabel = "Open ${AccessGroup.ADB.title}",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessOverview(
    enabled: Set<Capability>,
    accessibilityOn: Boolean,
    auto: AutoMode,
    appsOn: Int,
    allApps: Boolean,
    approveEveryAction: Boolean,
    folders: Int,
    photosAllowed: Boolean,
    onOpenSetup: () -> Unit,
    onPreset: (AccessPreset) -> Unit,
    onOpenApps: () -> Unit,
    open: (AccessGroup) -> Unit,
) {
    val signal = LocalSignal.current
    val chosen = AccessPreset.matching(enabled)
    ScreenColumn {
        ScreenTitle("Access", "How much may your AI do? Pick one.")

        if (!accessibilityOn) {
            Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                ListRow(
                    LatchIcons.Warning,
                    "Screen access is off",
                    "None of this works until you turn it on.",
                    tint = signal.warning,
                    onClick = onOpenSetup,
                    onClickLabel = "Turn on screen access",
                )
            }
        }

        // The one choice most people need. Each card sets every switch below at once.
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccessPreset.entries.forEach { preset ->
                PresetOption(preset, selected = chosen == preset && (preset != AccessPreset.EVERYTHING || allApps || auto != AutoMode.OFF)) { onPreset(preset) }
            }
        }

        Card {
            ListRow(
                LatchIcons.Apps,
                "Your apps",
                when {
                    auto != AutoMode.OFF -> "Auto mode: every app, no questions"
                    allApps -> "Every app \u00b7 bank and password apps ask once"
                    appsOn == 0 -> "Each app asks you the first time"
                    else -> "$appsOn on \u00b7 others ask you the first time"
                },
                tint = signal.accent,
                onClick = onOpenApps,
                onClickLabel = "Choose apps the AI can use",
            )
            RowDivider()
            ListRow(
                AccessGroup.FILES.icon,
                "Files",
                when {
                    AccessGroup.FILES.capabilities.none { it in enabled } -> "Off \u00b7 comes with Everything"
                    folders == 0 && !photosAllowed -> "On \u00b7 Downloads only"
                    else -> "On \u00b7 " + listOfNotNull(
                        if (folders > 0) "$folders folder${if (folders == 1) "" else "s"}" else null,
                        if (photosAllowed) "photos" else null,
                        "Downloads",
                    ).joinToString(", ")
                },
                tint = signal.hueOf(AccessGroup.FILES),
                onClick = { open(AccessGroup.FILES) },
                onClickLabel = "Choose which files the AI may use",
            )
            RowDivider()
            ListRow(
                AccessGroup.MORE.icon,
                AccessGroup.MORE.title,
                listOfNotNull(
                    if (auto != AutoMode.OFF) "Auto mode on" else null,
                    if (approveEveryAction) "asks before every action" else null,
                    if (AccessGroup.ADB.capabilities.any { it in enabled }) "full ADB on" else null,
                ).ifEmpty { listOf("Asking, the cursor, full ADB") }.joinToString(" \u00b7 "),
                tint = signal.text2,
                onClick = { open(AccessGroup.MORE) },
                onClickLabel = "Open more choices",
            )
        }
    }
}

private fun statusOf(group: AccessGroup, enabled: Set<Capability>): String {
    val on = group.capabilities.count { it in enabled }
    return when (on) {
        0 -> "Off"
        group.capabilities.size -> "All on"
        else -> "$on of ${group.capabilities.size} on"
    }
}

/** Top of a group page: back, name, ⓘ, and a card with the group's one switch. */
@Composable
private fun GroupHeader(group: AccessGroup, enabled: Set<Capability>, onBack: () -> Unit, onToggleGroup: (Boolean) -> Unit) {
    val signal = LocalSignal.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(LatchIcons.Back, contentDescription = "Back to Access", tint = signal.text, modifier = Modifier.size(20.dp))
        }
        Text(
            group.title,
            style = MaterialTheme.typography.titleLarge,
            color = signal.text,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp).semantics { heading() },
        )
        FeatureInfoButton(group.title, group.intro, featuresOf(group))
    }
    // One switch for the whole page, when the page has one; the ⓘ above explains it.
    if (group.capabilities.isNotEmpty()) {
        val on = group.capabilities.count { it in enabled }
        Card {
            ListRow(
                group.icon,
                if (on > 0) "On" else "Off",
                when (group) {
                    AccessGroup.FILES -> if (on > 0) "The AI may use the places below" else "The AI can't see or save files"
                    AccessGroup.ADB -> if (on > 0) "Any ADB command, after pairing below" else "Off until you turn it on"
                    else -> statusOf(group, enabled)
                },
                tint = signal.hueOf(group),
                trailing = {
                    LatchSwitch(
                        checked = on > 0,
                        onCheckedChange = onToggleGroup,
                        modifier = Modifier.semantics { contentDescription = group.title },
                    )
                },
            )
        }
    }
}

/** Every capability of [group] with its own switch; tap a row for what it sees and does. */
@Composable
private fun CapabilityList(group: AccessGroup, enabled: Set<Capability>, onToggle: (Capability, Boolean) -> Unit, includeDeviceInfo: Boolean = false) {
    val signal = LocalSignal.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = if (includeDeviceInfo) listOf(Capability.DEVICE_INFO) + group.capabilities else group.capabilities
    SectionCaption("One by one")
    Card {
        rows.forEachIndexed { index, capability ->
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
                    Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill("${copy.risk.replaceFirstChar { it.uppercase() }} risk", riskColor(copy.risk))
                    Text("Sees: ${copy.sees}", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                    Text("Does: ${copy.does}", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
            }
        }
    }
}

/**
 * The folders and photos the file switches reach (ADR-026): any number of
 * folders the owner picks with Android's folder picker, and photos behind
 * Latch's own switch as well as Android's permission. The AI never sees
 * anything else.
 */
@Composable
private fun FilePlaces(
    folders: List<String>,
    photosAllowed: Boolean,
    onPickFolder: () -> Unit,
    onForgetFolder: (Int) -> Unit,
    onAllowPhotos: () -> Unit,
) {
    val signal = LocalSignal.current
    SectionCaption("Folders")
    Card {
        folders.forEachIndexed { index, name ->
            if (index > 0) RowDivider()
            ListRow(
                LatchIcons.Folder,
                name,
                "Read, save, rename, delete",
                tint = signal.accent,
                trailing = {
                    TextButton(
                        onClick = { onForgetFolder(index) },
                        modifier = Modifier.semantics { contentDescription = "Stop sharing the folder $name with the AI" },
                    ) { Text("Remove") }
                },
            )
        }
        if (folders.isNotEmpty()) RowDivider()
        ListRow(
            LatchIcons.Plus,
            if (folders.isEmpty()) "Add a folder for the AI" else "Add another folder",
            "It never sees folders you don't add",
            tint = signal.accent,
            onClick = onPickFolder,
            onClickLabel = "Add a folder",
        )
    }
    SectionCaption("Photos")
    Card {
        // Android's own permission is the one switch: all photos, some, or none.
        ListRow(
            LatchIcons.Photo,
            if (photosAllowed) "Photos allowed" else "Photos not allowed",
            if (photosAllowed) "Tap to change which ones" else "Tap to allow all, or only some",
            tint = if (photosAllowed) signal.success else signal.text2,
            onClick = onAllowPhotos,
            onClickLabel = "Choose which photos the AI can see",
        )
    }
}

/** ADR-041: the owner's switch for whole files over the same Wi-Fi; off by default. */
@Composable
private fun FastTransfers(on: Boolean, onChange: (Boolean) -> Unit) {
    val signal = LocalSignal.current
    SectionCaption("Big files")
    Card {
        ListRow(
            LatchIcons.Wifi,
            "Faster on the same Wi-Fi",
            if (on) "Straight to your computer on this Wi-Fi, encrypted" else "Off: files go through your relay, encrypted",
            tint = if (on) signal.accent else signal.text2,
            trailing = {
                LatchSwitch(
                    checked = on,
                    onCheckedChange = onChange,
                    modifier = Modifier.semantics { contentDescription = "Fast transfers on the same Wi-Fi" },
                )
            },
        )
    }
}

@Composable
private fun AdbPairing(
    endpoint: String?,
    available: Boolean,
    onPair: suspend (code: String, host: String?, port: Int?) -> String,
    onUnpair: () -> Unit,
    onPairFromNotification: () -> Boolean,
    fixedPort: Boolean,
    onFixedPort: suspend (Boolean) -> String,
) {
    val signal = LocalSignal.current
    SectionCaption("Wireless debugging")
    if (!available) {
        Card { ListRow(LatchIcons.Key, "Not available on this device", "Full ADB needs this build's wireless-debugging support.", tint = signal.text2) }
        return
    }
    if (endpoint != null) {
        Card {
            ListRow(
                LatchIcons.ShieldCheck, "Paired over Wi-Fi",
                "This phone is paired for full ADB access. Turn it off to revoke.",
                tint = signal.accent,
                trailing = { TextButton(onClick = onUnpair) { Text("Turn off") } },
            )
        }
        // R29 (b): an opt-in, with its risk said where it is switched on.
        var portStatus by rememberSaveable { mutableStateOf<String?>(null) }
        var portBusy by remember { mutableStateOf(false) }
        val portScope = rememberCoroutineScope()
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListRow(
                    LatchIcons.Key,
                    "Keep working on mobile data",
                    if (fixedPort) {
                        "On until this phone restarts: full ADB keeps working without Wi-Fi."
                    } else {
                        "Off: full ADB needs Wi-Fi, because Android's wireless debugging does."
                    },
                    tint = signal.warning,
                    trailing = {
                        LatchSwitch(
                            checked = fixedPort,
                            onCheckedChange = { on ->
                                if (!portBusy) {
                                    portBusy = true
                                    portStatus = if (on) "Opening…" else "Closing…"
                                    portScope.launch {
                                        portStatus = try {
                                            onFixedPort(on)
                                        } finally {
                                            portBusy = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "Keep full ADB working on mobile data" },
                        )
                    },
                )
                Text(
                    "This asks Android to also accept ADB on port 5555 of this phone, as \"adb tcpip 5555\" does, " +
                        "until it restarts. Latch reaches it on the phone itself. While it is open, a device on the " +
                        "same network that can reach this phone, such as one on public Wi-Fi, can try ADB too: it " +
                        "gets in only with a key you allowed, and an unknown key makes Android ask you. Mobile " +
                        "networks usually do not let others reach your phone at all. Switch on while Wi-Fi works; " +
                        "turning it off closes the port at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = signal.text2,
                )
                portStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = signal.text2) }
            }
        }
        return
    }
    var code by rememberSaveable { mutableStateOf("") }
    var hostPort by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saveable: after a rotation the pairing coroutine is gone, so the button must be usable again.
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var noticeStatus by rememberSaveable { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Easiest: tap below. Latch opens Developer options and puts a box in your notifications. Open " +
                    "Wireless debugging → Pair device with a code, then pull down the notifications and type the " +
                    "6 digits into Latch's box. The code stays on screen the whole time.",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
            )
            noticeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = signal.text2) }
            Button(
                onClick = {
                    noticeStatus = if (onPairFromNotification()) {
                        null
                    } else {
                        "Latch cannot show notifications. Allow them in Android Settings → Apps → Latch → Notifications, or use the form below."
                    }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pair from a notification" },
            ) { Text("Pair from a notification") }
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Or open Latch and Settings side by side (split screen). In Settings open Developer options → Wireless " +
                    "debugging → Pair device with a code, and keep that code on screen: Android closes it when you leave. " +
                    "Type the 6-digit code here. Leave the address blank to find the phone automatically, or enter the " +
                    "pairing address it shows (host:port).",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hostPort, onValueChange = { hostPort = it }, label = { Text("Pairing address (optional)") },
                placeholder = { Text("192.168.1.20:37123") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = signal.text2) }
            Button(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true
                    status = "Pairing…"
                    val host = hostPort.substringBeforeLast(':', "").ifBlank { null }
                    val port = if (':' in hostPort) hostPort.substringAfterLast(':').toIntOrNull() else null
                    scope.launch {
                        try {
                            status = onPair(code, host, port)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = "Pairing failed: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pair full ADB access" },
            ) { Text(if (busy) "Pairing…" else "Pair") }
        }
    }
}

@Composable
private fun AiAccessBody(
    auto: AutoMode,
    onAuto: (AutoMode) -> Unit,
    approveEveryAction: Boolean,
    onApproveEveryAction: (Boolean) -> Unit,
    remoteApprovals: Boolean,
    onRemoteApprovals: (Boolean) -> Unit,
    saved: List<SavedApprovalRow>,
    onRemoveSaved: (String) -> Unit,
    onRemoveAllSaved: () -> Unit,
    trustCritical: Boolean = false,
    onTrustCritical: (Boolean) -> Unit = {},
) {
    val signal = LocalSignal.current
    var confirmTrust by remember { mutableStateOf(false) }
    AutoModeCard(auto, onAuto)
    SectionCaption("When Latch asks you")
    Card {
        ListRow(
            LatchIcons.ShieldCheck,
            "Ask me before every action",
            "You OK every tap and every word",
            tint = signal.warning,
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
            "Answer in your AI chat too",
            "Not only on this phone",
            tint = signal.mint,
            trailing = {
                LatchSwitch(
                    checked = remoteApprovals,
                    onCheckedChange = onRemoteApprovals,
                    modifier = Modifier.semantics { contentDescription = "Answer Latch's questions from your AI chat too" },
                )
            },
        )
        RowDivider()
        ListRow(
            LatchIcons.Warning,
            "Skip asking for payments",
            if (trustCritical) "On: they don't ask in switched-on apps" else "Off: they always ask you",
            tint = if (trustCritical) signal.danger else signal.warning,
            trailing = {
                LatchSwitch(
                    checked = trustCritical,
                    onCheckedChange = { on -> if (on) confirmTrust = true else onTrustCritical(false) },
                    modifier = Modifier.semantics { contentDescription = "Also allow payments and permissions without asking" },
                )
            },
        )
    }

    if (confirmTrust) {
        AlertDialog(
            onDismissRequest = { confirmTrust = false },
            title = { Text("Never ask, even for money?") },
            text = {
                Text(
                    "In apps you switched on, the AI will pay, transfer, buy, install apps, answer Android permission pop-ups, and delete accounts without asking you. " +
                        "Text on a screen, like a message or a website, can trick an AI into doing these. " +
                        "Latch still never types passwords, PINs, or one-time codes, so payments that need your PIN still need you. " +
                        "You can switch this off at any time; Stop ends everything at once.",
                )
            },
            confirmButton = { TextButton(onClick = { onTrustCritical(true); confirmTrust = false }) { Text("Allow without asking", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmTrust = false }) { Text("Keep asking") } },
        )
    }

    // Only when there is something to undo: an empty list is one more thing to read.
    if (saved.isEmpty()) return
    SectionCaption("Always allowed")
    Card {
        run {
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
            TextButton(onClick = onRemoveAllSaved, modifier = Modifier.padding(start = 56.dp)) { Text("Remove all") }
        }
    }
}

/** Brightness also needs Android's own "Modify system settings" for Latch; this row opens it. */
@Composable
private fun BrightnessPermission(wanted: Boolean) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val allowed = remember(resumes) { android.provider.Settings.System.canWrite(context) }
    if (!wanted || allowed) return
    val signal = LocalSignal.current
    Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
        ListRow(
            LatchIcons.Warning,
            "Brightness and rotation",
            "Tap to allow it in Android",
            tint = signal.warning,
            onClick = { context.startActivity(PhoneControls.writeSettingsIntent(context)) },
            onClickLabel = "Open Modify system settings",
        )
    }
}

/** The ringer and Do Not Disturb also need Android's "Do Not Disturb access" for Latch; this row opens it. */
@Composable
private fun DoNotDisturbPermission(wanted: Boolean) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val allowed = remember(resumes) { PhoneControls.canChangeDoNotDisturb(context) }
    if (!wanted || allowed) return
    val signal = LocalSignal.current
    Card {
        ListRow(
            LatchIcons.Bell,
            "Ringer and Do Not Disturb",
            "Optional. Tap to allow it in Android",
            tint = signal.accent,
            onClick = { context.startActivity(PhoneControls.doNotDisturbAccessIntent()) },
            onClickLabel = "Open Do Not Disturb access",
        )
    }
}


/** One "always allow" answer, ready to show. [action] quotes untrusted app text. */
data class SavedApprovalRow(val key: String, val action: String, val app: String)

/**
 * Auto mode (ADR-024): every app and every action without questions. It
 * only turns on after the owner reads what it means and chooses how long.
 */
@Composable
private fun AutoModeCard(auto: AutoMode, onAuto: (AutoMode) -> Unit) {
    val signal = LocalSignal.current
    var asking by remember { mutableStateOf(false) }
    val on = auto != AutoMode.OFF
    Card(color = if (on) signal.accent.copy(alpha = if (signal.dark) 0.22f else 0.12f) else null) {
        ListRow(
            LatchIcons.Sparkle,
            "Auto mode",
            when (auto) {
                AutoMode.OFF -> "Off. When on, nothing asks, not even payments"
                AutoMode.SESSION -> "On until this session ends: nothing asks"
                AutoMode.ALWAYS -> "On until you turn it off: nothing asks"
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
                    Text("• send messages, post, and delete\n• pay, buy, and transfer money\n• install apps and answer Android's permission pop-ups")
                    Text("Latch still never types passwords, PINs, or one-time codes. The red Stop button ends everything at once, and every action is listed in History.")
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
