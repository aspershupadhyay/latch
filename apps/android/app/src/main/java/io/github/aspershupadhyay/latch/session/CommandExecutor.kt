package io.github.aspershupadhyay.latch.session

import android.os.Build
import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.ApprovalGrants
import io.github.aspershupadhyay.latch.data.AppDecision
import io.github.aspershupadhyay.latch.data.Autonomy
import io.github.aspershupadhyay.latch.files.PhoneFiles
import io.github.aspershupadhyay.latch.files.PhoneTransfers
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
import io.github.aspershupadhyay.latch.protocol.FileChunk
import io.github.aspershupadhyay.latch.protocol.FileItem
import io.github.aspershupadhyay.latch.protocol.FileList
import io.github.aspershupadhyay.latch.protocol.FileLocation
import io.github.aspershupadhyay.latch.protocol.FilePreview
import io.github.aspershupadhyay.latch.protocol.FileTransfer
import io.github.aspershupadhyay.latch.protocol.GlobalAction
import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.ObserveAfter
import io.github.aspershupadhyay.latch.protocol.Protocol
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import io.github.aspershupadhyay.latch.protocol.ScreenInfo
import io.github.aspershupadhyay.latch.protocol.SessionInfo
import io.github.aspershupadhyay.latch.protocol.Target
import io.github.aspershupadhyay.latch.protocol.WaitResult
import io.github.aspershupadhyay.latch.protocol.isFileChange
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
    is Command.AskOwner -> "Ask you to do something"
    is Command.ListFiles -> "List files in ${where(command.location)}"
    is Command.PreviewFile -> "Look at a file"
    is Command.ReadFile -> "Copy a file from your phone"
    is Command.WriteFile -> (if (command.overwrite) "Replace" else if (command.append) "Add to" else "Save") +
        " “${command.fileName}” " + (if (command.overwrite || command.append) "in " else "to ") + where(command.location)
    is Command.MakeFolder -> "Create the folder “${command.folderName}”"
    is Command.RenameFile -> "Rename a file to “${command.newName}”"
    is Command.DeleteFile -> "Delete a file from your phone"
    is Command.Share -> "Share ${command.ids.size} ${if (command.ids.size == 1) "file" else "files"} to ${command.packageName}"
    is Command.FetchFile -> (if (command.overwrite) "Replace" else "Save") +
        " “${command.fileName}” " + (if (command.overwrite) "in " else "to ") + where(command.location)
    is Command.PushFile -> "Copy a file from your phone"
    is Command.TransferStatus -> if (command.cancel) "Stop a file transfer" else "Check a file transfer"
    is Command.SetClipboard -> "Copy ${command.text.codePointCount(0, command.text.length)} characters to the clipboard"
}

private fun where(location: FileLocation) = when (location) {
    FileLocation.PHOTOS -> "your photos"
    FileLocation.DOWNLOADS -> "Downloads"
    FileLocation.FOLDER -> "your Latch folder"
}

/** A file name as the cursor shows it: short enough for the label. */
private fun shortName(name: String) = if (name.length <= 28) name else name.take(25) + "…"

/**
 * What the cursor says while a command runs. Gestures draw themselves; this
 * covers what happens off screen, so the owner always sees the AI at work.
 */
private fun cursorLabel(command: Command): String? = when (command) {
    is Command.Observe -> "Looking at the screen"
    is Command.WaitFor -> "Waiting for the screen"
    Command.ListApps -> "Looking at your apps"
    is Command.LaunchApp -> "Opening an app"
    is Command.AskOwner -> "Waiting for you"
    is Command.ListFiles, is Command.PreviewFile -> "Looking at files"
    is Command.ReadFile, is Command.PushFile -> "Copying a file"
    is Command.WriteFile -> "Saving “${shortName(command.fileName)}”"
    is Command.FetchFile -> "Saving “${shortName(command.fileName)}”"
    is Command.MakeFolder -> "Making a folder"
    is Command.RenameFile -> "Renaming a file"
    is Command.DeleteFile -> "Deleting a file"
    is Command.Share -> "Opening a share screen"
    is Command.SetClipboard -> "Copying text"
    is Command.Global -> when (command.action) {
        GlobalAction.BACK -> "Going back"
        GlobalAction.HOME -> "Going home"
        GlobalAction.RECENTS -> "Opening Recents"
    }
    else -> null
}

