// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/** A short explanation behind an ⓘ button, in words a child can follow. */
enum class HelpTopic(val title: String, val paragraphs: List<String>) {
    RELAY(
        "What is the relay?",
        listOf(
            "Your AI app (like Claude or ChatGPT) cannot reach your phone directly. The relay is a small, free website that belongs to you. It passes messages between the AI and this phone, like a post office that only you own.",
            "Nobody else can use it, not even the people who make Latch.",
            "Its web address ends in .vercel.app, for example latch-gateway-ada.vercel.app. You get it at the end of the setup.",
        ),
    ),
    SECRET_KEY(
        "What is the secret key?",
        listOf(
            "It's like the master key to your relay. Whoever has it can connect phones and AI apps to your relay.",
            "Latch made it for you on this phone. You paste it once into Vercel while you create the relay. After that, keep it private, like a password.",
        ),
    ),
    JOIN_CODE(
        "What is a join code?",
        listOf(
            "A short one-time code, like ABCD-EFGH. Someone who owns a relay can make one to let your phone join it.",
            "Ask them to open their relay page and tap “Add a phone”. The code works once and expires after 10 minutes.",
        ),
    ),
    AI_LINK(
        "What is the AI link?",
        listOf(
            "It's the web address you give your AI app so it can reach this phone through your relay.",
            "In Claude: Settings → Connectors → Add custom connector, then paste the link. Other AI apps have a similar “add a connector” or “MCP server” option.",
            "Your AI app then shows a 4-letter code. When the same code appears in Latch, tap Approve.",
        ),
    ),
    SCREEN_ACCESS(
        "Why does Latch need screen access?",
        listOf(
            "To see what is on the screen and to tap for the AI, Latch uses Android's accessibility permission.",
            "It only works during a session you start, only in apps you allow, and Latch never reads passwords, PINs, or one-time codes.",
        ),
    ),
    APPS(
        "Choosing apps",
        listOf(
            "The AI can only use apps you switch on. In those apps it works without asking you every time, and everything it does is listed in Activity.",
            "If the AI needs an app that's off, Latch asks you first, on the phone and in your AI chat.",
        ),
    ),
    AUTO(
        "What is Auto mode?",
        listOf(
            "With Auto mode on, the AI can use every app and do everything in them without asking, including paying, installing apps, and answering Android's permission pop-ups.",
            "Use it only when you trust the task. Latch still never types passwords, PINs, or one-time codes, the red Stop button still ends everything at once, and every action is listed in Activity.",
        ),
    ),
}

/** The ⓘ button: opens a calm sheet that explains [topic]. */
@Composable
fun InfoButton(topic: HelpTopic, modifier: Modifier = Modifier) {
    InfoSheetButton(topic.title, modifier) {
        topic.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = LocalSignal.current.text2) }
    }
}

/** One thing a setting allows, said in one line. */
data class Feature(val icon: ImageVector, val title: String, val line: String)

/**
 * The ⓘ button of an Access group: a sheet listing, one line each, every
 * thing the group's switch allows. [onPastel] draws it on a pastel card.
 */
@Composable
fun FeatureInfoButton(title: String, intro: String, features: List<Feature>, modifier: Modifier = Modifier, onPastel: Boolean = false) {
    InfoSheetButton(title, modifier, onPastel) {
        val signal = LocalSignal.current
        Text(intro, style = MaterialTheme.typography.bodyLarge, color = signal.text2)
        features.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(f.icon, signal.accent, size = 32.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = MaterialTheme.typography.titleMedium, color = signal.text)
                    Text(f.line, style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheetButton(title: String, modifier: Modifier = Modifier, onPastel: Boolean = false, body: @Composable () -> Unit) {
    val signal = LocalSignal.current
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = modifier.size(40.dp).then(if (onPastel) Modifier.padding(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)) else Modifier),
    ) {
        Icon(LatchIcons.Info, contentDescription = "Explain: $title", tint = if (onPastel) signal.onPastel else signal.accent, modifier = Modifier.size(20.dp))
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = signal.surface) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = signal.text, modifier = Modifier.semantics { heading() })
                body()
                PrimaryButton("Got it", { open = false }, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/**
 * A text field with its name, an ⓘ button, and one line under it that says
 * what goes in it, so nobody has to guess.
 */
@Composable
fun LabeledField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    topic: HelpTopic? = null,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle? = null,
) {
    val signal = LocalSignal.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = signal.text, modifier = Modifier.weight(1f))
            topic?.let { InfoButton(it) }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it, color = signal.text2.copy(alpha = 0.7f)) } },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            textStyle = textStyle ?: MaterialTheme.typography.bodyLarge,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = signal.accent,
                unfocusedBorderColor = signal.border,
                focusedContainerColor = signal.surface,
                unfocusedContainerColor = signal.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(hint, style = MaterialTheme.typography.bodySmall, color = signal.text2)
    }
}

/** One step of the guide: a number, a title, and a few plain sentences. */
private data class GuideStep(val title: String, val text: String)

private val GUIDE = listOf(
    GuideStep(
        "What you need",
        "This phone, about 5 minutes, and a free Vercel account (you can make one while you go, with Google or GitHub). " +
            "If someone already has a relay for you, you only need their join code.",
    ),
    GuideStep(
        "Make your relay",
        "Tap “Set up Latch”, then “Copy” next to your secret key. Tap “Open Vercel”. " +
            "On Vercel, paste the key where it asks for LATCH_ADMIN_TOKEN, keep everything else as it is, and tap Deploy. Wait about a minute.",
    ),
    GuideStep(
        "Bring the address back",
        "When Vercel shows “Congratulations”, copy the web address it shows (it ends in .vercel.app). " +
            "Come back to Latch, paste it, and tap “Connect”.",
    ),
    GuideStep(
        "Let Latch see the screen",
        "Latch takes you to Android's settings. Turn on “Latch remote control”. " +
            "If Android says the setting is restricted, open App info, tap the three dots at the top, choose “Allow restricted settings”, and try again.",
    ),
    GuideStep(
        "Choose apps",
        "In the Access tab, open “Apps the AI can use” and switch on only the apps you want the AI to help with.",
    ),
    GuideStep(
        "Connect your AI app",
        "In the Connect tab, copy the AI link and add it to your AI app (in Claude: Settings → Connectors → Add custom connector). " +
            "When a 4-letter code shows in both places, tap Approve in Latch.",
    ),
    GuideStep(
        "Start and stop",
        "On Home, tap “Start session” and ask your AI for something, like “open Maps and find a coffee shop”. " +
            "The red Stop button on the screen ends everything at once.",
    ),
)

/** "How to set up Latch": the whole setup in plain words, one step at a time. */
@Composable
fun GuideScreen(onBack: () -> Unit, onStart: (() -> Unit)? = null) {
    val signal = LocalSignal.current
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = signal.accent) }
            ScreenTitle("How to set up Latch", "Seven short steps. You can stop at any time; nothing is shared until you start a session.")
            GUIDE.forEachIndexed { index, step ->
                Card {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(signal.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(step.title, style = MaterialTheme.typography.titleMedium, color = signal.text)
                            Text(step.text, style = MaterialTheme.typography.bodyMedium, color = signal.text2)
                        }
                    }
                }
            }
            onStart?.let { PrimaryButton("Set up Latch", it, Modifier.fillMaxWidth()) }
            Spacer(Modifier.size(16.dp))
        }
    }
}
