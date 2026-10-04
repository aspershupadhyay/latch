use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::{Capability, FileLink, FileLocation};

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

    /// Since 1.6: list files in a location, newest first. `folder` is a
    /// folder id from an earlier answer (location `folder` only); `query`
    /// filters by name. Answers with a [`crate::FileList`].
    #[serde(rename = "file.list")]
    ListFiles {
        location: FileLocation,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        folder: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        query: Option<String>,
        #[serde(default = "default_file_limit")]
        limit: u32,
        #[serde(default, skip_serializing_if = "is_zero")]
        offset: u32,
    },

    /// Since 1.6: a look at one file: text, or a downscaled image. Answers
    /// with a [`crate::FilePreview`].
    #[serde(rename = "file.preview")]
    PreviewFile { id: String },

    /// Since 1.6: raw bytes of a file from `offset`, at most `length`.
    /// Answers with a [`crate::FileChunk`].
    #[serde(rename = "file.read")]
    ReadFile {
        id: String,
        #[serde(default)]
        offset: u64,
        length: u32,
    },

    /// Since 1.6: save bytes as a new file, or append them to a file this
    /// session created (`append`). In `folder` the file goes into `folder`
    /// (a folder id, default the picked folder); in `photos` and
    /// `downloads` into `subfolder` (default "Latch"). An existing name is
    /// kept and the new file gets a free name, unless `overwrite`, which the
    /// owner approves. Answers with a [`crate::FileItem`].
    #[serde(rename = "file.write")]
    WriteFile {
        location: FileLocation,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        folder: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        subfolder: Option<String>,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        mime: Option<String>,
        data_base64: String,
        #[serde(default, skip_serializing_if = "is_false")]
        append: bool,
        #[serde(default, skip_serializing_if = "is_false")]
        overwrite: bool,
    },

    /// Since 1.6: create a folder inside the picked folder (or `folder`).
    /// Answers with a [`crate::FileItem`].
    #[serde(rename = "file.mkdir")]
    MakeFolder {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        folder: Option<String>,
        name: String,
    },

    /// Since 1.6: rename a file or folder. Answers with a [`crate::FileItem`].
    #[serde(rename = "file.rename")]
    RenameFile { id: String, name: String },

    /// Since 1.6: delete a file or folder; the owner approves.
    #[serde(rename = "file.delete")]
    DeleteFile { id: String },

    /// Since 1.6: open `package`'s Android share screen with these files
    /// (and optional text), so the owner or the AI can finish the post or
    /// message in that app. Answers with an [`crate::ActionResult`].
    #[serde(rename = "app.share")]
    Share {
        package: String,
        ids: Vec<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        text: Option<String>,
    },

    /// Since 1.7 (ADR-027): download a whole file from `link`, decrypt it,
    /// check that its SHA-256 is `sha256`, and save it like `file.write`
    /// (same places, names, and overwrite rule). A file whose check fails is
    /// never kept. The phone starts the download in the background and
    /// answers with a [`crate::FileTransfer`] at once (or when it is done, if
    /// that is quick); `file.transfer` follows it.
    #[serde(rename = "file.fetch")]
    FetchFile {
        location: FileLocation,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        folder: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        subfolder: Option<String>,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        mime: Option<String>,
        #[serde(default, skip_serializing_if = "is_false")]
        overwrite: bool,
        link: Box<FileLink>,
        /// SHA-256 of the plain file, 64 lowercase hex characters.
        sha256: String,
        /// Plain size in bytes, when the gateway knows it (for the progress bar and the free-space check).
        #[serde(default, skip_serializing_if = "Option::is_none")]
        size: Option<u64>,
    },

    /// Since 1.7 (ADR-027): encrypt the file `id` and upload it to `link`, at
    /// most `max_bytes`. Answers like `file.fetch`; when done the
    /// [`crate::FileTransfer`] carries the plain file's SHA-256.
    #[serde(rename = "file.push")]
    PushFile {
        id: String,
        link: Box<FileLink>,
        max_bytes: u64,
    },

    /// Since 1.7: progress of a transfer this session started, waiting up to
    /// `wait_ms` for it to finish. With `cancel` the transfer stops and its
    /// partial file is removed. Answers with a [`crate::FileTransfer`], or
    /// with the error that ended the transfer.
    #[serde(rename = "file.transfer")]
    TransferStatus {
        transfer: String,
        #[serde(default, skip_serializing_if = "is_zero")]
        wait_ms: u32,
        #[serde(default, skip_serializing_if = "is_false")]
        cancel: bool,
    },

    /// Since 1.7: put `text` on the phone's clipboard, e.g. a caption to paste
    /// into an app whose text box Latch cannot type into. Reading the
    /// clipboard is not offered. Answers with an [`crate::ActionResult`].
    #[serde(rename = "clipboard.set")]
    SetClipboard { text: String },

    /// Since 1.8: the newest entries of the phone's activity log, newest
    /// first: apps the AI used, its actions, the owner's approvals and
    /// refusals, files and folders it touched. Entries hold no screen
    /// content or typed text. `kinds` keeps only those kinds; `since_ms`
    /// only entries at or after that time. Answers with an
    /// [`crate::ActivityList`].
    #[serde(rename = "activity.list")]
    ListActivity {
        #[serde(default = "default_activity_limit")]
        limit: u32,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        kinds: Vec<crate::ActivityKind>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        since_ms: Option<u64>,
    },

    /// Since 1.8: the AI finished the owner's task. The phone hides the
    /// cursor at once and logs `summary` (the AI's own words, at most 500
    /// characters). Changes nothing else. Answers with an
    /// [`crate::ActionResult`].
    #[serde(rename = "task.done")]
    TaskDone {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        summary: Option<String>,
    },
}

