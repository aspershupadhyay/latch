// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::capability::known_capabilities;

use crate::{CapabilityState, Command, ConfirmRequest, Observation, ProtocolError, ScreenInfo};

/// Static facts about the phone, sent once per connection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct DeviceDescriptor {
    /// `android`, `ios`, or `fake`.
    pub platform: String,
    pub os_version: String,
    pub model: String,
    pub app_version: String,
}

/// The owner's current session settings on the phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct SessionInfo {
    /// Unix epoch milliseconds after which the phone stops accepting commands.
    pub expires_at_ms: u64,
    /// When true the owner wants to approve every action, not only risky ones.
    #[serde(default)]
    pub approve_every_action: bool,
    /// When true the owner paused the session; commands are refused.
    #[serde(default)]
    pub paused: bool,
    /// Since 1.4: the owner lets approvals be answered in the AI app as well as
    /// on the phone. The gateway then asks the AI app's user (MCP elicitation)
    /// whenever the phone sends an [`ApprovalRequest`] marked `remote`.
    #[serde(default)]
    pub remote_approvals: bool,
    /// Since 1.20 (R67): which file places the owner shares with the AI.
    /// Absent from older phones.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub files: Option<FileAccess>,
}

/// The file places the owner shares (since 1.20, R67), so an AI knows what
/// `list_files` can reach before it asks. Downloads (the files Latch saved) is
/// always reachable while file reading is on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FileAccess {
    /// Photos are switched on in Latch and allowed by Android.
    pub photos: bool,
    /// How many folders the owner shares in Latch → Access → Files.
    pub folders: u32,
}

/// What an approval is about (since 1.4).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ApprovalKind {
    /// One action, such as a tap on "Send".
    Action,
    /// Letting the AI use an app at all.
    App,
}

/// An answer to an approval (since 1.4).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ApprovalChoice {
    Once,
    Session,
    Always,
    Deny,
}

/// The phone is waiting for the owner to answer an approval for a running
/// command (since 1.4). Gateways keep waiting for that command's result
/// while it is open, and may relay an answer when `remote` is true.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ApprovalRequest {
    /// The command the approval belongs to.
    pub command_id: String,
    /// Random, 32 lowercase hex characters; an answer must quote it.
    pub nonce: String,
    /// What the owner is asked, e.g. "Tap “Send” in com.example.chat". Untrusted app text may appear quoted.
    pub title: String,
    pub detail: String,
    pub kind: ApprovalKind,
    /// The answers the phone offers; `deny` is always possible.
    pub choices: Vec<ApprovalChoice>,
    /// The owner allows answering from the AI app (their `remote_approvals` switch).
    pub remote: bool,
    /// Unix ms (device clock) when the phone stops waiting.
    pub expires_at_ms: u64,
}

/// First message on every device connection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Hello {
    pub protocol: String,
    pub device: DeviceDescriptor,
    #[serde(deserialize_with = "known_capabilities")]
    pub capabilities: Vec<CapabilityState>,
    pub session: SessionInfo,
    /// Device clock (Unix ms) when the message was sent, so the gateway can
    /// interpret `session.expires_at_ms` despite clock skew.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_time_ms: Option<u64>,
    /// Since 1.12 (ADR-037): the phone's device key, a P-256 public key as
    /// base64 SubjectPublicKeyInfo DER. Its private half never leaves the
    /// phone's keystore. A hello carrying it must arrive [`DeviceToGateway::Signed`]
    /// by that key; from then on every message from this phone must be signed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_key: Option<String>,
    /// Since 1.13 (roadmap R13): true when Android's battery optimisation
    /// applies to the app, so Android may stop it in the background and the
    /// phone drops off mid-task. Absent when the phone does not say.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub battery_restricted: Option<bool>,
}

/// Since 1.12: the text a phone signs for one message: a fixed context, the
/// connection the gateway named in its welcome (empty for `hello`), and the
/// exact message bytes, each on its own line. Binding the connection keeps a
/// message from being replayed into another connection.
pub fn signing_input(connection: &str, payload: &str) -> Vec<u8> {
    format!("{SIGNING_CONTEXT}\n{connection}\n{payload}").into_bytes()
}

/// First line of every [`signing_input`].
pub const SIGNING_CONTEXT: &str = "latch-signed-v1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum Outcome {
    Ok { data: serde_json::Value },
    Error { error: ProtocolError },
}

