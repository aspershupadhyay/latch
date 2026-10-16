// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Link transfers for the simulated phone (protocol 1.7, ADR-027): it
//! downloads or uploads the encrypted bytes itself, over plain HTTP to a
//! local gateway, exactly as the Android app does over HTTPS. AES-256-CTR
//! with the link's key; the plain file's SHA-256 must match before a
//! download is kept.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use aes::cipher::{KeyIvInit, StreamCipher};
use latch_protocol::{ErrorCode, FileItem, FileLink, ProtocolError};
use sha2::{Digest, Sha256};

type Aes256Ctr = ctr::Ctr128BE<aes::Aes256>;

fn err(code: ErrorCode, message: impl Into<String>) -> ProtocolError {
    ProtocolError::new(code, message)
}

fn unhex(hex: &str) -> Vec<u8> {
    (0..hex.len() / 2)
        .filter_map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok())
        .collect()
}

/// Encrypts or decrypts in place (CTR is its own inverse).
pub fn apply(link: &FileLink, data: &mut [u8]) -> Result<(), ProtocolError> {
    let mut cipher = Aes256Ctr::new_from_slices(&unhex(&link.key_hex), &unhex(&link.iv_hex))
        .map_err(|_| err(ErrorCode::InvalidRequest, "bad key or iv"))?;
    cipher.apply_keystream(data);
    Ok(())
}

pub fn sha256_hex(data: &[u8]) -> String {
    Sha256::digest(data)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// What a running transfer will do once its job runs.
#[derive(Debug, Clone)]
pub enum Job {
    Fetch {
        transfer: String,
        link: FileLink,
        sha256: String,
        /// The phone's own Wi-Fi slot, when the link points there (1.14).
        local: Option<Slot>,
    },
    Push {
        transfer: String,
        link: FileLink,
        data: Vec<u8>,
        max_bytes: u64,
        local: Option<Slot>,
    },
}

/// The ciphertext a same-Wi-Fi slot holds (protocol 1.14, ADR-041).
pub type Slot = Arc<Mutex<Option<Vec<u8>>>>;

/// One same-Wi-Fi address the phone opened with `file.local`.
#[derive(Debug, Clone)]
pub struct LocalSlot {
    pub url: String,
    pub direction: latch_protocol::LocalDirection,
    pub data: Slot,
}

/// How long a slot listens, like the app's.
pub const LOCAL_SLOT_TTL_MS: u64 = 15 * 60 * 1000;

/// Serves one slot on 127.0.0.1 until one transfer is done or it expires:
/// `PUT /<token>` fills a receive slot, `GET /<token>` reads a send slot.
/// Anything else is answered 404, and the slot keeps listening.
pub async fn serve_slot(listener: std::net::TcpListener, slot: LocalSlot, token: String) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let Ok(listener) = tokio::net::TcpListener::from_std(listener) else {
        return;
    };
    let ttl = std::time::Duration::from_millis(LOCAL_SLOT_TTL_MS);
    let _ = tokio::time::timeout(ttl, async {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                continue;
            };
            let mut raw = Vec::new();
            let mut buf = [0u8; 64 * 1024];
            let head_end = loop {
                if let Some(end) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
                    break Some(end);
                }
                match stream.read(&mut buf).await {
                    Ok(0) | Err(_) => break None,
                    Ok(n) => raw.extend_from_slice(&buf[..n]),
                }
            };
            let Some(end) = head_end else { continue };
            let head = String::from_utf8_lossy(&raw[..end]).to_ascii_lowercase();
            let mut words = head.split_whitespace();
            let (method, path) = (words.next().unwrap_or(""), words.next().unwrap_or(""));
            let length: usize = head
                .lines()
                .find_map(|l| l.strip_prefix("content-length:"))
                .and_then(|v| v.trim().parse().ok())
                .unwrap_or(0);
            let mut body = raw[end + 4..].to_vec();
            while body.len() < length {
                match stream.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => body.extend_from_slice(&buf[..n]),
                }
            }
            let ours = path.strip_prefix('/') == Some(token.as_str());
            let reply = |status: &str, payload: &[u8]| {
                let mut out = format!(
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    payload.len()
                )
                .into_bytes();
                out.extend_from_slice(payload);
                out
            };
            let (answer, done) = {
                let mut data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
                match (method, slot.direction) {
                    ("put", latch_protocol::LocalDirection::Receive)
                        if ours && data.is_none() && body.len() == length =>
                    {
                        *data = Some(body);
                        (reply("201 Created", b""), true)
                    }
                    ("get", latch_protocol::LocalDirection::Send) if ours && data.is_some() => {
                        (reply("200 OK", data.as_deref().unwrap_or_default()), true)
                    }
                    _ => (reply("404 Not Found", b""), false),
                }
            };
            let _ = stream.write_all(&answer).await;
            let _ = stream.shutdown().await;
            if done {
                return;
            }
        }
    })
    .await;
}

