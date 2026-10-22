// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import kotlinx.coroutines.launch

/**
 * "Send to computer" in Android's Share sheet (ADR-069): files shared from
 * any app go to the computer watching this phone's mirror and are saved in
 * its downloads. It stays open while they go, since Android lets Latch read
 * a shared file only while this screen lives.
 */
class SendToComputerActivity : ComponentActivity() {
    private val result = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LatchApp
        val uris = shared(intent)
        setContent {
            LatchTheme {
                val signal = LocalSignal.current
                val mirror by app.mirror.status.collectAsStateWithLifecycle()
                val done by result
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Card(Modifier.widthIn(max = 480.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Send to computer", style = MaterialTheme.typography.titleMedium, color = signal.text)
                            val line = done ?: mirror.sending ?: "Getting ready…"
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = signal.text2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (done == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Keep this open until the files are sent.", style = MaterialTheme.typography.bodySmall, color = signal.text2)
                            } else {
                                PrimaryButton("Done", { finish() }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                result.value = if (uris.isEmpty()) "Nothing to send." else app.mirror.sendToComputer(uris)
            }
        }
    }

    private fun shared(intent: Intent): List<Uri> {
        @Suppress("DEPRECATION")
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
                    ?.toList().orEmpty()
            else -> emptyList()
        }
    }
}
