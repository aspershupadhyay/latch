// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.mirror.MirrorController
import io.github.aspershupadhyay.latch.mirror.MirrorStatus
import io.github.aspershupadhyay.latch.ui.theme.GeistMono
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/**
 * Live mirror on Home (R30): start it, see where to open it on the computer
 * and the code to type there, and stop it.
 */
@Composable
fun MirrorCard(status: MirrorStatus, actions: HomeActions, remembered: Boolean = false) {
    val signal = LocalSignal.current
    when (status.phase) {
        MirrorStatus.Phase.OFF -> Card {
            ListRow(
                icon = LatchIcons.Phone,
                title = "Mirror on computer",
                subtitle = if (remembered) {
                    "Your computer's mirror page reconnects by itself as soon as you start. No code needed."
                } else {
                    "See and use this phone from your computer's browser."
                },
                tint = signal.live,
                onClick = actions.startMirror,
                onClickLabel = "Start mirroring to your computer",
            )
            if (remembered) {
                androidx.compose.material3.TextButton(
                    onClick = actions.forgetMirrorComputers,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                ) {
                    Text("Forget computers", color = signal.text2)
                }
            }
        }
        MirrorStatus.Phase.FAILED -> Card(color = signal.warning.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
            ListRow(
                icon = LatchIcons.Warning,
                title = "Mirroring stopped",
                subtitle = (status.message ?: "Something went wrong.") + " Tap to try again.",
                tint = signal.warning,
                onClick = actions.startMirror,
                onClickLabel = "Try mirroring again",
            )
        }
        else -> Card(color = signal.live.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
            // The code and link let a computer see and use this phone: keep them out of screenshots and casts.
            if (status.code != null || status.link != null) SecureWhileShown()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val (title, detail) = when (status.phase) {
                    MirrorStatus.Phase.STARTING -> "Starting the mirror…" to "Connecting to your relay."
                    MirrorStatus.Phase.WATCHING -> "Your computer is watching" to "It sees this screen and can use it. Latch's own screens and password fields stay on the phone."
                    MirrorStatus.Phase.RECONNECTING -> "Reconnecting…" to "The network dropped; the mirror comes back on its own."
                    else -> if (remembered) {
                        "Waiting for your computer" to "A mirror page left open reconnects by itself. Another computer: open the address below and type the code."
                    } else {
                        "Open on your computer" to "In Chrome, Edge, or Safari, open the address below and type the code."
                    }
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = signal.text)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
                val url = status.urls.firstOrNull()
                if (status.phase == MirrorStatus.Phase.WAITING && url != null) {
                    Text(url, fontFamily = GeistMono, style = MaterialTheme.typography.bodyMedium, color = signal.text)
                    if (status.urls.size > 1) {
                        Text(
                            "That address works on the computer running your relay. From another computer: ${status.urls[1]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = signal.text2,
                        )
                    }
                }
                status.code?.takeIf { status.phase == MirrorStatus.Phase.WAITING }?.let { code ->
                    val shown = MirrorController.formatCode(code)
                    Text(
                        shown,
                        fontFamily = GeistMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 34.sp,
                        letterSpacing = 4.sp,
                        color = signal.text,
                        modifier = Modifier.semantics { contentDescription = "Code ${code.toList().joinToString(" ")}" },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (status.link != null && status.phase != MirrorStatus.Phase.STARTING) {
                        SecondaryButton("Copy link", actions.copyMirrorLink, Modifier.weight(1f))
                    }
                    PrimaryButton("Stop", actions.stopMirror, Modifier.weight(1f), color = signal.danger)
                }
                if (status.link != null && status.phase == MirrorStatus.Phase.WAITING) {
                    Text(
                        "The link opens the mirror without the code, now and next time. Anyone with it can see and use this phone while you mirror, until you press Forget computers or the emergency Stop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.text2,
                    )
                }
            }
        }
    }
}
