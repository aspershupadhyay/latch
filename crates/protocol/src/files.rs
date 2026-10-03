//! Files, photos, and sharing (since 1.6, ADR-026).
//!
//! The phone addresses every file and folder by an opaque id it issued in an
//! earlier answer, never by a path, so nothing outside the owner's chosen
//! scopes can be named. Ids are valid for the session that issued them.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::Screenshot;

/// Where on the phone a file lives.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum FileLocation {
    /// Photos and videos the owner allowed (Android's photo permission).
    /// New files saved here appear in the gallery (`Pictures/<subfolder>`).
    Photos,
    /// The phone's Download folder (`Download/<subfolder>`). Without extra
    /// permissions the phone lists only files Latch saved there.
    Downloads,
    /// The one folder the owner picked in Latch, and its subfolders.
    Folder,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum FileKind {
    Folder,
    Image,
    Video,
    File,
}

/// One file or folder, as the phone describes it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FileItem {
    /// Opaque id for later commands in this session.
    pub id: String,
    /// File name as stored on the phone (untrusted text).
    pub name: String,
    pub kind: FileKind,
    pub location: FileLocation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mime: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
    /// Unix ms of the last change, when known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modified_ms: Option<u64>,
}

/// Answer to `file.list`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FileList {
    pub location: FileLocation,
    /// Display name of the folder listed (the picked folder or a subfolder).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub folder_name: Option<String>,
    pub items: Vec<FileItem>,
    /// How many items match in total.
    pub total: u32,
    /// Pass as `offset` to get the next page; absent on the last page.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_offset: Option<u32>,
}

/// Answer to `file.preview`: something the AI can look at directly.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FilePreview {
    pub item: FileItem,
    /// Text files, decoded as UTF-8, up to [`crate::validate::MAX_PREVIEW_TEXT_BYTES`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub text_truncated: bool,
    /// Images (and video thumbnails), downscaled.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<Screenshot>,
}

/// Answer to `file.read`: raw bytes, one chunk at a time.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FileChunk {
    pub item: FileItem,
    pub offset: u64,
    pub data_base64: String,
    /// True when this chunk ends the file.
    pub eof: bool,
}
