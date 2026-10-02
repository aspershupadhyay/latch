package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.session.SignInRequest
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectScreen(state: ConnectState, actions: ConnectActions) {
    val signal = LocalSignal.current
    var name by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Connect an AI", "Works with any app that supports MCP.")

        // Sign-in requests come first: someone is waiting in a browser.
        state.requests.forEach { request -> SignInCard(request, actions) }

        state.created?.let { created ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(LatchIcons.Key, signal.success)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Key created", style = MaterialTheme.typography.titleLarge, color = signal.text)
                            Text("Shown once. Treat it like a password.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                        }
                    }
                    CopyRow("Secret link (apps that take only a URL)", created.secretLink, { actions.copy("Secret link", created.secretLink, true) })
                    CopyRow("Header (apps that take URL + header)", "Bearer ${created.token}", { actions.copy("Authorization header", "Bearer ${created.token}", true) }, masked = true)
                    PrimaryButton("Done, I saved it", actions.dismissCreated, Modifier.fillMaxWidth())
                }
            }
        }

        if (state.created == null) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add Latch to your AI app", style = MaterialTheme.typography.titleMedium, color = signal.text)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(signal.surface2).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.mcpUrl,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = signal.text,
                            maxLines = 2,
                            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                        )
                        IconButton(onClick = { actions.copy("MCP address", state.mcpUrl, false) }) {
                            Icon(LatchIcons.Copy, contentDescription = "Copy MCP address", tint = signal.text)
                        }
                    }
                    HowStep(1, "Paste it as a remote MCP server in Claude, ChatGPT, Codex, Cursor, or any MCP app.")
                    HowStep(2, "A Latch page opens in your browser with a 4-letter code.")
                    HowStep(3, "Approve it here when the same code appears.")
                }
            }
        }

        state.error?.let {
            Card(color = signal.danger.copy(alpha = 0.12f)) {
                ListRow(LatchIcons.Warning, "Something went wrong", it, tint = signal.danger)
            }
        }

        if (state.isOwner && state.created == null) {
            if (state.clients.isNotEmpty()) {
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                SectionCaption("Connected apps")
                Card {
                    state.clients.forEachIndexed { index, client ->
                        if (index > 0) RowDivider()
                        val how = if (client.kind == "oauth") "Signed in" else "Key"
                        ListRow(
                            if (client.kind == "oauth") LatchIcons.ShieldCheck else LatchIcons.Key,
                            client.name,
                            "$how · " + (client.lastUsedMs?.let { "used ${format.format(Date(it))}" } ?: "not used yet"),
                            tint = signal.accent,
                            trailing = {
                                IconButton(onClick = { actions.revoke(client.id) }) {
                                    Icon(LatchIcons.Trash, contentDescription = "Remove ${client.name}", tint = signal.danger, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                }
            }

            TextButton(onClick = { showKeys = !showKeys }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showKeys) "Hide keys" else "App has no sign-in? Use a key instead", color = signal.text2)
            }
            AnimatedVisibility(showKeys, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            label = { Text("App name, e.g. My agent") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryButton(
                            if (state.loading) "Creating…" else "Create key",
                            { actions.create(name.trim()); name = "" },
                            Modifier.fillMaxWidth(),
                            enabled = !state.loading && name.isNotBlank(),
                        )
                    }
                }
            }
        } else if (!state.isOwner) {
            Card {
                ListRow(LatchIcons.Info, "Joined with a code", "Only the gateway owner can approve AI apps.", tint = signal.text2)
            }
        }
    }
}

@Composable
private fun SignInCard(request: SignInRequest, actions: ConnectActions) {
    val signal = LocalSignal.current
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(signal.surface).border(2.dp, signal.accent, CardShape).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(LatchIcons.Sparkle, signal.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${request.clientName} wants to connect", style = MaterialTheme.typography.titleMedium, color = signal.text)
                if (request.returnTo.isNotEmpty()) {
                    Text("Returns to ${request.returnTo}", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
            }
        }
        Text("Approve only if your browser shows this code:", style = MaterialTheme.typography.bodyMedium, color = signal.text2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Code ${request.match.toList().joinToString(" ")}" }) {
            request.match.forEach { c ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(signal.surface2).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = signal.text)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Deny", { actions.answer(request.id, false) }, Modifier.weight(1f), contentColor = signal.danger)
            PrimaryButton("Approve", { actions.answer(request.id, true) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HowStep(n: Int, text: String) {
    val signal = LocalSignal.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(signal.surface2), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = signal.text)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = signal.text2, modifier = Modifier.padding(top = 2.dp))
    }
}
