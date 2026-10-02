package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    OnboardingPage {
        Tile(brush = signal.activeBrush, minHeight = 240.dp) {
            SignalField(FieldState.CONNECTED, "Latch", reducedMotion = true, size = 72.dp, tint = Color.White)
            Push()
            Text("Latch", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            Text("Let any AI use your phone — only the way you allow.", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        notice?.let {
            Tile(color = signal.danger.copy(alpha = 0.12f), minHeight = 0.dp) { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium) }
        }
        Tile(onClick = onCreateGateway, onClickLabel = "Create my gateway", minHeight = 150.dp) {
            TileLabel("Recommended · free · about 3 minutes", signal.accent)
            TileValue("Create my own gateway")
            TileNote("One click deploys a private Latch gateway to your own Vercel account. You own it, you pay nothing on the free tier, and nobody else can reach your phone.", maxLines = 4)
        }
        BentoRow {
            Tile(Modifier.weight(1f), onClick = onHaveGateway, onClickLabel = "Use an existing gateway") {
                TileLabel("Already have one")
                Push()
                Text("Connect to a gateway", style = MaterialTheme.typography.titleMedium, color = signal.text)
                TileNote("Owner key or a pairing code")
            }
            Tile(Modifier.weight(1f), color = signal.surface2) {
                TileLabel("Works with")
                Push()
                Text("Any MCP AI", style = MaterialTheme.typography.titleMedium, color = signal.text)
                TileNote("Claude, ChatGPT, Cursor, VS Code, your own agents")
            }
        }
        BentoRow {
            FactTile(Modifier.weight(1f), "Off by default", "Every capability starts switched off.")
            FactTile(Modifier.weight(1f), "Never secrets", "Passwords, PINs, and codes are never read or typed.")
        }
        BentoRow {
            FactTile(Modifier.weight(1f), "You approve", "Sending, buying, deleting waits for you.")
            FactTile(Modifier.weight(1f), "One tap stop", "A red Stop button stays on screen.")
        }
    }
}

@Composable
private fun FactTile(modifier: Modifier, title: String, note: String) {
    Tile(modifier, color = LocalSignal.current.surface2, minHeight = 104.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LocalSignal.current.text)
        TileNote(note)
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
            PrimaryButton("Open Vercel", actions.openDeploy, Modifier.fillMaxWidth(), color = Color(0xFF111111), contentColor = Color.White)
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
