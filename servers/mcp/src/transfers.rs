// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Short-lived links that move whole files between the AI's computer and the
//! phone without passing the bytes through the AI's context (ADR-026).
//!
//! `get_file_link` copies a phone file here and hands out a download link;
//! `upload_link` hands out an upload link whose bytes `write_file` then
//! saves on the phone. Links are 256-bit secrets, live 15 minutes, and are
//! kept in memory only; contents are never logged.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::{now_ms, secret};

/// How long a link works.
pub const LINK_TTL_MS: u64 = 15 * 60 * 1000;
/// Largest file one link carries.
pub const MAX_TRANSFER_BYTES: usize = 64 * 1024 * 1024;
/// All links together, so a busy AI cannot fill the gateway's memory.
const MAX_TOTAL_BYTES: usize = 256 * 1024 * 1024;

pub struct Download {
    pub name: String,
    pub mime: String,
    pub bytes: Vec<u8>,
}

enum Slot {
    Download(Download),
    /// Waiting for, or holding, an upload.
    Upload(Option<Vec<u8>>),
}

impl Slot {
    fn len(&self) -> usize {
        match self {
            Slot::Download(d) => d.bytes.len(),
            Slot::Upload(b) => b.as_ref().map_or(0, Vec::len),
        }
    }
}

#[derive(Default)]
pub struct Transfers {
    slots: Mutex<HashMap<String, (Slot, u64)>>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum TransferError {
    TooLarge,
    Full,
    Unknown,
    AlreadyUploaded,
}

impl Transfers {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, (Slot, u64)>> {
        let mut slots = self.slots.lock().unwrap_or_else(|e| e.into_inner());
        let now = now_ms();
        slots.retain(|_, (_, expires)| *expires > now);
        slots
    }

    fn room_for(slots: &HashMap<String, (Slot, u64)>, bytes: usize) -> Result<(), TransferError> {
        if bytes > MAX_TRANSFER_BYTES {
            return Err(TransferError::TooLarge);
        }
        let used: usize = slots.values().map(|(s, _)| s.len()).sum();
        if used + bytes > MAX_TOTAL_BYTES {
            return Err(TransferError::Full);
        }
        Ok(())
    }

    /// Stores a phone file and returns its secret token.
    pub fn put_download(&self, download: Download) -> Result<String, TransferError> {
        let mut slots = self.lock();
        Self::room_for(&slots, download.bytes.len())?;
        let token = secret::new_token("ldl");
        slots.insert(
            token.clone(),
            (Slot::Download(download), now_ms() + LINK_TTL_MS),
        );
        Ok(token)
    }

    /// Name, type, and bytes of a download link, while it lives.
    pub fn download(&self, token: &str) -> Option<(String, String, Vec<u8>)> {
        match self.lock().get(token) {
            Some((Slot::Download(d), _)) => Some((d.name.clone(), d.mime.clone(), d.bytes.clone())),
            _ => None,
        }
    }

    /// An empty upload slot; its token is the upload id.
    pub fn new_upload(&self) -> String {
        let token = secret::new_token("lul");
        self.lock()
            .insert(token.clone(), (Slot::Upload(None), now_ms() + LINK_TTL_MS));
        token
    }

    /// Fills an upload slot once.
    pub fn put_upload(&self, token: &str, bytes: Vec<u8>) -> Result<(), TransferError> {
        let mut slots = self.lock();
        Self::room_for(&slots, bytes.len())?;
        match slots.get_mut(token) {
            Some((Slot::Upload(slot @ None), _)) => {
                *slot = Some(bytes);
                Ok(())
            }
            Some((Slot::Upload(Some(_)), _)) => Err(TransferError::AlreadyUploaded),
            _ => Err(TransferError::Unknown),
        }
    }

    /// Takes an uploaded file out (each upload is saved once).
    pub fn take_upload(&self, token: &str) -> Result<Vec<u8>, TransferError> {
        let mut slots = self.lock();
        match slots.get(token) {
            Some((Slot::Upload(Some(_)), _)) => match slots.remove(token) {
                Some((Slot::Upload(Some(bytes)), _)) => Ok(bytes),
                _ => Err(TransferError::Unknown),
            },
            Some((Slot::Upload(None), _)) => Err(TransferError::Unknown),
            _ => Err(TransferError::Unknown),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uploads_are_filled_once_and_taken_once() {
        let t = Transfers::default();
        let id = t.new_upload();
        assert_eq!(t.take_upload(&id), Err(TransferError::Unknown));
        t.put_upload(&id, b"abc".to_vec()).expect("put");
        assert_eq!(
            t.put_upload(&id, b"x".to_vec()),
            Err(TransferError::AlreadyUploaded)
        );
        assert_eq!(t.take_upload(&id).expect("take"), b"abc");
        assert_eq!(t.take_upload(&id), Err(TransferError::Unknown));
        assert_eq!(
            t.put_upload("lul_nope", vec![]),
            Err(TransferError::Unknown)
        );
    }

    #[test]
    fn downloads_are_readable_until_they_expire_and_size_is_capped() {
        let t = Transfers::default();
        let token = t
            .put_download(Download {
                name: "a.txt".into(),
                mime: "text/plain".into(),
                bytes: b"hi".to_vec(),
            })
            .expect("put");
        assert_eq!(t.download(&token).expect("get").2, b"hi");
        assert!(t.download("ldl_nope").is_none());
        let big = Download {
            name: "big".into(),
            mime: "application/octet-stream".into(),
            bytes: vec![0; MAX_TRANSFER_BYTES + 1],
        };
        assert_eq!(t.put_download(big), Err(TransferError::TooLarge));
    }
}
