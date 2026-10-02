package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectScreen(state: ConnectState, actions: ConnectActions) {
    val signal = LocalSignal.current
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Connect an AI", "Give each AI app its own key. Revoke any key at any time.")

        state.created?.let { created ->
            Card(color = null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(LatchIcons.Key, signal.success)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Key created", style = MaterialTheme.typography.titleLarge, color = signal.text)
                            Text("Shown once. Paste it into your AI app now. Treat it like a password.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                        }
                    }
                    Text("Apps that ask for one link (ChatGPT, Claude.ai)", style = MaterialTheme.typography.labelLarge, color = signal.text2)
                    CopyRow("Secret link", created.secretLink, { actions.copy("Secret link", created.secretLink, true) })
                    Text("Apps that ask for a link and a header (Claude Code, Cursor, VS Code)", style = MaterialTheme.typography.labelLarge, color = signal.text2)
                    CopyRow("MCP URL", created.mcpUrl, { actions.copy("MCP URL", created.mcpUrl, false) })
                    CopyRow("Authorization header", "Bearer ${created.token}", { actions.copy("Authorization header", "Bearer ${created.token}", true) }, masked = true)
                    val command = "claude mcp add --transport http latch ${created.mcpUrl} --header \"Authorization: Bearer ${created.token}\""
                    CopyRow("Claude Code command", command, { actions.copy("Claude Code command", command, true) }, masked = true)
                    PrimaryButton("Done, I saved it", actions.dismissCreated, Modifier.fillMaxWidth())
                }
            }
        }

        if (state.created == null) {
            Card {
                ListRow(
                    LatchIcons.Plug,
                    "Your MCP address",
                    state.mcpUrl.removePrefix("https://"),
                    tint = signal.accent,
                    trailing = {
                        IconButton(onClick = { actions.copy("MCP URL", state.mcpUrl, false) }) {
                            Icon(LatchIcons.Copy, contentDescription = "Copy MCP address", tint = signal.text2, modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }

            if (state.isOwner) {
                SectionCaption("Add an AI app")
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            label = { Text("Name, e.g. Claude on laptop") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        IconButtonLarge(
                            if (state.loading) "Creating…" else "Create key",
                            LatchIcons.Plus,
                            { if (!state.loading && name.isNotBlank()) { actions.create(name.trim()); name = "" } },
                            Modifier.fillMaxWidth(),
                            container = if (name.isNotBlank()) signal.accent else signal.surface2,
                            content = if (name.isNotBlank()) (if (signal.dark) Color.Black else Color.White) else signal.text2,
                        )
                    }
                }
            } else {
                Card {
                    ListRow(LatchIcons.Info, "Joined with a code", "Only the gateway owner can create AI keys. Ask them for one.", tint = signal.text2)
                }
            }
        }

        state.error?.let {
            Card(color = signal.danger.copy(alpha = 0.12f)) {
                ListRow(LatchIcons.Warning, "Something went wrong", it, tint = signal.danger)
            }
        }

        if (state.isOwner && state.created == null && state.clients.isNotEmpty()) {
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            SectionCaption("Keys in use · ${state.clients.size}")
            Card {
                state.clients.forEachIndexed { index, client ->
                    if (index > 0) RowDivider()
                    ListRow(
                        LatchIcons.Key,
                        client.name,
                        client.lastUsedMs?.let { "Last used ${format.format(Date(it))}" } ?: "Never used yet",
                        tint = signal.accent2,
                        trailing = {
                            IconButton(onClick = { actions.revoke(client.id) }) {
                                Icon(LatchIcons.Trash, contentDescription = "Revoke ${client.name}", tint = signal.danger, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }
        }
    }
}
