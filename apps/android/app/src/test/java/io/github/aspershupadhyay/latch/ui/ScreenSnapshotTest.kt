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
@Ignore("Baseline images not recorded yet: Robolectric's Android runtime download was rate-limited. Record with -Psnapshots=record.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenSnapshotTest {
    @get:org.junit.Rule
    val name = org.junit.rules.TestName()

    private fun snap(dark: Boolean, content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/${name.methodName}.png") {
            LatchTheme(dark = dark) {
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

    @Test fun welcome_light() = snap(false) { WelcomeScreen({}, {}) }
    @Test fun welcome_dark() = snap(true) { WelcomeScreen({}, {}) }

    @Test fun createGateway_light() = snap(false) {
        CreateGatewayScreen(CreateGatewayState("lok_" + "a".repeat(64), keyCopied = true, deployOpened = false, address = "", busy = false, error = null), CreateGatewayActions())
    }

    @Test fun join_dark() = snap(true) {
        JoinGatewayScreen(JoinState("https://latch.example.com", JoinMode.PAIRING_CODE, "K7QX-M2PD", busy = false, error = "That code is wrong or expired. Create a new one."), JoinActions())
    }

    @Test fun home_idle_needsSetup_light() = snap(false) { HomeScreen(home(Phase.IDLE, accessibilityOn = false), HomeActions()) }
    @Test fun home_active_dark() = snap(true) { HomeScreen(home(Phase.ACTIVE), HomeActions()) }
    @Test fun home_approval_light() = snap(false) {
        HomeScreen(
            home(Phase.ACTIVE, PendingApproval("n", "Tap “Send” in WhatsApp", "Requested by an AI agent connected through Latch. This control may send, buy, delete, publish, or change something that is hard to undo.", "high", now)),
            HomeActions(),
        )
    }

    @Test fun capabilities_light() = snap(false) {
        CapabilitiesScreen(setOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE), accessibilityOn = true, approveEveryAction = false, onToggle = { _, _ -> }, onApproveEveryAction = {})
    }

    @Test fun connect_created_dark() = snap(true) {
        ConnectScreen(
            ConnectState(
                "https://latch-gateway-ada.vercel.app/mcp", isOwner = true, clients = emptyList(), loading = false, error = null,
                created = NewMcpClient("m_1", "lmt_" + "b".repeat(64), "https://latch-gateway-ada.vercel.app/mcp"),
            ),
            ConnectActions(),
        )
    }

    @Test fun connect_list_light() = snap(false) {
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

    @Test fun setup_accessibility_dark() = snap(true) { SetupScreen(setup, SetupActions()) }
    @Test fun setup_presets_light() = snap(false) {
        SetupScreen(setup.copy(accessibilityOn = true, batteryOn = true, preset = AccessPreset.LOOK_AND_TAP), SetupActions())
    }

    @Test fun activity_dark() = snap(true) {
        ActivityScreen(
            listOf(
                ActivityEntry(now, ActivityKind.ACTION, "Tap an element"),
                ActivityEntry(now, ActivityKind.APPROVAL, "You approved: Tap “Send” in WhatsApp"),
                ActivityEntry(now, ActivityKind.REFUSAL, "Refused input.type: sensitive_target"),
                ActivityEntry(now, ActivityKind.OBSERVE, "Read the screen and take a screenshot · com.whatsapp"),
            ),
            onClear = {},
        )
    }
}
