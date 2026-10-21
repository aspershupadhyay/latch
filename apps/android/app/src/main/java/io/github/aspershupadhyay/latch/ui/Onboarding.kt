// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import io.github.aspershupadhyay.latch.ui.theme.GeistMono

@Composable
private fun OnboardingPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalSignal.current.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) { content() }
    }
}

@Composable
fun WelcomeScreen(
    onCreateGateway: () -> Unit,
    onHaveGateway: () -> Unit,
    notice: String? = null,
    onGuide: () -> Unit = {},
    saved: Map<io.github.aspershupadhyay.latch.data.ConnectionKind, String> = emptyMap(),
    onUseSaved: (io.github.aspershupadhyay.latch.data.ConnectionKind) -> Unit = {},
    /** Straight to the camera: the computer already shows Latch's QR code. */
    onScan: (() -> Unit)? = null,
    /** No camera needed: the phone finds the computer on the Wi-Fi (ADR-060). */
    onFind: (() -> Unit)? = null,
) {
    val signal = LocalSignal.current
    val context = LocalContext.current
    val reducedMotion = remember { AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LatchLogo(36.dp)
                Spacer(Modifier.size(10.dp))
                Text("latch", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = GeistMono, letterSpacing = (-0.6).sp), color = signal.text)
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(signal.surface)
                    .border(1.dp, signal.border, RoundedCornerShape(22.dp)).padding(vertical = 18.dp, horizontal = 8.dp),
            ) {
                ConnectionIllustration(reducedMotion, Modifier.fillMaxWidth().height(170.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("Let your AI use this phone.", style = MaterialTheme.typography.displaySmall, color = signal.text)
                Text("Only how you allow.", style = MaterialTheme.typography.displaySmall, color = signal.accent)
            }
            Text(
                "Ask Claude, ChatGPT, or another AI to do things on your phone. You pick the apps, and you can stop it any time.",
                style = MaterialTheme.typography.bodyLarge,
                color = signal.text2,
            )
            // The three promises answer the first worry ("can it act without me?"), so they come before any button.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Promise(Modifier.weight(1f), LatchIcons.Shield, "Off until you start", signal.success)
                Promise(Modifier.weight(1f), LatchIcons.ShieldCheck, "Only apps you pick", signal.accent)
                Promise(Modifier.weight(1f), LatchIcons.Stop, "Stop button on screen", signal.danger)
            }
            notice?.let {
                Card(color = signal.danger.copy(alpha = 0.12f)) { ListRow(LatchIcons.Warning, it, tint = signal.danger) }
            }
            SavedConnectionsCard(saved, onUseSaved)
            Spacer(Modifier.size(2.dp))
            // One way in is the obvious one: the computer runs latch-start and shows a QR code; one scan connects.
            // Every other way waits under "Other ways to connect".
            var others by rememberSaveable { mutableStateOf(false) }
            if (onScan != null) {
                IconButtonLarge("Scan the code on my computer", LatchIcons.Camera, onScan, Modifier.fillMaxWidth(), container = signal.ink, content = signal.onInk, tall = true)
                Text(
                    "On your computer, run latch start. It shows the code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = signal.text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                IconButtonLarge(if (saved.isEmpty()) "Set up Latch" else "Set up another way", LatchIcons.Plus, onCreateGateway, Modifier.fillMaxWidth(), container = signal.ink, content = signal.onInk, tall = true)
            }
            if (!others) {
                SecondaryButton("Other ways to connect", { others = true }, Modifier.fillMaxWidth())
            } else {
                Card {
                    onFind?.let {
                        ListRow(LatchIcons.Wifi, "Find my computer", "No camera needed. Same Wi-Fi as your computer.", tint = signal.live, onClick = it, onClickLabel = "Find my computer on this Wi-Fi")
                        RowDivider()
                    }
                    if (onScan != null) {
                        ListRow(LatchIcons.Cloud, if (saved.isEmpty()) "Set up Latch" else "Set up another way", "No computer needed. Free, about 5 minutes.", tint = signal.accent, onClick = onCreateGateway, onClickLabel = "Set up Latch")
                        RowDivider()
                    }
                    ListRow(LatchIcons.Key, "I have a code or a relay", "Someone gave you a join code, or you made a relay before.", tint = signal.text2, onClick = onHaveGateway, onClickLabel = "Connect with a code or relay")
                }
            }
            TextButton(onClick = onGuide, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(LatchIcons.Info, contentDescription = null, tint = signal.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("How does setup work?", color = signal.accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Promise(modifier: Modifier, icon: ImageVector, text: String, hue: Color) {
    val signal = LocalSignal.current
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(signal.surface).border(1.dp, signal.border, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, hue, size = 32.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = signal.text)
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val signal = LocalSignal.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (done) signal.success else signal.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else "$number", color = if (done) signal.onAccent else signal.onInk, style = MaterialTheme.typography.labelLarge)
    }
}

/** One numbered step: a badge, a title, an ⓘ, and plain instructions. */
@Composable
private fun SetupStep(number: Int, done: Boolean, title: String, text: String, topic: HelpTopic? = null, content: @Composable () -> Unit) {
    val signal = LocalSignal.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepBadge(number, done)
                Text(title, style = MaterialTheme.typography.titleMedium, color = signal.text, modifier = Modifier.weight(1f))
                topic?.let { InfoButton(it) }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = signal.text2)
            content()
        }
    }
}

