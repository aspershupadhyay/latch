// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The checked-in JSON Schemas are generated from the Rust types. This test
//! fails when they drift; regenerate with:
//!
//!     LATCH_UPDATE_SCHEMAS=1 cargo test -p latch-protocol --test schema

use std::fs;
use std::path::Path;

use latch_protocol::{DeviceToGateway, GatewayToDevice};

fn check(file: &str, schema: schemars::Schema) {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/schemas/v1")
        .join(file);
    let generated = serde_json::to_string_pretty(&schema).expect("schema serializes") + "\n";
    if std::env::var_os("LATCH_UPDATE_SCHEMAS").is_some() {
        fs::write(&path, &generated).expect("write schema");
        return;
    }
    let on_disk = fs::read_to_string(&path).unwrap_or_default();
    assert!(
        on_disk == generated,
        "{file} is out of date; run LATCH_UPDATE_SCHEMAS=1 cargo test -p latch-protocol --test schema"
    );
}

#[test]
fn schemas_match_types() {
    check(
        "device-to-gateway.schema.json",
        schemars::schema_for!(DeviceToGateway),
    );
    check(
        "gateway-to-device.schema.json",
        schemars::schema_for!(GatewayToDevice),
    );
}
