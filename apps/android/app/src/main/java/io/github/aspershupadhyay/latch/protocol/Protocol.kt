package io.github.aspershupadhyay.latch.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Kotlin side of the Latch device protocol v1.
 *
 * The normative definition is the Rust crate `latch-protocol`; this file must
 * accept and reject exactly the shared fixtures in `packages/schemas/v1/fixtures`.
 */
object Protocol {
    const val VERSION = "1.8"

    val json = Json {
        ignoreUnknownKeys = true // minor versions may add fields
        explicitNulls = false
        encodeDefaults = true
    }

    /** This app's minor protocol version. */
    val MINOR: Int = VERSION.substringAfter('.').toInt()

    /** Minor version of a compatible `major.minor` string, or null. */
    fun minorOf(version: String): Int? = if (isCompatible(version)) version.substringAfter('.').toIntOrNull() else null

    fun isCompatible(version: String): Boolean {
        val parts = version.split('.')
        return parts.size == 2 && parts[0] == VERSION.substringBefore('.') && parts[1].toIntOrNull() != null
    }
}

class ProtocolException(val code: ErrorCode, message: String) : Exception(message)

enum class ErrorCode(val wire: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_CAPABILITY("unsupported_capability"),
    PERMISSION_MISSING("permission_missing"),
    POLICY_REFUSED("policy_refused"),
    SENSITIVE_TARGET("sensitive_target"),
    STALE_OBSERVATION("stale_observation"),
    TARGET_NOT_FOUND("target_not_found"),
    SCREEN_PROTECTED("screen_protected"),
    USER_DENIED("user_denied"),
    CONFIRMATION_EXPIRED("confirmation_expired"),
    CANCELLED("cancelled"),
    DEADLINE_EXCEEDED("deadline_exceeded"),
    DEVICE_UNAVAILABLE("device_unavailable"),
    AMBIGUOUS_DEVICE("ambiguous_device"),
    TRANSPORT_UNAVAILABLE("transport_unavailable"),
    INTERNAL("internal"),
}

enum class Capability(val wire: String) {
    DEVICE_INFO("device.info"),
    UI_OBSERVE("ui.observe"),
    SCREEN_CAPTURE("screen.capture"),
    INPUT_GESTURE("input.gesture"),
    INPUT_TEXT("input.text"),
    NAV_GLOBAL("nav.global"),
    APP_LAUNCH("app.launch"),
    /** Since 1.6: list and read allowed photos, Latch's downloads, and the picked folder. */
    FILE_READ("file.read"),
    /** Since 1.6: save, rename, and delete files there. */
    FILE_WRITE("file.write"),
    /** Since 1.6: open an app's share screen with files. */
    APP_SHARE("app.share"),
    /** Since 1.7: put text on the clipboard (never read it). */
    CLIPBOARD_WRITE("clipboard.write"),
    /** Since 1.8: read Latch's own activity log (never screen content). */
    ACTIVITY_READ("activity.read"),
    ;

    companion object {
        fun fromWire(wire: String): Capability? = entries.firstOrNull { it.wire == wire }
    }
}

enum class CapabilityStatus(val wire: String) {
    ENABLED("enabled"),
    DISABLED("disabled"),
    NEEDS_PERMISSION("needs_permission"),
    UNSUPPORTED("unsupported"),
}

// ---- Wire models (snake_case JSON) ----

@Serializable
data class CapabilityState(val capability: String, val status: String)

@Serializable
data class SessionInfo(
    @SerialName("expires_at_ms") val expiresAtMs: Long,
    @SerialName("approve_every_action") val approveEveryAction: Boolean = false,
    val paused: Boolean = false,
    /** Since 1.4: approvals may also be answered in the AI app (the owner's switch). */
    @SerialName("remote_approvals") val remoteApprovals: Boolean = false,
)

@Serializable
data class DeviceDescriptor(
    val platform: String,
    @SerialName("os_version") val osVersion: String,
    val model: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    val isEmpty get() = right <= left || bottom <= top
    val centerX get() = left + (right - left) / 2
    val centerY get() = top + (bottom - top) / 2
}

@Serializable
data class ScreenInfo(val width: Int, val height: Int, val rotation: Int = 0)

@Serializable
data class UiNode(
    val id: String,
    val parent: String? = null,
    val role: String,
    val text: String? = null,
    val description: String? = null,
    @SerialName("resource_id") val resourceId: String? = null,
    val bounds: Rect,
    val clickable: Boolean = false,
    @SerialName("long_clickable") val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val sensitive: Boolean = false,
)

@Serializable
data class Screenshot(
    val mime: String,
    val width: Int,
    val height: Int,
    @SerialName("data_base64") val dataBase64: String,
)

