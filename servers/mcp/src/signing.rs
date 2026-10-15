// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Device-key signatures (ADR-037, roadmap R19). A phone on protocol 1.12
//! holds a P-256 key in its keystore and signs every message it sends; the
//! gateway learns the public key from the phone's first signed hello and from
//! then on refuses anything from that phone that is not signed by it. A stolen
//! phone token alone can no longer speak for the phone.
//!
//! Standard primitives only (ADR-014): ECDSA P-256 with SHA-256, keys as
//! SubjectPublicKeyInfo DER, signatures as DER, both base64.

use latch_protocol::{DeviceToGateway, signing_input, validate};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;

/// Why a phone's message was refused; never echoes the message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refused {
    /// Not JSON, or not a device message.
    Malformed,
    /// The signature does not verify with this phone's key.
    BadSignature,
    /// This phone signs its messages; an unsigned one is not from it.
    Unsigned,
    /// A hello names a different key than the one this phone registered.
    KeyChanged,
    /// A hello with a key must prove it holds the key.
    UnsignedKey,
}

impl Refused {
    pub fn message(self) -> &'static str {
        match self {
            Refused::Malformed => "unparseable device message",
            Refused::BadSignature => "the message signature does not match this phone's device key",
            Refused::Unsigned => "this phone signs its messages; an unsigned message was refused",
            Refused::KeyChanged => {
                "this phone's device key changed (reinstalled?); pair it again from the Latch app"
            }
            Refused::UnsignedKey => "a hello that carries a device_key must be signed by it",
        }
    }
}

/// Standard base64 (with padding) to bytes.
pub fn base64_decode(text: &str) -> Option<Vec<u8>> {
    fn value(c: u8) -> Option<u32> {
        Some(match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return None,
        } as u32)
    }
    let bytes = text.as_bytes();
    if bytes.is_empty() || !bytes.len().is_multiple_of(4) {
        return None;
    }
    let mut out = Vec::with_capacity(bytes.len() / 4 * 3);
    for (i, chunk) in bytes.chunks(4).enumerate() {
        let last = i == bytes.len() / 4 - 1;
        let pad = chunk.iter().rev().take_while(|&&c| c == b'=').count();
        if pad > 2 || (pad > 0 && !last) {
            return None;
        }
        let mut n = 0u32;
        for &c in &chunk[..4 - pad] {
            n = (n << 6) | value(c)?;
        }
        n <<= 6 * pad as u32;
        let got = [(n >> 16) as u8, (n >> 8) as u8, n as u8];
        out.extend_from_slice(&got[..3 - pad]);
    }
    Some(out)
}

fn verifying_key(key_b64: &str) -> Option<VerifyingKey> {
    if !validate::is_base64(key_b64, validate::MAX_DEVICE_KEY_CHARS) {
        return None;
    }
    VerifyingKey::from_public_key_der(&base64_decode(key_b64)?).ok()
}

/// Whether `key_b64` is a P-256 public key in SubjectPublicKeyInfo DER.
pub fn valid_key(key_b64: &str) -> bool {
    verifying_key(key_b64).is_some()
}

/// Whether `sig_b64` signs [`signing_input`]`(connection, payload)` with `key_b64`.
pub fn verify(key_b64: &str, connection: &str, payload: &str, sig_b64: &str) -> bool {
    let Some(key) = verifying_key(key_b64) else {
        return false;
    };
    if validate::signed(payload, sig_b64).is_err() {
        return false;
    }
    let Some(sig) = base64_decode(sig_b64).and_then(|d| Signature::from_der(&d).ok()) else {
        return false;
    };
    key.verify(&signing_input(connection, payload), &sig)
        .is_ok()
}

/// A phone's hello, checked against the key it registered (if any). Returns
/// the hello and, the first time a phone shows a key, that key to store.
pub fn open_hello(
    body: &[u8],
    registered: Option<&str>,
) -> Result<(latch_protocol::Hello, Option<String>), Refused> {
    match serde_json::from_slice::<DeviceToGateway>(body).map_err(|_| Refused::Malformed)? {
        DeviceToGateway::Signed { payload, sig } => {
            let Ok(DeviceToGateway::Hello(hello)) = serde_json::from_str(&payload) else {
                return Err(Refused::Malformed);
            };
            let key = hello.device_key.clone().ok_or(Refused::UnsignedKey)?;
            if registered.is_some_and(|r| r != key) {
                return Err(Refused::KeyChanged);
            }
            if !verify(&key, "", &payload, &sig) {
                return Err(Refused::BadSignature);
            }
            let new = registered.is_none().then_some(key);
            Ok((hello, new))
        }
        DeviceToGateway::Hello(hello) => {
            if registered.is_some() {
                return Err(Refused::Unsigned);
            }
            if hello.device_key.is_some() {
                return Err(Refused::UnsignedKey);
            }
            Ok((hello, None))
        }
        _ => Err(Refused::Malformed),
    }
}

/// A phone's message after its hello, signed when the phone registered a key.
pub fn open_message(
    body: &[u8],
    registered: Option<&str>,
    connection: &str,
) -> Result<DeviceToGateway, Refused> {
    match serde_json::from_slice::<DeviceToGateway>(body).map_err(|_| Refused::Malformed)? {
        DeviceToGateway::Signed { payload, sig } => {
            let key = registered.ok_or(Refused::BadSignature)?;
            if !verify(key, connection, &payload, &sig) {
                return Err(Refused::BadSignature);
            }
            match serde_json::from_str::<DeviceToGateway>(&payload) {
                Ok(DeviceToGateway::Signed { .. }) | Err(_) => Err(Refused::Malformed),
                Ok(message) => Ok(message),
            }
        }
        _ if registered.is_some() => Err(Refused::Unsigned),
        message => Ok(message),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Android's keystore does not normalise `s`: both halves must verify.
    #[test]
    fn high_and_low_s_signatures_both_verify() {
        use p256::ecdsa::SigningKey;
        use p256::ecdsa::signature::Signer;
        use p256::pkcs8::EncodePublicKey;
        let key = SigningKey::from_slice(&[7u8; 32]).expect("key");
        let public = base64_encode(
            key.verifying_key()
                .to_public_key_der()
                .expect("der")
                .as_bytes(),
        );
        let input = signing_input("k_1", "{}");
        let sig: Signature = key.sign(&input);
        let flipped = Signature::from_scalars(sig.r(), -*sig.s()).expect("flipped");
        for s in [sig, flipped] {
            let b64 = base64_encode(s.to_der().as_bytes());
            assert!(verify(&public, "k_1", "{}", &b64));
        }
    }

    fn base64_encode(bytes: &[u8]) -> String {
        const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let n = chunk
                .iter()
                .enumerate()
                .fold(0u32, |n, (i, b)| n | (u32::from(*b) << (16 - 8 * i)));
            for i in 0..4 {
                out.push(if i <= chunk.len() {
                    A[(n >> (18 - 6 * i) & 63) as usize] as char
                } else {
                    '='
                });
            }
        }
        out
    }

    #[test]
    fn base64_round_trips_standard_vectors() {
        for (plain, encoded) in [
            ("f", "Zg=="),
            ("fo", "Zm8="),
            ("foo", "Zm9v"),
            ("foobar", "Zm9vYmFy"),
        ] {
            assert_eq!(base64_decode(encoded).as_deref(), Some(plain.as_bytes()));
        }
        for bad in ["", "Zg=", "Z===", "Zg==Zg==", "Zm9v!"] {
            assert!(base64_decode(bad).is_none(), "{bad}");
        }
    }
}
