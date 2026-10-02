package io.github.aspershupadhyay.latch.ui

import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aspershupadhyay.latch.LatchApp
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = LatchApp.get(this)
        val reducedMotion = AndroidSettings.Global.getFloat(contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            LatchTheme {
                LatchRoot(app, reducedMotion)
            }
        }
    }
}

enum class Tab(val label: String) { HOME("Home"), CAPABILITIES("Capabilities"), ACTIVITY("Activity"), SETTINGS("Settings") }

@Composable
fun LatchRoot(app: LatchApp, reducedMotion: Boolean) {
    val state by app.session.state.collectAsStateWithLifecycle()
    val pairing by app.settings.pairing.collectAsStateWithLifecycle()
    if (pairing == null && state !is SessionState.Revoked) {
        PairScreen(app)
        return
    }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { TabDot(selected = tab == t) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(app, reducedMotion, onOpenCapabilities = { tab = Tab.CAPABILITIES })
                Tab.CAPABILITIES -> CapabilitiesScreen(app)
                Tab.ACTIVITY -> ActivityScreen(app)
                Tab.SETTINGS -> SettingsScreen(app)
            }
        }
    }
}

@Composable
private fun TabDot(selected: Boolean) {
    val signal = LocalSignal.current
    Box(Modifier.size(if (selected) 10.dp else 8.dp).clip(CircleShape).background(if (selected) signal.accent else signal.disabled))
}