/// 64 lowercase hex characters from the OS random generator: a slot's path.
pub fn random_token() -> String {
    let mut bytes = [0u8; 32];
    // The OS RNG failing is unrecoverable for a secret path.
    #[allow(clippy::expect_used)]
    getrandom::fill(&mut bytes).expect("operating system random number generator failed");
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Decrypts what arrived at the phone's own receive slot.
pub fn take_local(slot: &Slot, link: &FileLink, sha256: &str) -> Result<Vec<u8>, ProtocolError> {
    let mut data = slot
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .take()
        .ok_or_else(|| {
            err(
                ErrorCode::TargetNotFound,
                "nothing arrived at the phone's Wi-Fi address",
            )
        })?;
    apply(link, &mut data)?;
    if sha256_hex(&data) != sha256 {
        return Err(err(
            ErrorCode::InvalidRequest,
            "the file did not match its sha256, so it was not saved",
        ));
    }
    Ok(data)
}

/// Encrypts a push into the phone's own send slot.
pub fn fill_local(slot: &Slot, link: &FileLink, mut data: Vec<u8>) -> Result<(), ProtocolError> {
    apply(link, &mut data)?;
    *slot.lock().unwrap_or_else(|e| e.into_inner()) = Some(data);
    Ok(())
}

/// Where a fetched file goes once it checks out.
#[derive(Debug, Clone)]
pub struct Destination {
    pub location: latch_protocol::FileLocation,
    pub folder: Option<String>,
    pub subfolder: Option<String>,
    pub name: String,
    pub mime: Option<String>,
    pub overwrite: bool,
}

#[derive(Debug, Clone)]
pub struct FakeTransfer {
    pub id: String,
    pub done_bytes: u64,
    pub total_bytes: Option<u64>,
    pub destination: Option<Destination>,
    /// Done: the file (`fetch`) or the copied one (`push`).
    pub item: Option<FileItem>,
    pub sha256: Option<String>,
    pub error: Option<ProtocolError>,
    pub finished: bool,
}

/// Minimal HTTP/1.1 over plain TCP with binary bodies, for local gateways.
pub async fn http_bytes(
    method: &str,
    url: &str,
    headers: &BTreeMap<String, String>,
    body: &[u8],
) -> Result<(u16, Vec<u8>), ProtocolError> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let net = |e: std::io::Error| err(ErrorCode::TransportUnavailable, e.to_string());
    let rest = url.strip_prefix("http://").ok_or_else(|| {
        err(
            ErrorCode::PolicyRefused,
            "the fake phone only follows http:// links to a local gateway",
        )
    })?;
    let (host, path) = rest.split_once('/').unwrap_or((rest, ""));
    let mut head = format!(
        "{method} /{path} HTTP/1.1\r\nHost: {host}\r\nContent-Length: {}\r\nConnection: close\r\n",
        body.len()
    );
    for (k, v) in headers {
        head.push_str(&format!("{k}: {v}\r\n"));
    }
    head.push_str("\r\n");
    let mut stream = tokio::net::TcpStream::connect(host).await.map_err(net)?;
    stream.write_all(head.as_bytes()).await.map_err(net)?;
    stream.write_all(body).await.map_err(net)?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.map_err(net)?;
    let split = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or_else(|| err(ErrorCode::TransportUnavailable, "malformed response"))?;
    let head = String::from_utf8_lossy(&raw[..split]).to_ascii_lowercase();
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| err(ErrorCode::TransportUnavailable, "malformed status line"))?;
    let rest = raw[split + 4..].to_vec();
    let body = if head.contains("transfer-encoding: chunked") {
        dechunk(&rest)
    } else {
        rest
    };
    Ok((status, body))
}

fn dechunk(mut s: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    while let Some(end) = s.windows(2).position(|w| w == b"\r\n") {
        let n = usize::from_str_radix(String::from_utf8_lossy(&s[..end]).trim(), 16).unwrap_or(0);
        let start = end + 2;
        if n == 0 || s.len() < start + n {
            break;
        }
        out.extend_from_slice(&s[start..start + n]);
        s = &s[(start + n).min(s.len())..];
        if s.starts_with(b"\r\n") {
            s = &s[2..];
        }
    }
    out
}

/// Downloads and decrypts a fetch, returning the plain bytes once their
/// SHA-256 matches.
pub async fn download(link: &FileLink, sha256: &str) -> Result<Vec<u8>, ProtocolError> {
    let (status, mut data) = http_bytes("GET", &link.url, &link.headers, &[]).await?;
    if status != 200 {
        return Err(err(
            ErrorCode::TargetNotFound,
            format!("the link answered HTTP {status}; nothing was uploaded to it, or it expired"),
        ));
    }
    apply(link, &mut data)?;
    if sha256_hex(&data) != sha256 {
        return Err(err(
            ErrorCode::InvalidRequest,
            "the file did not match its sha256, so it was not saved",
        ));
    }
    Ok(data)
}

/// Encrypts and uploads a push.
pub async fn upload(link: &FileLink, mut data: Vec<u8>) -> Result<(), ProtocolError> {
    apply(link, &mut data)?;
    let (status, _) = http_bytes("PUT", &link.url, &link.headers, &data).await?;
    if !(200..300).contains(&status) {
        return Err(err(
            ErrorCode::TransportUnavailable,
            format!("the upload link answered HTTP {status}"),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ctr_matches_openssl_aes_256_ctr() {
        // head -c 40 /dev/zero | openssl enc -aes-256-ctr -K 0001..1f -iv ffff..ff
        // (the all-ones counter also checks the full 128-bit wrap-around).
        let link = FileLink {
            url: "http://x/".into(),
            headers: BTreeMap::new(),
            key_hex: "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".into(),
            iv_hex: "ffffffffffffffffffffffffffffffff".into(),
        };
        let mut data = vec![0u8; 40];
        apply(&link, &mut data).expect("encrypt");
        assert_eq!(
            data.iter().map(|b| format!("{b:02x}")).collect::<String>(),
            "e999e41d4ca770da5387117b5d8f57eef29000b62a499fd0a9f39a6add2e7780f05d76ae4ab99fe5"
        );
        apply(&link, &mut data).expect("decrypt");
        assert_eq!(data, vec![0u8; 40]);
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }
}
