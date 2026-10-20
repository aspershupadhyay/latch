// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Find my computer (ADR-060): a phone on the Wi-Fi links to this gateway
//! with no QR code. The phone finds the gateway by its pin-checked address
//! search, both sides show a 6-digit number from a commit-then-reveal
//! exchange, and the owner approves on the computer only when the phone
//! shows the same number. Approval hands the phone a single-use owner setup
//! code (ADR-054), which it redeems at `/v1/pair` as after a scan.
//!
//! Nothing here is logged with its nonces or codes.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use axum::Json;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};

use crate::{AppState, secret};

/// A request lives this long from its start.
pub const REQUEST_TTL_MS: u64 = 120_000;
/// Requests waiting at once.
pub const MAX_WAITING: usize = 3;
/// New requests a minute.
pub const STARTS_PER_MINUTE: u32 = 10;
/// The longest a phone's status poll is held open.
pub const POLL_HOLD_MS: u64 = 20_000;
/// Device names longer than this are refused.
pub const MAX_DEVICE_CHARS: usize = 40;

const COMMIT_LABEL: &[u8] = b"latch-nearby-commit-v1";
const SAS_LABEL: &[u8] = b"latch-nearby-sas-v1";

/// SHA-256("latch-nearby-commit-v1" ‖ nonce): what the gateway shows before revealing its nonce.
pub fn commitment(nonce: &[u8; 32]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(COMMIT_LABEL);
    h.update(nonce);
    h.finalize().into()
}

/// The number both screens show: the first 4 bytes of
/// SHA-256("latch-nearby-sas-v1" ‖ pin ‖ phone nonce ‖ gateway nonce),
/// big-endian, mod 1 000 000.
pub fn number(pin: &str, phone: &[u8; 32], gateway: &[u8; 32]) -> u32 {
    let mut h = Sha256::new();
    h.update(SAS_LABEL);
    h.update(pin.as_bytes());
    h.update(phone);
    h.update(gateway);
    let d = h.finalize();
    u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000
}

/// `123456` as `123 456`.
pub fn shown(number: u32) -> String {
    format!("{:03} {:03}", number / 1000, number % 1000)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Status {
    /// Started; the phone has not sent its nonce yet.
    Started,
    /// Nonces exchanged: the owner sees the number and decides.
    Waiting,
    /// Approved; the code waits for the phone.
    Approved(String),
    /// The phone has the code.
    Delivered,
    Denied,
}

struct Request {
    device: String,
    gateway_nonce: [u8; 32],
    phone_nonce: Option<[u8; 32]>,
    started_ms: u64,
    status: Status,
}

/// A request the owner can approve, as the terminal shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pending {
    pub id: String,
    pub device: String,
    pub number: u32,
    pub age_ms: u64,
}

#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum NearbyError {
    #[error("too many phones are asking; try again in a minute")]
    Busy,
    #[error("this request ended; start again")]
    Unknown,
    #[error("this step was already done")]
    Again,
    #[error("the request is not ready for that")]
    NotReady,
}

/// Linking requests from phones on the Wi-Fi.
#[derive(Default)]
pub struct Nearby {
    requests: Mutex<HashMap<String, Request>>,
    /// (minute, starts in it)
    starts: Mutex<(u64, u32)>,
}

impl Nearby {
    fn requests(&self) -> MutexGuard<'_, HashMap<String, Request>> {
        self.requests.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn sweep(requests: &mut HashMap<String, Request>, now_ms: u64) {
        requests.retain(|_, r| now_ms.saturating_sub(r.started_ms) < REQUEST_TTL_MS);
    }

    /// A phone asks to link: the request id and the gateway's commitment.
    pub fn start(&self, device: &str, now_ms: u64) -> Result<(String, [u8; 32]), NearbyError> {
        {
            let mut starts = self.starts.lock().unwrap_or_else(|e| e.into_inner());
            let minute = now_ms / 60_000;
            if starts.0 != minute {
                *starts = (minute, 0);
            }
            if starts.1 >= STARTS_PER_MINUTE {
                return Err(NearbyError::Busy);
            }
            starts.1 += 1;
        }
        let mut requests = self.requests();
        Self::sweep(&mut requests, now_ms);
        let open = requests
            .values()
            .filter(|r| matches!(r.status, Status::Started | Status::Waiting))
            .count();
        if open >= MAX_WAITING {
            return Err(NearbyError::Busy);
        }
        let id = secret::new_id("nby");
        let gateway_nonce = secret::random_bytes::<32>();
        let commit = commitment(&gateway_nonce);
        requests.insert(
            id.clone(),
            Request {
                device: device.trim().to_owned(),
                gateway_nonce,
                phone_nonce: None,
                started_ms: now_ms,
                status: Status::Started,
            },
        );
        Ok((id, commit))
    }

