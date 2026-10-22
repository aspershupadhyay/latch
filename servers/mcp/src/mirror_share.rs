// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Files both ways and the scan-to-mirror QR code on the live mirror page
//! (ADR-057, ADR-069).
//!
//! - Computer to phone: a file dropped on the page goes to `POST /v1/mirror/files`
//!   with the viewer key; the phone fetches it into Downloads over its mirror link.
//! - Phone to computer: the phone streams a file to `POST /v1/mirror/outbox` with
//!   its device credential; the page watching it is told, and its browser saves
//!   it from `GET /v1/mirror/outbox/{token}` (single use, 256-bit token, 15 minutes).
//! - The page's QR code (`GET /v1/mirror/qr.svg?k=…`): this gateway's mirror
//!   address, the key the page made, and the Wi-Fi pin, for Latch to scan.
//!
//! Stored bytes are always AES-256-CTR ciphertext under a key held only in
//! memory, as for every file link ([`crate::links`]).

use std::sync::Arc;

use aes::cipher::{KeyIvInit, StreamCipher};
use axum::Json;
use axum::body::{Body, Bytes};
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::http::{LINK_CHUNK_BYTES, UploadClaim, error};
use crate::links::{Kind, LinkError, Record};
use crate::{AppState, mcp, secret};

#[derive(Deserialize)]
pub(crate) struct NameQuery {
    name: String,
}

#[derive(Deserialize)]
pub(crate) struct KeyQuery {
    k: String,
}

/// A name a file can be saved under on either side: no folders, no control characters.
fn file_name_ok(name: &str) -> bool {
    let count = name.chars().count();
    (1..=200).contains(&count)
        && name != "."
        && name != ".."
        && !name
            .chars()
            .any(|c| c == '/' || c == '\\' || c.is_control())
}

fn unhex(text: &str) -> Vec<u8> {
    (0..text.len() / 2)
        .filter_map(|i| u8::from_str_radix(text.get(2 * i..2 * i + 2)?, 16).ok())
        .collect()
}

/// `Content-Disposition` for a download of `name`, ASCII-safe (RFC 6266 / 8187).
fn attachment(name: &str) -> String {
    let mut encoded = String::new();
    for b in name.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            encoded.push(b as char);
        } else {
            encoded.push_str(&format!("%{b:02X}"));
        }
    }
    let plain: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_graphic() && c != '"' && c != '\\' || c == ' ' {
                c
            } else {
                '_'
            }
        })
        .collect();
    format!("attachment; filename=\"{plain}\"; filename*=UTF-8''{encoded}")
}

/// One file stored encrypted under a fresh link.
struct Stored {
    token: String,
    record: Record,
    sha256: String,
    size: u64,
}

/// Streams `body` into a new link of `kind`, encrypting as it goes.
async fn store(
    state: &AppState,
    kind: Kind,
    headers: &HeaderMap,
    body: Body,
) -> Result<Stored, Response> {
    let declared = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    let too_large = || {
        error(
            StatusCode::PAYLOAD_TOO_LARGE,
            &format!(
                "the file is larger than this gateway's limit of {}",
                mcp::files::size_text(state.links.max_bytes)
            ),
        )
    };
    let stored_fail = || {
        error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "the gateway could not store the file",
        )
    };
    let (token, record) = state.links.create(kind);
    let path = match state.links.begin_upload(&token, declared) {
        Ok(path) => path,
        Err(LinkError::TooLarge) => return Err(too_large()),
        Err(LinkError::Full) => {
            return Err(error(
                StatusCode::SERVICE_UNAVAILABLE,
                "the gateway holds too many files right now; try again in a few minutes",
            ));
        }
        Err(_) => return Err(stored_fail()),
    };
    let mut claim = UploadClaim {
        links: &state.links,
        token: &token,
        done: false,
    };
    let Ok(mut cipher) = ctr::Ctr128BE::<aes::Aes256>::new_from_slices(
        &unhex(&record.key_hex),
        &unhex(&record.iv_hex),
    ) else {
        return Err(stored_fail());
    };
    let Ok(file) = tokio::fs::File::create(&path).await else {
        return Err(stored_fail());
    };
    // Large writes: one system call per megabyte, not per network chunk.
    let mut file = tokio::io::BufWriter::with_capacity(1024 * 1024, file);
    let mut hasher = Sha256::new();
    let mut size = 0u64;
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        let Ok(chunk) = chunk else {
            return Err(error(StatusCode::BAD_REQUEST, "the upload was interrupted"));
        };
        size += chunk.len() as u64;
        if size > state.links.max_bytes {
            return Err(too_large());
        }
        hasher.update(&chunk);
        let mut sealed = chunk.to_vec();
        cipher.apply_keystream(&mut sealed);
        if file.write_all(&sealed).await.is_err() {
            return Err(stored_fail());
        }
    }
    if file.flush().await.is_err() {
        return Err(stored_fail());
    }
    if declared.is_some_and(|n| n != size) {
        return Err(error(StatusCode::BAD_REQUEST, "the upload was incomplete"));
    }
    if state.links.finish_upload(&token, size).is_err() {
        return Err(stored_fail());
    }
    claim.done = true;
    drop(claim);
    Ok(Stored {
        token,
        record,
        sha256: secret::hex(&hasher.finalize()),
        size,
    })
}

