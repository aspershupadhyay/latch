// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Runs the shared policy conformance table (`packages/schemas/v1/policy/cases.json`).
//! The TypeScript gateway runs the same table; both must agree exactly.

use latch_policy::{Decision, DeviceContext, evaluate};
use latch_protocol::{
    Capability, CapabilityState, CapabilityStatus, Command, Observation, SessionInfo,
};
use serde_json::Value;

fn capability_status(name: &str) -> CapabilityStatus {
    serde_json::from_value(Value::String(name.into())).expect("capability status")
}

fn capabilities(spec: &Value) -> Vec<CapabilityState> {
    Capability::ALL
        .iter()
        .map(|&capability| {
            let status = match spec {
                Value::String(s) if s == "all" => CapabilityStatus::Enabled,
                // A list names the enabled capabilities; everything else is disabled.
                Value::Array(enabled) => {
                    if enabled.iter().any(|e| e == capability.id()) {
                        CapabilityStatus::Enabled
                    } else {
                        CapabilityStatus::Disabled
                    }
                }
                // An object overrides some statuses; everything else is enabled.
                Value::Object(overrides) => overrides
                    .get(capability.id())
                    .and_then(Value::as_str)
                    .map_or(CapabilityStatus::Enabled, capability_status),
                other => panic!("bad capabilities spec {other}"),
            };
            CapabilityState { capability, status }
        })
        .collect()
}

#[test]
fn shared_policy_cases() {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../packages/schemas/v1/policy/cases.json"
    );
    let doc: Value =
        serde_json::from_str(&std::fs::read_to_string(path).expect("cases file")).expect("json");
    let now = doc["now_ms"].as_u64().expect("now_ms");
    let observation: Observation =
        serde_json::from_value(doc["observation"].clone()).expect("observation");
    let defaults = &doc["defaults"];
    let cases = doc["cases"].as_array().expect("cases");
    assert!(cases.len() >= 30);

    for case in cases {
        let name = case["name"].as_str().expect("name");
        let field = |k: &str| case.get(k).unwrap_or(&defaults[k]).clone();
        let command: Command = serde_json::from_value(case["command"].clone())
            .unwrap_or_else(|e| panic!("{name}: {e}"));
        let caps = capabilities(&field("capabilities"));
        let s = field("session");
        let session = SessionInfo {
            expires_at_ms: now + s["expires_in_ms"].as_u64().expect("expires_in_ms"),
            approve_every_action: s["approve_every_action"].as_bool().expect("approve"),
            paused: s["paused"].as_bool().expect("paused"),
            remote_approvals: false,
        };
        let age = field("observation_age_ms");
        let latest = age.as_u64().map(|a| (&observation, now - a));
        let decision = evaluate(
            &command,
            &DeviceContext {
                capabilities: &caps,
                session,
                latest_observation: latest,
                now_ms: now,
            },
        );

        let expect = &case["expect"];
        let risk = |r: latch_protocol::RiskLevel| serde_json::to_value(r).expect("risk");
        match (expect["decision"].as_str().expect("decision"), &decision) {
            ("allow", Decision::Allow { risk: r }) => {
                assert_eq!(expect["risk"], risk(*r), "{name}")
            }
            ("confirm", Decision::Confirm(c)) => {
                assert_eq!(expect["risk"], risk(c.risk), "{name}");
                if let Some(title) = expect.get("title") {
                    assert_eq!(title, &Value::String(c.title.clone()), "{name}");
                }
                if let Some(remember) = expect.get("remember") {
                    assert_eq!(remember, &serde_json::json!(c.remember), "{name}");
                }
            }
            ("deny", Decision::Deny(e)) => assert_eq!(
                expect["code"],
                Value::String(e.code.as_str().into()),
                "{name}"
            ),
            (want, got) => panic!("{name}: expected {want}, got {got:?}"),
        }
    }
}