    /// The phone's nonce; answers the gateway's, now that the phone is bound to its own.
    pub fn nonce(&self, id: &str, phone: [u8; 32], now_ms: u64) -> Result<[u8; 32], NearbyError> {
        let mut requests = self.requests();
        Self::sweep(&mut requests, now_ms);
        let r = requests.get_mut(id).ok_or(NearbyError::Unknown)?;
        if r.phone_nonce.is_some() || r.status != Status::Started {
            return Err(NearbyError::Again);
        }
        r.phone_nonce = Some(phone);
        r.status = Status::Waiting;
        Ok(r.gateway_nonce)
    }

    /// What the phone sees when it asks: the status, and the code once (then `Delivered`).
    pub fn status(&self, id: &str, now_ms: u64) -> Result<Status, NearbyError> {
        let mut requests = self.requests();
        Self::sweep(&mut requests, now_ms);
        let r = requests.get_mut(id).ok_or(NearbyError::Unknown)?;
        let status = r.status.clone();
        if matches!(status, Status::Approved(_)) {
            r.status = Status::Delivered;
        }
        Ok(status)
    }

    /// The phone gave up.
    pub fn cancel(&self, id: &str) {
        self.requests().remove(id);
    }

    /// Requests whose number the owner can compare now, oldest first.
    pub fn pending(&self, pin: &str, now_ms: u64) -> Vec<Pending> {
        let mut requests = self.requests();
        Self::sweep(&mut requests, now_ms);
        let mut out: Vec<Pending> = requests
            .iter()
            .filter(|(_, r)| r.status == Status::Waiting)
            .filter_map(|(id, r)| {
                r.phone_nonce.map(|p| Pending {
                    id: id.clone(),
                    device: r.device.clone(),
                    number: number(pin, &p, &r.gateway_nonce),
                    age_ms: now_ms.saturating_sub(r.started_ms),
                })
            })
            .collect();
        out.sort_by_key(|p| std::cmp::Reverse(p.age_ms));
        out
    }

    /// The owner's answer. `code` makes the setup code on approval, only when the request may take it.
    pub fn decide(
        &self,
        id: &str,
        approve: bool,
        now_ms: u64,
        code: impl FnOnce() -> Option<String>,
    ) -> Result<(), NearbyError> {
        let mut requests = self.requests();
        Self::sweep(&mut requests, now_ms);
        let r = requests.get_mut(id).ok_or(NearbyError::Unknown)?;
        if r.status != Status::Waiting {
            return Err(NearbyError::NotReady);
        }
        r.status = if approve {
            Status::Approved(code().ok_or(NearbyError::Busy)?)
        } else {
            Status::Denied
        };
        Ok(())
    }
}

/// This computer's name for the phone's list: `LATCH_NAME`, else the host name.
pub fn computer_name() -> String {
    let from_env = std::env::var("LATCH_NAME").ok();
    let from_host = || {
        std::process::Command::new("hostname")
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
    };
    let name = from_env.or_else(from_host).unwrap_or_default();
    let name: String = name
        .trim()
        .trim_end_matches(".local")
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_DEVICE_CHARS)
        .collect();
    if name.is_empty() {
        "This computer".into()
    } else {
        name
    }
}

fn enabled() -> bool {
    std::env::var("LATCH_NEARBY").map_or(true, |v| v.trim() != "0")
}

fn now_ms() -> u64 {
    crate::now_ms()
}

/// The Wi-Fi certificate's pin, which the phone saw; None without a Wi-Fi listener.
fn own_pin(state: &AppState) -> Option<String> {
    crate::wifi::existing_pin(&state.config.data_dir).ok()
}

fn no_store(mut response: Response) -> Response {
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

fn error(status: StatusCode, message: &str) -> Response {
    no_store((status, Json(json!({ "error": message }))).into_response())
}

fn off() -> Response {
    error(
        StatusCode::NOT_FOUND,
        "linking without a QR code is off on this gateway",
    )
}

fn refused(e: &NearbyError) -> Response {
    let status = match e {
        NearbyError::Busy => StatusCode::TOO_MANY_REQUESTS,
        NearbyError::Unknown => StatusCode::NOT_FOUND,
        NearbyError::Again | NearbyError::NotReady => StatusCode::CONFLICT,
    };
    error(status, &e.to_string())
}

fn parse_nonce(hex: &str) -> Option<[u8; 32]> {
    if hex.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(hex.get(2 * i..2 * i + 2)?, 16).ok()?;
    }
    Some(out)
}