/** Commands that read or act on the screen in front, as opposed to opening an app or going home. */
private fun worksOnScreen(command: Command): Boolean = when (command) {
    Command.DeviceInfoCommand, Command.ListApps, is Command.LaunchApp, is Command.AskOwner,
    is Command.ListFiles, is Command.PreviewFile, is Command.ReadFile, is Command.WriteFile,
    is Command.MakeFolder, is Command.RenameFile, is Command.DeleteFile, is Command.Share,
    is Command.FetchFile, is Command.PushFile, is Command.TransferStatus, is Command.SetClipboard -> false
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

/** After typing, how long to wait for the app to answer it (search results, suggestions). */
private const val TYPE_EXPECT_CHANGE_MS = 900L

private const val SYSTEM_UI = "com.android.systemui"

/** Android's app choosers ("Open with", "Share"): a tap there opens another app. */
private val CHOOSER_PACKAGES = setOf("android", "com.android.intentresolver", "com.google.android.intentresolver")

/** After a tap in a chooser, how long to wait for the chosen app to take over. */
private const val CHOOSER_EXPECT_CHANGE_MS = 2_500L

/** Longest a question stays open when the gateway waits for it (protocol 1.4); under the gateway's 120 s. */
private const val APPROVAL_WAIT_MS = 110_000L

private const val AGENT_DETAIL = "Your AI asked to do this."
private const val FILE_DETAIL = "Your AI asked to do this. The file's current contents cannot be brought back afterwards."
private const val OWNER_TASK_DETAIL = "Do it on the phone, then tap Done. The AI waits and carries on from there."
private const val CONSEQUENTIAL_DETAIL =
    "Your AI asked to do this. It may send, call, post, delete, or change something that's hard to undo."
private const val CRITICAL_DETAIL =
    "Your AI asked to do this. It involves money, installing an app, a permission, or deleting an account, so Latch asks every time."

class CommandExecutor(
    private val bridge: DeviceBridge,
    private val approvals: ApprovalBroker,
    private val log: ActivityLog,
    private val grants: ApprovalGrants,
    private val consequences: Consequences,
    /** Human name of an app, for the "Always in …" button. */
    private val appName: (String) -> String? = { null },
    private val autonomy: Autonomy? = null,
    /** The gateway keeps waiting while the owner answers (protocol 1.4), so questions may stay open longer. */
    private val longApprovals: () -> Boolean = { false },
    /** Photos, Downloads, and the picked folder (protocol 1.6). */
    private val files: PhoneFiles? = null,
    /** Whole files by encrypted link (protocol 1.7). */
    private val transfers: PhoneTransfers? = null,
) {
    /** How long a question waits for the owner, given the command's own deadline. */
    private fun approvalTimeout(deadlineMs: Long): Long {
        val own = (deadlineMs - 2_000).coerceAtLeast(5_000)
        return if (longApprovals()) maxOf(own, APPROVAL_WAIT_MS) else own
    }

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

        // The owner sees what the AI is doing, also when it happens off screen.
        cursorLabel(command)?.let { bridge.service.value?.cursorStatus(it) }

        // Latch in front would refuse every screen command; step aside to the home screen first.
        if (worksOnScreen(command) && bridge.service.value?.stepAsideFromLatch() == true) {
            log.add(ActivityKind.ACTION, "Went to the home screen so the AI can work (Latch is off limits to it)")
        }

        // Only apps the owner allowed; the first use of an app asks once.
        appTarget(command)?.let { requireApp(it, envelope) }

        // Asking the owner is itself a question to the owner, never approved first.
        val judged = if (command.isAction && command !is Command.AskOwner) approve(envelope, session) else null

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
        fun files() = files ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone cannot use files")
        fun transfers() = transfers ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone cannot move whole files")
        return when (command) {
            is Command.ListFiles -> {
                val list = files().list(command.location, command.folder, command.query, command.limit, command.offset)
                log.add(ActivityKind.OBSERVE, "${describe(command)} · ${list.items.size} shown")
                Protocol.json.encodeToJsonElement(FileList.serializer(), list)
            }
            is Command.PreviewFile -> {
                val preview = files().preview(command.id)
                log.add(ActivityKind.OBSERVE, "Looked at “${preview.item.name}”")
                Protocol.json.encodeToJsonElement(FilePreview.serializer(), preview)
            }
            is Command.ReadFile -> {
                val chunk = files().read(command.id, command.offset, command.length)
                // One line per file, not per chunk.
                if (command.offset == 0L) log.add(ActivityKind.OBSERVE, "Copied “${chunk.item.name}” off the phone")
                Protocol.json.encodeToJsonElement(FileChunk.serializer(), chunk)
            }
            is Command.WriteFile -> {
                val item = files().write(
                    command.location, command.folder, command.subfolder, command.fileName, command.mime,
                    command.dataBase64, command.append, command.overwrite,
                )
                if (!command.append) log.add(ActivityKind.ACTION, judged ?: describe(command))
                Protocol.json.encodeToJsonElement(FileItem.serializer(), item)
            }
            is Command.FetchFile -> {
                val result = transfers().fetch(command)
                log.add(ActivityKind.ACTION, judged ?: describe(command))
                Protocol.json.encodeToJsonElement(FileTransfer.serializer(), result)
            }
            is Command.PushFile -> {
                val result = transfers().push(command)
                log.add(ActivityKind.OBSERVE, "Copied a file off the phone")
                Protocol.json.encodeToJsonElement(FileTransfer.serializer(), result)
            }
            is Command.TransferStatus -> {
                // Never longer than the command's own deadline allows.
                val wait = command.waitMs.toLong().coerceAtMost((envelope.deadlineMs - 2_000).coerceAtLeast(0))
                val result = transfers().status(command.transfer, wait, command.cancel)
                if (command.cancel) log.add(ActivityKind.ACTION, describe(command))
                Protocol.json.encodeToJsonElement(FileTransfer.serializer(), result)
            }
            is Command.SetClipboard -> {
                service.setClipboard(command.text)
                // The text itself never goes into the log.
                log.add(ActivityKind.ACTION, judged ?: describe(command))
                Protocol.json.encodeToJsonElement(ActionResult.serializer(), action)
            }
            is Command.MakeFolder -> {
                val item = files().mkdir(command.folder, command.folderName)
                log.add(ActivityKind.ACTION, judged ?: describe(command))
                Protocol.json.encodeToJsonElement(FileItem.serializer(), item)
            }
            is Command.RenameFile -> {
                val item = files().rename(command.id, command.newName)
                log.add(ActivityKind.ACTION, judged ?: describe(command))
                Protocol.json.encodeToJsonElement(FileItem.serializer(), item)
            }
            is Command.DeleteFile -> {
                files().delete(command.id)
                log.add(ActivityKind.ACTION, judged ?: describe(command))
                Protocol.json.encodeToJsonElement(ActionResult.serializer(), action)
            }
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
                var submitted: Boolean? = null
                var owner: String? = null
                // What the screen looked like before, so settling can tell when the action took effect.
                var before = if (envelope.observeAfter?.quietMs != null) service.screenSignature() else 0
                var expectChangeMs = EXPECT_CHANGE_MS
                when (command) {
                    is Command.Tap -> {
                        // Android's "Open with" and share choosers start another app, which takes longer
                        // than an in-app change: wait for it, or the agent gets the closing chooser.
                        if (service.currentPackage() in CHOOSER_PACKAGES) expectChangeMs = CHOOSER_EXPECT_CHANGE_MS
                        service.tap(command.observationId, command.target, command.longPress, command.double)
                    }
                    is Command.Swipe -> service.swipe(
                        command.observationId, command.fromX, command.fromY, command.toX, command.toY, command.durationMs, command.holdMs,
                    )
                    is Command.Pinch ->
                        service.pinch(command.observationId, command.centerX, command.centerY, command.startSpan, command.endSpan, command.durationMs)
                    is Command.TypeText -> {
                        val typed = service.typeText(command.observationId, command.element, command.text, command.submit)
                        submitted = typed.submitted
                        // The text itself shows at once; what matters is what the app does with it
                        // (search results load a moment later), so settle against the typed screen.
                        before = typed.signature
                        expectChangeMs = if (typed.submitted == false) 0L else TYPE_EXPECT_CHANGE_MS
                    }
                    is Command.Global -> service.global(command.action)
                    is Command.LaunchApp -> service.launch(command.packageName)
                    is Command.AskOwner -> owner = askOwner(command, envelope)
                    is Command.Share -> files().share(service, command.packageName, command.ids, command.text)
                    is Command.ScrollTo ->
                        found = service.scrollTo(command.observationId, command.text, command.direction, command.container, command.maxSwipes)
                }
                log.add(ActivityKind.ACTION, judged ?: describe(command))
                var result = action.copy(`package` = service.currentPackage(), found = found, submitted = submitted, owner = owner)
                envelope.observeAfter?.let { result = observeAfter(command, service, it, before, expectChangeMs, result, current) }
                Protocol.json.encodeToJsonElement(ActionResult.serializer(), result)
            }
        }
    }

    /**
     * Decides whether the owner must approve, from the gateway's request and
     * the phone's own check of the same screen (whichever is stricter), and
     * waits for the answer or uses one the owner saved.
     */
    private suspend fun approve(envelope: CommandEnvelope, session: SessionInfo): String? {
        val command = envelope.command
        if (command.isFileChange) return approveFileChange(envelope, session)
        val confirm = envelope.confirm
        val judgement = judge(command)
        val deviceAsks = judgement != null && judgement.consequence != Consequence.NONE
        if (confirm == null && !session.approveEveryAction && !deviceAsks) return judgement?.title

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

        // ADR-021: in an app the owner switched on, consequential actions run without
        // asking and are logged. Critical ones ask too, unless the owner opted in to
        // trusting those as well (ADR-022), which also covers Android's permission and
        // install dialogs. "Ask me before every action" overrides both.
        val inApp = bridge.service.value?.currentPackage()
        val trustCritical = autonomy?.state?.value?.trustsCritical == true
        val appOn = inApp != null && !exempt(inApp) && autonomy?.decide(inApp) == AppDecision.ALLOWED
        val systemDialog = inApp != null && consequences.isCriticalPackage(inApp)
        if (!session.approveEveryAction && ((appOn && (!critical || trustCritical)) || (systemDialog && trustCritical))) {
            log.add(
                ActivityKind.APPROVAL,
                when {
                    autonomy.state.value.autoOn -> "Done without asking (Auto mode): $title"
                    critical -> "Done without asking (you allow payments and permissions): $title"
                    else -> "Done without asking (app switched on): $title"
                },
            )
            return title
        }
        if (key != null && grants.allows(key)) {
            log.add(ActivityKind.APPROVAL, "Allowed by your saved choice: $title")
            return title
        }
        log.add(ActivityKind.APPROVAL, "Asked you: $title")
        // Leave the gateway a little time to receive the answer before its deadline.
        val timeout = approvalTimeout(envelope.deadlineMs)
        val app = bridge.service.value?.currentPackage()?.let(appName)
        when (approvals.request(title, detail, risk, timeout, rememberable = key != null, appName = app, commandId = envelope.id)) {
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
        return title
    }

    /**
     * Saving, renaming, and deleting files (ADR-026). Replacing an existing file
     * and deleting ask every time, unless Auto mode is on; "Ask me before every
     * action" asks for every change (not for the later chunks of one save).
     */
    private suspend fun approveFileChange(envelope: CommandEnvelope, session: SessionInfo): String {
        val command = envelope.command
        val files = files ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone cannot use files")
        val title = when (command) {
            is Command.DeleteFile -> "Delete “${files.nameOf(command.id) ?: "a file"}” from your phone"
            is Command.RenameFile -> "Rename “${files.nameOf(command.id) ?: "a file"}” to “${command.newName}”"
            else -> describe(command)
        }
        // Replacing asks only when there is something to replace.
        val replaces = (command is Command.WriteFile && command.overwrite &&
            files.existing(command.location, command.folder, command.subfolder, command.fileName) != null) ||
            (command is Command.FetchFile && command.overwrite &&
                files.existing(command.location, command.folder, command.subfolder, command.fileName) != null)
        val risky = command is Command.DeleteFile || replaces
        val strict = session.approveEveryAction && !(command is Command.WriteFile && command.append)
        if (!risky && !strict) return title
        if (!strict && autonomy?.state?.value?.autoOn == true) {
            log.add(ActivityKind.APPROVAL, "Done without asking (Auto mode): $title")
            return title
        }
        log.add(ActivityKind.APPROVAL, "Asked you: $title")
        val outcome = approvals.request(
            title, if (risky) FILE_DETAIL else AGENT_DETAIL, if (risky) "high" else "medium",
            approvalTimeout(envelope.deadlineMs), commandId = envelope.id,
        )
        when (outcome) {
            ApprovalOutcome.DENIED -> {
                log.add(ActivityKind.APPROVAL, "You denied: $title")
                throw ProtocolException(ErrorCode.USER_DENIED, "the owner denied this action")
            }
            ApprovalOutcome.EXPIRED -> {
                log.add(ActivityKind.APPROVAL, "Expired without an answer: $title")
                throw ProtocolException(ErrorCode.CONFIRMATION_EXPIRED, "the owner did not answer in time")
            }
            else -> log.add(ActivityKind.APPROVAL, "You approved: $title")
        }
        return title
    }

    /**
     * Shows the owner what the AI needs them to do and waits for "Done" or
     * "I can't". The AI app can never answer this (it is not offered there).
     */
    private suspend fun askOwner(command: Command.AskOwner, envelope: CommandEnvelope): String {
        log.add(ActivityKind.APPROVAL, "The AI asked you: ${command.message}")
        val outcome = approvals.request(
            title = command.message,
            detail = OWNER_TASK_DETAIL,
            risk = "medium",
            timeoutMs = approvalTimeout(envelope.deadlineMs),
            kind = ApprovalKind.OWNER_TASK,
            commandId = envelope.id,
        )
        return when (outcome) {
            ApprovalOutcome.EXPIRED -> {
                log.add(ActivityKind.APPROVAL, "No answer to the AI's request")
                "no_answer"
            }
            ApprovalOutcome.DENIED -> {
                log.add(ActivityKind.APPROVAL, "You said you can't do it now")
                "cant"
            }
            else -> {
                log.add(ActivityKind.APPROVAL, "You said it's done")
                "done"
            }
        }
    }

    /** The launcher is always usable; Latch and system UI are refused by the service itself. */
    private fun exempt(target: String): Boolean {
        val service = bridge.service.value ?: return true
        // Permission and install dialogs belong to the app that opened them; their buttons are critical anyway.
        return target == service.packageName || target == SYSTEM_UI || service.isHomeApp(target) || consequences.isCriticalPackage(target)
    }

    private fun appUsable(target: String): Boolean =
        autonomy == null || exempt(target) || autonomy.decide(target) == AppDecision.ALLOWED

    /** The app a command works in: the one it opens, or the one in front. */
    private fun appTarget(command: Command): String? = when (command) {
        Command.DeviceInfoCommand, Command.ListApps, is Command.AskOwner,
        is Command.ListFiles, is Command.PreviewFile, is Command.ReadFile, is Command.WriteFile,
        is Command.MakeFolder, is Command.RenameFile, is Command.DeleteFile,
        is Command.FetchFile, is Command.PushFile, is Command.TransferStatus, is Command.SetClipboard -> null
        is Command.LaunchApp -> command.packageName
        // Sharing opens that app: only apps the owner allowed (or Auto mode).
        is Command.Share -> command.packageName
        // Going home leaves an app; it never works in one.
        is Command.Global -> if (command.action == GlobalAction.HOME) null else bridge.service.value?.currentPackage()
        else -> bridge.service.value?.currentPackage()
    }

    /**
     * Lets the command run only in an app the owner allowed (ADR-021). The
     * first time the AI needs an app, the owner is asked once for it. The
     * launcher is always usable; Latch and system UI are refused elsewhere.
     */
    private suspend fun requireApp(target: String, envelope: CommandEnvelope) {
        val access = autonomy ?: return
        if (exempt(target) || access.decide(target) == AppDecision.ALLOWED) return
        val name = appName(target) ?: target
        val sensitive = consequences.isSensitiveApp(target, name)
        log.add(ActivityKind.APPROVAL, "Asked you: let the AI use $name")
        val timeout = approvalTimeout(envelope.deadlineMs)
        val outcome = approvals.request(
            title = "Let the AI use $name?",
            detail = (if (sensitive) "$name may hold money, accounts, or passwords. " else "") +
                "The AI can see $name's screen and act in it, including sending and deleting, without asking again. " +
                if (access.state.value.trustsCritical) "You also allowed payments, installs, and permissions without asking." else "Payments, installs, and permissions still ask you every time.",
            risk = if (sensitive) "high" else "medium",
            timeoutMs = timeout,
            rememberable = true,
            appName = name,
            kind = ApprovalKind.APP,
            commandId = envelope.id,
        )
        when (outcome) {
            ApprovalOutcome.APPROVED_ALWAYS -> {
                access.setAllowed(target, true)
                log.add(ActivityKind.APPROVAL, "You switched on $name for the AI")
            }
            ApprovalOutcome.APPROVED_SESSION, ApprovalOutcome.APPROVED_ONCE -> {
                access.allowForSession(target)
                log.add(ActivityKind.APPROVAL, "You let the AI use $name for this session")
            }
            ApprovalOutcome.DENIED -> {
                log.add(ActivityKind.APPROVAL, "You did not let the AI use $name")
                throw ProtocolException(ErrorCode.USER_DENIED, "the owner did not let AI use $name; do not open it again unless they ask")
            }
            ApprovalOutcome.EXPIRED -> {
                log.add(ActivityKind.APPROVAL, "Expired without an answer: let the AI use $name")
                throw ProtocolException(ErrorCode.CONFIRMATION_EXPIRED, "the owner did not answer whether AI may use $name")
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
        expectChangeMs: Long,
        result: ActionResult,
        current: () -> Pair<SessionInfo, Map<Capability, CapabilityStatus>>,
    ): ActionResult {
        val quiet = after.quietMs
        if (quiet == null) {
            delay(after.settleMs.toLong())
        } else {
            // Opening an app or going home starts with an animation that sends few events.
            val opening = (command as? Command.LaunchApp)?.packageName ?: (command as? Command.Share)?.packageName
            val floor = if (opening != null || command is Command.Global) TRANSITION_FLOOR_MS else 0L
            // An app that is still starting would be observed as the previous app: wait for it first.
            val waited = if (opening != null) service.awaitForeground(opening, after.settleMs.toLong()) else 0L
            // Then wait for the screen to change and hold still: a new page slides in silently,
            // so the agent would otherwise get the old page or one caught mid-animation.
            service.awaitSettled(
                before,
                quiet.toLong(),
                (after.settleMs - waited).coerceAtLeast(maxOf(quiet.toLong(), expectChangeMs + quiet)),
                (floor - waited).coerceAtLeast(0),
                expectChangeMs,
                expectKeyboard = command is Command.Tap && !command.longPress,
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
            // The action may have opened another app; its screen is shared only once the owner allows it.
            service.currentPackage()?.let { now ->
                if (!appUsable(now)) {
                    return result.copy(
                        observationError = ErrorBody(
                            ErrorCode.POLICY_REFUSED.wire,
                            "now in ${appName(now) ?: now}, which the owner has not switched on; call observe and Latch will ask them",
                        ),
                    )
                }
            }
            val observation = service.observe(after.includeScreenshot, after.maxNodes)
            log.add(ActivityKind.OBSERVE, "Read the screen after the action · ${observation.`package` ?: "unknown app"}")
            result.copy(`package` = observation.`package` ?: result.`package`, observation = observation)
        } catch (e: ProtocolException) {
            result.copy(observationError = ErrorBody(e.code.wire, e.message ?: e.code.wire))
        }
    }
}
