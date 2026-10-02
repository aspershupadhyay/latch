use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

/// A named, separately grantable device capability.
///
/// Capabilities are what the phone owner switches on and off. Every command
/// maps to the capabilities it needs (see [`crate::Command::required_capabilities`]);
/// a command whose capabilities are not all enabled is refused before it
/// reaches the platform.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, JsonSchema)]
pub enum Capability {
    /// Device model, OS version, screen size, and session state.
    #[serde(rename = "device.info")]
    DeviceInfo,
    /// Read a redacted tree of on-screen UI elements.
    #[serde(rename = "ui.observe")]
    UiObserve,
    /// Take a screenshot of the current screen.
    #[serde(rename = "screen.capture")]
    ScreenCapture,
    /// Tap, long-press, and swipe.
    #[serde(rename = "input.gesture")]
    InputGesture,
    /// Type text into an editable, non-sensitive field.
    #[serde(rename = "input.text")]
    InputText,
    /// Press back, home, or recents.
    #[serde(rename = "nav.global")]
    NavGlobal,
    /// List launchable apps and open one.
    #[serde(rename = "app.launch")]
    AppLaunch,
}

impl Capability {
    pub const ALL: [Capability; 7] = [
        Capability::DeviceInfo,
        Capability::UiObserve,
        Capability::ScreenCapture,
        Capability::InputGesture,
        Capability::InputText,
        Capability::NavGlobal,
        Capability::AppLaunch,
    ];

    /// Stable wire identifier, e.g. `input.gesture`.
    pub fn id(self) -> &'static str {
        match self {
            Capability::DeviceInfo => "device.info",
            Capability::UiObserve => "ui.observe",
            Capability::ScreenCapture => "screen.capture",
            Capability::InputGesture => "input.gesture",
            Capability::InputText => "input.text",
            Capability::NavGlobal => "nav.global",
            Capability::AppLaunch => "app.launch",
        }
    }

    /// Plain-language description used in tool docs and user-facing errors.
    pub fn describe(self) -> &'static str {
        match self {
            Capability::DeviceInfo => "read basic device and session information",
            Capability::UiObserve => "read on-screen UI elements (sensitive fields are redacted)",
            Capability::ScreenCapture => "take screenshots",
            Capability::InputGesture => "tap, long-press, and swipe",
            Capability::InputText => "type into non-sensitive text fields",
            Capability::NavGlobal => "press back, home, and recents",
            Capability::AppLaunch => "list and open apps",
        }
    }
}

/// Whether a capability can be used right now on a specific device.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum CapabilityStatus {
    /// The owner switched it on and the OS permission is granted.
    Enabled,
    /// The owner has not switched it on (the default for everything but `device.info`).
    Disabled,
    /// The owner wants it but an OS permission is missing.
    NeedsPermission,
    /// This platform or OS version cannot provide it.
    Unsupported,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct CapabilityState {
    pub capability: Capability,
    pub status: CapabilityStatus,
}
