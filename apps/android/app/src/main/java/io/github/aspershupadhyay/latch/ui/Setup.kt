package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/** Starting points for what an AI may do. The owner can fine-tune each switch later. */
enum class AccessPreset(val title: String, val note: String, val icon: ImageVector, val capabilities: Set<Capability>) {
    LOOK("Just look", "Read the screen. Nothing else.", LatchIcons.Eye, setOf(Capability.UI_OBSERVE)),
    LOOK_AND_TAP(
        "Look and tap",
        "Read, tap, scroll, go back, open apps.",
        LatchIcons.Tap,
        setOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE, Capability.NAV_GLOBAL, Capability.APP_LAUNCH),
    ),
    EVERYTHING(
        "Everything",
        "Also screenshots and typing. Risky actions still ask you.",
        LatchIcons.Sparkle,
        Capability.entries.toSet() - Capability.DEVICE_INFO,
    ),
    ;

    companion object {
        /** The preset that matches exactly what is switched on, if any. */
        fun matching(enabled: Set<Capability>): AccessPreset? = entries.firstOrNull { it.capabilities == enabled - Capability.DEVICE_INFO }
    }
}

enum class SetupStep { NOTIFICATIONS, ACCESSIBILITY, BATTERY, ACCESS }

data class SetupState(
    /** False below Android 13, where apps may post notifications without asking. */
    val notificationsNeeded: Boolean,
    val notificationsOn: Boolean,
    /** The system will not show its dialog again; only Settings can change it now. */
    val notificationsBlocked: Boolean,
    val notificationsSkipped: Boolean,
    val accessibilityOn: Boolean,
    val batteryOn: Boolean,
    val batterySkipped: Boolean,
    val preset: AccessPreset?,
    val approveEveryAction: Boolean,
    val reducedMotion: Boolean,
) {
    fun done(step: SetupStep) = when (step) {
        SetupStep.NOTIFICATIONS -> !notificationsNeeded || notificationsOn || notificationsSkipped
        SetupStep.ACCESSIBILITY -> accessibilityOn
        SetupStep.BATTERY -> batteryOn || batterySkipped
        SetupStep.ACCESS -> preset != null
    }

    val current: SetupStep? get() = SetupStep.entries.firstOrNull { !done(it) }
    val doneCount: Int get() = SetupStep.entries.count { done(it) }
    val complete: Boolean get() = current == null
}

class SetupActions(
    val allowNotifications: () -> Unit = {},
    val openNotificationSettings: () -> Unit = {},
    val skipNotifications: () -> Unit = {},
    val openAccessibility: () -> Unit = {},
    val openAppInfo: () -> Unit = {},
    val allowBattery: () -> Unit = {},
    val skipBattery: () -> Unit = {},
    val choosePreset: (AccessPreset) -> Unit = {},
    val setApproveEveryAction: (Boolean) -> Unit = {},
    val finish: () -> Unit = {},
    val later: () -> Unit = {},
)

/**
 * Asks for everything Latch needs, one step at a time, right after pairing:
 * nothing is switched on silently, and each step says why it is needed.
 */
