// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! `diagnose` (roadmap R13): one call that says whether each phone is ready
//! for an agent and, for every problem, what the owner does to fix it.
//!
//! The text is a pure function of [`DiagnosisInput`] so both gateways render
//! it byte for byte alike (`packages/schemas/v1/mcp/diagnose-*.{json,txt}`).
//! It holds no screen content: only ids, names, versions, switches, and times.

use latch_protocol::{CapabilityState, CapabilityStatus, SessionInfo};
use serde::Deserialize;
use std::fmt::Write;

/// Everything the diagnosis reads, taken from the gateway's own state.
#[derive(Debug, Clone, Deserialize)]
pub struct DiagnosisInput {
    pub gateway_protocol: String,
    pub now_ms: u64,
    /// Paired phones in the order to report them.
    pub phones: Vec<PhoneState>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PhoneState {
    pub id: String,
    pub name: String,
    pub model: String,
    /// Present while the phone is connected.
    pub live: Option<LiveState>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LiveState {
    pub protocol: String,
    pub capabilities: Vec<CapabilityState>,
    /// Expiry already on the gateway clock.
    pub session: SessionInfo,
    /// Since protocol 1.13: Android may stop the app in the background.
    #[serde(default)]
    pub battery_restricted: Option<bool>,
}

/// What an agent needs for ordinary screen work, and what each one lets it do.
const CORE: [(&str, &str); 5] = [
    ("ui.observe", "read the screen"),
    ("input.gesture", "tap, scroll, or swipe"),
    ("input.text", "type"),
    ("nav.global", "press back or home"),
    ("app.launch", "open apps"),
];

pub fn render_diagnosis(input: &DiagnosisInput) -> String {
    let mut out = format!(
        "Latch diagnosis (gateway protocol {}). Owner-facing fixes are marked \"Fix:\"; pass them on to the user.\n",
        cut(&input.gateway_protocol, 16)
    );
    if input.phones.is_empty() {
        out.push_str(
            "- No phone is paired with this gateway. Fix: the owner opens the Latch app (Settings → Your AI apps) and connects the phone to this gateway.\n\
             Verdict: not ready.\n",
        );
        return out;
    }
    let gateway_minor = latch_protocol::minor_version(&input.gateway_protocol).unwrap_or(0);
    let mut ready = 0;
    for phone in &input.phones {
        let label = format!(
            "{} \"{}\" ({})",
            phone.id,
            cut(&phone.name, 40),
            cut(&phone.model, 40)
        );
        let Some(live) = &phone.live else {
            let _ = writeln!(
                out,
                "- {label}: not connected. Fix: open Latch on the phone and check that it says it is connected; keep the phone online, and set Latch's battery use to Unrestricted so Android does not stop it."
            );
            continue;
        };
        let _ = writeln!(
            out,
            "- {label}: connected, app protocol {}.",
            cut(&live.protocol, 16)
        );
        let mut blocked = false;
        let status = |id: &str| {
            live.capabilities
                .iter()
                .find(|c| c.capability.id() == id)
                .map(|c| c.status)
                .unwrap_or(CapabilityStatus::Unsupported)
        };
        if live.session.paused {
            blocked = true;
            out.push_str("  - The owner paused the session; every command is refused. Fix: tap Resume in the Latch app.\n");
        }
        if live.session.expires_at_ms <= input.now_ms {
            blocked = true;
            out.push_str("  - The session has ended. Fix: the owner starts a new session in the Latch app.\n");
        } else {
            let minutes = (live.session.expires_at_ms - input.now_ms).div_ceil(60_000);
            let _ = writeln!(out, "  - Session: {minutes} min left.");
        }
        let missing: Vec<&str> = CORE
            .iter()
            .filter(|(id, _)| status(id) == CapabilityStatus::NeedsPermission)
            .map(|(id, _)| *id)
            .collect();
        if !missing.is_empty() {
            blocked = true;
            let _ = writeln!(
                out,
                "  - Switched on but Android has not allowed it: {}. Fix: Android Settings → Accessibility → Latch → On (on Android 13+, first allow restricted settings in Latch's App info).",
                missing.join(", ")
            );
        }
        let off: Vec<(&str, &str)> = CORE
            .iter()
            .copied()
            .filter(|(id, _)| {
                matches!(
                    status(id),
                    CapabilityStatus::Disabled | CapabilityStatus::Unsupported
                )
            })
            .collect();
        if !off.is_empty() {
            if off.iter().any(|(id, _)| *id == "ui.observe") {
                blocked = true;
            }
            let whats: Vec<&str> = off.iter().map(|(_, what)| *what).collect();
            let _ = writeln!(
                out,
                "  - Off: {}, so the AI cannot {}. Fix: the owner switches {} on in the Latch app's Access tab.",
                off.iter().map(|(id, _)| *id).collect::<Vec<_>>().join(", "),
                match whats.split_last() {
                    Some((last, [])) => (*last).to_owned(),
                    Some((last, rest)) => format!("{} or {last}", rest.join(", ")),
                    None => String::new(),
                },
                if off.len() == 1 { "it" } else { "them" }
            );
        }
        if latch_protocol::minor_version(&live.protocol).unwrap_or(0) < gateway_minor {
            let _ = writeln!(
                out,
                "  - The app is older than the gateway, so newer tools fail on this phone. Fix: update Latch on the phone (Settings → Check for updates)."
            );
        }
        if live.battery_restricted == Some(true) {
            out.push_str("  - Android's battery optimisation is on for Latch, so Android may stop it in the background and the phone drops off mid-task. Fix: Android Settings → Apps → Latch → Battery → Unrestricted.\n");
        }
        if live.session.approve_every_action {
            out.push_str("  - Note: the owner approves every action, so each step waits for a tap on the phone.\n");
        }
        if blocked {
            out.push_str("  Not ready until the fixes above are done.\n");
        } else {
            ready += 1;
            out.push_str("  Ready.\n");
        }
    }
    if ready == 0 {
        out.push_str("Verdict: not ready.\n");
    } else {
        let _ = writeln!(
            out,
            "Verdict: ready ({ready} phone{} usable).",
            if ready == 1 { "" } else { "s" }
        );
    }
    out
}

fn cut(s: &str, max: usize) -> String {
    let mut out: String = s.chars().take(max).collect();
    if s.chars().count() > max {
        out.push('…');
    }
    out
}
