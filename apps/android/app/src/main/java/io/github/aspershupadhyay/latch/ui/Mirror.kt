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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.mirror.MirrorStatus
import io.github.aspershupadhyay.latch.ui.theme.GeistMono
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/**
 * Live mirror (R30, ADR-069): start it, then either scan the QR code on the
 * computer's mirror page or open the link there. No code to type. While a
 * computer watches, files go to it with Send files (or Android's Share).
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
                    "Your computer's mirror page connects by itself as soon as you start."
                } else {
                    "See and use this phone from your computer's browser, and send files both ways."
                },
                tint = signal.live,
                onClick = actions.startMirror,
                onClickLabel = "Start mirroring to your computer",
            )
            Row(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                androidx.compose.material3.TextButton(onClick = actions.scanMirror) {
                    Text("Scan computer's code", color = signal.text2)
                }
                if (remembered) {
                    androidx.compose.material3.TextButton(onClick = actions.forgetMirrorComputers) {
                        Text("Forget computers", color = signal.text2)
                    }
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
            // The link lets a computer see and use this phone: keep it out of screenshots and casts.
            if (status.link != null) SecureWhileShown()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val (title, detail) = when (status.phase) {
                    MirrorStatus.Phase.STARTING -> "Starting the mirror…" to "Connecting to your computer."
                    MirrorStatus.Phase.WATCHING -> "Your computer is watching" to "It sees this screen and can use it. Latch's own screens and password fields stay on the phone."
                    MirrorStatus.Phase.RECONNECTING -> "Reconnecting…" to "The network dropped; the mirror comes back on its own."
                    else -> "Connect your computer" to "Open the mirror page on your computer and scan its code, or open this link there."
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = signal.text)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
                if (status.phase == MirrorStatus.Phase.WAITING) {
                    status.urls.firstOrNull()?.let { url ->
                        Text(url, fontFamily = GeistMono, style = MaterialTheme.typography.bodyMedium, color = signal.text)
                    }
                    if (status.urls.size > 1) {
                        Text(
                            "That page is on the computer running Latch. From another computer: ${status.urls[1]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = signal.text2,
                        )
                    }
                    PrimaryButton("Scan computer's code", actions.scanMirror, Modifier.fillMaxWidth())
                    if (status.link != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Copy link", actions.copyMirrorLink, Modifier.weight(1f))
                            SecondaryButton("Share link", actions.shareMirrorLink, Modifier.weight(1f))
                        }
                    }
                }
                status.sending?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = signal.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (status.phase == MirrorStatus.Phase.WATCHING) {
                        SecondaryButton("Send files", actions.sendFilesToComputer, Modifier.weight(1f), enabled = status.sending == null)
                    }
                    PrimaryButton("Stop", actions.stopMirror, Modifier.weight(1f), color = signal.danger)
                }
                if (status.link != null && status.phase == MirrorStatus.Phase.WAITING) {
                    Text(
                        "Scan only a code on your own computer's screen. The link and the scanned code let that computer see and use this phone while you mirror, now and next time, until you press Forget computers or the emergency Stop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = signal.text2,
                    )
                }
            }
        }
    }
}
