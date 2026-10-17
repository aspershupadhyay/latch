// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Any bytes a gateway could receive from a phone: parsing and validating
//! never panic, and a message that parses survives a round trip unchanged.
#![no_main]

use latch_protocol::{DeviceToGateway, validate};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(message) = serde_json::from_slice::<DeviceToGateway>(data) else {
        return;
    };
    match &message {
        DeviceToGateway::Hello(hello) => {
            let _ = validate::hello(hello);
        }
        DeviceToGateway::ApprovalRequest(request) => {
            let _ = validate::approval_request(request);
        }
        DeviceToGateway::Signed { payload, sig } => {
            let _ = validate::signed(payload, sig);
        }
        DeviceToGateway::Result { outcome, .. } => {
            // Results are typed by the command they answer; any observation must validate safely.
            if let latch_protocol::Outcome::Ok { data } = outcome
                && let Ok(obs) = serde_json::from_value::<latch_protocol::Observation>(data.clone())
            {
                let _ = validate::observation(&obs, 400);
            }
        }
        DeviceToGateway::State { .. } | DeviceToGateway::Bye { .. } => {}
    }
    // Exact float parsing (the crate's `float_roundtrip` feature) keeps a
    // result's untyped `data` equal through a round trip too.
    let again = serde_json::to_vec(&message).expect("a parsed message serializes");
    let back: DeviceToGateway = serde_json::from_slice(&again).expect("and parses again");
    assert_eq!(back, message);
});
