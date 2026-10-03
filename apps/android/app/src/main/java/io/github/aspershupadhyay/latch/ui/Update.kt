package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import io.github.aspershupadhyay.latch.update.UpdateInfo
import io.github.aspershupadhyay.latch.update.UpdateState

/** True when [state] has something for the owner to see or do on Home. */
fun UpdateState.shownOnHome(): Boolean = when (this) {
    is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.NeedsPermission -> true
    is UpdateState.Failed -> info != null
    UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> false
}

/** "Update available" and its progress; the same card on Home and in Settings. */
@Composable
fun UpdateCard(state: UpdateState, reducedMotion: Boolean, onInstall: (UpdateInfo) -> Unit) {
    val signal = LocalSignal.current
    val (title, detail, info) = when (state) {
        is UpdateState.Available -> Triple(
            "Update available",
            "Latch ${state.info.versionName}. Pairing, saved approvals, and settings stay. Latch restarts, so a running session stops.",
            state.info,
        )
        is UpdateState.Downloading -> Triple("Downloading update · ${state.percent}%", "Latch checks the file's fingerprint before installing.", null)
        is UpdateState.Installing -> Triple("Installing…", "Android may show its own Update screen: tap Update there.", state.info)
        is UpdateState.NeedsPermission -> Triple(
            "Allow Latch to install updates",
            "Android asks once. Switch on “Allow from this source”, come back, and tap Update.",
            state.info,
        )
        is UpdateState.Failed -> Triple("The update didn't finish", state.message, state.info)
        else -> return
    }
    Card(color = signal.accent.copy(alpha = if (signal.dark) 0.14f else 0.1f)) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            ListRow(LatchIcons.Sparkle, title, detail, tint = signal.accent)
        }
        when {
            state is UpdateState.Downloading -> Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                ProgressBar(state.percent / 100f, reducedMotion)
            }
            info != null -> Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = when (state) {
                    is UpdateState.Installing, is UpdateState.Failed -> "Try again"
                    else -> "Update"
                }
                PrimaryButton(label, { onInstall(info) })
                info.commit?.let {
                    Text("From commit $it on GitHub", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                }
            }
        }
    }
}
