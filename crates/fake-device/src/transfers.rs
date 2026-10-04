// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Link transfers for the simulated phone (protocol 1.7, ADR-027): it
//! downloads or uploads the encrypted bytes itself, over plain HTTP to a
//! local gateway, exactly as the Android app does over HTTPS. AES-256-CTR
//! with the link's key; the plain file's SHA-256 must match before a
//! download is kept.

use std::collections::BTreeMap;

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
    },
    Push {
        transfer: String,
        link: FileLink,
        data: Vec<u8>,
        max_bytes: u64,
    },
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
