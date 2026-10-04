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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    WORKING(
        "While the AI works", LatchIcons.Phone, emptyList(),
        "What you see on the phone while a session runs. None of this gives the AI anything.",
    ),
}

private fun Signal.colorOf(group: AccessGroup) = when (group) {
    AccessGroup.AI -> clay
    AccessGroup.SCREEN -> sage
    AccessGroup.FILES -> butter
    AccessGroup.SHARING -> pool
    AccessGroup.WORKING -> surface
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
    AccessGroup.WORKING -> listOf(
        Feature(LatchIcons.Tap, "Show where the AI taps", "A dot follows each tap. The AI never sees it."),
        Feature(LatchIcons.Phone, "Keep the screen on", "The lock screen won't cut a task off. Uses more battery."),
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
    folders: List<String> = emptyList(),
    photosAllowed: Boolean = false,
    onPickFolder: () -> Unit = {},
    onForgetFolder: (Int) -> Unit = {},
    onAllowPhotos: () -> Unit = {},
    photosOn: Boolean = true,
    onPhotosOn: (Boolean) -> Unit = {},
    /** The open group page, hoisted so it survives a trip to the Apps list. */
    openGroup: AccessGroup? = null,
    onOpenGroup: ((AccessGroup?) -> Unit)? = null,
) {
    var localGroup by rememberSaveable { mutableStateOf<AccessGroup?>(null) }
    val group = if (onOpenGroup != null) openGroup else localGroup
    val setGroup: (AccessGroup?) -> Unit = onOpenGroup ?: { localGroup = it }
    BackHandler(enabled = group != null) { setGroup(null) }

    // Turning a group on switches on all of its capabilities; off switches all of them off.
    val toggleGroup: (AccessGroup, Boolean) -> Unit = { g, on -> g.capabilities.forEach { onToggle(it, on) } }

    AnimatedContent(
        targetState = group,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "access",
    ) { current ->
        if (current == null) {
            AccessOverview(
                enabled, accessibilityOn, auto, onAuto, appsOn, approveEveryAction, showCursor, keepAwake,
                onOpenSetup, toggleGroup, open = setGroup,
            )
        } else {
            ScreenColumn {
                GroupHeader(current, enabled, onBack = { setGroup(null) }, onToggleGroup = { toggleGroup(current, it) })
                when (current) {
                    AccessGroup.AI -> AiAccessBody(
                        auto, appsOn, onOpenApps, approveEveryAction, onApproveEveryAction, remoteApprovals, onRemoteApprovals,
                        saved, onRemoveSaved, onRemoveAllSaved,
                    )
                    AccessGroup.SCREEN -> CapabilityList(current, enabled, onToggle, includeDeviceInfo = true)
                    AccessGroup.FILES -> {
                        CapabilityList(current, enabled, onToggle)
                        FilePlaces(folders, photosAllowed, onPickFolder, onForgetFolder, onAllowPhotos, photosOn, onPhotosOn)
                    }
                    AccessGroup.SHARING -> CapabilityList(current, enabled, onToggle)
                    AccessGroup.WORKING -> WorkingBody(showCursor, keepAwake, onShowCursor, onKeepAwake)
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
    onAuto: (AutoMode) -> Unit,
    appsOn: Int,
    approveEveryAction: Boolean,
    showCursor: Boolean,
    keepAwake: Boolean,
    onOpenSetup: () -> Unit,
    toggleGroup: (AccessGroup, Boolean) -> Unit,
    open: (AccessGroup) -> Unit,
) {
    val signal = LocalSignal.current
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

        BentoRow {
            GroupTile(
                AccessGroup.AI,
                status = when {
                    auto != AutoMode.OFF -> "Auto mode: every app"
                    appsOn == 0 -> "No apps yet"
                    else -> "$appsOn app${if (appsOn == 1) "" else "s"} on"
                } + if (approveEveryAction) " · asks every time" else "",
                enabled = enabled,
                onToggle = null,
                onOpen = { open(AccessGroup.AI) },
            )
            GroupTile(AccessGroup.SCREEN, statusOf(AccessGroup.SCREEN, enabled), enabled, { toggleGroup(AccessGroup.SCREEN, it) }) { open(AccessGroup.SCREEN) }
        }
        BentoRow {
            GroupTile(AccessGroup.FILES, statusOf(AccessGroup.FILES, enabled), enabled, { toggleGroup(AccessGroup.FILES, it) }) { open(AccessGroup.FILES) }
            GroupTile(AccessGroup.SHARING, statusOf(AccessGroup.SHARING, enabled), enabled, { toggleGroup(AccessGroup.SHARING, it) }) { open(AccessGroup.SHARING) }
        }

        Card {
            ListRow(
                AccessGroup.WORKING.icon,
                AccessGroup.WORKING.title,
                listOf(if (showCursor) "Cursor shown" else "Cursor hidden", if (keepAwake) "screen stays on" else "screen may lock").joinToString(" · "),
                tint = signal.accent,
                onClick = { open(AccessGroup.WORKING) },
                onClickLabel = "Open ${AccessGroup.WORKING.title}",
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

/** A pastel card for one group: icon, count ring, name, state, ⓘ and its switch. */
@Composable
private fun RowScope.GroupTile(
    group: AccessGroup,
    status: String,
    enabled: Set<Capability>,
    onToggle: ((Boolean) -> Unit)?,
    onOpen: () -> Unit,
) {
    val signal = LocalSignal.current
    val ink = signal.onPastel
    val on = group.capabilities.count { it in enabled }
    Tile(
        modifier = Modifier.weight(1f),
        color = signal.colorOf(group),
        onClick = onOpen,
        onClickLabel = "Open ${group.title}",
        minHeight = 184.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(group.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            if (group.capabilities.isNotEmpty()) CountRing(on, group.capabilities.size, ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(group.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(status, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Push()
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeatureInfoButton(group.title, group.intro, featuresOf(group), onPastel = true)
            Spacer(Modifier.weight(1f))
            if (onToggle != null) {
                LatchSwitch(
                    checked = on > 0,
                    onCheckedChange = onToggle,
                    onPastel = true,
                    modifier = Modifier.semantics { contentDescription = "${group.title}, all switches" },
                )
            } else {
                Box(Modifier.size(36.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
                    Icon(LatchIcons.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "2/3" inside a ring that fills as more of the group is on. */
@Composable
private fun CountRing(on: Int, total: Int, color: Color) {
    Box(Modifier.size(40.dp).semantics { contentDescription = "$on of $total on" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(color.copy(alpha = 0.16f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (on > 0) drawArc(color, -90f, 360f * on / total, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$on/$total", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = color)
    }
}

/** Top of a group page: back, name, ⓘ, and a pastel card with the group's one switch. */
@Composable
private fun GroupHeader(group: AccessGroup, enabled: Set<Capability>, onBack: () -> Unit, onToggleGroup: (Boolean) -> Unit) {
    val signal = LocalSignal.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp).clip(CircleShape).background(signal.surface)) {
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
    val color = signal.colorOf(group)
    val pastel = group != AccessGroup.WORKING
    val ink = if (pastel) signal.onPastel else signal.text
    Card(color = color) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (pastel) Color.White else signal.surface2), contentAlignment = Alignment.Center) {
                Icon(group.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(group.intro, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.82f), modifier = Modifier.weight(1f))
        }
        if (group.capabilities.isNotEmpty()) {
            val on = group.capabilities.count { it in enabled }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.55f)).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (on > 0) "On" else "Off", style = MaterialTheme.typography.titleMedium, color = ink)
                    Text(statusOf(group, enabled), style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.72f))
                }
                LatchSwitch(
                    checked = on > 0,
                    onCheckedChange = onToggleGroup,
                    onPastel = true,
                    modifier = Modifier.semantics { contentDescription = "${group.title}, all switches" },
                )
            }
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
    photosOn: Boolean,
    onPhotosOn: (Boolean) -> Unit,
) {
    val signal = LocalSignal.current
    SectionCaption("Folders")
    Card {
        folders.forEachIndexed { index, name ->
            if (index > 0) RowDivider()
            ListRow(
                LatchIcons.Folder,
                name,
                "The AI may read, save, rename, and delete files in this folder and its subfolders.",
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
            if (folders.isEmpty()) "For example Documents/AI. The AI never sees folders you don't add." else "The AI never sees folders you don't add.",
            tint = signal.accent,
            onClick = onPickFolder,
            onClickLabel = "Add a folder",
        )
    }
    SectionCaption("Photos")
    Card {
        ListRow(
            LatchIcons.Photo,
            "Let the AI see photos",
            if (photosOn) "On: the AI can see and save the photos and videos Android allows below." else "Off: the AI can't list, open, or save photos.",
            tint = if (photosOn) signal.accent else signal.text2,
            trailing = {
                LatchSwitch(
                    checked = photosOn,
                    onCheckedChange = onPhotosOn,
                    modifier = Modifier.semantics { contentDescription = "Let the AI see photos" },
                )
            },
        )
        if (photosOn) {
            RowDivider()
            ListRow(
                LatchIcons.ShieldCheck,
                if (photosAllowed) "Android: photos allowed" else "Android: photos not allowed",
                if (photosAllowed) "Tap to change which photos and videos." else "Allow all photos, or only the ones you choose. Saving new pictures works without this.",
                tint = if (photosAllowed) signal.success else signal.text2,
                onClick = onAllowPhotos,
                onClickLabel = "Choose which photos the AI can see",
            )
        }
    }
}

@Composable
private fun AiAccessBody(
    auto: AutoMode,
    appsOn: Int,
    onOpenApps: () -> Unit,
    approveEveryAction: Boolean,
    onApproveEveryAction: (Boolean) -> Unit,
    remoteApprovals: Boolean,
    onRemoteApprovals: (Boolean) -> Unit,
    saved: List<SavedApprovalRow>,
    onRemoveSaved: (String) -> Unit,
    onRemoveAllSaved: () -> Unit,
) {
    val signal = LocalSignal.current
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
            "Answer from your AI chat too",
            if (remoteApprovals) {
                "On: when Latch asks you something, you can answer on this phone or in your AI chat."
            } else {
                "Off: only this phone can answer Latch's questions."
            },
            tint = signal.mint,
            trailing = {
                LatchSwitch(
                    checked = remoteApprovals,
                    onCheckedChange = onRemoteApprovals,
                    modifier = Modifier.semantics { contentDescription = "Answer Latch's questions from your AI chat too" },
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
            TextButton(onClick = onRemoveAllSaved, modifier = Modifier.padding(start = 56.dp)) { Text("Remove all") }
        }
    }
}

@Composable
private fun WorkingBody(showCursor: Boolean, keepAwake: Boolean, onShowCursor: (Boolean) -> Unit, onKeepAwake: (Boolean) -> Unit) {
    val signal = LocalSignal.current
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
                    Text("• send messages, post, and delete\n• pay, buy, and transfer money\n• install apps and answer Android's permission pop-ups")
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
