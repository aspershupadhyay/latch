//! Every shared fixture in `packages/schemas/v1/fixtures` must behave the same
//! in every implementation. This is the Rust half of that contract; the
//! Android unit tests read the same files.

use std::fs;
use std::path::{Path, PathBuf};

use latch_protocol::{
    DeviceToGateway, GatewayToDevice, Observation, Outcome, ProtocolError, validate,
};

fn fixture_dir(kind: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/schemas/v1/fixtures")
        .join(kind)
}

fn fixtures(kind: &str) -> Vec<(String, String)> {
    let mut out: Vec<_> = fs::read_dir(fixture_dir(kind))
        .expect("fixture directory exists")
        .map(|e| e.expect("readable entry").path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .map(|p| {
            let name = p
                .file_name()
                .expect("file name")
                .to_string_lossy()
                .into_owned();
            (name, fs::read_to_string(&p).expect("readable fixture"))
        })
        .collect();
    out.sort();
    assert!(!out.is_empty(), "no {kind} fixtures found");
    out
}

/// Parses and validates one fixture exactly as a receiving peer would.
fn accept(name: &str, json: &str) -> Result<(), String> {
    if name.starts_with("d2g-") {
        let msg: DeviceToGateway = serde_json::from_str(json).map_err(|e| e.to_string())?;
        match &msg {
            DeviceToGateway::Hello(hello) => validate::hello(hello).map_err(err)?,
            DeviceToGateway::Result {
                outcome: Outcome::Ok { data },
                ..
            } if data.get("observation_id").is_some() => {
                let obs: Observation =
                    serde_json::from_value(data.clone()).map_err(|e| e.to_string())?;
                validate::observation(&obs, validate::MAX_NODES).map_err(err)?;
            }
            _ => {}
        }
        let reparsed: DeviceToGateway =
            serde_json::from_str(&serde_json::to_string(&msg).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        assert_eq!(reparsed, msg, "{name} does not round-trip");
        Ok(())
    } else if name.starts_with("g2d-") {
        let msg: GatewayToDevice = serde_json::from_str(json).map_err(|e| e.to_string())?;
        if let GatewayToDevice::Command(envelope) = &msg {
            validate::command(&envelope.command).map_err(err)?;
        }
        let reparsed: GatewayToDevice =
            serde_json::from_str(&serde_json::to_string(&msg).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        assert_eq!(reparsed, msg, "{name} does not round-trip");
        Ok(())
    } else {
        panic!("fixture {name} must start with d2g- or g2d-");
    }
}

fn err(e: ProtocolError) -> String {
    e.to_string()
}

#[test]
fn valid_fixtures_are_accepted_and_round_trip() {
    for (name, json) in fixtures("valid") {
        if let Err(e) = accept(&name, &json) {
            panic!("valid fixture {name} was rejected: {e}");
        }
    }
}

#[test]
fn invalid_fixtures_are_rejected() {
    for (name, json) in fixtures("invalid") {
        assert!(
            accept(&name, &json).is_err(),
            "invalid fixture {name} was accepted"
        );
    }
}