/// A file dropped on the live mirror page (ADR-057). The page proves its
/// viewer key; the phone is told over its mirror link to fetch it into
/// Downloads, checking its SHA-256 as `file.fetch` does.
pub(crate) async fn mirror_drop(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<NameQuery>,
    body: Body,
) -> Response {
    if !crate::mirror::same_origin(&headers) {
        return error(StatusCode::FORBIDDEN, "cross-site uploads are refused");
    }
    let key = headers
        .get("x-latch-mirror-key")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    let Some(device_id) = state.mirrors.watched_by_key(key) else {
        return error(
            StatusCode::UNAUTHORIZED,
            "this mirror has ended; open it again from the phone",
        );
    };
    let name = query.name.trim().to_owned();
    if !file_name_ok(&name) {
        return error(
            StatusCode::BAD_REQUEST,
            "that file name can't be used on the phone",
        );
    }
    let file = match store(&state, Kind::Up, &headers, body).await {
        Ok(file) => file,
        Err(response) => return response,
    };
    // The phone fetches it over its own mirror link: no AI session is needed.
    let sent = state.mirrors.link_base(&device_id).is_some_and(|base| {
        let message = json!({
            "t": "file",
            "url": format!("{base}/v1/blobs/{}", file.token),
            "key": file.record.key_hex,
            "iv": file.record.iv_hex,
            "sha256": file.sha256,
            "name": name,
            "size": file.size,
        });
        state
            .mirrors
            .to_phone_link(&device_id, &message.to_string())
    });
    if !sent {
        state.links.discard(&file.token);
        return error(
            StatusCode::SERVICE_UNAVAILABLE,
            "the phone is not connected right now; try again when the picture is back",
        );
    }
    Json(json!({
        "ok": true,
        "text": format!("sent; the phone is saving it in Downloads ({}).", mcp::files::size_text(file.size)),
    }))
    .into_response()
}

/// A file the owner sends from the phone to the computer watching it (ADR-069).
/// The phone proves its device credential; the page watching is told and its
/// browser downloads the file once.
pub(crate) async fn mirror_send(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<NameQuery>,
    body: Body,
) -> Response {
    let device_id = secret::bearer(headers.get(header::AUTHORIZATION))
        .and_then(|token| state.store().authenticate(token).map(|d| d.id.clone()));
    let Some(device_id) = device_id else {
        return error(
            StatusCode::UNAUTHORIZED,
            "unknown or revoked device credential",
        );
    };
    let name = query.name.trim().to_owned();
    if !file_name_ok(&name) {
        return error(
            StatusCode::BAD_REQUEST,
            "that file name can't be saved on the computer",
        );
    }
    // Before any bytes are stored: someone must be watching to receive it.
    if !state.mirrors.is_watched(&device_id) {
        return error(
            StatusCode::CONFLICT,
            "no computer is watching this phone; open the mirror on your computer first",
        );
    }
    let file = match store(&state, Kind::Down, &headers, body).await {
        Ok(file) => file,
        Err(response) => return response,
    };
    // The record as stored now (with its size), named for the download.
    if let Some(mut record) = state.links.record(&file.token) {
        record.name = Some(name.clone());
        state.links.save(&file.token, record);
    }
    let message = json!({
        "t": "file_ready",
        "url": format!("/v1/mirror/outbox/{}", file.token),
        "name": name,
        "size": file.size,
        "sha256": file.sha256,
    });
    if !state.mirrors.to_viewer(&device_id, &message.to_string()) {
        state.links.discard(&file.token);
        return error(
            StatusCode::CONFLICT,
            "the computer stopped watching; open the mirror on your computer and send again",
        );
    }
    tracing::info!(
        bytes = file.size,
        "mirror: a file from the phone is ready for the computer"
    );
    Json(json!({ "ok": true, "size": file.size, "sha256": file.sha256 })).into_response()
}

