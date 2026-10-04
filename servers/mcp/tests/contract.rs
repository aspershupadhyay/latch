// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Shared MCP contract files in `packages/schemas/v1/mcp`. Every gateway
//! implementation (this one and `servers/vercel`) must produce exactly these.
//! Regenerate after an intentional change with:
//!
//!     LATCH_UPDATE_SCHEMAS=1 cargo test -p latch-gateway --test contract

use std::path::PathBuf;

use latch_gateway::mcp;
use latch_protocol::{ErrorCode, Observation, ProtocolError};
use serde_json::{Value, json};

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../packages/schemas/v1/mcp")
}

fn check(file: &str, generated: String) {
    let path = dir().join(file);
    if std::env::var_os("LATCH_UPDATE_SCHEMAS").is_some() {
        std::fs::write(&path, &generated).expect("write");
        return;
    }
    let on_disk = std::fs::read_to_string(&path).unwrap_or_default();
    assert!(
        on_disk == generated,
        "{file} is out of date; regenerate with LATCH_UPDATE_SCHEMAS=1"
    );
}

#[test]
fn server_description_matches() {
    let server = json!({
        "supported_versions": mcp::SUPPORTED_VERSIONS,
        "instructions": mcp::INSTRUCTIONS,
        "tools": mcp::tool_definitions(),
    });
    check(
        "server.json",
        serde_json::to_string_pretty(&server).expect("json") + "\n",
    );
}

#[test]
fn observation_rendering_matches() {
    let input: Value = serde_json::from_str(
        &std::fs::read_to_string(dir().join("render-input.json")).expect("input"),
    )
    .expect("json");
    let obs: Observation =
        serde_json::from_value(input["observation"].clone()).expect("observation");
    let text = mcp::render_observation(
        input["device_id"].as_str().expect("device_id"),
        &obs,
        input["screenshot_withheld"].as_bool().expect("flag"),
    );
    check("render-expected.txt", text);
}

#[test]
fn tool_errors_match() {
    let cases: Vec<Value> = [ErrorCode::StaleObservation, ErrorCode::DeviceUnavailable, ErrorCode::SensitiveTarget]
        .into_iter()
        .map(|code| {
            let error = ProtocolError::new(code, "example message");
            json!({ "code": code.as_str(), "message": error.message, "result": mcp::tool_error(&error) })
        })
        .collect();
    check(
        "tool-errors.json",
        serde_json::to_string_pretty(&cases).expect("json") + "\n",
    );
}

/// Skills (ADR-031): validation, expansion, and matching must agree word for
/// word between this gateway and servers/vercel.
#[test]
fn skills_engine_matches() {
    use latch_gateway::mcp::skills::{self, Want};
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../packages/schemas/v1/skills");
    let cases: Value =
        serde_json::from_str(&std::fs::read_to_string(path.join("cases.json")).expect("cases"))
            .expect("json");
    let skill = skills::validate(&cases["skill"]).expect("the case skill is valid");
    let expand: Vec<Value> = cases["expand"]
        .as_array()
        .expect("expand")
        .iter()
        .map(|c| match skills::expand(&skill, &c["params"]) {
            Ok(steps) => {
                let labels: Vec<String> = steps.iter().map(skills::label).collect();
                json!({ "steps": steps, "labels": labels })
            }
            Err(e) => json!({ "error": e }),
        })
        .collect();
    let validate: Vec<Value> = cases["validate"]
        .as_array()
        .expect("validate")
        .iter()
        .map(|s| match skills::validate(s) {
            Ok(v) => json!({ "ok": v }),
            Err(e) => json!({ "error": e }),
        })
        .collect();
    let find: Vec<Value> = cases["find"]
        .as_array()
        .expect("find")
        .iter()
        .map(|c| {
            let nodes: Vec<latch_protocol::UiNode> =
                serde_json::from_value(c["nodes"].clone()).expect("nodes");
            let want = if c["want"] == "type" {
                Want::Type
            } else {
                Want::Tap
            };
            json!(skills::find(
                &nodes,
                c["target"].as_str().unwrap_or_default(),
                c["near"].as_str(),
                want
            ))
        })
        .collect();
    let generated = serde_json::to_string_pretty(
        &json!({ "expand": expand, "validate": validate, "find": find }),
    )
    .expect("json")
        + "\n";
    let file = path.join("expected.json");
    if std::env::var_os("LATCH_UPDATE_SCHEMAS").is_some() {
        std::fs::write(&file, &generated).expect("write");
        return;
    }
    assert!(
        std::fs::read_to_string(&file).unwrap_or_default() == generated,
        "skills/expected.json is out of date; regenerate with LATCH_UPDATE_SCHEMAS=1"
    );
}
