use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

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
}

/// First message on every device connection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Hello {
    pub protocol: String,
    pub device: DeviceDescriptor,
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
        capabilities: Vec<CapabilityState>,
        session: SessionInfo,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        device_time_ms: Option<u64>,
    },
    /// The device is closing the session on purpose (stop button, unpair, expiry).
    Bye {
        reason: String,
    },
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