/// Messages a phone sends to the gateway.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum DeviceToGateway {
    Hello(Hello),
    /// Answer to exactly one [`GatewayToDevice::Command`].
    Result {
        id: String,
        outcome: Outcome,
    },
    /// The owner changed capabilities or session settings.
    State {
        #[serde(deserialize_with = "known_capabilities")]
        capabilities: Vec<CapabilityState>,
        session: SessionInfo,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        device_time_ms: Option<u64>,
    },
    /// The device is closing the session on purpose (stop button, unpair, expiry).
    Bye {
        reason: String,
    },
    /// Since 1.4: the phone is waiting for the owner's answer to an approval.
    ApprovalRequest(ApprovalRequest),
    /// Since 1.12 (ADR-037): another message, signed by the phone's device key.
    /// `payload` is that message's JSON text exactly as signed; `sig` is an
    /// ECDSA P-256 / SHA-256 signature (DER, base64) over
    /// [`signing_input`]`(connection, payload)`. Never nested.
    Signed {
        payload: String,
        sig: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct CommandEnvelope {
    /// Unique per gateway; used to correlate the result and for idempotency.
    ///
    /// Since 1.19 (ADR-048) a command runs at most once per id. When a phone
    /// reconnects while a command is still in flight, the gateway sends the
    /// same envelope again, with the same id, to a phone that said `hello`
    /// with 1.19 or later. The phone must not run it twice: it answers again
    /// with the answer it already gave, or, while the first run is still
    /// going, sends that run's answer once it finishes. An id it ran but no
    /// longer remembers is answered `cancelled` without running.
    pub id: String,
    /// Milliseconds the device may spend, including waiting for approval.
    pub deadline_ms: u32,
    pub command: Command,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirm: Option<ConfirmRequest>,
    /// Since 1.2, actions only: observe after the action succeeded and return
    /// the observation inside its result, saving the gateway a second command.
    /// Gateways send it only to phones that said `hello` with 1.2 or later.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observe_after: Option<ObserveAfter>,
}

/// How to observe after an action (since 1.2). The phone applies the same
/// rules as `ui.observe`: the `ui.observe` capability (and `screen.capture`
/// for a screenshot) must be enabled, and restricted screens are refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ObserveAfter {
    /// Milliseconds to let the UI settle before observing, at most 3000.
    pub settle_ms: u32,
    #[serde(default)]
    pub include_screenshot: bool,
    #[serde(default = "default_max_nodes")]
    pub max_nodes: u32,
    /// Since 1.3: observe as soon as the screen has not changed for this many
    /// milliseconds (at most 1000), waiting at most `settle_ms` in all. Older
    /// phones ignore it and wait the full `settle_ms`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quiet_ms: Option<u32>,
}

fn default_max_nodes() -> u32 {
    400
}

/// Messages the gateway sends to a phone.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum GatewayToDevice {
    Welcome {
        protocol: String,
        device_id: String,
        server_time_ms: u64,
        /// HTTP transport only: identifies this connection in later poll and
        /// message requests. A newer `hello` replaces it (since 1.1).
        #[serde(default, skip_serializing_if = "Option::is_none")]
        connection: Option<String>,
    },
    Command(CommandEnvelope),
    /// Abandon a command if it has not run yet. Best effort.
    Cancel {
        id: String,
    },
    /// The gateway owner revoked this device. Forget the credential and disconnect.
    Revoked {
        reason: String,
    },
    /// Since 1.4: the owner answered an [`ApprovalRequest`] in the AI app. The
    /// phone applies it only if the nonce matches its open request and the
    /// owner's `remote_approvals` switch is on.
    ApprovalAnswer {
        nonce: String,
        choice: ApprovalChoice,
    },
    /// Since 1.20 (R67): the gateway refused a tool call before any command
    /// reached the phone (a password field, a link Latch never opens, a switch
    /// that is off, arguments that do not check out). The phone writes it into
    /// the owner's activity log, so History and its report miss no refusal.
    /// Content-free: the tool's name and the error code only. Older phones
    /// drop it like any unknown message.
    Refused {
        tool: String,
        code: crate::ErrorCode,
    },
}

// ---- Typed `Outcome::Ok` payloads, by command ----

/// Result of `device.info`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct DeviceInfo {
    pub device: DeviceDescriptor,
    pub screen: ScreenInfo,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    pub capabilities: Vec<CapabilityState>,
    pub session: SessionInfo,
    /// Since 1.15: battery, network, storage, and screen state. Absent from older apps.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<DeviceStatus>,
}

/// Since 1.15: the phone's state, part of `device.info`. Every field is
/// optional: a phone leaves out what it cannot read. Nothing here is
/// personal: no network names, no addresses, no account or app content.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct DeviceStatus {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub battery_percent: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub charging: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub network: Option<NetworkKind>,
    /// Free space for files, in whole megabytes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub free_storage_mb: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub orientation: Option<Orientation>,
    /// True while the lock screen is showing: only the owner can unlock (ask_owner).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locked: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub do_not_disturb: Option<bool>,
    /// Since 1.21 (R72): the speed tier in use; `turbo` while animations are off for the AI.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub speed: Option<SpeedTier>,
    /// Since 1.21 (R72): how hot the phone runs; past `moderate` Android slows it down.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub thermal: Option<Thermal>,
}