/// `GET /v1/nearby`: "this is a Latch gateway", with the computer's name.
pub async fn hello(State(state): State<Arc<AppState>>) -> Response {
    if !enabled() || own_pin(&state).is_none() {
        return off();
    }
    no_store(
        Json(json!({ "latch": "nearby", "v": 1, "name": state.computer_name })).into_response(),
    )
}

#[derive(Deserialize)]
pub struct StartRequest {
    device: String,
}

/// `POST /v1/nearby`: a phone asks to link.
pub async fn start(
    State(state): State<Arc<AppState>>,
    Json(request): Json<StartRequest>,
) -> Response {
    if !enabled() || own_pin(&state).is_none() {
        return off();
    }
    let device = request.device.trim();
    if device.is_empty()
        || device.chars().count() > MAX_DEVICE_CHARS
        || device.chars().any(char::is_control)
    {
        return error(
            StatusCode::BAD_REQUEST,
            "device must be 1-40 printable characters",
        );
    }
    match state.nearby.start(device, now_ms()) {
        Ok((id, commit)) => {
            tracing::info!("a phone on the Wi-Fi asks to link: {device}");
            no_store(Json(json!({ "id": id, "commit": secret::hex(&commit) })).into_response())
        }
        Err(e) => refused(&e),
    }
}

#[derive(Deserialize)]
pub struct NonceRequest {
    nonce: String,
}

/// `POST /v1/nearby/{id}/nonce`: the phone's nonce for the gateway's.
pub async fn nonce(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(request): Json<NonceRequest>,
) -> Response {
    if !enabled() {
        return off();
    }
    let Some(phone) = parse_nonce(&request.nonce) else {
        return error(StatusCode::BAD_REQUEST, "nonce must be 64 hex digits");
    };
    match state.nearby.nonce(&id, phone, now_ms()) {
        Ok(gateway) => no_store(Json(json!({ "nonce": secret::hex(&gateway) })).into_response()),
        Err(e) => refused(&e),
    }
}

/// `GET /v1/nearby/{id}`: waits up to [POLL_HOLD_MS] for the owner's answer.
pub async fn poll(State(state): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    if !enabled() {
        return off();
    }
    let deadline = now_ms() + POLL_HOLD_MS;
    loop {
        let status = match state.nearby.status(&id, now_ms()) {
            Ok(s) => s,
            Err(e) => return refused(&e),
        };
        let answer = match status {
            Status::Approved(code) => json!({ "state": "approved", "code": code }),
            Status::Delivered => json!({ "state": "done" }),
            Status::Denied => json!({ "state": "denied" }),
            Status::Started | Status::Waiting if now_ms() >= deadline => {
                json!({ "state": "waiting" })
            }
            Status::Started | Status::Waiting => {
                tokio::time::sleep(Duration::from_millis(250)).await;
                continue;
            }
        };
        return no_store(Json(answer).into_response());
    }
}

/// `DELETE /v1/nearby/{id}`: the phone cancels.
pub async fn cancel(State(state): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    state.nearby.cancel(&id);
    StatusCode::NO_CONTENT.into_response()
}

/// `GET /v1/admin/nearby`: requests the owner can approve, with their numbers.
pub async fn admin_list(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !crate::http::is_admin(&state, &headers) {
        return error(StatusCode::UNAUTHORIZED, "missing or wrong bearer token");
    }
    let Some(pin) = own_pin(&state) else {
        return no_store(Json(json!({ "requests": [] })).into_response());
    };
    let list: Vec<_> = state
        .nearby
        .pending(&pin, now_ms())
        .into_iter()
        .map(|p| json!({ "id": p.id, "device": p.device, "number": shown(p.number), "age_ms": p.age_ms }))
        .collect();
    no_store(Json(json!({ "requests": list })).into_response())
}

#[derive(Deserialize)]
pub struct Decision {
    approve: bool,
}

