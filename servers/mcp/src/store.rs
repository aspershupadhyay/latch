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
}

/// An MCP client credential created in the owner console.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientRecord {
    pub id: String,
    pub name: String,
    pub token_sha256: String,
    pub created_at_ms: u64,
    #[serde(default)]
    pub last_used_ms: Option<u64>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct StoreFile {
    version: u32,
    devices: Vec<DeviceRecord>,
    #[serde(default)]
    clients: Vec<ClientRecord>,
}

pub struct Store {
    path: PathBuf,
    devices: Vec<DeviceRecord>,
    clients: Vec<ClientRecord>,
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
        })
    }

    /// In-memory store for tests.
    pub fn ephemeral() -> Self {
        Self {
            path: PathBuf::new(),
            devices: Vec::new(),
            clients: Vec::new(),
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

    pub fn remove_client(&mut self, id: &str) -> io::Result<bool> {
        let before = self.clients.len();
        self.clients.retain(|c| c.id != id);
        if self.clients.len() == before {
            return Ok(false);
        }
        self.save()?;
        Ok(true)
    }

    /// Updates the last-seen time in memory; persisted on the next write.
    pub fn touch(&mut self, id: &str, now_ms: u64) {
        if let Some(d) = self.devices.iter_mut().find(|d| d.id == id) {
            d.last_seen_ms = Some(now_ms);
        }
    }
}