/// Since 1.21 (R72, ADR-074): the speed tier a phone runs the AI's steps at.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum SpeedTier {
    Standard,
    /// System animations are off while the AI works (`WRITE_SECURE_SETTINGS`).
    Turbo,
}

/// Since 1.21 (R72): Android's thermal status, coarsened.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Thermal {
    None,
    Light,
    Moderate,
    Severe,
    Critical,
}

/// Since 1.15: how the phone reaches the internet right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum NetworkKind {
    Wifi,
    Cellular,
    Ethernet,
    None,
}

/// Since 1.15: which way up the screen is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Orientation {
    Portrait,
    Landscape,
}

/// Result of input and navigation commands.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, JsonSchema)]
pub struct ActionResult {
    /// Foreground package shortly after the action, when known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    /// The screen after the action, when the command asked with `observe_after` (since 1.2).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observation: Option<Observation>,
    /// Why the phone could not observe after the action. The action itself succeeded.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observation_error: Option<ProtocolError>,
    /// `ui.scroll_to` only (since 1.3): whether the text is now on screen.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub found: Option<bool>,
    /// `ui.type_text` with submit only: false when Enter visibly did nothing
    /// (the text stayed in the field and the screen said nothing new). Absent from older apps.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub submitted: Option<bool>,
    /// `owner.ask` only (since 1.5): what the owner answered.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub owner: Option<OwnerReply>,
    /// Since 1.9, with `observe_after`: what the action visibly did, judged by
    /// the phone after the screen settled. Absent from older apps.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect: Option<ActionEffect>,
    /// `device.control` volume only (since 1.20, R67): the level the phone
    /// actually set, read back from Android. Absent from older apps.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub level: Option<ControlLevel>,
}

/// The volume step a phone actually set (since 1.20, R67). Phones have a few
/// steps per stream (often 7 to 25), so a requested percentage lands on the
/// nearest step; the phone's own slider may draw that step as another number.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ControlLevel {
    /// The step now set, from 0.
    pub step: u32,
    /// The highest step of this stream.
    pub max_step: u32,
    /// `step` as a percentage of `max_step`.
    pub percent: u8,
}

/// What an action visibly did (since 1.9).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ActionEffect {
    /// The screen looks the same as before: the action may have missed.
    None,
    /// The same app shows something different.
    Changed,
    /// Another app is now in front.
    NewApp,
}

/// Result of `adb.shell` (since 1.17, ADR-046).
///
/// The device returns ADB's own output verbatim. In the owner-enabled
/// full-access mode Latch does not filter or redact it; the whole result goes
/// only to the authenticated client and is kept out of persistent logs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct AdbResult {
    /// Standard output of this chunk (empty string if none).
    pub stdout: String,
    /// Standard error of this chunk (empty string if none).
    pub stderr: String,
    /// The process exit status, once the command has finished. Absent while
    /// more output remains — `cursor` is then set.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i32>,
    /// Present when the output was chunked to fit a frame: send `adb.shell`
    /// again with this value as `continue_from` for the next chunk. Absent
    /// when the command is complete.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    /// Since 1.20: the job this answer is about (a `background` start or a collection).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub job: Option<String>,
    /// Since 1.20: the job's state once everything it wrote so far was read.
    /// `running` also while output is left to collect; `finished` comes with
    /// `exit_code`; after `finished` or `cancelled` the job id is forgotten.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub job_state: Option<AdbJobState>,
}

/// Since 1.20 (ADR-049): where an `adb.shell` job stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum AdbJobState {
    Running,
    Finished,
    Cancelled,
}

/// The owner's answer to `owner.ask` (since 1.5).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum OwnerReply {
    /// The owner says they did it.
    Done,
    /// The owner says they cannot or will not.
    Cant,
    /// Nobody answered before the deadline.
    NoAnswer,
}

/// Result of `ui.wait` (since 1.3).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct WaitResult {
    /// True when the condition was met before the timeout.
    pub matched: bool,
    /// The screen when the wait ended; it becomes the latest observation.
    pub observation: Observation,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct AppEntry {
    pub package: String,
    /// Launcher label. Untrusted app content.
    pub label: String,
}

/// Result of `app.list`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct AppList {
    pub apps: Vec<AppEntry>,
}
