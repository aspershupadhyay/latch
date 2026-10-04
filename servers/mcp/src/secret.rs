// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Tokens, hashing, and constant-time comparison.

use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

/// Fills a buffer from the operating system's CSPRNG.
pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut buf = [0u8; N];
    // The OS RNG failing is unrecoverable; continuing would mint guessable tokens.
    #[allow(clippy::expect_used)]
    getrandom::fill(&mut buf).expect("operating system random number generator failed");
    buf
}

pub fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0x0f) as usize] as char);
    }
    out
}

/// A new 256-bit bearer token, hex encoded with a recognisable prefix so leaked
/// tokens are easy to spot in secret scanners.
pub fn new_token(prefix: &str) -> String {
    format!("{prefix}_{}", hex(&random_bytes::<32>()))
}

/// Short random identifier for devices and commands. Not a secret.
pub fn new_id(prefix: &str) -> String {
    format!("{prefix}_{}", hex(&random_bytes::<8>()))
}

pub fn sha256_hex(input: &str) -> String {
    hex(&Sha256::digest(input.as_bytes()))
}

/// Compares two secrets without leaking where they differ through timing.
/// Hashing first makes the comparison length-independent.
pub fn secrets_equal(a: &str, b: &str) -> bool {
    let ha = Sha256::digest(a.as_bytes());
    let hb = Sha256::digest(b.as_bytes());
    ha.as_slice().ct_eq(hb.as_slice()).into()
}

/// Compares a presented token against a stored SHA-256 hex digest.
pub fn token_matches_hash(token: &str, stored_hex: &str) -> bool {
    let computed = sha256_hex(token);
    computed.as_bytes().ct_eq(stored_hex.as_bytes()).into()
}

/// Extracts the token from an `Authorization: Bearer <token>` header value.
pub fn bearer(header: Option<&axum::http::HeaderValue>) -> Option<&str> {
    let value = header?.to_str().ok()?;
    let (scheme, token) = value.split_once(' ')?;
    (scheme.eq_ignore_ascii_case("bearer") && !token.is_empty()).then_some(token.trim())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_unique_and_hash_verifiable() {
        let a = new_token("lt");
        let b = new_token("lt");
        assert_ne!(a, b);
        assert_eq!(a.len(), 3 + 64);
        assert!(token_matches_hash(&a, &sha256_hex(&a)));
        assert!(!token_matches_hash(&b, &sha256_hex(&a)));
        assert!(secrets_equal(&a, &a.clone()));
        assert!(!secrets_equal(&a, &b));
    }

    #[test]
    fn bearer_parsing() {
        let h = axum::http::HeaderValue::from_static("Bearer abc");
        assert_eq!(bearer(Some(&h)), Some("abc"));
        let h = axum::http::HeaderValue::from_static("Basic abc");
        assert_eq!(bearer(Some(&h)), None);
        assert_eq!(bearer(None), None);
    }
}
