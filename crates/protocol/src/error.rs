use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

/// Stable machine-readable failure codes.
///
/// Every failure anywhere in the system maps to exactly one of these. Codes
/// are part of the public contract: never rename one, only add.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    /// The request was malformed or violated a protocol limit.
    InvalidRequest,
    /// The device does not offer this capability on this platform.
    UnsupportedCapability,
    /// The owner has not enabled a required capability, or an OS permission is missing.
    PermissionMissing,
    /// Policy refused the operation outright.
    PolicyRefused,
    /// The operation targets a password, OTP, or otherwise sensitive element.
    SensitiveTarget,
    /// The screen changed since the referenced observation.
    StaleObservation,
    /// The referenced element no longer exists or is not actionable.
    TargetNotFound,
    /// The app marked its window as secure; the OS refused capture.
    ScreenProtected,
    /// The owner denied the approval request.
    UserDenied,
    /// The approval request was not answered in time.
    ConfirmationExpired,
    /// The operation was cancelled before it ran.
    Cancelled,
    /// The device did not answer before the deadline.
    DeadlineExceeded,
    /// No device is connected, the session is paused or stopped, or it ended.
    DeviceUnavailable,
    /// More than one device is online and none was named.
    AmbiguousDevice,
    /// A transient transport problem; retrying may help.
    TransportUnavailable,
    /// A bug in Latch. Please report it with the correlation id.
    Internal,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorCode::InvalidRequest => "invalid_request",
            ErrorCode::UnsupportedCapability => "unsupported_capability",
            ErrorCode::PermissionMissing => "permission_missing",
            ErrorCode::PolicyRefused => "policy_refused",
            ErrorCode::SensitiveTarget => "sensitive_target",
            ErrorCode::StaleObservation => "stale_observation",
            ErrorCode::TargetNotFound => "target_not_found",
            ErrorCode::ScreenProtected => "screen_protected",
            ErrorCode::UserDenied => "user_denied",
            ErrorCode::ConfirmationExpired => "confirmation_expired",
            ErrorCode::Cancelled => "cancelled",
            ErrorCode::DeadlineExceeded => "deadline_exceeded",
            ErrorCode::DeviceUnavailable => "device_unavailable",
            ErrorCode::AmbiguousDevice => "ambiguous_device",
            ErrorCode::TransportUnavailable => "transport_unavailable",
            ErrorCode::Internal => "internal",
        }
    }

    /// Whether repeating the identical request later might succeed.
    pub fn retryable(self) -> bool {
        matches!(
            self,
            ErrorCode::DeadlineExceeded
                | ErrorCode::DeviceUnavailable
                | ErrorCode::TransportUnavailable
        )
    }

    /// The safe next step, written for the agent calling the tool.
    pub fn recovery_hint(self) -> &'static str {
        match self {
            ErrorCode::InvalidRequest => "Fix the arguments and try again.",
            ErrorCode::UnsupportedCapability => {
                "This device cannot do that. Choose a different approach."
            }
            ErrorCode::PermissionMissing => {
                "Ask the phone owner to enable this capability in the Latch app."
            }
            ErrorCode::PolicyRefused => "This operation is not allowed. Do not retry it.",
            ErrorCode::SensitiveTarget => {
                "Latch never touches password, OTP, or payment fields. Ask the user to do this step."
            }
            ErrorCode::StaleObservation => "Call observe again and plan from the new screen.",
            ErrorCode::TargetNotFound => {
                "Call observe again and pick an element from the new screen."
            }
            ErrorCode::ScreenProtected => {
                "The app blocks screenshots. Use the element tree instead, or ask the user."
            }
            ErrorCode::UserDenied => "The owner said no. Do not retry without asking them.",
            ErrorCode::ConfirmationExpired => {
                "Nobody approved in time. Ask the user to watch their phone, then retry once."
            }
            ErrorCode::Cancelled => "The operation was cancelled. Observe before continuing.",
            ErrorCode::DeadlineExceeded => {
                "The device did not answer in time. Observe, then retry."
            }
            ErrorCode::DeviceUnavailable => {
                "Ask the owner to open Latch on the phone and start a session."
            }
            ErrorCode::AmbiguousDevice => "Pass device_id. Call list_devices to see the options.",
            ErrorCode::TransportUnavailable => "Wait a few seconds and retry.",
            ErrorCode::Internal => "This is a Latch bug. Stop and report it.",
        }
    }
}

impl std::fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

/// A typed protocol failure with a message that is safe to show and log.
///
/// Messages must never contain screen text, tokens, or personal data.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema, thiserror::Error)]
#[error("{code}: {message}")]
pub struct ProtocolError {
    pub code: ErrorCode,
    pub message: String,
}

impl ProtocolError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}
