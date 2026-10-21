// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.data.ConnectionKind
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/** What the owner reads about each way of connecting, wherever they choose one. */
private val ConnectionKind.icon: ImageVector get() = if (this == ConnectionKind.WIFI) LatchIcons.Wifi else LatchIcons.Cloud

private val ConnectionKind.tagline: String get() = when (this) {
    ConnectionKind.HOSTED -> "Your own free relay on Vercel. Works anywhere."
    ConnectionKind.WIFI -> "Latch on your computer. Stays on your home network."
}

private val ConnectionKind.facts: List<String> get() = when (this) {
    ConnectionKind.HOSTED -> listOf(
        "Works on any Wi-Fi or mobile data",
        "Nothing to keep running on a computer",
        "One-time setup in your browser, about 5 minutes",
    )
    ConnectionKind.WIFI -> listOf(
        "Nothing goes through the internet",
        "Fastest, and live mirror in your browser",
        "Your computer must be on, on the same Wi-Fi",
    )
}

/** The command that installs the gateway on macOS or Linux (published with every beta). */
const val GATEWAY_INSTALL_COMMAND =
    "curl -fsSL https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.sh | sh"

data class WifiGatewayState(
    val address: String,
    /** The setup code under the QR code; an owner key (lgt_…) is accepted too. */
    val code: String,
    val busy: Boolean,
    val error: String?,
    val installCopied: Boolean = false,
    val startCopied: Boolean = false,
)

class WifiGatewayActions(
    val copyInstall: () -> Unit = {},
    val copyStart: () -> Unit = {},
    val setAddress: (String) -> Unit = {},
    val setCode: (String) -> Unit = {},
    /** Opens the camera on the gateway's QR code (ADR-054). */
    val scan: () -> Unit = {},
    val connect: () -> Unit = {},
    val back: () -> Unit = {},
)

/** One way of connecting, as a large selectable card with what it means in practice. */
@Composable
fun ConnectionOption(
    kind: ConnectionKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val signal = LocalSignal.current
    val ring by animateColorAsState(if (selected) signal.accent else signal.border, label = "connectionBorder")
    Column(
        modifier.fillMaxWidth().clip(CardShape)
            .background(if (selected) signal.accent.copy(alpha = 0.07f) else signal.surface)
            .border(if (selected) 1.5.dp else 1.dp, ring, CardShape)
            .clickable(role = Role.RadioButton, onClickLabel = kind.title, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kind.icon, if (selected) signal.accent else signal.text2, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium, color = signal.text, modifier = Modifier.weight(1f, fill = false))
                    badge?.let {
                        Spacer(Modifier.width(8.dp))
                        Pill(it, signal.success)
                    }
                }
                Text(kind.tagline, style = MaterialTheme.typography.bodySmall, color = signal.text2)
            }
            if (selected) Icon(LatchIcons.Check, contentDescription = null, tint = signal.accent, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 58.dp)) {
            kind.facts.forEach { fact ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 7.dp).size(5.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) signal.accent else signal.text3))
                    Spacer(Modifier.width(8.dp))
                    Text(fact, style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
            }
        }
    }
}

