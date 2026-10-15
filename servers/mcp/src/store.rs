// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Durable list of paired devices.
//!
//! The only state the gateway persists. Tokens are stored as SHA-256 hashes;
//! nothing about screens, actions, or apps is ever written to disk.

use std::io;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::secret;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceRecord {
    pub id: String,
    /// Label chosen by the gateway owner when creating the pairing code.
    pub name: String,
    pub model: String,
    pub platform: String,
    pub token_sha256: String,
    pub paired_at_ms: u64,
    #[serde(default)]
    pub last_seen_ms: Option<u64>,
    /// Since protocol 1.12 (ADR-037): the phone's device key (P-256 SPKI, base64),
    /// learned from its first signed hello. From then on its messages must be signed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_key: Option<String>,
}

/// An AI app the owner allowed: a key made in the owner console, or an app
/// that signed in with OAuth and was approved on the phone (no key of its own).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientRecord {
    pub id: String,
    pub name: String,
    /// Empty for OAuth apps, which hold tokens instead.
    pub token_sha256: String,
    pub created_at_ms: u64,
    #[serde(default)]
    pub last_used_ms: Option<u64>,
    /// `oauth` for an app that signed in with the MCP URL; absent for a key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub oauth_client_id: Option<String>,
    /// Roadmap R18: the only phone apps (package names) this AI app may use.
    /// `None`: no limit beyond the phone's own app switches.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apps: Option<Vec<String>>,
}

/// An app that registered for OAuth (RFC 7591). Registration alone grants nothing.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OAuthClient {
    pub client_id: String,
    pub client_name: String,
    pub redirect_uris: Vec<String>,
    pub created_at_ms: u64,
}

/// A live OAuth refresh token, stored under its SHA-256.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RefreshRecord {
    pub client_id: String,
    /// The approved app ([`ClientRecord::id`]); revoking the app ends the token.
    pub app_id: String,
    pub expires_at_ms: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct StoreFile {
    version: u32,
    devices: Vec<DeviceRecord>,
    #[serde(default)]
    clients: Vec<ClientRecord>,
    /// Saved skills (ADR-031), by name.
    #[serde(default)]
    skills: std::collections::BTreeMap<String, serde_json::Value>,
    /// OAuth registrations and refresh tokens (roadmap R17), so a restart does not sign apps out.
    #[serde(default)]
    oauth_clients: Vec<OAuthClient>,
    #[serde(default)]
    oauth_refresh: std::collections::BTreeMap<String, RefreshRecord>,
}

pub struct Store {
    path: PathBuf,
    devices: Vec<DeviceRecord>,
    clients: Vec<ClientRecord>,
    skills: std::collections::BTreeMap<String, serde_json::Value>,
    oauth_clients: Vec<OAuthClient>,
    oauth_refresh: std::collections::BTreeMap<String, RefreshRecord>,
}

