// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Encrypted file links (protocol 1.7, ADR-027): whole files move between the
//! AI's computer and the phone through this gateway without passing through
//! the AI's context or a command frame. The computer and the phone stream the
//! bytes to and from `/v1/blobs/<token>` themselves.
//!
//! What is stored is always AES-256-CTR ciphertext under a random key made for
//! that one transfer. The key lives only in this process's memory, never on
//! disk; the ciphertext lives in a private temporary folder until the link
//! expires (15 minutes) or the phone has saved it. The plain file's SHA-256
//! travels separately, so any change on the way is caught.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::{now_ms, secret};

/// How long a link works.
pub const LINK_TTL_MS: u64 = 15 * 60 * 1000;
/// Default largest file one link carries; `LATCH_MAX_TRANSFER_MB` changes it.
pub const DEFAULT_MAX_LINK_BYTES: u64 = 2 * 1024 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Computer to phone.
    Up,
    /// Phone to computer.
    Down,
}

/// One transfer. `key` and `iv` never leave memory except to the phone and the AI.
#[derive(Debug, Clone)]
pub struct Record {
    pub kind: Kind,
    pub key_hex: String,
    pub iv_hex: String,
    pub expires_ms: u64,
    /// Bytes on disk, once something was uploaded.
    pub size: Option<u64>,
    /// An upload was handed to a phone (each upload is saved once).
    pub used: bool,
    /// Phone side of a running transfer, for `transfer_status`.
    pub device: Option<String>,
    pub transfer: Option<String>,
    /// What the tool answers once the phone is done.
    pub name: Option<String>,
    pub sha256: Option<String>,
    pub place: Option<String>,
    pub asked_name: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum LinkError {
    Unknown,
    Used,
    TooLarge,
    Full,
    Io,
}

pub struct Links {
    dir: PathBuf,
    pub max_bytes: u64,
    /// All stored files together, so a busy AI cannot fill the disk.
    max_total_bytes: u64,
    records: Mutex<HashMap<String, Record>>,
}

impl Links {
    pub fn new(max_bytes: u64) -> Self {
        let dir = std::env::temp_dir().join(format!(
            "latch-links-{}",
            secret::hex(&secret::random_bytes::<8>())
        ));
        Self {
            dir,
            max_bytes,
            max_total_bytes: max_bytes.saturating_mul(4),
            records: Mutex::new(HashMap::new()),
        }
    }