fn default_activity_limit() -> u32 {
    50
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

fn default_file_limit() -> u32 {
    50
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
            Command::ListFiles { .. } => "file.list",
            Command::PreviewFile { .. } => "file.preview",
            Command::ReadFile { .. } => "file.read",
            Command::WriteFile { .. } => "file.write",
            Command::MakeFolder { .. } => "file.mkdir",
            Command::RenameFile { .. } => "file.rename",
            Command::DeleteFile { .. } => "file.delete",
            Command::Share { .. } => "app.share",
            Command::FetchFile { .. } => "file.fetch",
            Command::PushFile { .. } => "file.push",
            Command::TransferStatus { .. } => "file.transfer",
            Command::SetClipboard { .. } => "clipboard.set",
            Command::ListActivity { .. } => "activity.list",
            Command::TaskDone { .. } => "task.done",
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
            Command::ListFiles { .. } | Command::PreviewFile { .. } | Command::ReadFile { .. } => {
                vec![Capability::FileRead]
            }
            Command::WriteFile { .. }
            | Command::MakeFolder { .. }
            | Command::RenameFile { .. }
            | Command::DeleteFile { .. } => vec![Capability::FileWrite],
            Command::Share { .. } => vec![Capability::AppShare],
            Command::FetchFile { .. } => vec![Capability::FileWrite],
            Command::PushFile { .. } => vec![Capability::FileRead],
            // Follows a transfer that already passed its own checks; the phone
            // answers only for transfers of the running session.
            Command::TransferStatus { .. } => vec![],
            Command::SetClipboard { .. } => vec![Capability::ClipboardWrite],
            Command::ListActivity { .. } => vec![Capability::ActivityRead],
            // Only hides Latch's own cursor and writes a log line.
            Command::TaskDone { .. } => vec![],
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
                | Command::ListFiles { .. }
                | Command::PreviewFile { .. }
                | Command::ReadFile { .. }
                | Command::PushFile { .. }
                | Command::TransferStatus { .. }
                | Command::ListActivity { .. }
                | Command::TaskDone { .. }
        )
    }

    /// True for actions that change what is on screen, after which the old
    /// observation is stale and a new one is worth returning. File changes
    /// and the clipboard happen off screen.
    pub fn changes_screen(&self) -> bool {
        self.is_action() && !self.is_file_change() && !matches!(self, Command::SetClipboard { .. })
    }

    /// `file.write`, `file.fetch`, `file.mkdir`, `file.rename`, `file.delete`.
    pub fn is_file_change(&self) -> bool {
        matches!(
            self,
            Command::WriteFile { .. }
                | Command::FetchFile { .. }
                | Command::MakeFolder { .. }
                | Command::RenameFile { .. }
                | Command::DeleteFile { .. }
        )
    }

    /// True for actions the owner approves under "Ask me before every
    /// action". Asking the owner is already a question to the owner.
    /// Appending the next chunk of a file Latch created moments ago is part
    /// of that same, already approved save.
    pub fn needs_owner_approval_when_strict(&self) -> bool {
        self.is_action()
            && !matches!(
                self,
                Command::AskOwner { .. } | Command::WriteFile { append: true, .. }
            )
    }

    /// The lowest protocol minor version a phone must speak to understand this
    /// command exactly (older phones would refuse it or ignore a field).
    pub fn min_minor_version(&self) -> u32 {
        match self {
            Command::WaitFor { .. } | Command::ScrollTo { .. } | Command::Pinch { .. } => 3,
            Command::TypeText { submit: true, .. } | Command::Tap { double: true, .. } => 3,
            Command::Swipe { hold_ms, .. } if *hold_ms > 0 => 3,
            Command::AskOwner { .. } => 5,
            Command::ListFiles { .. }
            | Command::PreviewFile { .. }
            | Command::ReadFile { .. }
            | Command::WriteFile { .. }
            | Command::MakeFolder { .. }
            | Command::RenameFile { .. }
            | Command::DeleteFile { .. }
            | Command::Share { .. } => 6,
            Command::FetchFile { .. }
            | Command::PushFile { .. }
            | Command::TransferStatus { .. }
            | Command::SetClipboard { .. } => 7,
            Command::ListActivity { .. } | Command::TaskDone { .. } => 8,
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
