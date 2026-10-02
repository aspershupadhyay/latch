package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        ScreenTitle("Connect an AI", "Any app that speaks MCP can use this phone through your gateway.")

        state.created?.let { created ->
            Tile(brush = signal.activeBrush, minHeight = 0.dp) {
                TileLabel("New key · shown once", Color.White.copy(alpha = 0.8f))
                Text("Paste one of these into your AI app now", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Treat them like passwords. You can revoke this key below at any time.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
            Tile(minHeight = 0.dp) {
                TileLabel("ChatGPT, Claude.ai, and apps that only ask for a URL")
                CopyRow("Secret link", created.secretLink, { actions.copy("Secret link", created.secretLink, true) }, masked = false)
            }
            Tile(minHeight = 0.dp) {
                TileLabel("Claude Code, Cursor, VS Code, agents (URL + header)")
                CopyRow("MCP URL", created.mcpUrl, { actions.copy("MCP URL", created.mcpUrl, false) })
                CopyRow("Authorization header", "Bearer ${created.token}", { actions.copy("Authorization header", "Bearer ${created.token}", true) }, masked = true)
                val command = "claude mcp add --transport http latch ${created.mcpUrl} --header \"Authorization: Bearer ${created.token}\""
                CopyRow("Claude Code command", command, { actions.copy("Claude Code command", command, true) }, masked = true)
            }
            SecondaryButton("Done — I saved it", actions.dismissCreated, Modifier.fillMaxWidth())
        }

        if (state.created == null) {
            BentoRow {
                Tile(Modifier.weight(1f), color = signal.surface2) {
                    TileLabel("Your MCP endpoint")
                    Push()
                    Text(state.mcpUrl.removePrefix("https://"), style = MaterialTheme.typography.titleMedium, color = signal.text, maxLines = 3)
                    TextButton(onClick = { actions.copy("MCP URL", state.mcpUrl, false) }) { Text("Copy") }
                }
                Tile(Modifier.weight(1f), color = signal.surface2) {
                    TileLabel("Keys in use")
                    Push()
                    Text("${state.clients.size}", style = MaterialTheme.typography.displaySmall, color = signal.text)
                    TileNote("One per AI app, so you can revoke one without the others.")
                }
            }

            if (state.isOwner) {
                Tile(minHeight = 0.dp) {
                    TileLabel("Add an AI app", signal.accent)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Name it, e.g. “Claude on laptop”") },
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
            } else {
                Tile(color = signal.surface2, minHeight = 0.dp) {
                    TileLabel("Joined with a code")
                    TileNote("Only the gateway owner can create AI keys. Ask them for one, or open the gateway console in a browser with the owner key.", maxLines = 4)
                }
            }
        }

        state.error?.let { Tile(color = signal.danger.copy(alpha = 0.12f), minHeight = 0.dp) { Text(it, color = signal.danger, style = MaterialTheme.typography.bodyMedium) } }

        if (state.isOwner && state.created == null && state.clients.isNotEmpty()) {
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            state.clients.forEach { client ->
                Tile(minHeight = 0.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(client.name, style = MaterialTheme.typography.titleMedium, color = signal.text)
                            TileNote(client.lastUsedMs?.let { "Last used ${format.format(Date(it))}" } ?: "Never used yet")
                        }
                        SecondaryButton("Revoke", { actions.revoke(client.id) }, contentColor = signal.danger)
                    }
                }
            }
        }

        Tile(color = signal.surface2, minHeight = 0.dp) {
            TileLabel("How it works")
            TileNote("Your AI app sends requests to your gateway. The gateway checks them against your switches and forwards them to this phone. Screen content goes only to the AI app you connected, through your gateway.", maxLines = 6)
        }
    }
}
