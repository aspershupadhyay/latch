package io.github.aspershupadhyay.latch.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun WelcomeScreen(onCreateGateway: () -> Unit, onHaveGateway: () -> Unit, notice: String? = null) {
    val signal = LocalSignal.current
    val context = LocalContext.current
    val reducedMotion = remember { AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(LatchIcons.ShieldCheck, signal.accent, size = 34.dp)
                Spacer(Modifier.size(10.dp))
                Text("Latch", style = MaterialTheme.typography.titleLarge, color = signal.text)
            }
            ConnectionIllustration(reducedMotion, Modifier.fillMaxWidth().height(220.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Let AI use your phone.", style = MaterialTheme.typography.displaySmall, color = signal.text)
                Text("Only the way you allow.", style = MaterialTheme.typography.displaySmall, color = signal.text2)
            }
            Text(
                "Claude, ChatGPT, or any AI app reaches this phone only through a gateway you own. You choose what it can do and can stop it with one tap.",
                style = MaterialTheme.typography.bodyLarge,
                color = signal.text2,
            )
            notice?.let {
                Card(color = signal.danger.copy(alpha = 0.12f)) { ListRow(LatchIcons.Warning, it, tint = signal.danger) }
            }
            Spacer(Modifier.size(4.dp))
            IconButtonLarge("Set up a new gateway", LatchIcons.Plus, onCreateGateway, Modifier.fillMaxWidth(), container = signal.ink, content = signal.onInk)
            Text(
                "Free · about 3 minutes · runs in your own Vercel account",
                style = MaterialTheme.typography.bodySmall,
                color = signal.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton("I already have a gateway", onHaveGateway, Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Promise(Modifier.weight(1f), LatchIcons.Shield, "Off by default")
                Promise(Modifier.weight(1f), LatchIcons.ShieldCheck, "You approve")
                Promise(Modifier.weight(1f), LatchIcons.Stop, "One-tap stop")
            }
        }
    }
}

@Composable
private fun Promise(modifier: Modifier, icon: ImageVector, text: String) {
    val signal = LocalSignal.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = signal.accent, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = signal.text2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val signal = LocalSignal.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (done) signal.success else signal.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else number.toString(), color = if (signal.dark) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun CreateGatewayScreen(state: CreateGatewayState, actions: CreateGatewayActions) {
    val signal = LocalSignal.current
    OnboardingPage {
        TextButton(onClick = actions.back) { Text("‹ Back") }
        ScreenTitle("Your own gateway", "Three steps. Everything is created in your accounts, not ours.")

        Tile {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepBadge(1, state.keyCopied)
                Text("Copy your owner key", style = MaterialTheme.typography.titleMedium, color = signal.text)
            }
            TileNote("This key controls your gateway. It stays on this phone and in your Vercel project — Latch never sees it.", maxLines = 4)
            CopyRow("Owner key", state.ownerKey, actions.copyKey, masked = true)
        }

        Tile {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepBadge(2, state.deployOpened)
                Text("Deploy to Vercel", style = MaterialTheme.typography.titleMedium, color = signal.text)
            }
            TileNote("Sign in or create a free Vercel account, keep the Upstash Redis store it suggests, paste the owner key into LATCH_ADMIN_TOKEN, and press Deploy.", maxLines = 5)
            PrimaryButton("Open Vercel", actions.openDeploy, Modifier.fillMaxWidth())
        }

        Tile {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepBadge(3, false)
                Text("Paste your gateway address", style = MaterialTheme.typography.titleMedium, color = signal.text)
            }
            TileNote("When Vercel says “Congratulations”, copy the domain it shows (for example latch-gateway-you.vercel.app).", maxLines = 3)
            OutlinedTextField(
                value = state.address,
                onValueChange = actions.setAddress,
                label = { Text("Gateway address") },
                placeholder = { Text("latch-gateway-you.vercel.app") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            PrimaryButton(if (state.busy) "Connecting…" else "Connect this phone", actions.connect, Modifier.fillMaxWidth(), enabled = !state.busy && state.address.isNotBlank())
        }
        Text(
            "Prefer Docker or your own server? Choose “Connect to a gateway” instead.",
            style = MaterialTheme.typography.bodySmall, color = signal.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun JoinGatewayScreen(state: JoinState, actions: JoinActions) {
    val signal = LocalSignal.current
    OnboardingPage {
        TextButton(onClick = actions.back) { Text("‹ Back") }
        ScreenTitle("Connect to a gateway", "Any Latch gateway: Vercel, Docker, or a home server.")
        Tile {
            OutlinedTextField(
                value = state.address,
                onValueChange = actions.setAddress,
                label = { Text("Gateway address") },
                placeholder = { Text("https://latch.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.mode == JoinMode.OWNER_KEY, onClick = { actions.setMode(JoinMode.OWNER_KEY) }, label = { Text("I own it") })
                FilterChip(selected = state.mode == JoinMode.PAIRING_CODE, onClick = { actions.setMode(JoinMode.PAIRING_CODE) }, label = { Text("I have a code") })
            }
            if (state.mode == JoinMode.OWNER_KEY) {
                TileNote("Paste the gateway's owner key (LATCH_ADMIN_TOKEN). This phone can then add AI apps and other phones.", maxLines = 3)
                OutlinedTextField(
                    value = state.secret,
                    onValueChange = actions.setSecret,
                    label = { Text("Owner key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TileNote("Ask the gateway owner for a pairing code. Codes work once, for 10 minutes.", maxLines = 3)
                OutlinedTextField(
                    value = state.secret,
                    onValueChange = { actions.setSecret(it.take(12)) },
                    label = { Text("Pairing code") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            PrimaryButton(
                if (state.busy) "Connecting…" else "Connect this phone",
                actions.connect,
                Modifier.fillMaxWidth(),
                enabled = !state.busy && state.address.isNotBlank() && state.secret.isNotBlank(),
            )
        }
        Tile(color = signal.surface2, minHeight = 0.dp) {
            TileNote("If you back out now, nothing is paired and nothing is shared.")
        }
    }
}
