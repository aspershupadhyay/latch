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
import io.github.aspershupadhyay.latch.protocol.WaitResult
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
    is Command.Tap -> {
        val verb = when {
            command.longPress -> "Long-press"
            command.double -> "Double-tap"
            else -> "Tap"
        }
        when (val t = command.target) {
            is Target.Element -> "$verb an element"
            is Target.Point -> "$verb at (${t.x}, ${t.y})"
        }
    }
    is Command.Swipe -> if (command.holdMs > 0) "Drag on the screen" else "Swipe on the screen"
    is Command.Pinch -> if (command.endSpan > command.startSpan) "Pinch to zoom in" else "Pinch to zoom out"
    is Command.TypeText ->
        "Type ${command.text.codePointCount(0, command.text.length)} characters" + if (command.submit) " and press Enter" else ""
    is Command.WaitFor -> if (command.gone) "Wait for text to disappear" else "Wait for text to appear"
    is Command.ScrollTo -> "Scroll to text"
    is Command.Global -> when (command.action) {
        GlobalAction.BACK -> "Press Back"
        GlobalAction.HOME -> "Press Home"
        GlobalAction.RECENTS -> "Open Recents"
    }
    Command.ListApps -> "List installed apps"
    is Command.LaunchApp -> "Open ${command.packageName}"
}

/** Commands that read or act on the screen in front, as opposed to opening an app or going home. */
private fun worksOnScreen(command: Command): Boolean = when (command) {
    Command.DeviceInfoCommand, Command.ListApps, is Command.LaunchApp -> false
    is Command.Global -> command.action != GlobalAction.HOME
    else -> true
}

/**
 * Runs one command on this phone after the device-side checks. The gateway
 * has already applied policy; these checks hold even if it did not.
 */
/** Least time to let an app open or the home screen appear before smart settle may answer. */
private const val TRANSITION_FLOOR_MS = 300L

/** How long to wait for an action to show any effect before taking the screen as it is. */
private const val EXPECT_CHANGE_MS = 450L

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

        // Latch in front would refuse every screen command; step aside to the home screen first.
        if (worksOnScreen(command) && bridge.service.value?.stepAsideFromLatch() == true) {
            log.add(ActivityKind.ACTION, "Went to the home screen so the AI can work (Latch is off limits to it)")
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
            is Command.WaitFor -> {
                // The searched-for text is the agent's, not screen content, but the log stays content-free anyway.
                val waited = service.waitFor(command.text, command.gone, command.timeoutMs, command.maxNodes)
                log.add(ActivityKind.OBSERVE, "${describe(command)} · ${if (waited.matched) "done" else "timed out"}")
                Protocol.json.encodeToJsonElement(WaitResult.serializer(), waited)
            }
            else -> {
                var found: Boolean? = null
                // What the screen looked like before, so settling can tell when the action took effect.
                val before = if (envelope.observeAfter?.quietMs != null) service.screenSignature() else 0
                when (command) {
                    is Command.Tap -> service.tap(command.observationId, command.target, command.longPress, command.double)
                    is Command.Swipe -> service.swipe(
                        command.observationId, command.fromX, command.fromY, command.toX, command.toY, command.durationMs, command.holdMs,
                    )
                    is Command.Pinch ->
                        service.pinch(command.observationId, command.centerX, command.centerY, command.startSpan, command.endSpan, command.durationMs)
                    is Command.TypeText -> service.typeText(command.observationId, command.element, command.text, command.submit)
                    is Command.Global -> service.global(command.action)
                    is Command.LaunchApp -> service.launch(command.packageName)
                    is Command.ScrollTo ->
                        found = service.scrollTo(command.observationId, command.text, command.direction, command.container, command.maxSwipes)
                }
                log.add(ActivityKind.ACTION, describe(command))
                var result = action.copy(`package` = service.currentPackage(), found = found)
                envelope.observeAfter?.let { result = observeAfter(command, service, it, before, result, current) }
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
                consequences.judgeTap(it.observation, it.node, command.longPress, it.live, command.double)
            }
            is Command.Swipe -> consequences.judgeSwipe(service.currentPackage(), drag = command.holdMs > 0)
            is Command.TypeText -> if (!command.submit) {
                null
            } else {
                service.tapContext(command.observationId, Target.Element(command.element))?.let { context ->
                    context.node?.let { consequences.judgeEnter(context.observation, it, command.text.codePointCount(0, command.text.length)) }
                }
            }
            else -> null
        }
    }

    /**
     * Protocol 1.2: lets the UI settle, then observes under the same rules as
     * `ui.observe`, so the agent gets the new screen without a second round
     * trip. The action already happened, so problems are reported, not thrown.
     */
    private suspend fun observeAfter(
        command: Command,
        service: LatchAccessibilityService,
        after: ObserveAfter,
        before: Int,
        result: ActionResult,
        current: () -> Pair<SessionInfo, Map<Capability, CapabilityStatus>>,
    ): ActionResult {
        val quiet = after.quietMs
        if (quiet == null) {
            delay(after.settleMs.toLong())
        } else {
            // Opening an app or going home starts with an animation that sends few events.
            val floor = if (command is Command.LaunchApp || command is Command.Global) TRANSITION_FLOOR_MS else 0L
            // An app that is still starting would be observed as the previous app: wait for it first.
            val waited = if (command is Command.LaunchApp) service.awaitForeground(command.packageName, after.settleMs.toLong()) else 0L
            // Then wait for the screen to change and hold still: a new page slides in silently,
            // so the agent would otherwise get the old page or one caught mid-animation.
            service.awaitSettled(
                before,
                quiet.toLong(),
                (after.settleMs - waited).coerceAtLeast(quiet.toLong()),
                (floor - waited).coerceAtLeast(0),
                EXPECT_CHANGE_MS,
            )
        }
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