@Composable
fun CreateGatewayScreen(state: CreateGatewayState, actions: CreateGatewayActions) {
    val signal = LocalSignal.current
    OnboardingPage {
        BackBar(actions.back)
        ScreenTitle("Make your relay", "Your relay is a small free website that connects your AI to this phone. It belongs only to you.")

        SetupStep(
            1, state.keyCopied, "Copy your secret key",
            "Latch made this key for you. You'll paste it on the next website. Keep it private, like a password.",
            HelpTopic.SECRET_KEY,
        ) {
            CopyRow("Secret key", state.ownerKey, actions.copyKey, masked = true)
        }

        SetupStep(
            2, state.deployOpened, "Create the relay on Vercel",
            "Vercel is a free website host. Sign in (Google or GitHub is fine), paste your key where it asks for LATCH_ADMIN_TOKEN, keep everything else as it is, and tap Deploy.",
            HelpTopic.RELAY,
        ) {
            PrimaryButton("Open Vercel", actions.openDeploy, Modifier.fillMaxWidth())
        }

        SetupStep(
            3, false, "Paste your relay's address",
            "When Vercel shows “Congratulations”, copy the web address it shows and paste it here.",
        ) {
            LabeledField(
                label = "Relay address",
                hint = "It ends in .vercel.app, for example latch-gateway-ada.vercel.app",
                value = state.address,
                onValueChange = actions.setAddress,
                topic = HelpTopic.RELAY,
                placeholder = "something.vercel.app",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            state.error?.let { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            PrimaryButton(if (state.busy) "Connecting…" else "Connect", actions.connect, Modifier.fillMaxWidth(), enabled = !state.busy && state.address.isNotBlank())
        }
        Text(
            "Prefer the gateway on your computer? Go back and choose “Computer on Wi-Fi”.",
            style = MaterialTheme.typography.bodySmall, color = signal.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun JoinGatewayScreen(state: JoinState, actions: JoinActions) {
    val signal = LocalSignal.current
    OnboardingPage {
        BackBar(actions.back)
        ScreenTitle("Connect to a relay", "Use this if you already made a relay, or someone gave you a join code.")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabeledField(
                    label = "Relay address",
                    hint = "The web address of the relay, for example latch-gateway-ada.vercel.app. For a relay on your computer, paste the whole address it prints, with #pin=.",
                    value = state.address,
                    onValueChange = actions.setAddress,
                    topic = HelpTopic.RELAY,
                    placeholder = "something.vercel.app",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.mode == JoinMode.PAIRING_CODE, onClick = { actions.setMode(JoinMode.PAIRING_CODE) }, label = { Text("I have a join code") })
                    FilterChip(selected = state.mode == JoinMode.OWNER_KEY, onClick = { actions.setMode(JoinMode.OWNER_KEY) }, label = { Text("It's my relay") })
                }
                if (state.mode == JoinMode.OWNER_KEY) {
                    LabeledField(
                        label = "Secret key",
                        hint = "The owner key: the one you pasted into Vercel (it starts with lok_), or the one latch start prints for a relay on your computer (lgt_…).",
                        value = state.secret,
                        onValueChange = actions.setSecret,
                        topic = HelpTopic.SECRET_KEY,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                } else {
                    LabeledField(
                        label = "Join code",
                        hint = "8 letters from the relay's owner. It works once, for 10 minutes.",
                        value = state.secret,
                        onValueChange = { actions.setSecret(it.take(12)) },
                        topic = HelpTopic.JOIN_CODE,
                        placeholder = "ABCD-EFGH",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = GeistMono),
                    )
                }
                state.error?.let { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                PrimaryButton(
                    if (state.busy) "Connecting…" else "Connect",
                    actions.connect,
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy && state.address.isNotBlank() && state.secret.isNotBlank(),
                )
            }
        }
        Text(
            "If you go back now, nothing is connected and nothing is shared.",
            style = MaterialTheme.typography.bodySmall, color = signal.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}
