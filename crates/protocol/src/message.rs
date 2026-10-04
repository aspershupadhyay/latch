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
}

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
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct CommandEnvelope {
    /// Unique per gateway; used to correlate the result and for idempotency.
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
