package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.session.ApprovalChoice
import io.github.aspershupadhyay.latch.session.ApprovalKind
import io.github.aspershupadhyay.latch.session.PendingApproval
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/** Plain-language heading for a question, by what it is about. */
fun approvalHeading(p: PendingApproval): String = when {
    p.kind == ApprovalKind.OWNER_TASK -> "Your AI needs you"
    p.kind == ApprovalKind.APP -> "The AI wants to use an app"
    p.risk == "high" -> "Check this before it happens"
    else -> "The AI is asking you"
}

/**
 * The question card inside the app, in the same calm style as the rest of
 * Latch: a quiet card, the question in plain words, and clear answers.
 * The floating card over other apps mirrors it (LatchAccessibilityService).
 */
@Composable
fun ApprovalCard(p: PendingApproval, answer: (String, ApprovalChoice) -> Unit) {
    val signal = LocalSignal.current
    val tint = if (p.risk == "high") signal.warning else signal.accent
    Card(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (p.kind == ApprovalKind.APP) LatchIcons.Apps else LatchIcons.ShieldCheck, tint, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(approvalHeading(p), style = MaterialTheme.typography.labelLarge, color = tint)
            }
            Text(p.title, style = MaterialTheme.typography.titleLarge, color = signal.text)
            Text(p.detail, style = MaterialTheme.typography.bodyMedium, color = signal.text2)
            if (p.kind == ApprovalKind.OWNER_TASK) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("I can't", { answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = signal.text2)
                    PrimaryButton("Done", { answer(p.nonce, ApprovalChoice.ONCE) }, Modifier.weight(1f))
                }
                return@Column
            }
            if (p.kind == ApprovalKind.APP) {
                PrimaryButton("Allow always", { answer(p.nonce, ApprovalChoice.ALWAYS) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Just this session", { answer(p.nonce, ApprovalChoice.SESSION) }, Modifier.weight(1f))
                    SecondaryButton("Not now", { answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = signal.text2)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Don't allow", { answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = signal.text2)
                    PrimaryButton("Allow", { answer(p.nonce, ApprovalChoice.ONCE) }, Modifier.weight(1f))
                }
                if (p.rememberable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("This session", { answer(p.nonce, ApprovalChoice.SESSION) }, Modifier.weight(1f))
                        SecondaryButton("Always in ${p.appName ?: "this app"}", { answer(p.nonce, ApprovalChoice.ALWAYS) }, Modifier.weight(1f))
                    }
                } else {
                    Text("Latch asks every time for this kind of action.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
            }
            Text("You can also answer in your AI chat if you turned that on.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
        }
    }
}