/// `POST /v1/admin/nearby/{id}`: the owner's yes or no.
pub async fn admin_decide(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(decision): Json<Decision>,
) -> Response {
    if !crate::http::is_admin(&state, &headers) {
        return error(StatusCode::UNAUTHORIZED, "missing or wrong bearer token");
    }
    let now = now_ms();
    let result = state.nearby.decide(&id, decision.approve, now, || {
        state
            .pairings()
            .create_owner(now)
            .ok()
            .map(|(code, _)| code)
    });
    match result {
        Ok(()) => {
            tracing::info!(
                "{} a phone's request to link",
                if decision.approve {
                    "approved"
                } else {
                    "refused"
                }
            );
            StatusCode::NO_CONTENT.into_response()
        }
        Err(e) => refused(&e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_number_is_six_digits_and_depends_on_every_input() {
        let (p, g) = ([1u8; 32], [2u8; 32]);
        let n = number("pinA", &p, &g);
        assert!(n < 1_000_000);
        assert_ne!(n, number("pinB", &p, &g));
        assert_ne!(n, number("pinA", &[3u8; 32], &g));
        assert_ne!(n, number("pinA", &p, &[3u8; 32]));
        assert_eq!(shown(7), "000 007");
        assert_eq!(shown(123_456), "123 456");
    }

    #[test]
    fn known_values_match_the_shared_fixture() {
        // packages/schemas/v1/nearby/number.json: the phone computes the same.
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../../packages/schemas/v1/nearby/number.json"
        ))
        .expect("fixture");
        for case in fixture["cases"].as_array().expect("cases") {
            let p = parse_nonce(case["phone_nonce"].as_str().expect("p")).expect("p");
            let g = parse_nonce(case["gateway_nonce"].as_str().expect("g")).expect("g");
            assert_eq!(
                secret::hex(&commitment(&g)),
                case["commit"].as_str().expect("c")
            );
            assert_eq!(
                shown(number(case["pin"].as_str().expect("pin"), &p, &g)),
                case["number"].as_str().expect("n")
            );
        }
    }

    #[test]
    fn a_request_goes_from_start_to_one_delivered_code() {
        let nearby = Nearby::default();
        let (id, commit) = nearby.start("Pixel 8", 1_000).expect("start");
        assert!(
            nearby.pending("pin", 1_000).is_empty(),
            "no number before the nonces"
        );
        assert_eq!(
            nearby.decide(&id, true, 1_000, || Some("C".into())),
            Err(NearbyError::NotReady)
        );
        let g = nearby.nonce(&id, [9u8; 32], 1_100).expect("nonce");
        assert_eq!(commitment(&g), commit, "the reveal matches the commitment");
        assert_eq!(
            nearby.nonce(&id, [8u8; 32], 1_200),
            Err(NearbyError::Again),
            "the phone cannot change its nonce"
        );
        let pending = nearby.pending("pin", 1_300);
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].device, "Pixel 8");
        assert_eq!(pending[0].number, number("pin", &[9u8; 32], &g));
        assert_eq!(nearby.status(&id, 1_300), Ok(Status::Waiting));
        nearby
            .decide(&id, true, 1_400, || Some("ABCDEFGH".into()))
            .expect("approve");
        assert_eq!(
            nearby.status(&id, 1_500),
            Ok(Status::Approved("ABCDEFGH".into()))
        );
        assert_eq!(
            nearby.status(&id, 1_600),
            Ok(Status::Delivered),
            "the code is handed out once"
        );
        assert_eq!(
            nearby.decide(&id, true, 1_700, || Some("X".into())),
            Err(NearbyError::NotReady)
        );
    }

    #[test]
    fn refusals_limits_and_expiry() {
        let nearby = Nearby::default();
        let (id, _) = nearby.start("A", 0).expect("start");
        nearby.nonce(&id, [1u8; 32], 0).expect("nonce");
        nearby
            .decide(&id, false, 0, || panic!("no code on refusal"))
            .expect("deny");
        assert_eq!(nearby.status(&id, 0), Ok(Status::Denied));
        // At most 3 open at once.
        let nearby = Nearby::default();
        for i in 0..MAX_WAITING {
            nearby.start(&format!("P{i}"), 0).expect("start");
        }
        assert_eq!(nearby.start("P", 0), Err(NearbyError::Busy));
        // They expire, and the slots come back.
        assert!(nearby.start("P", REQUEST_TTL_MS).is_ok());
        assert_eq!(nearby.status("nby_missing", 0), Err(NearbyError::Unknown));
        // At most 10 starts a minute, even when each is cancelled.
        let nearby = Nearby::default();
        for _ in 0..STARTS_PER_MINUTE {
            let (id, _) = nearby.start("P", 120_000).expect("start");
            nearby.cancel(&id);
        }
        assert_eq!(nearby.start("P", 120_001), Err(NearbyError::Busy));
        assert!(nearby.start("P", 180_000).is_ok(), "a new minute");
    }

    #[test]
    fn nonces_are_strict_hex() {
        assert!(parse_nonce(&"ab".repeat(32)).is_some());
        assert!(parse_nonce(&"ab".repeat(31)).is_none());
        assert!(parse_nonce(&"zz".repeat(32)).is_none());
        assert!(parse_nonce(&"é".repeat(32)).is_none());
    }
}
