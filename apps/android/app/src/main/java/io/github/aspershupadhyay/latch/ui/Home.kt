package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val signal = LocalSignal.current
    val live = state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED || state.phase == Phase.CONNECTING || state.phase == Phase.RECONNECTING
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        HeroTile(state, actions, live)

        state.pending?.let { p ->
            Tile(brush = signal.attentionBrush, minHeight = 0.dp) {
                TileLabel("Approval needed · ${p.risk} risk", Color.White.copy(alpha = 0.85f))
                Text(p.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(p.detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Deny", { actions.answer(p.nonce, false) }, Modifier.weight(1f), contentColor = Color.White)
                    PrimaryButton("Approve", { actions.answer(p.nonce, true) }, Modifier.weight(1f), color = Color.White, contentColor = Color(0xFF7C2D12))
                }
            }
        }

        if (!state.accessibilityOn) {
            Tile(color = signal.warning.copy(alpha = if (signal.dark) 0.18f else 0.12f), minHeight = 0.dp) {
                TileLabel("Setup needed", signal.warning)
                Text("Turn on the Latch accessibility service", style = MaterialTheme.typography.titleMedium, color = signal.text)
                TileNote("Android only lets an app read the screen and tap for you this way. Latch uses it only during a session you start. If Android says it is restricted: App info → ⋮ → Allow restricted settings.", maxLines = 5)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Open settings", actions.openAccessibilitySettings, Modifier.weight(1f))
                    SecondaryButton("App info", actions.openAppInfo, Modifier.weight(1f))
                }
            }
        }

        BentoRow {
            Tile(Modifier.weight(1f), onClick = actions.goCapabilities, onClickLabel = "Change capabilities") {
                TileLabel("Agent can")
                Push()
                Text("${state.enabled.size}", style = MaterialTheme.typography.displaySmall, color = signal.text)
                TileNote(
                    if (state.enabled.isEmpty()) "Only basic device info. Tap to allow more." else state.enabled.joinToString(" · ") { shortName(it) },
                    maxLines = 2,
                )
            }
            Tile(Modifier.weight(1f), color = signal.surface2) {
                TileLabel("Session")
                Push()
                Text(
                    state.minutesLeft?.let { "$it min" } ?: "${state.sessionMinutes} min",
                    style = MaterialTheme.typography.displaySmall,
                    color = signal.text,
                )
                TileNote(if (state.minutesLeft != null) "left, then it stops on its own" else "per session · ends on its own")
            }
        }

        Tile(onClick = actions.goConnect, onClickLabel = "Connect an AI app", minHeight = 0.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TileLabel("Connect an AI", signal.accent)
                    Text("Claude, ChatGPT, Cursor, or any MCP app", style = MaterialTheme.typography.titleMedium, color = signal.text)
                    TileNote(if (state.isOwner) "Create a key and paste it into your AI app." else "Ask the gateway owner for an AI key.")
                }
                Text("›", style = MaterialTheme.typography.displaySmall, color = signal.text2)
            }
        }

        BentoRow {
            Tile(Modifier.weight(1f), onClick = actions.goActivity, onClickLabel = "Open activity") {
                TileLabel("Last activity")
                Push()
                val last = state.lastActivity
                Text(last?.summary ?: "Nothing yet", style = MaterialTheme.typography.titleMedium, color = signal.text, maxLines = 3)
                TileNote(last?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.atMs)) } ?: "Everything the agent does shows here")
            }
            Tile(Modifier.weight(1f), color = signal.surface2) {
                TileLabel("Gateway")
                Push()
                Text(state.gatewayHost, style = MaterialTheme.typography.titleMedium, color = signal.text, maxLines = 2)
                StatusDot(
                    if (state.encrypted) signal.success else signal.warning,
                    if (state.encrypted) "Encrypted (https)" else "Unencrypted (debug)",
                )
                TileNote(if (state.isOwner) "You own this gateway" else "Joined with a code", maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeroTile(state: HomeState, actions: HomeActions, live: Boolean) {
    val signal = LocalSignal.current
    val brush = when {
        state.pending != null -> signal.attentionBrush
        state.phase == Phase.ACTIVE -> signal.activeBrush
        state.phase == Phase.REVOKED || state.phase == Phase.FAILED -> signal.stopBrush
        else -> signal.idleBrush
    }
    val field = when (state.phase) {
        Phase.IDLE -> FieldState.READY
        Phase.CONNECTING -> FieldState.CONNECTING
        Phase.ACTIVE -> if (state.pending != null) FieldState.AWAITING_APPROVAL else FieldState.CONNECTED
        Phase.PAUSED, Phase.RECONNECTING -> FieldState.DISCONNECTED
        Phase.REVOKED, Phase.FAILED -> FieldState.STOPPED
    }
    Tile(brush = brush, minHeight = 260.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TileLabel(state.phoneName, Color.White.copy(alpha = 0.75f))
            }
            SignalField(field, state.headline, state.reducedMotion, size = 64.dp, tint = Color.White)
        }
        Push()
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.headline, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(state.detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        }
        if (live) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton("Stop all activity", actions.stop, Modifier.weight(1.4f), color = Color.White, contentColor = Color(0xFFB91C1C))
                if (state.phase == Phase.ACTIVE || state.phase == Phase.PAUSED) {
                    SecondaryButton(if (state.phase == Phase.PAUSED) "Resume" else "Pause", { actions.setPaused(state.phase != Phase.PAUSED) }, Modifier.weight(1f), contentColor = Color.White)
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
                            selectedLabelColor = Color(0xFF1E2233),
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = state.sessionMinutes == m, borderColor = Color.White.copy(alpha = 0.4f)),
                    )
                }
            }
            PrimaryButton("Start session", actions.start, Modifier.fillMaxWidth(), color = Color.White, contentColor = Color(0xFF1E2233))
        }
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
