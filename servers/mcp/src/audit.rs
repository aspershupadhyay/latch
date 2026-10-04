// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! In-memory, redacted audit trail.
//!
//! Events record what was asked, what policy decided, and how it ended. They
//! never contain screen text, typed text, coordinates, or screenshots.

use std::collections::VecDeque;
use std::sync::Mutex;

use serde::Serialize;

const CAPACITY: usize = 1_000;

#[derive(Debug, Clone, Serialize)]
pub struct AuditEvent {
    pub at_ms: u64,
    pub device_id: String,
    /// Wire command name, e.g. `input.tap`.
    pub command: &'static str,
    /// `allow`, `confirm`, or `deny`.
    pub decision: &'static str,
    /// `ok` or an error code.
    pub outcome: &'static str,
    pub latency_ms: u64,
}

#[derive(Default)]
pub struct Audit {
    events: Mutex<VecDeque<AuditEvent>>,
}

impl Audit {
    pub fn record(&self, event: AuditEvent) {
        let mut events = self.events.lock().unwrap_or_else(|e| e.into_inner());
        if events.len() == CAPACITY {
            events.pop_front();
        }
        events.push_back(event);
    }

    /// Most recent first.
    pub fn recent(&self, limit: usize) -> Vec<AuditEvent> {
        let events = self.events.lock().unwrap_or_else(|e| e.into_inner());
        events.iter().rev().take(limit).cloned().collect()
    }
}
