// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
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
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

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
fun WelcomeScreen(onCreateGateway: () -> Unit, onHaveGateway: () -> Unit, notice: String? = null, onGuide: () -> Unit = {}) {
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
                Text("latch", style = MaterialTheme.typography.headlineSmall, color = signal.text)
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(signal.clay).padding(vertical = 18.dp, horizontal = 8.dp),
            ) {
                ConnectionIllustration(reducedMotion, Modifier.fillMaxWidth().height(190.dp), onPastel = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("Let your AI use this phone.", style = MaterialTheme.typography.displaySmall, color = signal.text)
                Text(
                    "Only how you allow.",
                    style = MaterialTheme.typography.displaySmall.copy(fontStyle = FontStyle.Italic),
                    color = signal.accent,
                )
            }
            Text(
                "Ask Claude, ChatGPT, or another AI to do things on your phone for you. You choose which apps it can use, and one tap stops it.",
                style = MaterialTheme.typography.bodyLarge,
                color = signal.text2,
            )
            notice?.let {
                Card(color = signal.danger.copy(alpha = 0.12f)) { ListRow(LatchIcons.Warning, it, tint = signal.danger) }
            }
            Spacer(Modifier.size(4.dp))
            IconButtonLarge("Set up Latch", LatchIcons.Plus, onCreateGateway, Modifier.fillMaxWidth(), container = signal.ink, content = signal.onInk)
            Text(
                "Free · about 5 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton("I have a code or a relay already", onHaveGateway, Modifier.fillMaxWidth())
            TextButton(onClick = onGuide, modifier = Modifier.fillMaxWidth()) {
                Icon(LatchIcons.Info, contentDescription = null, tint = signal.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("How does setup work?", color = signal.accent, style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Promise(Modifier.weight(1f), LatchIcons.Shield, "Off until you start", signal.sage)
                Promise(Modifier.weight(1f), LatchIcons.ShieldCheck, "Only apps you pick", signal.butter)
                Promise(Modifier.weight(1f), LatchIcons.Stop, "One tap stops it", signal.pool)
            }
        }
    }
}

@Composable
private fun Promise(modifier: Modifier, icon: ImageVector, text: String, color: Color) {
    val signal = LocalSignal.current
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(color).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = signal.onPastel, modifier = Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = signal.onPastel)
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val signal = LocalSignal.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (done) signal.success else signal.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else number.toString(), color = if (done) Color.White else signal.onInk, style = MaterialTheme.typography.labelLarge)
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
        TextButton(onClick = actions.back) { Text("‹ Back", color = signal.accent) }
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
            "Have your own server instead? Go back and choose “I have a code or a relay already”.",
            style = MaterialTheme.typography.bodySmall, color = signal.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun JoinGatewayScreen(state: JoinState, actions: JoinActions) {
    val signal = LocalSignal.current
    OnboardingPage {
        TextButton(onClick = actions.back) { Text("‹ Back", color = signal.accent) }
        ScreenTitle("Connect to a relay", "Use this if you already made a relay, or someone gave you a join code.")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabeledField(
                    label = "Relay address",
                    hint = "The web address of the relay, for example latch-gateway-ada.vercel.app",
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
                        hint = "The key you pasted into Vercel when you made this relay. It starts with lok_",
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
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
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
