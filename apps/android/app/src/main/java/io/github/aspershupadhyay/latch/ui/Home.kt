// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.aspershupadhyay.latch.ui.theme.StopRed
import io.github.aspershupadhyay.latch.ui.theme.HeroStoppedEdge
import io.github.aspershupadhyay.latch.ui.theme.HeroAttentionEdge
import io.github.aspershupadhyay.latch.ui.theme.HeroLiveEdge
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.session.ApprovalChoice
import io.github.aspershupadhyay.latch.session.ApprovalKind
import io.github.aspershupadhyay.latch.ui.theme.Cream
import io.github.aspershupadhyay.latch.ui.theme.HeroActive
import io.github.aspershupadhyay.latch.ui.theme.HeroAttention
import io.github.aspershupadhyay.latch.ui.theme.HeroIdle
import io.github.aspershupadhyay.latch.ui.theme.HeroStopped
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.text.DateFormat
import java.util.Date

/** Something on Home that needs the owner, most urgent first. Only the first shows until the owner asks for the rest. */
private enum class Notice { SCREEN_ACCESS, SIGN_IN, UPDATE, OUTDATED_RELAY, SLOW_RELAY }

private fun HomeState.notices(): List<Notice> = buildList {
    if (!accessibilityOn) add(Notice.SCREEN_ACCESS)
    if (signInRequests.isNotEmpty()) add(Notice.SIGN_IN)
    if (update.shownOnHome()) add(Notice.UPDATE)
    if (outdatedGateway != null) add(Notice.OUTDATED_RELAY)
    if (slowRelayMs != null) add(Notice.SLOW_RELAY)
}

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val signal = LocalSignal.current
    val motion = !state.reducedMotion
    var showAllNotices by rememberSaveable { mutableStateOf(false) }
    val notices = state.notices()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            LatchLogo(40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.headlineSmall, color = signal.text)
                Text(state.phoneName, style = MaterialTheme.typography.bodySmall, color = signal.text2)
            }
            // It says what is checked: the link to the relay is encrypted. Who runs the relay is in Settings.
            Pill(
                if (state.encrypted) "Encrypted" else "Not encrypted",
                if (state.encrypted) signal.success else signal.warning,
                if (state.encrypted) LatchIcons.Lock else LatchIcons.Warning,
            )
        }

        HeroCard(state, actions)

        AnimatedVisibility(
            visible = state.pending != null,
            enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
        ) {
            state.pending?.let { p -> ApprovalCard(p, actions.answer) }
        }

        state.lastSession?.takeIf { state.phase != Phase.ACTIVE && state.phase != Phase.PAUSED }?.let { SessionEndedCard(it, actions) }

        // One place for whatever needs the owner, so the hero and its buttons never move far down.
        if (notices.isNotEmpty()) {
            (if (showAllNotices) notices else notices.take(1)).forEach { NoticeCard(it, state, actions) }
            if (notices.size > 1) {
                androidx.compose.material3.TextButton(
                    onClick = { showAllNotices = !showAllNotices },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (showAllNotices) "Show less" else "${notices.size - 1} more ${if (notices.size == 2) "thing needs" else "things need"} you",
                        style = MaterialTheme.typography.labelLarge,
                        color = signal.text2,
                    )
                }
            }
        }

        BentoRow {
            Tile(
                Modifier.weight(1f),
                onClick = actions.goCapabilities,
                onClickLabel = "Change what the AI can do",
                minHeight = 150.dp,
            ) {
                TileHead(LatchIcons.ShieldCheck, "Allowed", signal.accent)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.enabled.isEmpty()) "Nothing yet" else allowedSentence(state.enabled),
                    style = MaterialTheme.typography.titleMedium,
                    color = signal.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Push()
                TileNote(if (state.enabled.isEmpty()) "Tap to choose" else "Tap to change", maxLines = 1)
            }
            Tile(
                Modifier.weight(1f),
                onClick = actions.goActivity,
                onClickLabel = "Open activity",
                minHeight = 150.dp,
            ) {
                TileHead(LatchIcons.Pulse, "Last action", signal.live)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.lastActivity?.summary ?: "Nothing yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = signal.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Push()
                TileNote(
                    state.lastActivity?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.atMs)) } ?: "See everything it did",
                    maxLines = 1,
                )
            }
        }
        Card {
            ListRow(
                icon = LatchIcons.Plug,
                title = "Connect your AI",
                subtitle = if (state.isOwner) "Claude, ChatGPT, or another AI app" else "Ask the relay's owner to connect your AI",
                tint = signal.accent,
                onClick = actions.goConnect,
                onClickLabel = "Connect an AI app",
            )
        }
        MirrorCard(state.mirror, actions, state.mirrorRemembered)
        Text(
            "Relay: ${state.gatewayHost}" + if (state.isOwner) " (yours)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = signal.text3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/**
 * How the session went, once it ends: people remember an experience by its
 * end, so the end says plainly what happened and that nothing else did.
 */
@Composable
private fun SessionEndedCard(summary: SessionSummary, actions: HomeActions) {
    val signal = LocalSignal.current
    fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
    Card(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(LatchIcons.Check, signal.success, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Session ended", style = MaterialTheme.typography.titleMedium, color = signal.text)
                    Text(n(summary.minutes.toInt(), "minute", "minutes"), style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
                androidx.compose.material3.IconButton(onClick = actions.dismissSummary, modifier = Modifier.size(48.dp)) {
                    Icon(LatchIcons.Close, contentDescription = "Close the summary", tint = signal.text2)
                }
            }
            Text(
                if (summary.actions == 0) {
                    "The AI did nothing on this phone."
                } else {
                    "The AI took ${n(summary.actions, "action", "actions")} in ${n(summary.apps, "app", "apps")}. " +
                        "You answered ${n(summary.approvals, "question", "questions")}" +
                        if (summary.refused > 0) ", and Latch refused ${n(summary.refused, "step", "steps")}." else "."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = signal.text,
                modifier = Modifier.padding(end = 12.dp),
            )
            androidx.compose.material3.TextButton(onClick = actions.goActivity, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("See every step", style = MaterialTheme.typography.labelLarge, color = signal.accent)
            }
        }
    }
}

/** One thing that needs the owner, as a tinted row they can tap. */
@Composable
private fun NoticeCard(notice: Notice, state: HomeState, actions: HomeActions) {
    val signal = LocalSignal.current
    val wash = if (signal.dark) 0.14f else 0.08f
    when (notice) {
        Notice.SCREEN_ACCESS -> Card(color = signal.warning.copy(alpha = wash)) {
            ListRow(
                icon = LatchIcons.Person,
                title = "One step left: screen access",
                subtitle = "So Latch can see the screen and tap for the AI. Tap to turn it on.",
                tint = signal.warning,
                onClick = actions.openSetup,
                onClickLabel = "Finish setup",
            )
        }
        Notice.SIGN_IN -> state.signInRequests.firstOrNull()?.let { request ->
            Card(color = signal.accent.copy(alpha = wash)) {
                ListRow(
                    icon = LatchIcons.Sparkle,
                    title = "${request.clientName} wants to connect",
                    subtitle = "Code ${request.match}. Tap to check it and answer.",
                    tint = signal.accent,
                    onClick = actions.reviewSignIns,
                    onClickLabel = "Review sign-in request",
                )
            }
        }
        Notice.UPDATE -> UpdateCard(state.update, state.reducedMotion, actions.installUpdate)
        Notice.OUTDATED_RELAY -> Card(color = signal.warning.copy(alpha = wash)) {
            ListRow(
                icon = LatchIcons.Warning,
                title = "Update your relay",
                subtitle = "It runs an older version, so your AI misses the newest, faster tools. Tap for the steps.",
                tint = signal.warning,
                onClick = actions.openGatewayUpdateHelp,
                onClickLabel = "Show how to update the relay",
            )
        }
        Notice.SLOW_RELAY -> Card(color = signal.warning.copy(alpha = wash)) {
            ListRow(
                icon = LatchIcons.Warning,
                title = "Your relay is slow",
                subtitle = "Its database is far away (${state.slowRelayMs ?: 0} ms each time). Tap for how to move it closer.",
                tint = signal.warning,
                onClick = actions.openRelaySpeedHelp,
                onClickLabel = "Show how to make the relay faster",
            )
        }
    }
}

/** What the AI may do, in words: "Can read the screen, tap, and type." */
private fun allowedSentence(enabled: List<Capability>): String {
    val words = enabled.map { verbOf(it) }.distinct()
    return "Can " + when (words.size) {
        1 -> words[0]
        2 -> "${words[0]} and ${words[1]}"
        3 -> "${words[0]}, ${words[1]}, and ${words[2]}"
        else -> "${words[0]}, ${words[1]}, and ${words.size - 2} more"
    }
}

private fun verbOf(c: Capability) = when (c) {
    Capability.DEVICE_INFO -> "see phone info"
    Capability.UI_OBSERVE -> "read the screen"
    Capability.SCREEN_CAPTURE -> "take screenshots"
    Capability.INPUT_GESTURE -> "tap and swipe"
    Capability.INPUT_TEXT -> "type"
    Capability.NAV_GLOBAL -> "go back and home"
    Capability.APP_LAUNCH -> "open apps"
    Capability.FILE_READ -> "see files"
    Capability.FILE_WRITE -> "save files"
    Capability.APP_SHARE -> "share"
    Capability.CLIPBOARD_WRITE -> "copy text"
    Capability.ACTIVITY_READ -> "read the log"
    Capability.CONTROL_SOUND, Capability.CONTROL_BRIGHTNESS, Capability.CONTROL_FLASHLIGHT, Capability.CONTROL_PANELS -> "change phone controls"
    Capability.ADB_SHELL -> "use full ADB"
}

/** A glyph chip in the tile's hue and a small label at the top of a tile. */
@Composable
private fun TileHead(icon: ImageVector, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        TileLabel(label)
    }
}

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late"
}

