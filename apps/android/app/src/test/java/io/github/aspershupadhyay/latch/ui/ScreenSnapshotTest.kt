// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.session.McpClient
import io.github.aspershupadhyay.latch.session.NewMcpClient
import io.github.aspershupadhyay.latch.session.PendingApproval
import io.github.aspershupadhyay.latch.ui.theme.LatchTheme
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens on the JVM (Robolectric native graphics) at a
 * Pixel-class size. Record with `./gradlew testDebugUnitTest -Psnapshots=record`;
 * `-Psnapshots=verify` fails on any unreviewed visual change. Images live
 * in app/src/test/snapshots.
 */
@Ignore("Run on demand: ./gradlew testDebugUnitTest --tests '*ScreenSnapshotTest' -Psnapshots=record (slow; downloads the Robolectric Android runtime). Remove once baselines are recorded.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenSnapshotTest {
    @get:org.junit.Rule
    val name = org.junit.rules.TestName()

    private fun snap(content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/${name.methodName}.png") {
            LatchTheme {
                Box(Modifier.background(LocalSignal.current.canvas)) { content() }
            }
        }

    private val now = 1_800_000_000_000L

    private fun home(phase: Phase, pending: PendingApproval? = null, accessibilityOn: Boolean = true) = HomeState(
        phase = phase,
        headline = when (phase) {
            Phase.ACTIVE -> if (pending != null) "Waiting for you" else "Session active"
            Phase.IDLE -> "Ready. Nothing is shared."
            else -> "Stopped"
        },
        detail = when (phase) {
            Phase.ACTIVE -> if (pending != null) "Nothing happens until you answer." else "An AI can use what you allowed. Stop ends everything at once."
            else -> "Start a session when you want an AI to work with this phone."
        },
        minutesLeft = if (phase == Phase.ACTIVE) 23 else null,
        sessionMinutes = 30,
        enabled = listOf(Capability.UI_OBSERVE, Capability.SCREEN_CAPTURE, Capability.INPUT_GESTURE),
        accessibilityOn = accessibilityOn,
        gatewayHost = "latch-gateway-ada.vercel.app",
        encrypted = true,
        phoneName = "Pixel 9",
        pending = pending,
        lastActivity = ActivityEntry(now, ActivityKind.ACTION, "Tap an element"),
        activityCount = 12,
        isOwner = true,
        reducedMotion = true,
    )

    @Test fun welcome() = snap { WelcomeScreen({}, {}) }

    @Test fun createGateway() = snap {
        CreateGatewayScreen(CreateGatewayState("lok_" + "a".repeat(64), keyCopied = true, deployOpened = false, address = "", busy = false, error = null), CreateGatewayActions())
    }

    @Test fun join() = snap {
        JoinGatewayScreen(JoinState("https://latch.example.com", JoinMode.PAIRING_CODE, "K7QX-M2PD", busy = false, error = "That code is wrong or expired. Create a new one."), JoinActions())
    }

    @Test fun home_idle_needsSetup() = snap { HomeScreen(home(Phase.IDLE, accessibilityOn = false), HomeActions()) }
    @Test fun home_active() = snap { HomeScreen(home(Phase.ACTIVE), HomeActions()) }
    @Test fun home_approval() = snap {
        HomeScreen(
            home(Phase.ACTIVE, PendingApproval("n", "Tap “Send” in WhatsApp", "Your AI asked to do this. It may send, call, post, delete, or change something that's hard to undo.", "high", now)),
            HomeActions(),
        )
    }

    @Test fun capabilities() = snap {
        CapabilitiesScreen(setOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE), accessibilityOn = true, approveEveryAction = false, onToggle = { _, _ -> }, onApproveEveryAction = {})
    }
    @Test fun capabilities_files() = snap {
        CapabilitiesScreen(setOf(Capability.FILE_READ), accessibilityOn = true, approveEveryAction = false, onToggle = { _, _ -> }, onApproveEveryAction = {}, openGroup = AccessGroup.FILES, onOpenGroup = {}, folders = listOf("Documents/AI", "Pictures/Posts"))
    }
    @Test fun capabilities_screen() = snap {
        CapabilitiesScreen(setOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE), accessibilityOn = true, approveEveryAction = false, onToggle = { _, _ -> }, onApproveEveryAction = {}, openGroup = AccessGroup.SCREEN, onOpenGroup = {})
    }
    @Test fun settings() = snap {
        SettingsScreen("https://latch-gateway-ada.vercel.app", "d_eaddca7638d2e9ae", "Pixel 9", true, "0.1.0-beta.16", {}, {})
    }

    @Test fun connect_created() = snap {
        ConnectScreen(
            ConnectState(
                "https://latch-gateway-ada.vercel.app/mcp", isOwner = true, clients = emptyList(), loading = false, error = null,
                created = NewMcpClient("m_1", "lmt_" + "b".repeat(64), "https://latch-gateway-ada.vercel.app/mcp"),
            ),
            ConnectActions(),
        )
    }

    @Test fun connect_list() = snap {
        ConnectScreen(
            ConnectState(
                "https://latch-gateway-ada.vercel.app/mcp", isOwner = true,
                clients = listOf(McpClient("m_1", "Claude on laptop", now, now), McpClient("m_2", "ChatGPT", now, null)),
                loading = false, error = null, created = null,
            ),
            ConnectActions(),
        )
    }

    private val setup = SetupState(
        notificationsNeeded = true, notificationsOn = true, notificationsBlocked = false, notificationsSkipped = false,
        accessibilityOn = false, batteryOn = false, batterySkipped = false, preset = null, approveEveryAction = false, reducedMotion = true,
    )

    @Test fun setup_accessibility() = snap { SetupScreen(setup, SetupActions()) }
    @Test fun setup_presets() = snap {
        SetupScreen(setup.copy(accessibilityOn = true, batteryOn = true, preset = AccessPreset.LOOK_AND_TAP), SetupActions())
    }

    @Test fun activity() = snap {
        ActivityScreen(
            listOf(
                ActivityEntry(now, ActivityKind.ACTION, "Tap an element"),
                ActivityEntry(now, ActivityKind.APPROVAL, "You approved: Tap “Send” in WhatsApp"),
                ActivityEntry(now, ActivityKind.REFUSAL, "Refused input.type: sensitive_target"),
                ActivityEntry(now, ActivityKind.SCREEN, "Read the screen and take a screenshot · com.whatsapp"),
            ),
            onClear = {},
        )
    }


    @Test fun apps() = snap {
        AppsScreen(
            listOf(
                AppRow("com.google.android.apps.maps", "Maps", sensitive = false, on = true),
                AppRow("com.android.settings", "Settings", sensitive = false, on = true),
                AppRow("com.phonepe.app", "PhonePe", sensitive = true, on = false),
                AppRow("com.whatsapp", "WhatsApp", sensitive = false, on = false),
            ),
            loading = false, onToggle = { _, _ -> }, onAllOff = {}, onBack = {},
        )
    }

    @Test fun home_update_installing() = snap {
        val info = io.github.aspershupadhyay.latch.update.UpdateInfo(117, "0.1.0-beta.17", "https://github.com/x", "a".repeat(64), 3_000_000, "abc1234")
        HomeScreen(home(Phase.IDLE).copy(update = io.github.aspershupadhyay.latch.update.UpdateState.Installing(info)), HomeActions())
    }

    @Test fun guide() = snap { GuideScreen(onBack = {}, onStart = {}) }
}
