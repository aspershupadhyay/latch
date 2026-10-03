use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::Capability;

/// A point in physical screen pixels of the observation it was taken from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Point {
    pub x: i32,
    pub y: i32,
}

/// What an input command acts on.
///
/// Element targets are preferred: the device re-resolves the element from the
/// referenced observation and refuses if it moved, vanished, or is sensitive.
/// Point targets are only accepted together with a fresh observation id.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(untagged)]
pub enum Target {
    Element { element: String },
    Point { x: i32, y: i32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum GlobalAction {
    Back,
    Home,
    Recents,
}

/// Scroll direction, named after the content being revealed: `down` shows
/// what is further down.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

fn is_false(b: &bool) -> bool {
    !*b
}

fn is_zero(n: &u32) -> bool {
    *n == 0
}

/// One device operation. Serialized as `{"name": "...", "params": {...}}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "name", content = "params")]
pub enum Command {
    #[serde(rename = "device.info")]
    DeviceInfo {},

    /// Capture the current screen as a redacted element tree, optionally with a screenshot.
    #[serde(rename = "ui.observe")]
    Observe {
        #[serde(default)]
        include_screenshot: bool,
        #[serde(default = "default_max_nodes")]
        max_nodes: u32,
    },

    #[serde(rename = "input.tap")]
    Tap {
        observation_id: String,
        target: Target,
        #[serde(default)]
        long_press: bool,
        /// Since 1.3: two quick taps (zoom a map, like a photo). Not with `long_press`.
        #[serde(default, skip_serializing_if = "is_false")]
        double: bool,
    },

    #[serde(rename = "input.swipe")]
    Swipe {
        observation_id: String,
        from: Point,
        to: Point,
        #[serde(default = "default_swipe_ms")]
        duration_ms: u32,
        /// Since 1.3: press and hold at `from` this long before moving, which
        /// drags (icons, list items, sliders) instead of scrolling.
        #[serde(default, skip_serializing_if = "is_zero")]
        hold_ms: u32,
    },

    /// Since 1.3: two fingers moving apart (`end_span` > `start_span`, zoom
    /// in) or together (zoom out) around `center`, horizontally.
    #[serde(rename = "input.pinch")]
    Pinch {
        observation_id: String,
        center: Point,
        start_span: u32,
        end_span: u32,
        #[serde(default = "default_swipe_ms")]
        duration_ms: u32,
    },

    /// Replace the text of an editable element. Never allowed on sensitive fields.
    #[serde(rename = "input.type")]
    TypeText {
        observation_id: String,
        element: String,
        text: String,
        /// Since 1.3: then press the keyboard's action key (Enter, Search,
        /// Send, Go, Done) in that field. Sent only to 1.3+ phones.
        #[serde(default, skip_serializing_if = "is_false")]
        submit: bool,
    },

    /// Since 1.3: wait until an element whose text or description contains
    /// `text` (case-insensitive) is on screen, or until none is when `gone`.
    /// Answers with a [`crate::WaitResult`] whose observation becomes the latest.
    #[serde(rename = "ui.wait")]
    WaitFor {
        text: String,
        #[serde(default)]
        gone: bool,
        #[serde(default = "default_wait_ms")]
        timeout_ms: u32,
        #[serde(default = "default_max_nodes")]
        max_nodes: u32,
    },

    /// Since 1.3: scroll `container` (or the largest scrollable element) in
    /// `direction` until an element containing `text` is visible, at most
    /// `max_swipes` times. Answers with an [`crate::ActionResult`] whose
    /// `found` says whether it got there.
    #[serde(rename = "ui.scroll_to")]
    ScrollTo {
        observation_id: String,
        text: String,
        direction: Direction,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        container: Option<String>,
        #[serde(default = "default_max_swipes")]
        max_swipes: u32,
    },

    #[serde(rename = "nav.global")]
    Global { action: GlobalAction },

    #[serde(rename = "app.list")]
    ListApps {},

    #[serde(rename = "app.launch")]
    LaunchApp { package: String },

    /// Since 1.5: ask the owner to do something only a person should do (log
    /// in, unlock, type a code, choose). The phone shows `message` on a card
    /// with "Done" and "I can't" and answers with an [`crate::ActionResult`]
    /// whose `owner` holds the reply. Needs no capability; only the owner can
    /// answer, never the AI.
    #[serde(rename = "owner.ask")]
    AskOwner { message: String },
}

fn default_max_nodes() -> u32 {
    400
}

fn default_swipe_ms() -> u32 {
    300
}

fn default_wait_ms() -> u32 {
    5_000
}

fn default_max_swipes() -> u32 {
    10
}

impl Command {
    /// Wire name, e.g. `input.tap`.
    pub fn name(&self) -> &'static str {
        match self {
            Command::DeviceInfo {} => "device.info",
            Command::Observe { .. } => "ui.observe",
            Command::Tap { .. } => "input.tap",
            Command::Swipe { .. } => "input.swipe",
            Command::TypeText { .. } => "input.type",
            Command::Global { .. } => "nav.global",
            Command::ListApps {} => "app.list",
            Command::LaunchApp { .. } => "app.launch",
            Command::WaitFor { .. } => "ui.wait",
            Command::ScrollTo { .. } => "ui.scroll_to",
            Command::Pinch { .. } => "input.pinch",
            Command::AskOwner { .. } => "owner.ask",
        }
    }

    /// Every capability that must be enabled for this command to run.
    pub fn required_capabilities(&self) -> Vec<Capability> {
        match self {
            Command::DeviceInfo {} => vec![Capability::DeviceInfo],
            Command::Observe {
                include_screenshot, ..
            } => {
                if *include_screenshot {
                    vec![Capability::UiObserve, Capability::ScreenCapture]
                } else {
                    vec![Capability::UiObserve]
                }
            }
            Command::Tap { .. } | Command::Swipe { .. } | Command::Pinch { .. } => {
                vec![Capability::InputGesture]
            }
            Command::TypeText { .. } => vec![Capability::InputText],
            Command::Global { .. } => vec![Capability::NavGlobal],
            Command::ListApps {} | Command::LaunchApp { .. } => vec![Capability::AppLaunch],
            Command::WaitFor { .. } => vec![Capability::UiObserve],
            Command::ScrollTo { .. } => vec![Capability::UiObserve, Capability::InputGesture],
            // Only shows the owner a question; the owner does the rest.
            Command::AskOwner { .. } => vec![],
        }
    }

    /// True for commands that change device state (as opposed to reading it).
    /// Asking the owner counts: the owner changes the screen while answering.
    pub fn is_action(&self) -> bool {
        !matches!(
            self,
            Command::DeviceInfo {}
                | Command::Observe { .. }
                | Command::ListApps {}
                | Command::WaitFor { .. }
        )
    }

    /// True for actions the owner approves under "Ask me before every
    /// action". Asking the owner is already a question to the owner.
    pub fn needs_owner_approval_when_strict(&self) -> bool {
        self.is_action() && !matches!(self, Command::AskOwner { .. })
    }

    /// The lowest protocol minor version a phone must speak to understand this
    /// command exactly (older phones would refuse it or ignore a field).
    pub fn min_minor_version(&self) -> u32 {
        match self {
            Command::WaitFor { .. } | Command::ScrollTo { .. } | Command::Pinch { .. } => 3,
            Command::TypeText { submit: true, .. } | Command::Tap { double: true, .. } => 3,
            Command::Swipe { hold_ms, .. } if *hold_ms > 0 => 3,
            Command::AskOwner { .. } => 5,
            _ => 0,
        }
    }

    /// The observation an action was planned against, if any.
    pub fn observation_id(&self) -> Option<&str> {
        match self {
            Command::Tap { observation_id, .. }
            | Command::Swipe { observation_id, .. }
            | Command::TypeText { observation_id, .. }
            | Command::ScrollTo { observation_id, .. }
            | Command::Pinch { observation_id, .. } => Some(observation_id),
            _ => None,
        }
    }
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum RiskLevel {
    Low,
    Medium,
    High,
}

/// Longest `ConfirmRequest::remember` key.
pub const MAX_REMEMBER_CHARS: usize = 160;

/// Attached to a command when a human must approve it on the phone first.
///
/// The device shows `title` and `detail` verbatim, waits for the owner, and
/// answers `user_denied` or `confirmation_expired` instead of executing when
/// approval does not arrive before the command deadline.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ConfirmRequest {
    pub title: String,
    pub detail: String,
    pub risk: RiskLevel,
    /// Since 1.3: present when the owner may answer "allow for this session"
    /// or "always allow in this app" instead of only "once". Absent for
    /// critical actions (money, permissions, installs, account deletion),
    /// which are asked every time. The phone matches saved answers by this
    /// exact key and may still ignore it after its own live check.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub remember: Option<String>,
}