/** Onboarding: the owner picks how their AI will reach this phone. */
@Composable
fun ChooseConnectionScreen(onChoose: (ConnectionKind) -> Unit, onBack: () -> Unit) {
    val signal = LocalSignal.current
    var picked by remember { mutableStateOf<ConnectionKind?>(null) }
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackBar(onBack)
            ScreenTitle("How should your AI reach this phone?", "Pick one now. You can add the other later and switch in one tap.")
            ConnectionKind.entries.forEach { kind ->
                ConnectionOption(kind, selected = picked == kind, onClick = { picked = kind })
            }
            PrimaryButton(
                picked?.let { "Continue with ${it.inSentence}" } ?: "Choose one to continue",
                { picked?.let(onChoose) },
                Modifier.fillMaxWidth(),
                enabled = picked != null,
            )
            Text(
                "Either way, the AI sees and does only what you allow, and only while a session runs.",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Onboarding for the gateway on the owner's computer (ADR-042, ADR-054): install, start, scan its QR code. */
@Composable
fun WifiGatewayScreen(state: WifiGatewayState, actions: WifiGatewayActions) {
    val signal = LocalSignal.current
    // Typing is the fallback: open when there is already something typed.
    var byHand by rememberSaveable { mutableStateOf(state.address.isNotBlank() || state.code.isNotBlank()) }
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackBar(actions.back)
            ScreenTitle("Connect over Wi-Fi", "Run the Latch gateway on your computer. The phone and the computer must be on the same Wi-Fi.")

            WifiStep(1, state.installCopied, "Install the gateway", "On your Mac or Linux computer, open Terminal and run this once.") {
                CopyRow("Install command", GATEWAY_INSTALL_COMMAND, actions.copyInstall)
                Text(
                    "Windows: download latch-gateway-windows-x86_64.exe from the beta release instead.",
                    style = MaterialTheme.typography.bodySmall, color = signal.text2,
                )
            }

            WifiStep(2, state.startCopied, "Start it", "It shows a QR code for this phone.") {
                CopyRow("Start command", "latch-start", actions.copyStart)
                Text(
                    "Already running, or the code expired? Run latch-gateway pair for a new QR code. To keep it running after a restart, run latch-autostart on once.",
                    style = MaterialTheme.typography.bodySmall, color = signal.text2,
                )
            }

            WifiStep(3, false, "Scan the QR code", "Point the camera at the code in the terminal. That's all: no keys to copy.") {
                PrimaryButton(
                    if (state.busy) "Connecting…" else "Scan QR code",
                    actions.scan,
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
                if (!byHand) {
                    state.error?.let { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    TextButton(onClick = { byHand = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Can't scan? Enter it by hand", color = signal.accent)
                    }
                } else {
                    LabeledField(
                        label = "Address",
                        hint = "The line after “Address” under the QR code, including #pin=…",
                        value = state.address,
                        onValueChange = actions.setAddress,
                        placeholder = "https://192.168.1.5:8788#pin=…",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    LabeledField(
                        label = "Setup code",
                        hint = "The 8-letter code under the QR code, like K7QX-M2PD.",
                        value = state.code,
                        onValueChange = actions.setCode,
                        placeholder = "K7QX-M2PD",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    state.error?.let { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    SecondaryButton(
                        if (state.busy) "Connecting…" else "Connect",
                        actions.connect,
                        Modifier.fillMaxWidth(),
                        enabled = !state.busy && state.address.isNotBlank() && (state.code.isNotBlank() || "&code=" in state.address),
                    )
                }
            }
            Text(
                "Same address every time: if your computer gets a new one on the Wi-Fi, this phone finds it again by itself.",
                style = MaterialTheme.typography.bodySmall, color = signal.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WifiStep(number: Int, done: Boolean, title: String, text: String, content: @Composable () -> Unit) {
    val signal = LocalSignal.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(if (done) signal.success else signal.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (done) "✓" else "$number", color = if (done) signal.onAccent else signal.onInk, style = MaterialTheme.typography.labelLarge)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = signal.text, modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = signal.text2)
            content()
        }
    }
}

/** Welcome screen: connections this phone kept, one tap to go back to each. */
@Composable
fun SavedConnectionsCard(saved: Map<ConnectionKind, String>, onUse: (ConnectionKind) -> Unit) {
    if (saved.isEmpty()) return
    val signal = LocalSignal.current
    Card {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "Kept on this phone",
                style = MaterialTheme.typography.labelLarge,
                color = signal.text2,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
            )
            saved.entries.forEachIndexed { index, (kind, host) ->
                if (index > 0) RowDivider()
                ListRow(kind.icon, "Reconnect: your ${kind.inSentence}", "$host · one tap, no key or QR code", onClick = { onUse(kind) }, onClickLabel = "Reconnect through your ${kind.inSentence}")
            }
        }
    }
}

/**
 * AI apps page: how the AI reaches this phone now, and the switch to the other
 * way. A kept connection switches in one tap; otherwise the owner sets it up
 * once and this one is kept.
 */
@Composable
fun ConnectionCard(
    current: ConnectionKind,
    host: String,
    saved: Map<ConnectionKind, String>,
    onSwitch: (ConnectionKind) -> Unit,
    onSetUp: (ConnectionKind) -> Unit,
    onForgetSaved: (ConnectionKind) -> Unit,
    bothConnected: Boolean = true,
    onBothConnected: (Boolean) -> Unit = {},
) {
    val signal = LocalSignal.current
    var confirm by remember { mutableStateOf<ConnectionKind?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("How your AI reaches this phone", style = MaterialTheme.typography.titleMedium, color = signal.text)
                    Text(
                        if (saved.isNotEmpty() && bothConnected) "Both connected: during a session your AI can use either. The one selected is the one this tab manages."
                        else "Switch any time. Latch keeps both, so switching back asks for nothing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.text2,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(signal.canvas)
                    .border(1.dp, signal.border2, RoundedCornerShape(10.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ConnectionKind.entries.forEach { kind ->
                    Segment(kind, kind == current, Modifier.weight(1f)) { if (kind != current) confirm = kind }
                }
            }
            ListRow(
                current.icon,
                "Using ${current.inSentence}",
                host,
                tint = signal.success,
                trailing = { Pill("In use", signal.success) },
            )
            ConnectionKind.entries.filter { it != current }.forEach { other ->
                val keptHost = saved[other]
                if (keptHost != null) {
                    ListRow(
                        other.icon,
                        if (bothConnected) "${other.title} also connected" else "${other.title} kept",
                        if (bothConnected) "$keptHost · in every session too" else "$keptHost · switch without a key",
                        tint = signal.text2,
                        trailing = {
                            TextButton(onClick = { onForgetSaved(other) }) { Text("Forget", color = signal.danger) }
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep both connected", style = MaterialTheme.typography.titleMedium, color = signal.text)
                            Text(
                                "Each session connects through both, until you turn this off or disconnect one. Live mirror always uses Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = signal.text2,
                            )
                        }
                        LatchSwitch(
                            checked = bothConnected,
                            onCheckedChange = onBothConnected,
                            modifier = Modifier.semantics { contentDescription = "Keep both connected" },
                        )
                    }
                } else {
                    Text(
                        "No ${other.inSentence} yet. Tap “${other.short}” above to set one up; this one stays kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.text2,
                    )
                }
            }
        }
    }
    confirm?.let { target ->
        val kept = saved[target] != null
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (kept) "Switch to ${target.inSentence}?" else "Set up a ${target.inSentence}?") },
            text = {
                Text(
                    if (kept) {
                        "A running session stops. Your AI then reaches this phone through ${saved[target]}. Your ${current.inSentence} stays kept for switching back."
                    } else {
                        "A running session stops and you set up the ${target.inSentence} once. Your ${current.inSentence} stays kept: you can switch back from the first screen or from here, with no key."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (kept) onSwitch(target) else onSetUp(target)
                }) { Text(if (kept) "Switch" else "Set up") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Segment(kind: ConnectionKind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val signal = LocalSignal.current
    val fill by animateColorAsState(if (selected) signal.ink else Color.Transparent, label = "segment")
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp)).background(fill)
            .clickable(role = Role.Tab, onClickLabel = kind.title, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon, contentDescription = null, tint = if (selected) signal.onInk else signal.text2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(kind.short, style = MaterialTheme.typography.labelLarge, color = if (selected) signal.onInk else signal.text)
    }
}

/** What the Find my computer screen shows (ADR-060). */
sealed interface NearbyState {
    data object Searching : NearbyState
    data class Found(val computers: List<io.github.aspershupadhyay.latch.session.NearbyLink.Found>) : NearbyState
    /** The number both screens show, while the owner answers on the computer. */
    data class Number(val computer: String, val number: String) : NearbyState
    data class Failed(val message: String) : NearbyState
}

/** Find my computer: no QR code; the phone finds the computer and both show the same number (ADR-060). */
@Composable
fun FindComputerScreen(
    state: NearbyState,
    onPick: (io.github.aspershupadhyay.latch.session.NearbyLink.Found) -> Unit,
    onRetry: () -> Unit,
    onScanInstead: () -> Unit,
    onBack: () -> Unit,
) {
    val signal = LocalSignal.current
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackBar(onBack)
            when (state) {
                NearbyState.Searching -> {
                    ScreenTitle("Looking for your computer…", "Keep latch-start running on your computer. The phone and the computer must be on the same Wi-Fi.")
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = signal.accent)
                }
                is NearbyState.Found -> {
                    ScreenTitle("Which one is your computer?", "Tap yours. A number appears here and on your computer.")
                    state.computers.forEach { computer ->
                        PrimaryButton(computer.name, { onPick(computer) }, Modifier.fillMaxWidth())
                    }
                }
                is NearbyState.Number -> {
                    ScreenTitle("Check the number", "Your computer (${state.computer}) shows a number too.")
                    Card {
                        Text(
                            state.number,
                            style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 4.sp),
                            color = signal.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Text(
                        "Same number on the computer? Type y there and press Enter. This phone connects by itself. Different number? Type n there and go back.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = signal.text2,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = signal.accent)
                }
                is NearbyState.Failed -> {
                    ScreenTitle("Not connected yet", null)
                    Text(state.message, color = signal.danger, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    PrimaryButton("Look again", onRetry, Modifier.fillMaxWidth())
                }
            }
            SecondaryButton("Scan the QR code instead", onScanInstead, Modifier.fillMaxWidth())
        }
    }
}
