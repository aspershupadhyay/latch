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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.session.ApprovalChoice
import io.github.aspershupadhyay.latch.session.ApprovalKind
import io.github.aspershupadhyay.latch.ui.theme.Cream
import io.github.aspershupadhyay.latch.ui.theme.Ember
import io.github.aspershupadhyay.latch.ui.theme.HeroActive
import io.github.aspershupadhyay.latch.ui.theme.HeroAttention
import io.github.aspershupadhyay.latch.ui.theme.HeroIdle
import io.github.aspershupadhyay.latch.ui.theme.HeroStopped
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val signal = LocalSignal.current
    val motion = !state.reducedMotion
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            LatchLogo(42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.headlineSmall, color = signal.text)
                Text(state.phoneName, style = MaterialTheme.typography.labelMedium, color = signal.text2)
            }
            Pill(
                if (state.encrypted) "Private" else "Not private",
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

        AnimatedVisibility(
            visible = state.outdatedGateway != null,
            enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
        ) {
            Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                ListRow(
                    icon = LatchIcons.Warning,
                    title = "Update your relay",
                    subtitle = "Your relay runs an older version, so your AI can't use the newest, faster tools. Tap for the steps.",
                    tint = signal.warning,
                    onClick = actions.openGatewayUpdateHelp,
                    onClickLabel = "Show how to update the relay",
                )
            }
        }

        AnimatedVisibility(
            visible = state.update.shownOnHome(),
            enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
        ) {
            UpdateCard(state.update, state.reducedMotion, actions.installUpdate)
        }

        AnimatedVisibility(
            visible = state.signInRequests.isNotEmpty(),
            enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
        ) {
            state.signInRequests.firstOrNull()?.let { request ->
                Card(color = signal.accent.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                    ListRow(
                        icon = LatchIcons.Sparkle,
                        title = "${request.clientName} wants to connect",
                        subtitle = "Code ${request.match} · tap to review",
                        tint = signal.accent,
                        onClick = actions.reviewSignIns,
                        onClickLabel = "Review sign-in request",
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !state.accessibilityOn,
            enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
        ) {
            Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                ListRow(
                    icon = LatchIcons.Person,
                    title = "One step left: screen access",
                    subtitle = "Latch needs it to see the screen and tap for the AI. Tap to turn it on.",
                    tint = signal.warning,
                    onClick = actions.openSetup,
                    onClickLabel = "Finish setup",
                )
            }
        }

        BentoRow {
            Tile(
                Modifier.weight(1f),
                color = signal.clay,
                onClick = actions.goCapabilities,
                onClickLabel = "Change what the AI can do",
                minHeight = 168.dp,
            ) {
                TileHead(LatchIcons.ShieldCheck, "Access", signal.accent)
                Spacer(Modifier.height(10.dp))
                BigNumber("${state.enabled.size}")
                Push()
                TileNote(
                    if (state.enabled.isEmpty()) "Nothing allowed yet. Tap to choose." else state.enabled.joinToString(" \u00b7 ") { shortName(it) },
                    color = signal.onPastel.copy(alpha = 0.72f),
                    maxLines = 2,
                )
            }
            Tile(
                Modifier.weight(1f),
                color = signal.pool,
                onClick = actions.goActivity,
                onClickLabel = "Open activity",
                minHeight = 168.dp,
            ) {
                TileHead(LatchIcons.Pulse, "Activity", signal.accent2)
                Spacer(Modifier.height(10.dp))
                BigNumber("${state.activityCount}")
                Push()
                TileNote(
                    state.lastActivity?.let { "${it.summary} \u00b7 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.atMs))}" } ?: "Nothing yet",
                    color = signal.onPastel.copy(alpha = 0.72f),
                    maxLines = 2,
                )
            }
        }
        Tile(color = signal.butter, onClick = actions.goConnect, onClickLabel = "Connect an AI app", minHeight = 0.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(LatchIcons.Plug, signal.warning, size = 42.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Connect your AI", style = MaterialTheme.typography.titleMedium, color = signal.onPastel)
                    Text(
                        if (state.isOwner) "Claude, ChatGPT, or another AI app" else "Ask the relay's owner to connect your AI",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.onPastel.copy(alpha = 0.72f),
                    )
                }
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(signal.accent), contentAlignment = Alignment.Center) {
                    Icon(LatchIcons.ChevronRight, contentDescription = null, tint = signal.onInk, modifier = Modifier.size(18.dp))
                }
            }
        }
        Card {
            ListRow(
                icon = LatchIcons.Cloud,
                title = "Relay",
                subtitle = state.gatewayHost + if (state.isOwner) " \u00b7 yours" else "",
                tint = signal.accent2,
            )
        }
    }
}

