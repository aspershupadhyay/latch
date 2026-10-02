//! Latch gateway.
//!
//! One small process with three faces:
//!
//! * `POST /mcp` — MCP Streamable HTTP for AI clients (bearer `LATCH_MCP_TOKEN`).
//! * `GET /v1/device` — WebSocket that phones dial out to (per-device bearer token).
//! * `/` and `/v1/admin/*` — owner console for pairing and revocation (bearer `LATCH_ADMIN_TOKEN`).
//!
//! Screen data only ever lives in memory for the duration of a request and in
//! a single "latest observation" slot per device (without screenshots).

pub mod audit;
pub mod config;
mod device_ws;
pub mod devices;
mod http;
pub mod mcp;
pub mod pairing;
pub mod secret;
pub mod store;

use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

pub use config::Config;
pub use http::router;

pub struct AppState {
    pub config: Config,
    store: Mutex<store::Store>,
    pub pairings: Mutex<pairing::Pairings>,
    pub devices: devices::Registry,
    pub audit: audit::Audit,
    /// Pause after an action before observing the result.
    pub settle_ms: u64,
}

impl AppState {
    pub fn new(config: Config, store: store::Store) -> Self {
        Self {
            config,
            store: Mutex::new(store),
            pairings: Mutex::new(pairing::Pairings::default()),
            devices: devices::Registry::default(),
            audit: audit::Audit::default(),
            settle_ms: 600,
        }
    }

    pub fn store(&self) -> MutexGuard<'_, store::Store> {
        self.store.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub(crate) fn pairings(&self) -> MutexGuard<'_, pairing::Pairings> {
        self.pairings.lock().unwrap_or_else(|e| e.into_inner())
    }
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
