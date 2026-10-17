// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Any bytes a phone could receive: parsing and validating never panic, and
//! a message that parses survives a round trip unchanged.
#![no_main]

use latch_protocol::{GatewayToDevice, validate};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(message) = serde_json::from_slice::<GatewayToDevice>(data) else {
        return;
    };
    if let GatewayToDevice::Command(envelope) = &message {
        let _ = validate::envelope(envelope);
    }
    let again = serde_json::to_vec(&message).expect("a parsed message serializes");
    let back: GatewayToDevice = serde_json::from_slice(&again).expect("and parses again");
    assert_eq!(back, message);
});
