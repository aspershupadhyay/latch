// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The policy engine on any command and any switches: it never panics, it
//! decides the same way twice, and it never lets through a command that is
//! invalid, one whose capability the owner switched off, or anything while
//! the owner paused the session.
#![no_main]

use latch_policy::{Decision, DeviceContext, evaluate};
use latch_protocol::{Capability, CapabilityState, CapabilityStatus, Command, SessionInfo, validate};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let [switches_lo, switches_hi, switches_top, flags, json @ ..] = data else {
        return;
    };
    let Ok(command) = serde_json::from_slice::<Command>(json) else {
        return;
    };
    let switches = u32::from(*switches_lo) | u32::from(*switches_hi) << 8 | u32::from(*switches_top) << 16;
    let capabilities: Vec<CapabilityState> = Capability::ALL
        .iter()
        .enumerate()
        .map(|(i, &capability)| CapabilityState {
            capability,
            status: if switches >> (i % 24) & 1 == 1 { CapabilityStatus::Enabled } else { CapabilityStatus::Disabled },
        })
        .collect();
    let session = SessionInfo {
        expires_at_ms: if flags & 1 == 1 { 2_000_000 } else { 500 },
        approve_every_action: flags & 2 == 2,
        paused: flags & 4 == 4,
        remote_approvals: flags & 8 == 8,
        files: None,
    };
    let ctx = DeviceContext {
        capabilities: &capabilities,
        session,
        latest_observation: None,
        now_ms: 1_000_000,
        allowed_apps: None,
    };
    let decision = evaluate(&command, &ctx);
    assert_eq!(format!("{decision:?}"), format!("{:?}", evaluate(&command, &ctx)), "deterministic");
    let lets_through = !matches!(decision, Decision::Deny(_));
    if lets_through {
        assert!(validate::command(&command).is_ok(), "an invalid command got through");
        assert!(!session.paused, "a command got through a paused session");
        for needed in command.required_capabilities() {
            let on = capabilities.iter().any(|c| c.capability == needed && c.status == CapabilityStatus::Enabled);
            assert!(on, "{} got through with {needed:?} off", command.name());
        }
    }
});
