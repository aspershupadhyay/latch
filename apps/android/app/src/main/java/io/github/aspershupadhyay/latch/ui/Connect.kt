// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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
        ScreenTitle("Connect your AI", "Works with Claude, ChatGPT, and other AI apps that support connectors.")

        // Sign-in requests come first: someone is waiting in a browser.
        state.requests.forEach { request -> SignInCard(request, actions) }

        state.created?.let { created ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(LatchIcons.Key, signal.success)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Key ready", style = MaterialTheme.typography.titleLarge, color = signal.text)
                            Text("You'll see it only once. Keep it private, like a password.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                        }
                    }
                    CopyRow("Private link (for apps that ask only for a link)", created.secretLink, { actions.copy("Secret link", created.secretLink, true) })
                    CopyRow("Key (for apps that ask for a link and a key)", "Bearer ${created.token}", { actions.copy("Authorization header", "Bearer ${created.token}", true) }, masked = true)
                    PrimaryButton("Done, I saved it", actions.dismissCreated, Modifier.fillMaxWidth())
                }
            }
        }

        if (state.created == null) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your AI link", style = MaterialTheme.typography.titleMedium, color = signal.text)
                            Text("Give this link to your AI app so it can reach this phone.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                        }
                        InfoButton(HelpTopic.AI_LINK)
                    }
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
                        IconButton(onClick = { actions.copy("AI link", state.mcpUrl, false) }) {
                            Icon(LatchIcons.Copy, contentDescription = "Copy AI link", tint = signal.text)
                        }
                    }
                    HowStep(1, "In your AI app, add a connector and paste the link. In Claude: Settings \u2192 Connectors \u2192 Add custom connector.")
                    HowStep(2, "A Latch page opens and shows a 4-letter code.")
                    HowStep(3, "When the same code shows up here, tap Approve.")
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
                Text(if (showKeys) "Hide keys" else "AI app has no sign-in? Use a key instead", color = signal.accent)
            }
            AnimatedVisibility(showKeys, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            label = { Text("Name it, e.g. Claude on my laptop") },
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
                ListRow(LatchIcons.Info, "Joined with a code", "Only the relay's owner can connect AI apps.", tint = signal.text2)
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
