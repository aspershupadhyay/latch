// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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
mod device_http;
mod device_ws;
pub mod devices;
mod http;
pub mod links;
pub mod mcp;
pub mod oauth;
pub mod pairing;
pub mod secret;
pub mod signing;
pub mod store;
pub mod transfers;

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
    /// Download and upload links for whole files (protocol 1.6).
    pub transfers: transfers::Transfers,
    /// Encrypted links for whole files of any size (protocol 1.7).
    pub links: links::Links,
    /// Pause after an action before observing the result.
    pub settle_ms: u64,
    /// How long one tool call follows a file transfer before answering with its progress.
    pub transfer_budget_ms: u64,
    /// OAuth sign-in requests, codes, and access tokens (roadmap R17).
    oauth: Mutex<oauth::OAuth>,
    /// Roadmap R18: tool calls each AI app may make per minute (0: no limit).
    pub calls_per_minute: u32,
    /// Tool calls in the current minute, per caller key.
    calls: Mutex<std::collections::HashMap<String, (u64, u32)>>,
}

impl AppState {
    pub fn new(config: Config, store: store::Store) -> Self {
        Self {
            config,
            store: Mutex::new(store),
            pairings: Mutex::new(pairing::Pairings::default()),
            devices: devices::Registry::default(),
            audit: audit::Audit::default(),
            transfers: transfers::Transfers::default(),
            links: links::Links::from_env(),
            settle_ms: 600,
            transfer_budget_ms: mcp::files::TRANSFER_TOOL_BUDGET_MS,
            oauth: Mutex::new(oauth::OAuth::default()),
            calls_per_minute: std::env::var("LATCH_CLIENT_CALLS_PER_MINUTE")
                .ok()
                .and_then(|v| v.trim().parse().ok())
                .unwrap_or(DEFAULT_CALLS_PER_MINUTE),
            calls: Mutex::new(std::collections::HashMap::new()),
        }
    }

    /// Where AI apps reach this gateway, for file links.
    pub fn base_url(&self) -> String {
        match &self.config.public_url {
            Some(url) => url.trim_end_matches('/').to_owned(),
            None => format!("http://{}", self.config.bind),
        }
    }

    pub fn store(&self) -> MutexGuard<'_, store::Store> {
        self.store.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Counts one tool call for `key`; false when it is over this minute's budget.
    pub fn count_call(&self, key: &str, now_ms: u64) -> bool {
        if self.calls_per_minute == 0 {
            return true;
        }
        let minute = now_ms / 60_000;
        let mut calls = self.calls.lock().unwrap_or_else(|e| e.into_inner());
        calls.retain(|_, (m, _)| *m == minute);
        let entry = calls.entry(key.to_owned()).or_insert((minute, 0));
        entry.1 += 1;
        entry.1 <= self.calls_per_minute
    }

    pub(crate) fn oauth(&self) -> MutexGuard<'_, oauth::OAuth> {
        self.oauth.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub(crate) fn pairings(&self) -> MutexGuard<'_, pairing::Pairings> {
        self.pairings.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The AI app making the current MCP request (roadmap R18), set for the
/// request's task by the HTTP layer so every command it runs is checked
/// against the owner's limits for that app.
#[derive(Debug, Clone, Default)]
pub struct Caller {
    /// The AI app's id, or `env` for `LATCH_MCP_TOKEN`; rate limits count per key.
    pub key: String,
    /// The only phone apps it may use; `None` for no limit beyond the phone's own.
    pub apps: Option<Vec<String>>,
}

/// Tool calls an AI app may make per minute unless `LATCH_CLIENT_CALLS_PER_MINUTE` says otherwise.
/// An agent working fast makes about one call a second; this leaves room for bursts.
pub const DEFAULT_CALLS_PER_MINUTE: u32 = 120;

tokio::task_local! {
    pub static CALLER: Caller;
}

/// The phone apps the current caller may use (`None`: no limit, or no caller, as in stdio mode).
pub fn caller_apps() -> Option<Vec<String>> {
    CALLER.try_with(|c| c.apps.clone()).ok().flatten()
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