impl Store {
    pub fn open(data_dir: &Path) -> io::Result<Self> {
        std::fs::create_dir_all(data_dir)?;
        let path = data_dir.join("devices.json");
        let file = match std::fs::read_to_string(&path) {
            Ok(text) => serde_json::from_str::<StoreFile>(&text).map_err(|e| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("{} is corrupt: {e}", path.display()),
                )
            })?,
            Err(e) if e.kind() == io::ErrorKind::NotFound => StoreFile::default(),
            Err(e) => return Err(e),
        };
        Ok(Self {
            path,
            devices: file.devices,
            clients: file.clients,
            skills: file.skills,
            oauth_clients: file.oauth_clients,
            oauth_refresh: file.oauth_refresh,
        })
    }

    /// In-memory store for tests.
    pub fn ephemeral() -> Self {
        Self {
            path: PathBuf::new(),
            devices: Vec::new(),
            clients: Vec::new(),
            skills: std::collections::BTreeMap::new(),
            oauth_clients: Vec::new(),
            oauth_refresh: std::collections::BTreeMap::new(),
        }
    }

    fn save(&self) -> io::Result<()> {
        if self.path.as_os_str().is_empty() {
            return Ok(());
        }
        let text = serde_json::to_string_pretty(&StoreFile {
            version: 2,
            devices: self.devices.clone(),
            clients: self.clients.clone(),
            skills: self.skills.clone(),
            oauth_clients: self.oauth_clients.clone(),
            oauth_refresh: self.oauth_refresh.clone(),
        })
        .map_err(io::Error::other)?;
        let tmp = self.path.with_extension("json.tmp");
        std::fs::write(&tmp, text)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
        }
        std::fs::rename(&tmp, &self.path)
    }

    /// Saved skills, sorted by name.
    pub fn skills(&self) -> Vec<serde_json::Value> {
        self.skills.values().cloned().collect()
    }

    pub fn skill(&self, name: &str) -> Option<serde_json::Value> {
        self.skills.get(name).cloned()
    }

    /// Saves (or replaces) a validated skill under its name.
    pub fn put_skill(&mut self, skill: serde_json::Value) -> io::Result<()> {
        let name = skill["name"].as_str().unwrap_or_default().to_owned();
        self.skills.insert(name, skill);
        self.save()
    }

    pub fn delete_skill(&mut self, name: &str) -> io::Result<bool> {
        let removed = self.skills.remove(name).is_some();
        if removed {
            self.save()?;
        }
        Ok(removed)
    }

    pub fn devices(&self) -> &[DeviceRecord] {
        &self.devices
    }

    pub fn get(&self, id: &str) -> Option<&DeviceRecord> {
        self.devices.iter().find(|d| d.id == id)
    }

    /// Finds the device a bearer token belongs to. Checks every record so the
    /// time taken does not reveal which, if any, matched.
    pub fn authenticate(&self, token: &str) -> Option<&DeviceRecord> {
        let mut found = None;
        for device in &self.devices {
            if secret::token_matches_hash(token, &device.token_sha256) {
                found = Some(device);
            }
        }
        found
    }

    pub fn insert(&mut self, record: DeviceRecord) -> io::Result<()> {
        self.devices.push(record);
        self.save()
    }

    pub fn remove(&mut self, id: &str) -> io::Result<bool> {
        let before = self.devices.len();
        self.devices.retain(|d| d.id != id);
        if self.devices.len() == before {
            return Ok(false);
        }
        self.save()?;
        Ok(true)
    }

    pub fn clients(&self) -> &[ClientRecord] {
        &self.clients
    }

    /// Finds the MCP client a bearer token belongs to, in constant time per record.
    pub fn authenticate_client(&mut self, token: &str, now_ms: u64) -> Option<&ClientRecord> {
        let mut found = None;
        for (i, client) in self.clients.iter().enumerate() {
            if secret::token_matches_hash(token, &client.token_sha256) {
                found = Some(i);
            }
        }
        let i = found?;
        self.clients[i].last_used_ms = Some(now_ms);
        Some(&self.clients[i])
    }

    pub fn insert_client(&mut self, record: ClientRecord) -> io::Result<()> {
        self.clients.push(record);
        self.save()
    }

    /// Revokes an AI app; an OAuth app's refresh tokens go with it.
    pub fn remove_client(&mut self, id: &str) -> io::Result<bool> {
        let before = self.clients.len();
        self.clients.retain(|c| c.id != id);
        if self.clients.len() == before {
            return Ok(false);
        }
        self.oauth_refresh.retain(|_, r| r.app_id != id);
        self.save()?;
        Ok(true)
    }

    /// Marks an approved app as used; false when the owner revoked it.
    pub fn use_client(&mut self, id: &str, now_ms: u64) -> bool {
        match self.clients.iter_mut().find(|c| c.id == id) {
            Some(c) => {
                c.last_used_ms = Some(now_ms);
                true
            }
            None => false,
        }
    }

    /// Sets (or with `None` clears) an AI app's limit; false when there is no such app.
    pub fn set_client_apps(&mut self, id: &str, apps: Option<Vec<String>>) -> io::Result<bool> {
        let Some(c) = self.clients.iter_mut().find(|c| c.id == id) else {
            return Ok(false);
        };
        c.apps = apps;
        self.save()?;
        Ok(true)
    }

    pub fn client(&self, id: &str) -> Option<&ClientRecord> {
        self.clients.iter().find(|c| c.id == id)
    }

    pub fn client_exists(&self, id: &str) -> bool {
        self.clients.iter().any(|c| c.id == id)
    }

    // ---- OAuth (roadmap R17) ----

    pub fn oauth_client(&self, client_id: &str) -> Option<OAuthClient> {
        self.oauth_clients
            .iter()
            .find(|c| c.client_id == client_id)
            .cloned()
    }

    /// Registers an app, making room by dropping the oldest registrations
    /// beyond `max`: registration is open to anyone, so a full list must not
    /// lock new apps out. Approved apps keep their tokens.
    pub fn register_oauth_client(&mut self, client: OAuthClient, max: usize) -> io::Result<()> {
        self.oauth_clients.sort_by_key(|c| c.created_at_ms);
        let excess = (self.oauth_clients.len() + 1).saturating_sub(max);
        self.oauth_clients
            .drain(..excess.min(self.oauth_clients.len()));
        self.oauth_clients.push(client);
        self.save()
    }

    pub fn put_refresh(&mut self, sha256: String, record: RefreshRecord) -> io::Result<()> {
        self.oauth_refresh.insert(sha256, record);
        self.save()
    }

    /// Takes a refresh token out (single use), if it is live.
    pub fn take_refresh(&mut self, sha256: &str, now_ms: u64) -> io::Result<Option<RefreshRecord>> {
        let Some(record) = self.oauth_refresh.remove(sha256) else {
            return Ok(None);
        };
        self.oauth_refresh.retain(|_, r| r.expires_at_ms > now_ms);
        self.save()?;
        Ok((record.expires_at_ms > now_ms).then_some(record))
    }

    /// Remembers a phone's device key the first time it shows one; never replaces one.
    pub fn set_device_key(&mut self, id: &str, key: String) -> io::Result<()> {
        if let Some(d) = self
            .devices
            .iter_mut()
            .find(|d| d.id == id && d.device_key.is_none())
        {
            d.device_key = Some(key);
            self.save()?;
        }
        Ok(())
    }

    /// Updates the last-seen time in memory; persisted on the next write.
    pub fn touch(&mut self, id: &str, now_ms: u64) {
        if let Some(d) = self.devices.iter_mut().find(|d| d.id == id) {
            d.last_seen_ms = Some(now_ms);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(secret::new_id("latch-store-test"));
        std::fs::create_dir_all(&dir).expect("temp dir");
        dir
    }

    fn app(id: &str) -> ClientRecord {
        ClientRecord {
            id: id.into(),
            name: "SDK test app".into(),
            token_sha256: String::new(),
            created_at_ms: 1,
            last_used_ms: None,
            kind: Some("oauth".into()),
            oauth_client_id: Some("lc_1".into()),
            apps: None,
        }
    }

    fn refresh(app_id: &str, expires_at_ms: u64) -> RefreshRecord {
        RefreshRecord {
            client_id: "lc_1".into(),
            app_id: app_id.into(),
            expires_at_ms,
        }
    }

    #[test]
    fn oauth_registrations_and_refresh_tokens_survive_a_restart() {
        let dir = temp_dir();
        {
            let mut store = Store::open(&dir).expect("open");
            store.insert_client(app("m_1")).expect("app");
            let client = OAuthClient {
                client_id: "lc_1".into(),
                client_name: "SDK test app".into(),
                redirect_uris: vec!["http://127.0.0.1:1/cb".into()],
                created_at_ms: 1,
            };
            store.register_oauth_client(client, 200).expect("register");
            store
                .put_refresh("h1".into(), refresh("m_1", 100))
                .expect("refresh");
        }
        let mut store = Store::open(&dir).expect("reopen");
        assert!(store.oauth_client("lc_1").is_some());
        // Single use, and an expired token is never handed back.
        assert!(store.take_refresh("h1", 50).expect("take").is_some());
        assert!(store.take_refresh("h1", 50).expect("take").is_none());
        store
            .put_refresh("h2".into(), refresh("m_1", 100))
            .expect("refresh");
        assert!(store.take_refresh("h2", 100).expect("take").is_none());
        // Revoking the app removes its refresh tokens.
        store
            .put_refresh("h3".into(), refresh("m_1", 100))
            .expect("refresh");
        assert!(store.remove_client("m_1").expect("remove"));
        assert!(store.take_refresh("h3", 50).expect("take").is_none());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn a_full_registration_list_drops_the_oldest() {
        let mut store = Store::ephemeral();
        for i in 0..5u64 {
            let client = OAuthClient {
                client_id: format!("lc_{i}"),
                client_name: "x".into(),
                redirect_uris: vec![],
                created_at_ms: i,
            };
            store.register_oauth_client(client, 3).expect("register");
        }
        assert!(store.oauth_client("lc_1").is_none());
        assert!(store.oauth_client("lc_2").is_some() && store.oauth_client("lc_4").is_some());
    }
}
