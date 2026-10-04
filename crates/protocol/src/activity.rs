// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

/// Since 1.8: what an activity log entry is about.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ActivityKind {
    /// A session started, stopped, paused, or ended.
    Session,
    /// The phone connected to or lost the gateway.
    Connection,
    /// The AI read the screen or the app list.
    Screen,
    /// The AI acted: tapped, typed, swiped, opened an app, copied text.
    Action,
    /// The owner let the AI use an app, or said not now.
    App,
    /// The owner was asked to approve something, and the answer.
    Approval,
    /// Something was refused: by the owner, by a rule, or because it failed.
    Refusal,
    /// A file was listed, read, saved, renamed, deleted, or moved by link.
    File,
    /// A folder was listed or created.
    Folder,
    /// The AI said a task was done.
    Task,
}

/// Since 1.8: one line of the phone's activity log. Holds no screen content
/// or typed text: only what happened, in which app, and when.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ActivityEntry {
    pub at_ms: u64,
    pub kind: ActivityKind,
    pub summary: String,
    /// The app it happened in, as the launcher names it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app: Option<String>,
}

/// Since 1.8: answer to `activity.list`, newest first.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ActivityList {
    pub entries: Vec<ActivityEntry>,
    /// Entries that matched before `limit` was applied.
    pub total: u32,
}