    /// From `LATCH_MAX_TRANSFER_MB` (default 2048).
    pub fn from_env() -> Self {
        let mb = std::env::var("LATCH_MAX_TRANSFER_MB")
            .ok()
            .and_then(|v| v.trim().parse::<u64>().ok())
            .filter(|&mb| mb > 0);
        Self::new(mb.map_or(DEFAULT_MAX_LINK_BYTES, |mb| {
            (mb * 1024 * 1024).min(latch_protocol::validate::MAX_LINK_FILE_BYTES)
        }))
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Record>> {
        self.records.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn path(&self, token: &str) -> PathBuf {
        self.dir.join(format!("{token}.bin"))
    }

    fn valid(token: &str) -> bool {
        token.len() == 68
            && token.starts_with("ltr_")
            && latch_protocol::validate::is_hex(&token[4..], 64)
    }

    /// Forgets expired links and deletes their files.
    fn collect(&self) {
        let now = now_ms();
        let expired: Vec<String> = {
            let mut records = self.lock();
            let expired = records
                .iter()
                .filter(|(_, r)| r.expires_ms <= now)
                .map(|(t, _)| t.clone())
                .collect::<Vec<_>>();
            for token in &expired {
                records.remove(token);
            }
            expired
        };
        for token in expired {
            let _ = std::fs::remove_file(self.path(&token));
        }
    }

    pub fn create(&self, kind: Kind) -> (String, Record) {
        self.collect();
        let token = secret::new_token("ltr");
        let record = Record {
            kind,
            key_hex: secret::hex(&secret::random_bytes::<32>()),
            iv_hex: secret::hex(&secret::random_bytes::<16>()),
            expires_ms: now_ms() + LINK_TTL_MS,
            size: None,
            used: false,
            device: None,
            transfer: None,
            name: None,
            sha256: None,
            place: None,
            asked_name: None,
        };
        self.lock().insert(token.clone(), record.clone());
        (token, record)
    }

    pub fn record(&self, token: &str) -> Option<Record> {
        if !Self::valid(token) {
            return None;
        }
        self.lock()
            .get(token)
            .filter(|r| r.expires_ms > now_ms())
            .cloned()
    }

    pub fn save(&self, token: &str, record: Record) {
        if let Some(r) = self.lock().get_mut(token) {
            *r = record;
        }
    }

    pub fn by_transfer(&self, device: &str, transfer: &str) -> Option<(String, Record)> {
        let now = now_ms();
        self.lock()
            .iter()
            .find(|(_, r)| {
                r.expires_ms > now
                    && r.device.as_deref() == Some(device)
                    && r.transfer.as_deref() == Some(transfer)
            })
            .map(|(t, r)| (t.clone(), r.clone()))
    }

    /// Deletes a link's file now (the phone has the bytes).
    pub fn discard(&self, token: &str) {
        let _ = std::fs::remove_file(self.path(token));
        if let Some(r) = self.lock().get_mut(token) {
            r.size = None;
        }
    }

    /// Checks that `token` may receive `declared` bytes; returns the file to write.
    pub fn begin_upload(&self, token: &str, declared: Option<u64>) -> Result<PathBuf, LinkError> {
        self.collect();
        let record = self.record(token).ok_or(LinkError::Unknown)?;
        if record.size.is_some() || record.used {
            return Err(LinkError::Used);
        }
        if declared.is_some_and(|n| n > self.max_bytes) {
            return Err(LinkError::TooLarge);
        }
        let used: u64 = self.lock().values().filter_map(|r| r.size).sum();
        if used + declared.unwrap_or(0) > self.max_total_bytes {
            return Err(LinkError::Full);
        }
        std::fs::create_dir_all(&self.dir).map_err(|_| LinkError::Io)?;
        restrict(&self.dir);
        Ok(self.path(token))
    }

    /// Records a finished upload of `size` bytes.
    pub fn finish_upload(&self, token: &str, size: u64) -> Result<(), LinkError> {
        let mut records = self.lock();
        let record = records.get_mut(token).ok_or(LinkError::Unknown)?;
        if record.size.is_some() {
            return Err(LinkError::Used);
        }
        record.size = Some(size);
        Ok(())
    }

    /// The stored file of a link, if something was uploaded.
    pub fn stored(&self, token: &str) -> Option<(PathBuf, u64)> {
        let size = self.record(token)?.size?;
        Some((self.path(token), size))
    }
}

/// Only this user may read the folder of stored files.
#[cfg(unix)]
fn restrict(dir: &std::path::Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
}

#[cfg(not(unix))]
fn restrict(_dir: &std::path::Path) {}

impl Drop for Links {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uploads_are_filled_once_within_limits() {
        let links = Links::new(10);
        let (token, record) = links.create(Kind::Up);
        assert_eq!(record.key_hex.len(), 64);
        assert_eq!(record.iv_hex.len(), 32);
        assert_eq!(
            links.begin_upload(&token, Some(11)),
            Err(LinkError::TooLarge)
        );
        assert!(links.begin_upload(&token, Some(3)).is_ok());
        links.finish_upload(&token, 3).expect("finish");
        assert_eq!(links.begin_upload(&token, Some(3)), Err(LinkError::Used));
        assert_eq!(
            links.begin_upload("ltr_nope", None),
            Err(LinkError::Unknown)
        );
        assert!(links.record("ltr_nope").is_none());
    }
}
