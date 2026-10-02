package io.github.aspershupadhyay.latch.session

import android.os.Build
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.ApprovalGrants
import io.github.aspershupadhyay.latch.policy.Consequence
import io.github.aspershupadhyay.latch.policy.Consequences
import io.github.aspershupadhyay.latch.policy.Judgement
import io.github.aspershupadhyay.latch.protocol.ActionResult
import io.github.aspershupadhyay.latch.protocol.AppList
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.Command
import io.github.aspershupadhyay.latch.protocol.CommandEnvelope
import io.github.aspershupadhyay.latch.protocol.DeviceDescriptor
import io.github.aspershupadhyay.latch.protocol.DeviceInfo
import io.github.aspershupadhyay.latch.protocol.ErrorBody
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.GlobalAction
import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.ObserveAfter
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.Target
import kotlinx.coroutines.delay
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
private const val AGENT_DETAIL = "Requested by an AI agent connected through Latch."
private const val CONSEQUENTIAL_DETAIL =
    "Requested by an AI agent connected through Latch. This control may send, call, post, delete, or change something that is hard to undo."
private const val CRITICAL_DETAIL =
    "Requested by an AI agent connected through Latch. This involves money, app installs, permissions, or account deletion, so Latch asks every time."

class CommandExecutor(
    private val bridge: DeviceBridge,
    private val approvals: ApprovalBroker,
    private val log: ActivityLog,
    private val grants: ApprovalGrants,
    private val consequences: Consequences,
    /** Human name of an app, for the "Always in …" button. */
    private val appName: (String) -> String? = { null },
) {
    /**
     * [current] reads the owner's session and switches again later, so an
     * observation after an action respects a pause or switch made meanwhile.
     */
    suspend fun execute(
        envelope: CommandEnvelope,
        session: SessionInfo,
        capabilities: Map<Capability, CapabilityStatus>,
        current: () -> Pair<SessionInfo, Map<Capability, CapabilityStatus>> = { session to capabilities },
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

        if (command.isAction) approve(envelope, session)

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
                var result = action.copy(`package` = service.currentPackage())
                envelope.observeAfter?.let { result = observeAfter(service, it, result, current) }
                Protocol.json.encodeToJsonElement(ActionResult.serializer(), result)
            }
        }
    }

    /**
     * Decides whether the owner must approve, from the gateway's request and
     * the phone's own check of the same screen (whichever is stricter), and
     * waits for the answer or uses one the owner saved.
     */
    private suspend fun approve(envelope: CommandEnvelope, session: SessionInfo) {
        val command = envelope.command
        val confirm = envelope.confirm
        val judgement = judge(command)
        val deviceAsks = judgement != null && judgement.consequence != Consequence.NONE
        if (confirm == null && !session.approveEveryAction && !deviceAsks) return

        // Critical by either judge: asked every time. A gateway older than 1.3 never sends
        // remember keys, so its high-risk prompts are treated the same way.
        val critical = judgement?.consequence == Consequence.CRITICAL || (confirm != null && confirm.risk == "high" && confirm.remember == null)
        val key = when {
            critical || session.approveEveryAction -> null
            confirm != null -> confirm.remember
            deviceAsks -> judgement.rememberKey
            else -> null
        }
        val title = confirm?.title ?: judgement?.takeIf { deviceAsks }?.title ?: describe(command)
        val detail = confirm?.detail ?: when {
            !deviceAsks -> AGENT_DETAIL
            critical -> CRITICAL_DETAIL
            else -> CONSEQUENTIAL_DETAIL
        }
        val risk = confirm?.risk ?: if (deviceAsks) "high" else "medium"

        if (key != null && grants.allows(key)) {
            log.add(ActivityKind.APPROVAL, "Allowed by your saved choice: $title")
            return
        }
        log.add(ActivityKind.APPROVAL, "Asked you: $title")
        // Leave the gateway a little time to receive the answer before its deadline.
        val timeout = (envelope.deadlineMs - 2_000).coerceAtLeast(5_000)
        val app = bridge.service.value?.currentPackage()?.let(appName)
        when (approvals.request(title, detail, risk, timeout, rememberable = key != null, appName = app)) {
            ApprovalOutcome.APPROVED_ONCE -> log.add(ActivityKind.APPROVAL, "You approved: $title")
            ApprovalOutcome.APPROVED_SESSION -> {
                grants.allowForSession(key!!)
                log.add(ActivityKind.APPROVAL, "You approved for this session: $title")
            }
            ApprovalOutcome.APPROVED_ALWAYS -> {
                grants.allowAlways(key!!)
                log.add(ActivityKind.APPROVAL, "You always allow: $title")
            }
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

    /** The phone's own judgement of a tap or swipe, or null for other commands. */
    private fun judge(command: Command): Judgement? {
        val service = bridge.service.value ?: return null
        return when (command) {
            is Command.Tap -> service.tapContext(command.observationId, command.target)?.let {
                consequences.judgeTap(it.observation, it.node, command.longPress, it.live)
            }
            is Command.Swipe -> consequences.judgeSwipe(service.currentPackage())
            else -> null
        }
    }

    /**
     * Protocol 1.2: lets the UI settle, then observes under the same rules as
     * `ui.observe`, so the agent gets the new screen without a second round
     * trip. The action already happened, so problems are reported, not thrown.
     */
    private suspend fun observeAfter(
        service: LatchAccessibilityService,
        after: ObserveAfter,
        result: ActionResult,
        current: () -> Pair<SessionInfo, Map<Capability, CapabilityStatus>>,
    ): ActionResult {
        delay(after.settleMs.toLong())
        val (session, capabilities) = current()
        val needed = if (after.includeScreenshot) listOf(Capability.UI_OBSERVE, Capability.SCREEN_CAPTURE) else listOf(Capability.UI_OBSERVE)
        val refusal = when {
            session.paused || System.currentTimeMillis() >= session.expiresAtMs ->
                ErrorBody(ErrorCode.DEVICE_UNAVAILABLE.wire, "the session is paused or ended")
            needed.any { capabilities[it] != CapabilityStatus.ENABLED } ->
                ErrorBody(ErrorCode.PERMISSION_MISSING.wire, "the owner has not allowed reading the screen")
            else -> null
        }
        if (refusal != null) return result.copy(observationError = refusal)
        return try {
            val observation = service.observe(after.includeScreenshot, after.maxNodes)
            log.add(ActivityKind.OBSERVE, "Read the screen after the action · ${observation.`package` ?: "unknown app"}")
            result.copy(`package` = observation.`package` ?: result.`package`, observation = observation)
        } catch (e: ProtocolException) {
            result.copy(observationError = ErrorBody(e.code.wire, e.message ?: e.code.wire))
        }
    }
}