/// The browser saves a file from the phone: decrypted on the way out, once.
pub(crate) async fn mirror_take(
    State(state): State<Arc<AppState>>,
    Path(token): Path<String>,
) -> Response {
    let gone = || {
        error(
            StatusCode::NOT_FOUND,
            "this file was already saved or has expired; send it again from the phone",
        )
    };
    let Some(record) = state
        .links
        .record(&token)
        .filter(|r| r.kind == Kind::Down && !r.used)
    else {
        return gone();
    };
    let Some((path, size)) = state.links.stored(&token) else {
        return gone();
    };
    let Ok(file) = tokio::fs::File::open(&path).await else {
        return gone();
    };
    let Ok(cipher) = ctr::Ctr128BE::<aes::Aes256>::new_from_slices(
        &unhex(&record.key_hex),
        &unhex(&record.iv_hex),
    ) else {
        return gone();
    };
    // Single use: a second request finds the link used.
    let name = record.name.clone().unwrap_or_else(|| "file".into());
    let mut used = record;
    used.used = true;
    state.links.save(&token, used);
    let links = Arc::clone(&state);
    let stream =
        futures_util::stream::unfold(Some((file, cipher, links, token)), |step| async move {
            let (mut file, mut cipher, state, token) = step?;
            let mut buf = vec![0u8; LINK_CHUNK_BYTES];
            match file.read(&mut buf).await {
                Ok(0) => {
                    state.links.discard(&token);
                    None
                }
                Ok(n) => {
                    buf.truncate(n);
                    cipher.apply_keystream(&mut buf);
                    Some((
                        Ok::<_, std::io::Error>(Bytes::from(buf)),
                        Some((file, cipher, state, token)),
                    ))
                }
                Err(e) => Some((Err(e), None)),
            }
        });
    (
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_owned()),
            (header::CONTENT_LENGTH, size.to_string()),
            (header::CONTENT_DISPOSITION, attachment(&name)),
            (header::CACHE_CONTROL, "no-store".to_owned()),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff".to_owned()),
            (
                header::CONTENT_SECURITY_POLICY,
                "sandbox; default-src 'none'".to_owned(),
            ),
        ],
        Body::from_stream(stream),
    )
        .into_response()
}

/// What the page's QR code holds: this gateway's mirror page, the page's own
/// key, and the Wi-Fi certificate's pin so the phone knows it is its gateway.
pub fn scan_link(origin: &str, key: &str, pin: Option<&str>) -> String {
    match pin {
        Some(pin) => format!("{origin}/mirror#k={key}&pin={pin}"),
        None => format!("{origin}/mirror#k={key}"),
    }
}

/// The page's QR code (ADR-069). Nothing secret beyond the key the page itself
/// made and asked for; anyone can draw a QR code of their own text.
pub(crate) async fn mirror_qr(
    State(state): State<Arc<AppState>>,
    wifi: Option<axum::Extension<crate::wifi::WifiOrigin>>,
    headers: HeaderMap,
    Query(query): Query<KeyQuery>,
) -> Response {
    if !crate::mirror::valid_key(&query.k) {
        return error(StatusCode::BAD_REQUEST, "that key is not valid");
    }
    let origin = match wifi {
        Some(axum::Extension(o)) => o.0,
        None => crate::http::gateway_url(&state, &headers),
    };
    let pin = crate::nearby::own_pin(&state);
    let Some(svg) = crate::terminal::qr_svg(&scan_link(&origin, &query.k, pin.as_deref())) else {
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not draw the QR code",
        );
    };
    let mut response = svg.into_response();
    let h = response.headers_mut();
    h.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("image/svg+xml"),
    );
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; style-src 'unsafe-inline'"),
    );
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names_stay_in_one_folder() {
        assert!(file_name_ok("holiday photo.jpg"));
        assert!(file_name_ok("отчёт.pdf"));
        for bad in ["", ".", "..", "a/b.txt", "a\\b.txt", "bell\u{7}.txt"] {
            assert!(!file_name_ok(bad), "{bad:?}");
        }
        assert!(!file_name_ok(&"a".repeat(201)));
        assert_eq!(unhex("00ff10"), vec![0, 255, 16]);
    }

    #[test]
    fn downloads_keep_the_name_and_never_break_the_header() {
        assert_eq!(
            attachment("a b.pdf"),
            "attachment; filename=\"a b.pdf\"; filename*=UTF-8''a%20b.pdf"
        );
        let odd = attachment("отчёт \"x\".pdf");
        assert!(odd.is_ascii());
        assert!(odd.contains("filename*=UTF-8''%D0%BE"));
        assert!(!odd.contains("\"x\""));
    }

    #[test]
    fn the_scan_link_carries_key_and_pin() {
        let key = "A".repeat(43);
        assert_eq!(
            scan_link("http://127.0.0.1:8788", &key, Some("pin")),
            format!("http://127.0.0.1:8788/mirror#k={key}&pin=pin")
        );
        assert_eq!(
            scan_link("https://relay.example", &key, None),
            format!("https://relay.example/mirror#k={key}")
        );
    }
}
