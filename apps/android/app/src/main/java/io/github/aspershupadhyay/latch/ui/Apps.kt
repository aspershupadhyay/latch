package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/** One installed app on the Apps screen. [label] is the app's own (untrusted) name. */
data class AppRow(val packageName: String, val label: String, val sensitive: Boolean, val on: Boolean)

/**
 * Every launchable app with a switch (ADR-021). On: the AI uses the app
 * without asking. Off: the phone asks the first time the AI needs it. Apps
 * that may hold money, accounts, or passwords get an extra warning first.
 */
@Composable
fun AppsScreen(
    apps: List<AppRow>,
    loading: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onAllOff: () -> Unit,
    onBack: () -> Unit,
    icon: @Composable (String) -> ImageBitmap? = { null },
) {
    val signal = LocalSignal.current
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AppRow?>(null) }
    var confirmAllOff by remember { mutableStateOf(false) }
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }
            .sortedWith(compareBy({ !it.on }, { it.label.lowercase() }))
    }
    val onCount = apps.count { it.on }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Access") }
            }
            ScreenTitle("Apps the AI can use", "$onCount switched on. Others ask you the first time the AI needs them.")
        }
        item {
            Card(color = signal.accent.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
                ListRow(
                    LatchIcons.ShieldCheck,
                    "Switched on means no questions",
                    "In these apps the AI reads the screen, taps, types, sends, and deletes without asking, and every action is listed in Activity. " +
                        "Payments, app installs, Android permission prompts, and account deletion still ask you on the phone every time.",
                    tint = signal.accent,
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (loading) {
            item { Text("Loading apps…", style = MaterialTheme.typography.bodyMedium, color = signal.text2) }
        }
        items(shown, key = { it.packageName }) { app ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bitmap = icon(app.packageName)
                Box(Modifier.size(40.dp)) {
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
                    } else {
                        IconBadge(LatchIcons.Apps, signal.text2)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, color = signal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (app.sensitive) {
                        Pill("Money or accounts", signal.warning, LatchIcons.Warning)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = app.on,
                    onCheckedChange = { on -> if (on && app.sensitive) confirm = app else onToggle(app.packageName, on) },
                    modifier = Modifier.semantics { contentDescription = "Let the AI use ${app.label}" },
                )
            }
        }
        if (onCount > 0) {
            item {
                TextButton(onClick = { confirmAllOff = true }, modifier = Modifier.fillMaxWidth()) { Text("Switch all off", color = signal.danger) }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }

    confirm?.let { app ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Let the AI use ${app.label}?") },
            text = {
                Text(
                    "${app.label} may hold money, accounts, or passwords. The AI will see its screens, including balances and messages, " +
                        "and act in it without asking. Payments and transfers still ask you on the phone every time. " +
                        "Switch it on only if you will ask the AI to work in this app.",
                )
            },
            confirmButton = { TextButton(onClick = { onToggle(app.packageName, true); confirm = null }) { Text("Switch on", color = signal.warning) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    if (confirmAllOff) {
        AlertDialog(
            onDismissRequest = { confirmAllOff = false },
            title = { Text("Switch all apps off?") },
            text = { Text("The AI will have to ask you again before using any app.") },
            confirmButton = { TextButton(onClick = { onAllOff(); confirmAllOff = false }) { Text("Switch all off", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmAllOff = false }) { Text("Cancel") } },
        )
    }
}
