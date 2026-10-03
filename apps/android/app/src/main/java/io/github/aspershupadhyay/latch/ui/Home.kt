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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Latch", style = MaterialTheme.typography.headlineSmall, color = signal.text)
                Text(state.phoneName, style = MaterialTheme.typography.bodyMedium, color = signal.text2)
            }
            Pill(
                if (state.encrypted) "Private connection" else "Not private",
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

        SectionCaption("Your Latch")
        Card {
            ListRow(
                icon = LatchIcons.ShieldCheck,
                title = "What the AI can do",
                subtitle = if (state.enabled.isEmpty()) "Nothing yet. Tap to choose." else state.enabled.joinToString(" \u00b7 ") { shortName(it) },
                tint = signal.accent,
                onClick = actions.goCapabilities,
                onClickLabel = "Change what the AI can do",
            )
            RowDivider()
            ListRow(
                icon = LatchIcons.Plug,
                title = "Connect your AI",
                subtitle = if (state.isOwner) "Claude, ChatGPT, or another AI app" else "Ask the relay's owner to connect your AI",
                tint = signal.accent2,
                onClick = actions.goConnect,
                onClickLabel = "Connect an AI app",
            )
            RowDivider()
            val last = state.lastActivity
            ListRow(
                icon = LatchIcons.Pulse,
                title = "Activity",
                subtitle = last?.let { "${it.summary} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.atMs))}" } ?: "Nothing yet",
                tint = Color(0xFF5E5CE6),
                onClick = actions.goActivity,
                onClickLabel = "Open activity",
            )
            RowDivider()
            ListRow(
                icon = LatchIcons.Cloud,
                title = "Relay",
                subtitle = state.gatewayHost + if (state.isOwner) " \u00b7 yours" else "",
                tint = Color(0xFF8E8E93),
            )
        }
    }
}

@Composable
private fun HeroCard(state: HomeState, actions: HomeActions) {
    val motion = !state.reducedMotion
    val (start, end) = when {
        state.pending != null -> Color(0xFFC2410C) to Color(0xFFEA580C)
        state.phase == Phase.ACTIVE -> Color(0xFF2B2F8F) to Color(0xFF5048E5)
        state.phase == Phase.REVOKED || state.phase == Phase.FAILED -> Color(0xFF7F1D1D) to Color(0xFF9F1239)
        else -> Color(0xFF0F1222) to Color(0xFF232A52)
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
        Modifier.fillMaxWidth().clip(CardShape).background(Brush.linearGradient(listOf(from, to))).padding(20.dp),
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
                IconButtonLarge("Stop", LatchIcons.Stop, actions.stop, Modifier.weight(1.3f), container = Color.White, content = Color(0xFFB91C1C))
                if (state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED) {
                    val paused = state.phase == Phase.PAUSED
                    IconButtonLarge(
                        if (paused) "Resume" else "Pause",
                        if (paused) LatchIcons.Play else LatchIcons.Pause,
                        { actions.setPaused(!paused) },
                        Modifier.weight(1f),
                        container = Color.White.copy(alpha = 0.16f),
                        content = Color.White,
                    )
                }
            }
        } else {
            Text("How long can the AI work?", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            // A segmented control: equal widths, so no label ever wraps.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (m, label) ->
                    val selected = state.sessionMinutes == m
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClickLabel = "Session length $label") { actions.setMinutes(m) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Color(0xFF1C2140) else Color.White,
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
                container = Color.White,
                content = Color(0xFF2B2F8F),
            )
        }
    }
}

/** A tall pill button with an icon in front of its label. */
@Composable
fun IconButtonLarge(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, container: Color, content: Color) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
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
}
