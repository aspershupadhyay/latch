// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
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
 * The question card inside the app. Two answers carry the weight: no, and the
 * smallest yes (once, or this session for an app). Wider answers ("always")
 * wait behind More choices, so the quick tap is always the safe one. Risky
 * actions (money, deleting, permissions) get an amber card that looks
 * different from routine ones, because people stop seeing a warning that
 * always looks the same. The floating card over other apps mirrors it
 * (LatchAccessibilityService.showApproval).
 */
@Composable
fun ApprovalCard(p: PendingApproval, answer: (String, ApprovalChoice) -> Unit) {
    val signal = LocalSignal.current
    val high = p.risk == "high"
    val tint = if (high) signal.warning else signal.accent
    var more by rememberSaveable(p.nonce) { mutableStateOf(false) }
    Card(
        Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
            .then(if (high) Modifier.border(2.dp, signal.warning, CardShape) else Modifier),
        color = if (high) signal.warning.copy(alpha = if (signal.dark) 0.12f else 0.08f) else null,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    when {
                        p.kind == ApprovalKind.APP -> LatchIcons.Apps
                        high -> LatchIcons.Warning
                        else -> LatchIcons.ShieldCheck
                    },
                    tint,
                    size = 32.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(approvalHeading(p), style = MaterialTheme.typography.labelLarge, color = tint)
            }
            Text(p.title, style = MaterialTheme.typography.titleLarge, color = signal.text)
            Text(p.detail, style = MaterialTheme.typography.bodyMedium, color = if (high) signal.text else signal.text2)
            when {
                p.kind == ApprovalKind.OWNER_TASK -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("I can't", { answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = signal.text2)
                    PrimaryButton("Done", { answer(p.nonce, ApprovalChoice.ONCE) }, Modifier.weight(1f))
                }
                p.kind == ApprovalKind.APP -> {
                    // An app is allowed for a while, never for one command: that would ask again at once.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Not now", { answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = signal.text2)
                        PrimaryButton("This session", { answer(p.nonce, ApprovalChoice.SESSION) }, Modifier.weight(1f))
                    }
                    MoreChoices(more, { more = it }) {
                        SecondaryButton("Always allow this app", { answer(p.nonce, ApprovalChoice.ALWAYS) }, Modifier.fillMaxWidth())
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Don't allow", { answer(p.nonce, ApprovalChoice.DENY) }, Modifier.weight(1f), contentColor = signal.text2)
                        PrimaryButton("Allow once", { answer(p.nonce, ApprovalChoice.ONCE) }, Modifier.weight(1f))
                    }
                    if (p.rememberable) {
                        MoreChoices(more, { more = it }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SecondaryButton("This session", { answer(p.nonce, ApprovalChoice.SESSION) }, Modifier.weight(1f))
                                SecondaryButton("Always in ${p.appName ?: "this app"}", { answer(p.nonce, ApprovalChoice.ALWAYS) }, Modifier.weight(1f))
                            }
                        }
                    } else {
                        Text("Latch asks every time for this kind of action.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                    }
                }
            }
        }
    }
}

/** "More choices": the wider answers, folded until the owner asks for them. */
@Composable
private fun MoreChoices(open: Boolean, setOpen: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val signal = LocalSignal.current
    if (open) {
        content()
    } else {
        TextButton(onClick = { setOpen(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("More choices", style = MaterialTheme.typography.labelLarge, color = signal.text2)
        }
    }
}
