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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.session.McpClient
import io.github.aspershupadhyay.latch.session.SignInRequest
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import io.github.aspershupadhyay.latch.ui.theme.GeistMono
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectScreen(state: ConnectState, actions: ConnectActions) {
    val signal = LocalSignal.current
    var name by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var limiting by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
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
                    CopyRow("Private link (for apps that ask only for a link)", created.secretLink, { actions.copy("Secret link", created.secretLink, true) }, masked = true)
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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(signal.canvas)
                            .border(1.dp, signal.border2, RoundedCornerShape(8.dp)).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.mcpUrl,
                            fontFamily = GeistMono,
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
                            "$how · " + (client.lastUsedMs?.let { "used ${format.format(Date(it))}" } ?: "not used yet") +
                                " · " + appsSummary(client.apps, state.phoneApps),
                            tint = signal.accent,
                            onClick = { limiting = client.id },
                            onClickLabel = "Choose which phone apps ${client.name} may use",
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
            if (state.otherPhones.isNotEmpty()) {
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                SectionCaption("Other phones on this relay")
                Card {
                    state.otherPhones.forEachIndexed { index, phone ->
                        if (index > 0) RowDivider()
                        val seen = when {
                            phone.connected -> "Connected now"
                            phone.lastSeenMs != null -> "Last seen ${format.format(Date(phone.lastSeenMs))}"
                            else -> "Paired ${format.format(Date(phone.pairedAtMs))}, never connected"
                        }
                        ListRow(
                            LatchIcons.Phone,
                            phone.name,
                            if (phone.model.isNotBlank() && phone.model != phone.name) "${phone.model} · $seen" else seen,
                            tint = if (phone.connected) signal.success else signal.text2,
                            trailing = {
                                IconButton(onClick = { removing = phone.id }) {
                                    Icon(LatchIcons.Trash, contentDescription = "Remove ${phone.name}", tint = signal.danger, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                }
            }
            state.otherPhones.firstOrNull { it.id == removing }?.let { phone ->
                AlertDialog(
                    onDismissRequest = { removing = null },
                    title = { Text("Remove ${phone.name}?") },
                    text = {
                        Text(
                            "AI apps can no longer reach it" + (if (phone.connected) ", and its session ends now" else "") +
                                ". To use it again, pair it again.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { actions.removePhone(phone.id); removing = null }) { Text("Remove", color = signal.danger) }
                    },
                    dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
                )
            }
            state.clients.firstOrNull { it.id == limiting }?.let { client ->
                LimitAppsDialog(client, state.phoneApps, onDismiss = { limiting = null }) { apps ->
                    actions.limit(client.id, apps)
                    limiting = null
                }
            }
        } else if (!state.isOwner) {
            Card {
                ListRow(LatchIcons.Info, "Joined with a code", "Only the relay's owner can connect AI apps.", tint = signal.text2)
            }
        }
    }
}

/** How a client's app limit reads in the list (R18). */
private fun appsSummary(apps: List<String>?, phoneApps: List<PhoneApp>): String = when {
    apps == null -> "any app you switched on"
    apps.size == 1 -> "only " + (phoneApps.firstOrNull { it.packageName == apps[0] }?.label ?: apps[0])
    else -> "only ${apps.size} apps"
}

/**
 * R18: which phone apps one AI app may use. The phone's own app switches still
 * apply on top; this only narrows them for this AI app.
 */
@Composable
private fun LimitAppsDialog(client: McpClient, phoneApps: List<PhoneApp>, onDismiss: () -> Unit, onSave: (List<String>?) -> Unit) {
    val signal = LocalSignal.current
    var limited by remember(client.id) { mutableStateOf(client.apps != null) }
    var picked by remember(client.id) { mutableStateOf(client.apps.orEmpty().toSet()) }
    var query by remember(client.id) { mutableStateOf("") }
    // Apps already in the limit stay listed even if they are no longer installed.
    val choices = remember(phoneApps, client.apps) {
        val known = phoneApps.map { it.packageName }.toSet()
        (phoneApps + client.apps.orEmpty().filter { it !in known }.map { PhoneApp(it, it) }).sortedBy { it.label.lowercase() }
    }
    val shown = choices.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps ${client.name} may use") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow("Any app I switched on", !limited) { limited = false }
                ChoiceRow("Only the apps I pick", limited) { limited = true }
                if (limited) {
                    Text(
                        "Your app switches still apply. This AI app is also kept out of every app you don't pick, and never sees their screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.text2,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(40) },
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (choices.isEmpty()) Text("Reading your apps\u2026", color = signal.text2)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            val on = app.packageName in picked
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(value = on, role = Role.Checkbox) { now ->
                                        picked = if (now) picked + app.packageName else picked - app.packageName
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Spacer(Modifier.width(10.dp))
                                Text(app.label, color = signal.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(if (limited) picked.sorted().take(100) else null) },
                enabled = !limited || picked.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    val signal = LocalSignal.current
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label, color = signal.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SignInCard(request: SignInRequest, actions: ConnectActions) {
    val signal = LocalSignal.current
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(signal.surface).border(1.5.dp, signal.accent, CardShape).padding(16.dp)
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
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(signal.canvas)
                        .border(1.dp, signal.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", style = MaterialTheme.typography.headlineSmall, fontFamily = GeistMono, color = signal.text)
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
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(signal.surface2).border(1.dp, signal.border2, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = signal.text)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = signal.text2, modifier = Modifier.padding(top = 2.dp))
    }
}
