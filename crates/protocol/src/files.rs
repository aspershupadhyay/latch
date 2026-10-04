// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Files, photos, and sharing (since 1.6, ADR-026).
//!
//! The phone addresses every file and folder by an opaque id it issued in an
//! earlier answer, never by a path, so nothing outside the owner's chosen
//! scopes can be named. Ids are valid for the session that issued them.

use std::collections::BTreeMap;

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

/// Since 1.7 (ADR-027): where a whole file travels, for `file.fetch` and
/// `file.push`. The phone streams the file to or from `url` itself, so its
/// size is not bound by command frames. What is stored there is always
/// encrypted with AES-256-CTR under `key_hex` and `iv_hex`, a key made for
/// this one transfer that never goes to the storage; the plain file's
/// SHA-256 travels separately, so any change on the way is caught.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FileLink {
    /// `https://` (or the gateway's own `http://` origin on a LAN). The
    /// phone only accepts its own gateway's origin and the storage hosts the
    /// protocol names (Vercel Blob).
    pub url: String,
    /// Extra request headers the storage needs: lower-case names that start
    /// with `x-`, or `content-type`.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<String, String>,
    /// 32-byte AES-256 key as 64 lowercase hex characters.
    pub key_hex: String,
    /// 16-byte initial counter block as 32 lowercase hex characters.
    pub iv_hex: String,
}

/// Since 1.7: whether a transfer is still moving.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum TransferState {
    Running,
    Done,
}

/// Since 1.7: answer to `file.fetch`, `file.push`, and `file.transfer`. A
/// transfer that failed answers with an error instead.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct FileTransfer {
    /// Opaque id for `file.transfer`, valid for this session.
    pub id: String,
    pub state: TransferState,
    /// Plain bytes moved so far.
    pub done_bytes: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub total_bytes: Option<u64>,
    /// When done: the saved file (`file.fetch`) or the copied one (`file.push`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub item: Option<FileItem>,
    /// When a `file.push` is done: SHA-256 of the plain file, 64 lowercase hex characters.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
}
