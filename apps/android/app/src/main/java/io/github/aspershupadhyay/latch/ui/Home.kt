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
import androidx.compose.foundation.layout.Arrangement
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
            state.pending?.let { p ->
                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(signal.attentionBrush).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LatchIcons.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Approval needed · ${p.risk} risk", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                    }
                    Text(p.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(p.detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Deny", { actions.answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = Color.White)
                        PrimaryButton("Allow once", { actions.answer(p.nonce, ApprovalChoice.ONCE) }, Modifier.weight(1f), color = Color.White, contentColor = Color(0xFF7C2D12))
                    }
                    if (p.rememberable) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("This session", { actions.answer(p.nonce, ApprovalChoice.SESSION) }, Modifier.weight(1f), contentColor = Color.White)
                            SecondaryButton("Always in ${p.appName ?: "this app"}", { actions.answer(p.nonce, ApprovalChoice.ALWAYS) }, Modifier.weight(1f), contentColor = Color.White)
                        }
                    } else {
                        Text("Asked every time.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.outdatedGateway != null,
            enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None,
        ) {
            Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                ListRow(
                    icon = LatchIcons.Warning,
                    title = "Update your gateway",
                    subtitle = "It runs an older version (protocol ${state.outdatedGateway}), so your AI can't use the newest, faster tools. Tap for the steps.",
                    tint = signal.warning,
                    onClick = actions.openGatewayUpdateHelp,
                    onClickLabel = "Show how to update the gateway",
                )
            }
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
                    title = "Finish setup",
                    subtitle = "Turn on screen access so an AI can see and tap. Until then it can do nothing.",
                    tint = signal.warning,
                    onClick = actions.openSetup,
                    onClickLabel = "Finish setup",
                )
            }
        }

        SectionCaption("Overview")
        Card {
            ListRow(
                icon = LatchIcons.ShieldCheck,
                title = "AI can",
                subtitle = if (state.enabled.isEmpty()) "Nothing yet. Choose what to allow." else state.enabled.joinToString(" · ") { shortName(it) },
                tint = signal.accent,
                onClick = actions.goCapabilities,
                onClickLabel = "Change what the AI can do",
            )
            RowDivider()
            ListRow(
                icon = LatchIcons.Plug,
                title = "Connect an AI app",
                subtitle = if (state.isOwner) "Claude, ChatGPT, Cursor, or any MCP app" else "Ask the gateway owner for an AI key",
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
                tint = signal.mint,
                onClick = actions.goActivity,
                onClickLabel = "Open activity",
            )
            RowDivider()
            ListRow(
                icon = LatchIcons.Cloud,
                title = "Gateway",
                subtitle = state.gatewayHost + if (state.isOwner) " · you own it" else "",
                tint = signal.text2,
            )
        }
    }
}

@Composable
private fun HeroCard(state: HomeState, actions: HomeActions) {
    val motion = !state.reducedMotion
    val (start, end) = when {
        state.pending != null -> Color(0xFF7C2D12) to Color(0xFF9A3412)
        state.phase == Phase.ACTIVE -> Color(0xFF064E3B) to Color(0xFF0B3B30)
        state.phase == Phase.REVOKED || state.phase == Phase.FAILED -> Color(0xFF7F1D1D) to Color(0xFF881337)
        else -> Color(0xFF18181B) to Color(0xFF26262B)
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
                Text("$left min left · ends on its own", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 120).forEach { m ->
                    FilterChip(
                        selected = state.sessionMinutes == m,
                        onClick = { actions.setMinutes(m) },
                        label = { Text("$m min") },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color(0xFF0A0A0B),
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = state.sessionMinutes == m, borderColor = Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
            IconButtonLarge(
                if (state.accessibilityOn) "Start session" else "Finish setup to start",
                if (state.accessibilityOn) LatchIcons.Play else LatchIcons.ShieldCheck,
                if (state.accessibilityOn) actions.start else actions.openSetup,
                Modifier.fillMaxWidth(),
                container = Color.White,
                content = Color(0xFF0A0A0B),
            )
        }
    }
}

/** A tall pill button with an icon in front of its label. */
@Composable
fun IconButtonLarge(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, container: Color, content: Color) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        modifier = modifier.heightIn(min = 54.dp),
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
