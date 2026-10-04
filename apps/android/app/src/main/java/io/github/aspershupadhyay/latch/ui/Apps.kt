package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import io.github.aspershupadhyay.latch.data.AppCategories
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
data class AppRow(val packageName: String, val label: String, val sensitive: Boolean, val on: Boolean, val category: String = AppCategories.OTHER)

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
    trustCritical: Boolean = false,
    onTrustCritical: (Boolean) -> Unit = {},
) {
    val signal = LocalSignal.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(AppCategories.ALL) }
    val chips = remember(apps) { AppCategories.chips(apps.map { it.category }) }
    var confirm by remember { mutableStateOf<AppRow?>(null) }
    var confirmAllOff by remember { mutableStateOf(false) }
    var confirmTrust by remember { mutableStateOf(false) }
    val shown = remember(apps, query, category) {
        val q = query.trim().lowercase()
        apps.filter { category == AppCategories.ALL || it.category == category }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }
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
                        if (trustCritical) "Payments, installs, and permission pop-ups run without asking too." else "Payments, app installs, Android permission prompts, and account deletion still ask you on the phone.",
                    tint = signal.accent,
                )
            }
        }
        item {
            Card(color = if (trustCritical) signal.danger.copy(alpha = if (signal.dark) 0.16f else 0.1f) else null) {
                ListRow(
                    LatchIcons.Warning,
                    "Also allow payments and permissions",
                    if (trustCritical) {
                        "On: in switched-on apps the AI also pays, installs, answers permission pop-ups, and deletes accounts without asking you."
                    } else {
                        "Off: those ask you on the phone. Latch never types passwords, PINs, or one-time codes either way."
                    },
                    tint = if (trustCritical) signal.danger else signal.warning,
                    trailing = {
                        LatchSwitch(
                            checked = trustCritical,
                            onCheckedChange = { on -> if (on) confirmTrust = true else onTrustCritical(false) },
                            modifier = Modifier.semantics { contentDescription = "Also allow payments and permissions without asking" },
                        )
                    },
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
        if (!loading && chips.size > 2) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.first }) { (name, count) ->
                        val selected = name == category
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (selected) signal.ink else signal.text2.copy(alpha = 0.12f))
                                .selectable(selected = selected, role = Role.Tab) { category = name }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("$name $count", style = MaterialTheme.typography.labelLarge, color = if (selected) signal.canvas else signal.text, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (loading) {
            // Placeholder rows in the shape of the list, instead of a "loading" line.
            items(PLACEHOLDER_ROWS) { AppRowPlaceholder() }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    if (query.isNotBlank()) "No app matches “${query.trim()}”." else "No apps in $category.",
                    style = MaterialTheme.typography.bodyMedium, color = signal.text2, modifier = Modifier.padding(8.dp),
                )
            }
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, color = signal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (app.sensitive) {
                        Pill("Money or accounts", signal.warning, LatchIcons.Warning)
                    } else {
                        Text(app.category, style = MaterialTheme.typography.bodySmall, color = signal.text2, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(12.dp))
                LatchSwitch(
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
    if (confirmTrust) {
        AlertDialog(
            onDismissRequest = { confirmTrust = false },
            title = { Text("Never ask, even for money?") },
            text = {
                Text(
                    "In apps you switched on, the AI will pay, transfer, buy, install apps, answer Android permission pop-ups, and delete accounts without asking you. " +
                        "Text on a screen, like a message or a website, can trick an AI into doing these. " +
                        "Latch still never types passwords, PINs, or one-time codes, so payments that need your PIN still need you. " +
                        "You can switch this off at any time; Stop ends everything at once.",
                )
            },
            confirmButton = { TextButton(onClick = { onTrustCritical(true); confirmTrust = false }) { Text("Allow without asking", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmTrust = false }) { Text("Keep asking") } },
        )
    }
    if (confirmAllOff) {
        AlertDialog(
            onDismissRequest = { confirmAllOff = false },
            title = { Text("Switch all apps off?") },
            text = { Text("The AI will have to ask you again before using any app, and payments and permissions ask again too.") },
            confirmButton = { TextButton(onClick = { onAllOff(); confirmAllOff = false }) { Text("Switch all off", color = signal.danger) } },
            dismissButton = { TextButton(onClick = { confirmAllOff = false }) { Text("Cancel") } },
        )
    }
}

private const val PLACEHOLDER_ROWS = 8

/** A grey row in the shape of an app row, gently pulsing while the list loads. */
@Composable
private fun AppRowPlaceholder() {
    val signal = LocalSignal.current
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = 0.45f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    val shade = signal.text2.copy(alpha = 0.16f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp).alpha(pulse)
            .semantics { contentDescription = "Loading apps" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(shade))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(shade))
            Box(Modifier.fillMaxWidth(0.3f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(shade))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(width = 48.dp, height = 28.dp).clip(CircleShape).background(shade))
    }
}