@Composable
fun SetupScreen(state: SetupState, actions: SetupActions) {
    val signal = LocalSignal.current
    Box(Modifier.fillMaxSize().background(signal.canvas)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(LatchIcons.ShieldCheck, signal.accent, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Set up your phone", style = MaterialTheme.typography.headlineSmall, color = signal.text, modifier = Modifier.semantics { heading() })
                    Text("Nothing turns on without you.", style = MaterialTheme.typography.bodyMedium, color = signal.text2)
                }
            }
            Text(
                if (state.complete) "All set" else "${state.doneCount} of ${SetupStep.entries.size} done",
                style = MaterialTheme.typography.labelLarge,
                color = if (state.complete) signal.success else signal.text2,
            )
            ProgressBar(state.doneCount / SetupStep.entries.size.toFloat(), state.reducedMotion, if (state.complete) signal.success else signal.accent)
            Spacer(Modifier.size(4.dp))

            StepCard(
                state, SetupStep.NOTIFICATIONS, LatchIcons.Bell, "Notifications",
                if (!state.notificationsNeeded) "Allowed by your Android version." else "Shows a Stop button while a session runs.",
            ) {
                if (state.notificationsBlocked) {
                    StepText("Android won't ask again. Turn on notifications for Latch in Settings.")
                    StepButtons(primary = "Open settings" to actions.openNotificationSettings, secondary = "Skip" to actions.skipNotifications)
                } else {
                    StepText("You'll always see when a session is running, and can stop it from the notification.")
                    StepButtons(primary = "Allow notifications" to actions.allowNotifications, secondary = "Skip" to actions.skipNotifications)
                }
            }

            StepCard(state, SetupStep.ACCESSIBILITY, LatchIcons.Person, "Screen access", "Required. Lets Latch read the screen and tap for you.") {
                StepText("Android allows this only through the accessibility service. Latch uses it only during a session you start, and never in password, PIN, or payment fields.")
                StepButtons(primary = "Open accessibility settings" to actions.openAccessibility)
                Hint(
                    "Says “Restricted setting”?",
                    "Tap App info below → ⋮ (top right) → Allow restricted settings. Then come back and try again.",
                )
                TextButton(onClick = actions.openAppInfo) { Text("Open App info") }
            }

            StepCard(state, SetupStep.BATTERY, LatchIcons.Battery, "Stay connected", "Keeps a session alive when the screen is off.") {
                StepText("Some phones close apps in the background to save battery. Allowing Latch keeps your session from dropping. It only runs during sessions.")
                StepButtons(primary = "Allow" to actions.allowBattery, secondary = "Skip" to actions.skipBattery)
            }

            StepCard(state, SetupStep.ACCESS, LatchIcons.Sliders, "What the AI may do", "Pick a starting point. Change it any time.") {
                AccessPreset.entries.forEach { preset ->
                    PresetOption(preset, selected = state.preset == preset) { actions.choosePreset(preset) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask me before every action", style = MaterialTheme.typography.titleMedium, color = signal.text)
                        Text("Otherwise only send, buy, delete, and similar ask you.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                    }
                    Switch(
                        checked = state.approveEveryAction,
                        onCheckedChange = actions.setApproveEveryAction,
                        modifier = Modifier.semantics { contentDescription = "Ask me before every action" },
                    )
                }
            }

            Spacer(Modifier.size(4.dp))
            AnimatedVisibility(state.complete, enter = if (state.reducedMotion) EnterTransition.None else fadeIn() + expandVertically()) {
                PrimaryButton("Done, take me home", actions.finish, Modifier.fillMaxWidth(), color = signal.success, contentColor = if (signal.dark) Color.Black else Color.White)
            }
            if (!state.complete) {
                TextButton(onClick = actions.later, modifier = Modifier.fillMaxWidth()) {
                    Text("Finish later", color = signal.text2)
                }
                Text(
                    "Until screen access is on, an AI can't see or do anything on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = signal.text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    state: SetupState,
    step: SetupStep,
    icon: ImageVector,
    title: String,
    summary: String,
    body: @Composable () -> Unit,
) {
    val signal = LocalSignal.current
    val done = state.done(step)
    val current = state.current == step
    val ring by animateColorAsState(if (current) signal.accent else Color.Transparent, tween(if (state.reducedMotion) 0 else 300), label = "stepBorder")
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(signal.surface).border(2.dp, ring, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (done) signal.success else if (current) signal.accent else signal.text2)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = signal.text)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = signal.text2)
            }
            AnimatedContent(
                targetState = done,
                transitionSpec = {
                    if (state.reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                    else (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith fadeOut()
                },
                label = "stepMark",
            ) { isDone ->
                if (isDone) DoneMark() else StepNumber(step.ordinal + 1, current)
            }
        }
        AnimatedVisibility(
            visible = current,
            enter = if (state.reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
            exit = if (state.reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { body() }
        }
    }
}

@Composable
private fun StepNumber(n: Int, current: Boolean) {
    val signal = LocalSignal.current
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (current) signal.accent else signal.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = MaterialTheme.typography.labelLarge, color = if (current) (if (signal.dark) Color.Black else Color.White) else signal.text2)
    }
}

@Composable
private fun StepText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalSignal.current.text2)
}

@Composable
private fun StepButtons(primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        PrimaryButton(primary.first, primary.second, Modifier.weight(2f))
        secondary?.let { SecondaryButton(it.first, it.second, Modifier.weight(1f)) }
    }
}

@Composable
private fun Hint(title: String, text: String) {
    val signal = LocalSignal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)).padding(12.dp),
    ) {
        Icon(LatchIcons.Info, contentDescription = null, tint = signal.warning, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = signal.text)
            Text(text, style = MaterialTheme.typography.bodySmall, color = signal.text2)
        }
    }
}

@Composable
private fun PresetOption(preset: AccessPreset, selected: Boolean, onClick: () -> Unit) {
    val signal = LocalSignal.current
    val ring by animateColorAsState(if (selected) signal.accent else signal.border, label = "presetBorder")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .border(if (selected) 2.dp else 1.dp, ring, RoundedCornerShape(18.dp))
            .background(if (selected) signal.accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClickLabel = preset.title, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(preset.icon, if (selected) signal.accent else signal.text2, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.title, style = MaterialTheme.typography.titleMedium, color = signal.text)
            Text(preset.note, style = MaterialTheme.typography.bodySmall, color = signal.text2)
        }
        if (selected) Icon(LatchIcons.Check, contentDescription = "Selected", tint = signal.accent, modifier = Modifier.size(22.dp))
    }
}
