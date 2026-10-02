package io.github.aspershupadhyay.latch.session

import android.os.Build
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.protocol.ActionResult
import io.github.aspershupadhyay.latch.protocol.AppList
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.DeviceDescriptor
import io.github.aspershupadhyay.latch.protocol.DeviceInfo
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.GlobalAction
import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.Target
import kotlinx.serialization.json.JsonElement

/** Device descriptor sent in `hello` and `device.info`. */
fun deviceDescriptor() = DeviceDescriptor(
    platform = "android",
    osVersion = Build.VERSION.RELEASE.take(64).ifBlank { Build.VERSION.SDK_INT.toString() },
    model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64).ifBlank { "Android phone" },
    appVersion = BuildConfig.VERSION_NAME,
)

/** Plain-language summary of a command for approvals and the activity log. Never includes typed text. */
fun describe(command: Command): String = when (command) {
    Command.DeviceInfoCommand -> "Read device information"
    is Command.Observe -> if (command.includeScreenshot) "Read the screen and take a screenshot" else "Read the screen"
    is Command.Tap -> when (val t = command.target) {
        is Target.Element -> if (command.longPress) "Long-press an element" else "Tap an element"
        is Target.Point -> if (command.longPress) "Long-press at (${t.x}, ${t.y})" else "Tap at (${t.x}, ${t.y})"
    }
    is Command.Swipe -> "Swipe on the screen"
    is Command.TypeText -> "Type ${command.text.codePointCount(0, command.text.length)} characters"
    is Command.Global -> when (command.action) {
        GlobalAction.BACK -> "Press Back"
        GlobalAction.HOME -> "Press Home"
        GlobalAction.RECENTS -> "Open Recents"
    }
    Command.ListApps -> "List installed apps"
    is Command.LaunchApp -> "Open ${command.packageName}"
}

/**
 * Runs one command on this phone after the device-side checks. The gateway
 * has already applied policy; these checks hold even if it did not.
 */
class CommandExecutor(
    private val bridge: DeviceBridge,
    private val approvals: ApprovalBroker,
    private val log: ActivityLog,
) {
    suspend fun execute(
        envelope: CommandEnvelope,
        session: SessionInfo,
        capabilities: Map<Capability, CapabilityStatus>,
    ): JsonElement {
        val command = envelope.command
        if (session.paused) throw ProtocolException(ErrorCode.DEVICE_UNAVAILABLE, "the owner paused this session")
        if (System.currentTimeMillis() >= session.expiresAtMs) {
            throw ProtocolException(ErrorCode.DEVICE_UNAVAILABLE, "the session ended")
        }
        for (capability in command.requiredCapabilities) {
            when (capabilities[capability]) {
                CapabilityStatus.ENABLED -> Unit
                CapabilityStatus.UNSUPPORTED, null ->
                    throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone does not support ${capability.wire}")
                else -> throw ProtocolException(ErrorCode.PERMISSION_MISSING, "the owner has not allowed ${capability.wire}")
            }
        }

        val needsApproval = command.isAction && (envelope.confirm != null || session.approveEveryAction)
        if (needsApproval) {
            val title = envelope.confirm?.title ?: describe(command)
            val detail = envelope.confirm?.detail ?: "Requested by an AI agent connected through Latch."
            log.add(ActivityKind.APPROVAL, "Asked you: $title")
            // Leave the gateway a little time to receive the answer before its deadline.
            val outcome = approvals.request(title, detail, envelope.confirm?.risk ?: "medium", (envelope.deadlineMs - 2_000).coerceAtLeast(5_000))
            when (outcome) {
                ApprovalOutcome.APPROVED -> log.add(ActivityKind.APPROVAL, "You approved: $title")
                ApprovalOutcome.DENIED -> {
                    log.add(ActivityKind.APPROVAL, "You denied: $title")
                    throw ProtocolException(ErrorCode.USER_DENIED, "the owner denied this action")
                }
                ApprovalOutcome.EXPIRED -> {
                    log.add(ActivityKind.APPROVAL, "Expired without an answer: $title")
                    throw ProtocolException(ErrorCode.CONFIRMATION_EXPIRED, "the owner did not answer in time")
                }
            }
        }

        if (command == Command.DeviceInfoCommand) {
            val service = bridge.service.value
            return Protocol.json.encodeToJsonElement(
                DeviceInfo.serializer(),
                DeviceInfo(
                    device = deviceDescriptor(),
                    screen = service?.screen() ?: ScreenInfo(0, 0),
                    `package` = service?.currentPackage(),
                    capabilities = capabilities.map { (c, s) -> io.github.aspershupadhyay.latch.protocol.CapabilityState(c.wire, s.wire) },
                    session = session,
                ),
            )
        }

        val service = bridge.service.value
            ?: throw ProtocolException(ErrorCode.PERMISSION_MISSING, "the Latch accessibility service is switched off")

        val action = ActionResult()
        return when (command) {
            is Command.Observe -> {
                val observation = service.observe(command.includeScreenshot, command.maxNodes)
                log.add(ActivityKind.OBSERVE, "${describe(command)} · ${observation.`package` ?: "unknown app"}")
                Protocol.json.encodeToJsonElement(Observation.serializer(), observation)
            }
            Command.ListApps -> {
                log.add(ActivityKind.OBSERVE, describe(command))
                Protocol.json.encodeToJsonElement(AppList.serializer(), AppList(service.listApps()))
            }
            else -> {
                when (command) {
                    is Command.Tap -> service.tap(command.observationId, command.target, command.longPress)
                    is Command.Swipe -> service.swipe(command.observationId, command.fromX, command.fromY, command.toX, command.toY, command.durationMs)
                    is Command.TypeText -> service.typeText(command.observationId, command.element, command.text)
                    is Command.Global -> service.global(command.action)
                    is Command.LaunchApp -> service.launch(command.packageName)
                }
                log.add(ActivityKind.ACTION, describe(command))
                Protocol.json.encodeToJsonElement(ActionResult.serializer(), action.copy(`package` = service.currentPackage()))
            }
        }
    }
}
