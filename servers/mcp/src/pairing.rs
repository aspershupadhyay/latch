// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Short-lived, single-use pairing codes.

use std::collections::HashMap;

use crate::secret;

pub const CODE_TTL_MS: u64 = 10 * 60 * 1000;
/// Setup codes the gateway shows as a QR code on the owner's computer.
pub const OWNER_CODE_TTL_MS: u64 = 15 * 60 * 1000;
const MAX_OUTSTANDING: usize = 10;
const MAX_FAILURES_PER_MINUTE: usize = 20;
/// No 0/O, 1/I/L: codes are typed by people on phones.
const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

struct Pending {
    name: String,
    owner: bool,
    expires_at_ms: u64,
}

/// What a redeemed code was made for.
#[derive(Debug, PartialEq, Eq)]
pub struct Redeemed {
    pub name: String,
    /// A setup code from the owner's own terminal: the phone also gets the owner key.
    pub owner: bool,
}

#[derive(Default)]
pub struct Pairings {
    codes: HashMap<String, Pending>,
    recent_failures: Vec<u64>,
}

#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum PairingError {
    #[error("too many pairing codes are outstanding; wait for some to expire")]
    TooMany,
    #[error("that pairing code is wrong or has expired")]
    Invalid,
    #[error("too many wrong pairing codes; wait a minute")]
    RateLimited,
}

/// `ABCD-EFGH` → `ABCDEFGH`; lowercase, spaces, and dashes are tolerated.
fn normalize(code: &str) -> String {
    code.chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

/// Rejection sampling: `byte % 31` would make some letters likelier than others.
pub(crate) fn random_code(length: usize) -> String {
    let limit = 256 - (256 % ALPHABET.len());
    let mut code = String::with_capacity(length);
    while code.len() < length {
        for b in secret::random_bytes::<16>() {
            if (b as usize) < limit && code.len() < length {
                code.push(ALPHABET[b as usize % ALPHABET.len()] as char);
            }
        }
    }
    code
}

impl Pairings {
    fn prune(&mut self, now_ms: u64) {
        self.codes.retain(|_, p| p.expires_at_ms > now_ms);
        self.recent_failures
            .retain(|t| now_ms.saturating_sub(*t) < 60_000);
    }

    /// Returns the display form of a new code, e.g. `K7QX-M2PD`.
    pub fn create(&mut self, name: String, now_ms: u64) -> Result<(String, u64), PairingError> {
        self.insert(name, false, CODE_TTL_MS, now_ms)
    }

    /// A setup code for the owner's phone; whoever redeems it also gets the owner key.
    pub fn create_owner(&mut self, now_ms: u64) -> Result<(String, u64), PairingError> {
        self.insert(String::new(), true, OWNER_CODE_TTL_MS, now_ms)
    }

    fn insert(
        &mut self,
        name: String,
        owner: bool,
        ttl_ms: u64,
        now_ms: u64,
    ) -> Result<(String, u64), PairingError> {
        self.prune(now_ms);
        if self.codes.len() >= MAX_OUTSTANDING {
            return Err(PairingError::TooMany);
        }
        let code = random_code(8);
        let expires_at_ms = now_ms + ttl_ms;
        self.codes.insert(
            code.clone(),
            Pending {
                name,
                owner,
                expires_at_ms,
            },
        );
        Ok((format!("{}-{}", &code[..4], &code[4..]), expires_at_ms))
    }

    /// Consumes a code and returns what it was created for.
    pub fn redeem(&mut self, code: &str, now_ms: u64) -> Result<Redeemed, PairingError> {
        self.prune(now_ms);
        if self.recent_failures.len() >= MAX_FAILURES_PER_MINUTE {
            return Err(PairingError::RateLimited);
        }
        match self.codes.remove(&normalize(code)) {
            Some(p) => Ok(Redeemed {
                name: p.name,
                owner: p.owner,
            }),
            None => {
                self.recent_failures.push(now_ms);
                Err(PairingError::Invalid)
            }
        }
    }

    pub fn outstanding(&self, now_ms: u64) -> usize {
        self.codes
            .values()
            .filter(|p| p.expires_at_ms > now_ms)
            .count()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn named(name: &str) -> Result<Redeemed, PairingError> {
        Ok(Redeemed {
            name: name.into(),
            owner: false,
        })
    }

    #[test]
    fn codes_are_single_use_and_expire() {
        let mut p = Pairings::default();
        let (code, _) = p.create("pixel".into(), 0).expect("create");
        assert_eq!(code.len(), 9);
        assert_eq!(p.redeem(&code.to_lowercase(), 1), named("pixel"));
        assert_eq!(p.redeem(&code, 2), Err(PairingError::Invalid));

        let (late, _) = p.create("old".into(), 0).expect("create");
        assert_eq!(p.redeem(&late, CODE_TTL_MS), Err(PairingError::Invalid));
    }

    #[test]
    fn guessing_is_rate_limited() {
        let mut p = Pairings::default();
        let (code, _) = p.create("pixel".into(), 0).expect("create");
        for _ in 0..MAX_FAILURES_PER_MINUTE {
            assert_eq!(p.redeem("AAAA-AAAA", 10), Err(PairingError::Invalid));
        }
        assert_eq!(p.redeem(&code, 10), Err(PairingError::RateLimited));
        assert_eq!(p.redeem(&code, 70_011), named("pixel"));
    }

    #[test]
    fn owner_codes_are_marked_and_last_longer() {
        let mut p = Pairings::default();
        let (code, expires) = p.create_owner(0).expect("create");
        assert_eq!(expires, OWNER_CODE_TTL_MS);
        assert_eq!(
            p.redeem(&code, CODE_TTL_MS),
            Ok(Redeemed {
                name: String::new(),
                owner: true
            })
        );
        assert_eq!(p.redeem(&code, CODE_TTL_MS), Err(PairingError::Invalid));
        let (late, _) = p.create_owner(0).expect("create");
        assert_eq!(
            p.redeem(&late, OWNER_CODE_TTL_MS),
            Err(PairingError::Invalid)
        );
    }

    #[test]
    fn codes_use_only_the_alphabet() {
        for _ in 0..200 {
            let code = random_code(8);
            assert_eq!(code.len(), 8);
            assert!(code.bytes().all(|b| ALPHABET.contains(&b)));
        }
    }

    #[test]
    fn outstanding_codes_are_capped() {
        let mut p = Pairings::default();
        for _ in 0..MAX_OUTSTANDING {
            p.create("x".into(), 0).expect("create");
        }
        assert_eq!(p.create("x".into(), 0), Err(PairingError::TooMany));
        assert_eq!(p.outstanding(0), MAX_OUTSTANDING);
    }
}