@Serializable
data class Observation(
    @SerialName("observation_id") val observationId: String,
    @SerialName("captured_at_ms") val capturedAtMs: Long,
    val `package`: String? = null,
    val screen: ScreenInfo,
    val nodes: List<UiNode>,
    val screenshot: Screenshot? = null,
    @SerialName("redacted_count") val redactedCount: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class DeviceInfo(
    val device: DeviceDescriptor,
    val screen: ScreenInfo,
    val `package`: String? = null,
    val capabilities: List<CapabilityState>,
    val session: SessionInfo,
)

/** Error details inside a successful result, e.g. why the phone could not observe after an action. */
@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class ActionResult(
    val `package`: String? = null,
    /** The screen after the action, when the command asked with `observe_after` (since 1.2). */
    val observation: Observation? = null,
    /** Why the phone did not observe after the action. The action itself succeeded. */
    @SerialName("observation_error") val observationError: ErrorBody? = null,
    /** `ui.scroll_to` only (since 1.3): whether the text is on screen now. */
    val found: Boolean? = null,
    /** `ui.type_text` with submit only: false when Enter visibly did nothing. Absent from older apps. */
    val submitted: Boolean? = null,
    /** `owner.ask` only (since 1.5): done, cant, or no_answer. */
    val owner: String? = null,
)

// ---- Files (since 1.6, ADR-026) ----

enum class FileLocation(val wire: String) {
    PHOTOS("photos"),
    DOWNLOADS("downloads"),
    FOLDER("folder"),
    ;

    companion object {
        fun fromWire(wire: String): FileLocation? = entries.firstOrNull { it.wire == wire }
    }
}

/** One file or folder. [id] is opaque and valid for this session; [name] is untrusted. */
@Serializable
data class FileItem(
    val id: String,
    val name: String,
    /** folder, image, video, or file. */
    val kind: String,
    val location: String,
    val mime: String? = null,
    val size: Long? = null,
    @SerialName("modified_ms") val modifiedMs: Long? = null,
)

@Serializable
data class FileList(
    val location: String,
    @SerialName("folder_name") val folderName: String? = null,
    val items: List<FileItem>,
    val total: Int,
    @SerialName("next_offset") val nextOffset: Int? = null,
)

@Serializable
data class FilePreview(
    val item: FileItem,
    val text: String? = null,
    @SerialName("text_truncated") val textTruncated: Boolean = false,
    val image: Screenshot? = null,
)

@Serializable
data class FileChunk(
    val item: FileItem,
    val offset: Long,
    @SerialName("data_base64") val dataBase64: String,
    val eof: Boolean,
)

/**
 * Since 1.7 (ADR-027): where a whole file travels. The stored copy is
 * AES-256-CTR encrypted under [keyHex] and [ivHex], made for this one
 * transfer; the plain file's SHA-256 travels separately.
 */
data class FileLink(val url: String, val headers: Map<String, String>, val keyHex: String, val ivHex: String)

/** Since 1.7: answer to `file.fetch`, `file.push`, and `file.transfer`. */
@Serializable
data class FileTransfer(
    val id: String,
    /** running or done. */
    val state: String,
    @SerialName("done_bytes") val doneBytes: Long,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    val item: FileItem? = null,
    val sha256: String? = null,
)

/** Since 1.8: one activity log line; never screen content or typed text. */
@Serializable
data class ActivityItem(
    @SerialName("at_ms") val atMs: Long,
    val kind: String,
    val summary: String,
    val app: String? = null,
)

/** Since 1.8: answer to `activity.list`, newest first. */
@Serializable
data class ActivityList(val entries: List<ActivityItem>, val total: Int)

/** Result of `ui.wait` (since 1.3). */
@Serializable
data class WaitResult(val matched: Boolean, val observation: Observation)

@Serializable
data class AppEntry(val `package`: String, val label: String)

@Serializable
data class AppList(val apps: List<AppEntry>)

@Serializable
data class ConfirmRequest(
    val title: String,
    val detail: String,
    val risk: String,
    /** Since 1.3: key for "this session" / "always in this app" answers; absent = ask every time. */
    val remember: String? = null,
)

// ---- Device → gateway ----

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HelloMessage(
    val protocol: String,
    val device: DeviceDescriptor,
    val capabilities: List<CapabilityState>,
    val session: SessionInfo,
    @SerialName("device_time_ms") val deviceTimeMs: Long? = null,
    @EncodeDefault val type: String = "hello",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StateMessage(
    val capabilities: List<CapabilityState>,
    val session: SessionInfo,
    @SerialName("device_time_ms") val deviceTimeMs: Long? = null,
    @EncodeDefault val type: String = "state",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ByeMessage(val reason: String, @EncodeDefault val type: String = "bye")

object Outgoing {
    fun ok(id: String, data: JsonElement): String = buildJsonObject {
        put("type", "result")
        put("id", id)
        put("outcome", buildJsonObject {
            put("status", "ok")
            put("data", data)
        })
    }.toString()

    fun error(id: String, code: ErrorCode, message: String): String = buildJsonObject {
        put("type", "result")
        put("id", id)
        put("outcome", buildJsonObject {
            put("status", "error")
            put("error", buildJsonObject {
                put("code", code.wire)
                put("message", message)
            })
        })
    }.toString()

    /**
     * Since 1.4: the phone waits for the owner on [commandId]. [remote] says
     * whether an answer from the AI app counts (the owner's switch).
     */
    fun approvalRequest(
        commandId: String,
        nonce: String,
        title: String,
        detail: String,
        app: Boolean,
        choices: List<String>,
        remote: Boolean,
        expiresAtMs: Long,
    ): String = buildJsonObject {
        put("type", "approval_request")
        put("command_id", commandId)
        put("nonce", nonce)
        put("title", title.trim().replace(Regex("\\p{Cntrl}"), " ").take(200).ifBlank { "Approve this action?" })
        put("detail", detail.trim().replace(Regex("\\p{Cntrl}"), " ").take(600).ifBlank { "Your AI asked to do this." })
        put("kind", if (app) "app" else "action")
        put("choices", kotlinx.serialization.json.JsonArray(choices.map { JsonPrimitive(it) }))
        put("remote", remote)
        put("expires_at_ms", expiresAtMs)
    }.toString()
}

// ---- Gateway → device ----

sealed interface Target {
    data class Element(val element: String) : Target
    data class Point(val x: Int, val y: Int) : Target
}

enum class GlobalAction { BACK, HOME, RECENTS }

/** Named after the content revealed: DOWN shows what is further down. */
enum class Direction { UP, DOWN, LEFT, RIGHT }

sealed interface Command {
    val name: String
    val requiredCapabilities: List<Capability>
    val isAction: Boolean get() = true
    val observationId: String? get() = null

    data object DeviceInfoCommand : Command {
        override val name = "device.info"
        override val requiredCapabilities = listOf(Capability.DEVICE_INFO)
        override val isAction = false
    }

    data class Observe(val includeScreenshot: Boolean, val maxNodes: Int) : Command {
        override val name = "ui.observe"
        override val requiredCapabilities =
            if (includeScreenshot) listOf(Capability.UI_OBSERVE, Capability.SCREEN_CAPTURE) else listOf(Capability.UI_OBSERVE)
        override val isAction = false
    }

    /** [double] (since 1.3): two quick taps. */
    data class Tap(override val observationId: String, val target: Target, val longPress: Boolean, val double: Boolean = false) : Command {
        override val name = "input.tap"
        override val requiredCapabilities = listOf(Capability.INPUT_GESTURE)
    }

    data class Swipe(
        override val observationId: String,
        val fromX: Int, val fromY: Int, val toX: Int, val toY: Int,
        val durationMs: Int,
        /** Since 1.3: press and hold at the start this long before moving (a drag). */
        val holdMs: Int = 0,
    ) : Command {
        override val name = "input.swipe"
        override val requiredCapabilities = listOf(Capability.INPUT_GESTURE)
    }

    /** Since 1.3: two fingers apart (zoom in) or together (zoom out) around a point. */
    data class Pinch(
        override val observationId: String,
        val centerX: Int,
        val centerY: Int,
        val startSpan: Int,
        val endSpan: Int,
        val durationMs: Int,
    ) : Command {
        override val name = "input.pinch"
        override val requiredCapabilities = listOf(Capability.INPUT_GESTURE)
    }

    /** [submit] (since 1.3): then press the keyboard's action key (Enter, Search, Send) in that field. */
    data class TypeText(override val observationId: String, val element: String, val text: String, val submit: Boolean = false) : Command {
        override val name = "input.type"
        override val requiredCapabilities = listOf(Capability.INPUT_TEXT)
    }

    /** Since 1.3: wait until text appears (or, with [gone], disappears), up to [timeoutMs]. */
    data class WaitFor(val text: String, val gone: Boolean, val timeoutMs: Int, val maxNodes: Int) : Command {
        override val name = "ui.wait"
        override val requiredCapabilities = listOf(Capability.UI_OBSERVE)
        override val isAction = false
    }

    /** Since 1.3: scroll [container] (or the largest scrollable element) until [text] is visible. */
    data class ScrollTo(
        override val observationId: String,
        val text: String,
        val direction: Direction,
        val container: String?,
        val maxSwipes: Int,
    ) : Command {
        override val name = "ui.scroll_to"
        override val requiredCapabilities = listOf(Capability.UI_OBSERVE, Capability.INPUT_GESTURE)
    }

    data class Global(val action: GlobalAction) : Command {
        override val name = "nav.global"
        override val requiredCapabilities = listOf(Capability.NAV_GLOBAL)
    }

    data object ListApps : Command {
        override val name = "app.list"
        override val requiredCapabilities = listOf(Capability.APP_LAUNCH)
        override val isAction = false
    }

    data class LaunchApp(val packageName: String) : Command {
        override val name = "app.launch"
        override val requiredCapabilities = listOf(Capability.APP_LAUNCH)
    }

    /**
     * Since 1.5: ask the owner to do something only a person should do (log
     * in, unlock, a code). Needs no capability; only the owner can answer.
     */
    data class AskOwner(val message: String) : Command {
        override val name = "owner.ask"
        override val requiredCapabilities = emptyList<Capability>()
    }

    // ---- Since 1.6: files by opaque id (ADR-026) ----

    data class ListFiles(val location: FileLocation, val folder: String?, val query: String?, val limit: Int, val offset: Int) : Command {
        override val name = "file.list"
        override val requiredCapabilities = listOf(Capability.FILE_READ)
        override val isAction = false
    }

    data class PreviewFile(val id: String) : Command {
        override val name = "file.preview"
        override val requiredCapabilities = listOf(Capability.FILE_READ)
        override val isAction = false
    }

    data class ReadFile(val id: String, val offset: Long, val length: Int) : Command {
        override val name = "file.read"
        override val requiredCapabilities = listOf(Capability.FILE_READ)
        override val isAction = false
    }

    data class WriteFile(
        val location: FileLocation,
        val folder: String?,
        val subfolder: String?,
        val fileName: String,
        val mime: String?,
        val dataBase64: String,
        val append: Boolean,
        val overwrite: Boolean,
    ) : Command {
        override val name = "file.write"
        override val requiredCapabilities = listOf(Capability.FILE_WRITE)
    }

    data class MakeFolder(val folder: String?, val folderName: String) : Command {
        override val name = "file.mkdir"
        override val requiredCapabilities = listOf(Capability.FILE_WRITE)
    }

    data class RenameFile(val id: String, val newName: String) : Command {
        override val name = "file.rename"
        override val requiredCapabilities = listOf(Capability.FILE_WRITE)
    }

    data class DeleteFile(val id: String) : Command {
        override val name = "file.delete"
        override val requiredCapabilities = listOf(Capability.FILE_WRITE)
    }

    data class Share(val packageName: String, val ids: List<String>, val text: String?) : Command {
        override val name = "app.share"
        override val requiredCapabilities = listOf(Capability.APP_SHARE)
    }

    /** Since 1.7: download [link], decrypt, check [sha256], and save like [WriteFile]. */
    data class FetchFile(
        val location: FileLocation,
        val folder: String?,
        val subfolder: String?,
        val fileName: String,
        val mime: String?,
        val overwrite: Boolean,
        val link: FileLink,
        val sha256: String,
        val size: Long?,
    ) : Command {
        override val name = "file.fetch"
        override val requiredCapabilities = listOf(Capability.FILE_WRITE)
    }

    /** Since 1.7: encrypt file [id] and upload it to [link], at most [maxBytes]. */
    data class PushFile(val id: String, val link: FileLink, val maxBytes: Long) : Command {
        override val name = "file.push"
        override val requiredCapabilities = listOf(Capability.FILE_READ)
        override val isAction = false
    }

    /** Since 1.7: progress of a transfer of this session, waiting up to [waitMs]. */
    data class TransferStatus(val transfer: String, val waitMs: Int, val cancel: Boolean) : Command {
        override val name = "file.transfer"
        override val requiredCapabilities = emptyList<Capability>()
        override val isAction = false
    }

    /** Since 1.7: put [text] on the clipboard. */
    data class SetClipboard(val text: String) : Command {
        override val name = "clipboard.set"
        override val requiredCapabilities = listOf(Capability.CLIPBOARD_WRITE)
    }

    /** Since 1.8: the newest activity log entries, optionally only some [kinds] and since [sinceMs]. */
    data class ListActivity(val limit: Int, val kinds: List<String>, val sinceMs: Long?) : Command {
        override val name = "activity.list"
        override val requiredCapabilities = listOf(Capability.ACTIVITY_READ)
        override val isAction = false
    }

    /** Since 1.8: the AI finished its task; the cursor goes and [summary] is logged. */
    data class TaskDone(val summary: String?) : Command {
        override val name = "task.done"
        override val requiredCapabilities = emptyList<Capability>()
        override val isAction = false
    }
}

/** Saving, renaming, and deleting files: changes made off screen. */
val Command.isFileChange: Boolean
    get() = this is Command.WriteFile || this is Command.FetchFile || this is Command.MakeFolder ||
        this is Command.RenameFile || this is Command.DeleteFile

/** Since 1.2: observe after a successful action and return it in the same result. */
data class ObserveAfter(
    val settleMs: Int,
    val includeScreenshot: Boolean,
    val maxNodes: Int,
    /** Since 1.3: observe once the screen has been still this long, within [settleMs]. */
    val quietMs: Int? = null,
)

data class CommandEnvelope(
    val id: String,
    val deadlineMs: Long,
    val command: Command,
    val confirm: ConfirmRequest?,
    val observeAfter: ObserveAfter? = null,
)

sealed interface GatewayMessage {
    data class Welcome(val protocol: String, val deviceId: String, val serverTimeMs: Long, val connection: String?) : GatewayMessage
    data class CommandMessage(val envelope: CommandEnvelope) : GatewayMessage
    data class Cancel(val id: String) : GatewayMessage
    data class Revoked(val reason: String) : GatewayMessage
    /** Since 1.4: the owner answered in the AI app; [choice] is once, session, always, or deny. */
    data class ApprovalAnswer(val nonce: String, val choice: String) : GatewayMessage
}

/**
 * Parses one gateway frame. Throws [ProtocolException] for anything malformed,
 * unknown, or outside protocol limits; the caller answers with an error result
 * when a command id is known, and otherwise drops the frame.
 */
object GatewayParser {
    private fun invalid(message: String): Nothing = throw ProtocolException(ErrorCode.INVALID_REQUEST, message)

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$key must be a string")

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid("$key must be an integer")

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: invalid("$key must be an integer")

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        val value = this[key] ?: return default
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid("$key must be a boolean")
    }

    private fun JsonObject.optStr(key: String): String? = if (this[key] == null || this[key] is kotlinx.serialization.json.JsonNull) null else str(key)

    private fun location(wire: String): FileLocation = FileLocation.fromWire(wire) ?: invalid("location must be photos, downloads, or folder")

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: invalid("$key must be an object")

    fun parse(text: String): GatewayMessage {
        if (text.length > Limits.MAX_GATEWAY_FRAME_CHARS) invalid("frame too large")
        val root = try {
            Protocol.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            invalid("not a JSON object")
        }
        return when (root.str("type")) {
            "welcome" -> GatewayMessage.Welcome(
                root.str("protocol"),
                root.str("device_id"),
                root.long("server_time_ms"),
                (root["connection"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
            "cancel" -> GatewayMessage.Cancel(root.str("id"))
            "revoked" -> GatewayMessage.Revoked(root.str("reason"))
            "approval_answer" -> {
                val nonce = root.str("nonce")
                if (!Regex("^[0-9a-f]{32}$").matches(nonce)) invalid("nonce must be 32 lowercase hex characters")
                val choice = root.str("choice")
                if (choice !in setOf("once", "session", "always", "deny")) invalid("unknown approval choice")
                GatewayMessage.ApprovalAnswer(nonce, choice)
            }
            "command" -> {
                val id = root.str("id")
                if (!Limits.isValidId(id)) invalid("bad command id")
                val confirm = (root["confirm"] as? JsonObject)?.let {
                    val remember = if (it.containsKey("remember")) it.str("remember") else null
                    if (remember != null &&
                        (remember.isEmpty() || remember.codePointCount(0, remember.length) > Limits.MAX_REMEMBER_CHARS || remember.any(Character::isISOControl))
                    ) {
                        invalid("confirm.remember out of range")
                    }
                    ConfirmRequest(it.str("title"), it.str("detail"), it.str("risk"), remember)
                }
                val command = parseCommand(root.obj("command"))
                Validation.command(command)
                val observeAfter = (root["observe_after"] as? JsonObject)?.let {
                    ObserveAfter(
                        it.int("settle_ms"),
                        it.bool("include_screenshot", false),
                        if (it.containsKey("max_nodes")) it.int("max_nodes") else 400,
                        if (it.containsKey("quiet_ms")) it.int("quiet_ms") else null,
                    )
                }
                if (observeAfter != null) Validation.observeAfter(command, observeAfter)
                GatewayMessage.CommandMessage(CommandEnvelope(id, root.long("deadline_ms"), command, confirm, observeAfter))
            }
            else -> invalid("unknown message type")
        }
    }

    fun commandId(text: String): String? = try {
        Protocol.json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.contentOrNull?.takeIf(Limits::isValidId)
    } catch (e: Exception) {
        null
    }

    private fun parseCommand(command: JsonObject): Command {
        val params = command["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (command.str("name")) {
            "device.info" -> Command.DeviceInfoCommand
            "ui.observe" -> Command.Observe(
                params.bool("include_screenshot", false),
                if (params.containsKey("max_nodes")) params.int("max_nodes") else 400,
            )
            "input.tap" -> Command.Tap(
                params.str("observation_id"), parseTarget(params.obj("target")), params.bool("long_press", false), params.bool("double", false),
            )
            "input.pinch" -> {
                val center = params.obj("center")
                Command.Pinch(
                    params.str("observation_id"), center.int("x"), center.int("y"),
                    params.int("start_span"), params.int("end_span"),
                    if (params.containsKey("duration_ms")) params.int("duration_ms") else 300,
                )
            }
            "input.swipe" -> {
                val from = params.obj("from")
                val to = params.obj("to")
                Command.Swipe(
                    params.str("observation_id"),
                    from.int("x"), from.int("y"), to.int("x"), to.int("y"),
                    if (params.containsKey("duration_ms")) params.int("duration_ms") else 300,
                    if (params.containsKey("hold_ms")) params.int("hold_ms") else 0,
                )
            }
            "input.type" -> Command.TypeText(
                params.str("observation_id"), params.str("element"), params.str("text"), params.bool("submit", false),
            )
            "ui.wait" -> Command.WaitFor(
                params.str("text"),
                params.bool("gone", false),
                if (params.containsKey("timeout_ms")) params.int("timeout_ms") else 5_000,
                if (params.containsKey("max_nodes")) params.int("max_nodes") else 400,
            )
            "ui.scroll_to" -> Command.ScrollTo(
                params.str("observation_id"),
                params.str("text"),
                when (params.str("direction")) {
                    "up" -> Direction.UP
                    "down" -> Direction.DOWN
                    "left" -> Direction.LEFT
                    "right" -> Direction.RIGHT
                    else -> invalid("unknown direction")
                },
                if (params.containsKey("container")) params.str("container") else null,
                if (params.containsKey("max_swipes")) params.int("max_swipes") else 10,
            )
            "nav.global" -> Command.Global(
                when (params.str("action")) {
                    "back" -> GlobalAction.BACK
                    "home" -> GlobalAction.HOME
                    "recents" -> GlobalAction.RECENTS
                    else -> invalid("unknown global action")
                },
            )
            "app.list" -> Command.ListApps
            "app.launch" -> Command.LaunchApp(params.str("package"))
            "owner.ask" -> Command.AskOwner(params.str("message"))
            "file.list" -> Command.ListFiles(
                location(params.str("location")),
                params.optStr("folder"),
                params.optStr("query"),
                if (params.containsKey("limit")) params.int("limit") else 50,
                if (params.containsKey("offset")) params.int("offset") else 0,
            )
            "file.preview" -> Command.PreviewFile(params.str("id"))
            "file.read" -> Command.ReadFile(
                params.str("id"),
                if (params.containsKey("offset")) params.long("offset") else 0L,
                params.int("length"),
            )
            "file.write" -> Command.WriteFile(
                location(params.str("location")),
                params.optStr("folder"),
                params.optStr("subfolder"),
                params.str("name"),
                params.optStr("mime"),
                params.str("data_base64"),
                params.bool("append", false),
                params.bool("overwrite", false),
            )
            "file.mkdir" -> Command.MakeFolder(params.optStr("folder"), params.str("name"))
            "file.rename" -> Command.RenameFile(params.str("id"), params.str("name"))
            "file.delete" -> Command.DeleteFile(params.str("id"))
            "app.share" -> Command.Share(
                params.str("package"),
                (params["ids"] as? kotlinx.serialization.json.JsonArray)?.map {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("ids must be strings")
                } ?: invalid("ids must be a list"),
                params.optStr("text"),
            )
            "file.fetch" -> Command.FetchFile(
                location(params.str("location")),
                params.optStr("folder"),
                params.optStr("subfolder"),
                params.str("name"),
                params.optStr("mime"),
                params.bool("overwrite", false),
                parseLink(params.obj("link")),
                params.str("sha256"),
                if (params.containsKey("size")) params.long("size") else null,
            )
            "file.push" -> Command.PushFile(params.str("id"), parseLink(params.obj("link")), params.long("max_bytes"))
            "file.transfer" -> Command.TransferStatus(
                params.str("transfer"),
                if (params.containsKey("wait_ms")) params.int("wait_ms") else 0,
                params.bool("cancel", false),
            )
            "clipboard.set" -> Command.SetClipboard(params.str("text"))
            "activity.list" -> Command.ListActivity(
                if (params.containsKey("limit")) params.int("limit") else 50,
                (params["kinds"] as? kotlinx.serialization.json.JsonArray)?.map {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("kinds must be strings")
                } ?: if (params["kinds"] == null) emptyList() else invalid("kinds must be a list"),
                if (params.containsKey("since_ms")) params.long("since_ms") else null,
            )
            "task.done" -> Command.TaskDone(params.optStr("summary"))
            else -> throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "unknown command")
        }
    }

    private fun parseLink(link: JsonObject): FileLink {
        val headers = (link["headers"] as? JsonObject)?.mapValues { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("link.headers.$k must be a string")
        } ?: emptyMap()
        return FileLink(link.str("url"), headers, link.str("key_hex"), link.str("iv_hex"))
    }

    private fun parseTarget(target: JsonObject): Target = when {
        target.containsKey("element") -> Target.Element(target.str("element"))
        target.containsKey("x") || target.containsKey("y") -> Target.Point(target.int("x"), target.int("y"))
        else -> invalid("target needs element or x and y")
    }
}

/** Mirrors `latch_protocol::validate`. */
object Limits {
    const val MAX_TEXT_CHARS = 2_000
    const val MAX_NODES = 2_000
    const val MAX_SWIPE_MS = 5_000
    const val MAX_COORDINATE = 20_000
    const val MAX_ID_CHARS = 64
    const val MAX_PACKAGE_CHARS = 255
    /** Since 1.6 room for one file chunk. */
    const val MAX_GATEWAY_FRAME_CHARS = 1024 * 1024
    const val MAX_NODE_TEXT_CHARS = 4_000
    const val MAX_SETTLE_MS = 3_000
    const val MAX_REMEMBER_CHARS = 160
    const val MAX_QUIET_MS = 1_000
    const val MIN_WAIT_MS = 100
    const val MAX_WAIT_MS = 15_000
    const val MAX_FIND_TEXT_CHARS = 200
    const val MAX_SCROLL_SWIPES = 20
    const val MAX_HOLD_MS = 3_000
    const val MIN_PINCH_SPAN = 20
    const val MAX_ASK_OWNER_CHARS = 300
    const val MAX_FILE_CHUNK_BYTES = 512 * 1024
    const val MAX_PREVIEW_TEXT_BYTES = 64 * 1024
    const val MAX_FILE_NAME_CHARS = 120
    const val MAX_FILE_LIST = 200
    const val MAX_SHARE_FILES = 10
    const val MAX_MIME_CHARS = 100
    /** Since 1.7. */
    const val MAX_LINK_FILE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_LINK_URL_CHARS = 4_096
    const val MAX_LINK_HEADERS = 8
    const val MAX_CLIPBOARD_CHARS = 10_000
    const val MAX_ACTIVITY_ENTRIES = 500
    const val MAX_TASK_SUMMARY_CHARS = 500
    /** Since 1.8: the activity kinds `activity.list` may ask for. */
    val ACTIVITY_KINDS = setOf("session", "connection", "screen", "action", "app", "approval", "refusal", "file", "folder", "task")

    fun isHex(value: String, length: Int) = value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** Storage hints only: never credentials or connection headers. */
    fun isValidLinkHeader(name: String, value: String): Boolean {
        val nameOk = name == "content-type" ||
            (name.length in 3..64 && name.startsWith("x-") && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
        return nameOk && value.length <= 512 && value.all { it.code in 0x20..0x7e }
    }

    /** No path separators, control characters, or leading dot; same rule as the gateways. */
    fun isValidFileName(name: String): Boolean {
        val n = name.codePointCount(0, name.length)
        return n in 1..MAX_FILE_NAME_CHARS && name.trim() == name && !name.startsWith(".") &&
            name.none { Character.isISOControl(it) || it in "/\\:*?\"<>|" }
    }

    fun isValidMime(mime: String): Boolean =
        mime.length <= MAX_MIME_CHARS && Regex("^[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+$").matches(mime)

    fun isValidId(id: String) =
        id.isNotEmpty() && id.length <= MAX_ID_CHARS && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    fun isValidPackage(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_PACKAGE_CHARS) return false
        val segments = name.split('.')
        return segments.size >= 2 && segments.all { s ->
            s.isNotEmpty() && s[0].isAsciiLetter() && s.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}

object Validation {
    private fun invalid(message: String): Nothing = throw ProtocolException(ErrorCode.INVALID_REQUEST, message)

    private fun coordinate(value: Int) {
        if (value !in 0..Limits.MAX_COORDINATE) invalid("coordinate out of range")
    }

    private fun id(value: String) {
        if (!Limits.isValidId(value)) invalid("bad identifier")
    }

    fun observeAfter(command: Command, after: ObserveAfter) {
        if (!command.isAction) invalid("observe_after is only allowed on actions")
        if (after.settleMs !in 0..Limits.MAX_SETTLE_MS) invalid("observe_after.settle_ms out of range")
        if (after.maxNodes !in 1..Limits.MAX_NODES) invalid("observe_after.max_nodes out of range")
        if (after.quietMs != null && after.quietMs !in 0..Limits.MAX_QUIET_MS) invalid("observe_after.quiet_ms out of range")
    }

    private fun findText(text: String) {
        if (text.isBlank() || text.codePointCount(0, text.length) > Limits.MAX_FIND_TEXT_CHARS) invalid("text must be 1-200 characters")
        if (text.any(Character::isISOControl)) invalid("control characters")
    }

    /** Where `file.write` and `file.fetch` save. */
    private fun destination(location: FileLocation, folder: String?, subfolder: String?, name: String, mime: String?) {
        if (!Limits.isValidFileName(name)) invalid("bad file name")
        folder?.let {
            if (location != FileLocation.FOLDER) invalid("folder is only for location folder")
            id(it)
        }
        subfolder?.let {
            if (location == FileLocation.FOLDER) invalid("subfolder is for photos and downloads")
            if (!Limits.isValidFileName(it)) invalid("bad subfolder name")
        }
        mime?.let { if (!Limits.isValidMime(it)) invalid("bad mime type") }
    }

    private fun link(link: FileLink) {
        val url = link.url
        val rest = url.removePrefix("https://").takeIf { it != url } ?: url.removePrefix("http://").takeIf { it != url }
        if (url.length > Limits.MAX_LINK_URL_CHARS || rest.isNullOrEmpty() || rest.startsWith("/") || url.any { it.code !in 0x21..0x7e }) {
            invalid("link.url must be an http(s) URL")
        }
        if (link.headers.size > Limits.MAX_LINK_HEADERS || !link.headers.all { (k, v) -> Limits.isValidLinkHeader(k, v) }) {
            invalid("link.headers must be x- or content-type headers with printable values")
        }
        if (!Limits.isHex(link.keyHex, 64) || !Limits.isHex(link.ivHex, 32)) invalid("link.key_hex must be 64 and link.iv_hex 32 lowercase hex characters")
    }

    fun command(command: Command) {
        when (command) {
            Command.DeviceInfoCommand, Command.ListApps, is Command.Global -> Unit
            is Command.Observe -> if (command.maxNodes !in 1..Limits.MAX_NODES) invalid("max_nodes out of range")
            is Command.Tap -> {
                id(command.observationId)
                if (command.longPress && command.double) invalid("a tap is either long_press or double, not both")
                when (val t = command.target) {
                    is Target.Element -> id(t.element)
                    is Target.Point -> { coordinate(t.x); coordinate(t.y) }
                }
            }
            is Command.Swipe -> {
                id(command.observationId)
                listOf(command.fromX, command.fromY, command.toX, command.toY).forEach(::coordinate)
                if (command.durationMs !in 50..Limits.MAX_SWIPE_MS) invalid("duration_ms out of range")
                if (command.fromX == command.toX && command.fromY == command.toY) invalid("swipe start and end must differ")
                if (command.holdMs !in 0..Limits.MAX_HOLD_MS) invalid("hold_ms out of range")
            }
            is Command.TypeText -> {
                id(command.observationId)
                id(command.element)
                if (command.text.codePointCount(0, command.text.length) > Limits.MAX_TEXT_CHARS) invalid("text too long")
                if (command.text.any { Character.isISOControl(it) && it != '\n' && it != '\t' }) invalid("control characters")
            }
            is Command.LaunchApp -> if (!Limits.isValidPackage(command.packageName)) invalid("bad package name")
            is Command.ListFiles -> {
                command.folder?.let(::id)
                command.query?.let(::findText)
                if (command.limit !in 1..Limits.MAX_FILE_LIST) invalid("limit out of range")
                if (command.offset < 0) invalid("offset must not be negative")
            }
            is Command.PreviewFile -> id(command.id)
            is Command.DeleteFile -> id(command.id)
            is Command.ReadFile -> {
                id(command.id)
                if (command.offset < 0) invalid("offset must not be negative")
                if (command.length !in 1..Limits.MAX_FILE_CHUNK_BYTES) invalid("length out of range")
            }
            is Command.WriteFile -> {
                destination(command.location, command.folder, command.subfolder, command.fileName, command.mime)
                if (command.append && command.overwrite) invalid("a write either appends or overwrites, not both")
                if (command.dataBase64.length > (Limits.MAX_FILE_CHUNK_BYTES + 2) / 3 * 4 ||
                    !command.dataBase64.all { it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/' || it == '=' }
                ) {
                    invalid("data_base64 too long or not base64")
                }
            }
            is Command.MakeFolder -> {
                command.folder?.let(::id)
                if (!Limits.isValidFileName(command.folderName)) invalid("bad folder name")
            }
            is Command.RenameFile -> {
                id(command.id)
                if (!Limits.isValidFileName(command.newName)) invalid("bad file name")
            }
            is Command.Share -> {
                if (!Limits.isValidPackage(command.packageName)) invalid("bad package name")
                if (command.ids.size !in 1..Limits.MAX_SHARE_FILES) invalid("ids must hold 1-${Limits.MAX_SHARE_FILES} file ids")
                command.ids.forEach(::id)
                command.text?.let { t ->
                    if (t.codePointCount(0, t.length) > Limits.MAX_TEXT_CHARS || t.any { Character.isISOControl(it) && it != '\n' }) {
                        invalid("bad share text")
                    }
                }
            }
            is Command.FetchFile -> {
                destination(command.location, command.folder, command.subfolder, command.fileName, command.mime)
                link(command.link)
                if (!Limits.isHex(command.sha256, 64)) invalid("sha256 must be 64 lowercase hex characters")
                if (command.size != null && command.size !in 0..Limits.MAX_LINK_FILE_BYTES) invalid("size out of range")
            }
            is Command.PushFile -> {
                id(command.id)
                link(command.link)
                if (command.maxBytes !in 1..Limits.MAX_LINK_FILE_BYTES) invalid("max_bytes out of range")
            }
            is Command.TransferStatus -> {
                id(command.transfer)
                if (command.waitMs !in 0..Limits.MAX_WAIT_MS) invalid("wait_ms out of range")
            }
            is Command.ListActivity -> {
                if (command.limit !in 1..Limits.MAX_ACTIVITY_ENTRIES) invalid("limit out of range")
                if (command.kinds.size > 10 || command.kinds.any { it !in Limits.ACTIVITY_KINDS }) invalid("unknown activity kind")
                if (command.sinceMs != null && command.sinceMs < 0) invalid("since_ms out of range")
            }
            is Command.TaskDone -> command.summary?.let { s ->
                if (s.codePointCount(0, s.length) > Limits.MAX_TASK_SUMMARY_CHARS) invalid("summary too long")
                if (s.any { Character.isISOControl(it) && it != '\n' && it != '\t' }) invalid("control characters")
            }
            is Command.SetClipboard -> {
                val n = command.text.codePointCount(0, command.text.length)
                if (n !in 1..Limits.MAX_CLIPBOARD_CHARS) invalid("text must be 1-${Limits.MAX_CLIPBOARD_CHARS} characters")
                if (command.text.any { Character.isISOControl(it) && it != '\n' && it != '\t' }) invalid("control characters")
            }
            is Command.AskOwner -> {
                val n = command.message.codePointCount(0, command.message.length)
                if (n !in 1..Limits.MAX_ASK_OWNER_CHARS || command.message.isBlank()) invalid("message must be 1-${Limits.MAX_ASK_OWNER_CHARS} characters")
                if (command.message.any { Character.isISOControl(it) && it != '\n' }) invalid("control characters")
            }
            is Command.Pinch -> {
                id(command.observationId)
                coordinate(command.centerX)
                coordinate(command.centerY)
                for (span in listOf(command.startSpan, command.endSpan)) {
                    if (span !in Limits.MIN_PINCH_SPAN..Limits.MAX_COORDINATE) invalid("span out of range")
                }
                if (command.startSpan == command.endSpan) invalid("start_span and end_span must differ")
                if (command.durationMs !in 50..Limits.MAX_SWIPE_MS) invalid("duration_ms out of range")
            }
            is Command.WaitFor -> {
                findText(command.text)
                if (command.timeoutMs !in Limits.MIN_WAIT_MS..Limits.MAX_WAIT_MS) invalid("timeout_ms out of range")
                if (command.maxNodes !in 1..Limits.MAX_NODES) invalid("max_nodes out of range")
            }
            is Command.ScrollTo -> {
                id(command.observationId)
                findText(command.text)
                command.container?.let(::id)
                if (command.maxSwipes !in 1..Limits.MAX_SCROLL_SWIPES) invalid("max_swipes out of range")
            }
        }
    }
}

/** Builds a JSON object for typed results without a serializer round trip. */
fun observationJson(observation: Observation): JsonElement = Protocol.json.encodeToJsonElement(Observation.serializer(), observation)