/** Text on the hero card, which is dark in both themes. */
private val OnHero = Color.White
private val HeroInk = Color(0xFF0B0B0E)

@Composable
private fun HeroCard(state: HomeState, actions: HomeActions) {
    val motion = !state.reducedMotion
    val (start, end) = when {
        state.pending != null -> HeroAttention
        state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED -> HeroActive
        state.phase == Phase.REVOKED || state.phase == Phase.FAILED -> HeroStopped
        else -> HeroIdle
    }
    // The edge says the state too: cyan live, amber waiting, red stopped, quiet when ready.
    val edgeTarget = when {
        state.pending != null -> HeroAttentionEdge.copy(alpha = 0.55f)
        state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED -> HeroLiveEdge.copy(alpha = 0.45f)
        state.phase == Phase.REVOKED || state.phase == Phase.FAILED -> HeroStoppedEdge.copy(alpha = 0.5f)
        else -> Color.White.copy(alpha = 0.1f)
    }
    val spec = tween<Color>(if (motion) 600 else 0)
    val from by animateColorAsState(start, spec, label = "heroStart")
    val to by animateColorAsState(end, spec, label = "heroEnd")
    val edge by animateColorAsState(edgeTarget, spec, label = "heroEdge")
    val orb = when (state.phase) {
        Phase.IDLE -> OrbState.IDLE
        Phase.CONNECTING, Phase.RECONNECTING -> OrbState.CONNECTING
        Phase.ACTIVE -> if (state.pending != null) OrbState.ATTENTION else OrbState.ACTIVE
        Phase.PAUSED -> OrbState.PAUSED
        Phase.REVOKED, Phase.FAILED -> OrbState.STOPPED
    }
    val live = state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED || state.phase == Phase.CONNECTING || state.phase == Phase.RECONNECTING

    Column(
        Modifier.fillMaxWidth().clip(HeroShape).background(Brush.linearGradient(listOf(from, to)))
            .drawBehind { heroDecoration() }
            .border(1.5.dp, edge, HeroShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusOrb(orb, state.headline, state.reducedMotion, size = 68.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedContent(
                    targetState = state.headline,
                    transitionSpec = { if (motion) fadeIn(tween(250)) togetherWith fadeOut(tween(150)) else EnterTransition.None togetherWith ExitTransition.None },
                    label = "headline",
                ) { Text(it, style = MaterialTheme.typography.headlineSmall, color = OnHero) }
                Text(state.detail, style = MaterialTheme.typography.bodyMedium, color = OnHero.copy(alpha = 0.85f))
            }
        }
        state.minutesLeft?.let { left ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LatchIcons.Clock, contentDescription = null, tint = OnHero.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${if (left >= 60) "${left / 60} h ${left % 60} min" else "$left min"} left · stops by itself", style = MaterialTheme.typography.bodyMedium, color = OnHero.copy(alpha = 0.85f))
            }
        }
        if (live) {
            // Stop is the biggest thing on the card and the only red on it.
            IconButtonLarge("Stop everything", LatchIcons.Stop, actions.stop, Modifier.fillMaxWidth(), container = StopRed, content = HeroInk, tall = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED) {
                    val paused = state.phase == Phase.PAUSED
                    IconButtonLarge(
                        if (paused) "Resume" else "Pause",
                        if (paused) LatchIcons.Play else LatchIcons.Pause,
                        { actions.setPaused(!paused) },
                        Modifier.weight(1f),
                        container = Color.White.copy(alpha = 0.08f),
                        content = OnHero,
                        outline = Color.White.copy(alpha = 0.22f),
                    )
                }
                // The session still ends by itself; Extend adds the chosen length once more.
                IconButtonLarge(
                    "Add ${SESSION_LENGTHS.firstOrNull { it.first == state.sessionMinutes }?.second ?: "${state.sessionMinutes}m"}",
                    LatchIcons.Clock,
                    actions.extend,
                    Modifier.weight(1f).semantics { contentDescription = "Extend the session by ${spokenLength(state.sessionMinutes)}" },
                    container = Color.White.copy(alpha = 0.08f),
                    content = OnHero,
                    outline = Color.White.copy(alpha = 0.22f),
                )
            }
        } else {
            IconButtonLarge(
                if (state.accessibilityOn) "Start · ${spokenLength(state.sessionMinutes)}" else "Finish setup to start",
                if (state.accessibilityOn) LatchIcons.Play else LatchIcons.ShieldCheck,
                if (state.accessibilityOn) actions.start else actions.openSetup,
                Modifier.fillMaxWidth(),
                container = Cream,
                content = HeroInk,
                tall = true,
            )
            if (state.accessibilityOn) {
                Text("Stops by itself after", style = MaterialTheme.typography.labelMedium, color = OnHero.copy(alpha = 0.7f))
                // A segmented control: equal widths, so no label ever wraps; 48 dp tall for a thumb.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    SESSION_LENGTHS.forEach { (m, label) ->
                        val selected = state.sessionMinutes == m
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (selected) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                                .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClickLabel = "Session length ${spokenLength(m)}") { actions.setMinutes(m) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) HeroInk else OnHero.copy(alpha = 0.8f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val HeroShape = RoundedCornerShape(22.dp)

/** Session lengths the owner can pick, shortest first; short labels so six fit one row. */
private val SESSION_LENGTHS = listOf(15 to "15m", 30 to "30m", 60 to "1h", 120 to "2h", 240 to "4h", 480 to "8h")

/** A session length as a screen reader says it. */
private fun spokenLength(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}

/** A faint grid that fades down the card: the hero's texture, as on the website. */
private fun DrawScope.heroDecoration() {
    val step = 22.dp.toPx()
    val line = 1.dp.toPx()
    var x = step
    while (x < size.width) {
        drawLine(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.Transparent), endY = size.height * 0.7f), Offset(x, 0f), Offset(x, size.height), line)
        x += step
    }
    var y = step
    while (y < size.height * 0.7f) {
        drawLine(Color.White.copy(alpha = 0.04f * (1f - y / (size.height * 0.7f))), Offset(0f, y), Offset(size.width, y), line)
        y += step
    }
}

