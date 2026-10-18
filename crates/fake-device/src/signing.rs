// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The fake phone's device key (protocol 1.12, ADR-037): a P-256 key it signs
//! every message with, as the Android app does with a keystore key.

use latch_protocol::{DeviceToGateway, signing_input};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::EncodePublicKey;

#[derive(Clone)]
pub struct DeviceKey(SigningKey);

impl std::fmt::Debug for DeviceKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("DeviceKey(..)")
    }
}

impl DeviceKey {
    /// A new random key.
    pub fn generate() -> Self {
        loop {
            let mut bytes = [0u8; 32];
            // The OS RNG failing is unrecoverable for a key.
            #[allow(clippy::expect_used)]
            getrandom::fill(&mut bytes).expect("operating system random number generator failed");
            if let Ok(key) = SigningKey::from_slice(&bytes) {
                return Self(key);
            }
        }
    }

    /// The public key as base64 SubjectPublicKeyInfo DER, as `hello.device_key` carries it.
    pub fn public_b64(&self) -> String {
        let der = self
            .0
            .verifying_key()
            .to_public_key_der()
            .map(|d| d.as_bytes().to_vec())
            .unwrap_or_default();
        base64(&der)
    }

    /// Any JSON text wrapped in a signed envelope bound to `connection`, as the
    /// phone proves its key when it starts a mirror (R30).
    pub fn seal_text(&self, connection: &str, payload: &str) -> String {
        let sig: Signature = self.0.sign(&signing_input(connection, payload));
        serde_json::json!({
            "type": "signed",
            "payload": payload,
            "sig": base64(sig.to_der().as_bytes()),
        })
        .to_string()
    }

    /// `message` wrapped in a signed envelope bound to `connection`.
    pub fn seal(&self, connection: &str, message: &DeviceToGateway) -> serde_json::Result<String> {
        let payload = serde_json::to_string(message)?;
        let sig: Signature = self.0.sign(&signing_input(connection, &payload));
        serde_json::to_string(&DeviceToGateway::Signed {
            payload,
            sig: base64(sig.to_der().as_bytes()),
        })
    }
}

/// Standard base64 with padding.
pub fn base64(bytes: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |n, (i, b)| n | (u32::from(*b) << (16 - 8 * i)));
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(A[(n >> (18 - 6 * i) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}