/** A square glyph chip in the tile's hue and a mono uppercase label at the top of a tinted tile. */
@Composable
private fun TileHead(icon: ImageVector, label: String, tint: Color) {
    val signal = LocalSignal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        TileLabel(label, color = signal.onPastel.copy(alpha = 0.8f))
    }
}

@Composable
private fun BigNumber(text: String) {
    Text(text, style = MaterialTheme.typography.displayLarge, color = LocalSignal.current.onPastel, maxLines = 1)
}

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late"
}

@Composable
private fun HeroCard(state: HomeState, actions: HomeActions) {
    val motion = !state.reducedMotion
    val (start, end) = when {
        state.pending != null -> HeroAttention
        state.phase == Phase.ACTIVE -> HeroActive
        state.phase == Phase.REVOKED || state.phase == Phase.FAILED -> HeroStopped
        else -> HeroIdle
    }
    val spec = tween<Color>(if (motion) 600 else 0)
    val from by animateColorAsState(start, spec, label = "heroStart")
    val to by animateColorAsState(end, spec, label = "heroEnd")
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
            .drawBehind { heroDecoration(live) }
            .border(1.dp, Color.White.copy(alpha = if (live) 0.14f else 0.09f), HeroShape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusOrb(orb, state.headline, state.reducedMotion)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                AnimatedContent(
                    targetState = state.headline,
                    transitionSpec = { if (motion) fadeIn(tween(250)) togetherWith fadeOut(tween(150)) else EnterTransition.None togetherWith ExitTransition.None },
                    label = "headline",
                ) { Text(it, style = MaterialTheme.typography.headlineSmall, color = Color.White) }
                Text(state.detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        state.minutesLeft?.let { left ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LatchIcons.Clock, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("$left min left \u00b7 stops by itself", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        if (live) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                IconButtonLarge("Stop", LatchIcons.Stop, actions.stop, Modifier.weight(1.3f), container = LocalSignal.current.danger, content = LocalSignal.current.onInk)
                if (state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED) {
                    val paused = state.phase == Phase.PAUSED
                    IconButtonLarge(
                        if (paused) "Resume" else "Pause",
                        if (paused) LatchIcons.Play else LatchIcons.Pause,
                        { actions.setPaused(!paused) },
                        Modifier.weight(1f),
                        container = Color.White.copy(alpha = 0.08f),
                        content = Color.White,
                        outline = Color.White.copy(alpha = 0.22f),
                    )
                }
            }
        } else {
            Text("SESSION LENGTH", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            // A segmented control: equal widths, so no label ever wraps.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (m, label) ->
                    val selected = state.sessionMinutes == m
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(5.dp))
                            .background(if (selected) Cream else Color.Transparent)
                            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClickLabel = "Session length $label") { actions.setMinutes(m) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color(0xFF0A0A0A) else Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
            IconButtonLarge(
                if (state.accessibilityOn) "Start session" else "Finish setup to start",
                if (state.accessibilityOn) LatchIcons.Play else LatchIcons.ShieldCheck,
                if (state.accessibilityOn) actions.start else actions.openSetup,
                Modifier.fillMaxWidth(),
                container = LocalSignal.current.accent,
                content = LocalSignal.current.onInk,
            )
        }
    }
}

private val HeroShape = RoundedCornerShape(12.dp)

/** A faint engineering grid that fades down the card, and ember corner ticks: the hero's texture, as on the website. */
private fun DrawScope.heroDecoration(live: Boolean) {
    val step = 22.dp.toPx()
    val line = 1.dp.toPx()
    var x = step
    while (x < size.width) {
        drawLine(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), endY = size.height * 0.8f), Offset(x, 0f), Offset(x, size.height), line)
        x += step
    }
    var y = step
    while (y < size.height * 0.8f) {
        drawLine(Color.White.copy(alpha = 0.05f * (1f - y / (size.height * 0.8f))), Offset(0f, y), Offset(size.width, y), line)
        y += step
    }
    val tick = 12.dp.toPx()
    val inset = 10.dp.toPx()
    val ember = Ember.copy(alpha = if (live) 0.9f else 0.55f)
    drawLine(ember, Offset(inset, inset), Offset(inset + tick, inset), line)
    drawLine(ember, Offset(inset, inset), Offset(inset, inset + tick), line)
    drawLine(ember, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - tick, size.height - inset), line)
    drawLine(ember, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - tick), line)
}

/** A tall square-shouldered button with an icon in front of its label. */
@Composable
fun IconButtonLarge(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, container: Color, content: Color, outline: Color? = null) {
    Button(
        onClick = onClick,
        shape = ButtonShape,
        border = outline?.let { BorderStroke(1.dp, it) },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = 52.dp),
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
}