/** A big button with an icon in front of its label; [tall] for the one main action on a screen. */
@Composable
fun IconButtonLarge(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, container: Color, content: Color, outline: Color? = null, tall: Boolean = false) {
    Button(
        onClick = onClick,
        shape = ButtonShape,
        border = outline?.let { BorderStroke(1.dp, it) },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = if (tall) 58.dp else 52.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

fun shortName(c: Capability) = when (c) {
    Capability.DEVICE_INFO -> "Info"
    Capability.UI_OBSERVE -> "Read screen"
    Capability.SCREEN_CAPTURE -> "Screenshots"
    Capability.INPUT_GESTURE -> "Tap & swipe"
    Capability.INPUT_TEXT -> "Type"
    Capability.NAV_GLOBAL -> "Back/Home"
    Capability.APP_LAUNCH -> "Open apps"
    Capability.FILE_READ -> "See files"
    Capability.FILE_WRITE -> "Save files"
    Capability.APP_SHARE -> "Share"
    Capability.CLIPBOARD_WRITE -> "Clipboard"
    Capability.ACTIVITY_READ -> "Activity log"
    Capability.CONTROL_SOUND -> "Sound"
    Capability.CONTROL_BRIGHTNESS -> "Screen"
    Capability.CONTROL_FLASHLIGHT -> "Flashlight"
    Capability.CONTROL_PANELS -> "Panels"
    Capability.ADB_SHELL -> "Full ADB"
}
