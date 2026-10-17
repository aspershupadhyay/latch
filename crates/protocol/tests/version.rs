// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The Android app and the Vercel gateway announce the same protocol version
//! as this crate. A phone that announces an older one is refused every newer
//! command by both gateways (the Android app once stayed at 1.16 while it
//! implemented 1.17, so full-access ADB never reached it).

use std::fs;
use std::path::Path;

fn declared(file: &str, prefix: &str) -> String {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(file);
    let text = fs::read_to_string(&path).expect("read source");
    let line = text
        .lines()
        .find_map(|l| l.trim().strip_prefix(prefix))
        .unwrap_or_else(|| panic!("{file} declares no `{prefix}`"));
    line.trim()
        .trim_start_matches('=')
        .trim()
        .trim_end_matches(';')
        .trim_matches('"')
        .to_owned()
}

#[test]
fn every_implementation_announces_this_protocol_version() {
    assert_eq!(
        declared(
            "apps/android/app/src/main/java/io/github/aspershupadhyay/latch/protocol/Protocol.kt",
            "const val VERSION"
        ),
        latch_protocol::PROTOCOL_VERSION,
        "the Android app's Protocol.VERSION"
    );
    assert_eq!(
        declared(
            "servers/vercel/src/protocol.ts",
            "export const PROTOCOL_VERSION"
        ),
        latch_protocol::PROTOCOL_VERSION,
        "the Vercel gateway's PROTOCOL_VERSION"
    );
}
