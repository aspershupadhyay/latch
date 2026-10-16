// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! HTTP routes.

use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::store::{ClientRecord, DeviceRecord, OwnerKeyRecord};
use crate::{AppState, device_http, device_ws, mcp, now_ms, oauth, secret};

const PAGE_HTML: &str = include_str!("page/index.html");
const PAGE_CSS: &str = include_str!("page/gateway.css");
const ROBOTS_TXT: &str = include_str!("page/robots.txt");

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/", get(page))
        .route("/gateway.css", get(page_css))
        .route("/robots.txt", get(robots_txt))
        .route("/healthz", get(health))
        .route("/mcp", post(mcp_post).get(mcp_other).delete(mcp_other))
        .route(
            "/mcp/{token}",
            post(mcp_post_link).get(mcp_other).delete(mcp_other),
        )
        .route("/v1/pair", post(pair))
        .route("/v1/info", get(device_http::info))
        .route("/v1/device", get(device_ws::upgrade))
        .route("/v1/device/hello", post(device_http::hello))
        .route("/v1/device/poll", get(device_http::poll))
        .route(
            "/v1/device/messages",
            post(device_http::messages)
                .layer(DefaultBodyLimit::max(device_http::MAX_MESSAGE_BYTES)),
        )
        // File links (protocol 1.6): the 256-bit token in the path is the credential.
        .route("/v1/files/{token}", get(file_download))
        .route(
            "/v1/uploads/{token}",
            put(file_upload)
                .post(file_upload)
                .layer(DefaultBodyLimit::max(crate::transfers::MAX_TRANSFER_BYTES)),
        )
        // Encrypted links (protocol 1.7): streamed, never held in memory whole.
        .route(
            "/v1/blobs/{token}",
            get(link_download)
                .put(link_upload)
                .layer(DefaultBodyLimit::disable()),
        )
        .route("/v1/admin/devices", get(admin_devices))
        .route("/v1/admin/devices/{id}", delete(admin_revoke))
        .route("/v1/admin/pairings", post(admin_create_pairing))
        .route("/v1/admin/audit", get(admin_audit))
        .route("/v1/admin/owner-key", post(admin_rotate_owner_key))
        .route("/v1/admin/speed", get(admin_speed))
        .route(
            "/v1/admin/clients",
            get(admin_clients).post(admin_create_client),
        )
        .route(
            "/v1/admin/clients/{id}",
            delete(admin_revoke_client).patch(admin_limit_client),
        )
        .route("/v1/admin/oauth/requests", get(admin_oauth_requests))
        .route("/v1/admin/oauth/requests/{id}", post(admin_oauth_decide))
        // OAuth for MCP clients (roadmap R17): sign in with only the URL, approved on the phone.
        .route(
            "/.well-known/oauth-protected-resource",
            get(oauth::protected_resource).options(oauth::preflight),
        )
        .route(
            "/.well-known/oauth-protected-resource/mcp",
            get(oauth::protected_resource).options(oauth::preflight),
        )
        .route(
            "/.well-known/oauth-authorization-server",
            get(oauth::authorization_server).options(oauth::preflight),
        )
        .route(
            "/.well-known/openid-configuration",
            get(oauth::authorization_server).options(oauth::preflight),
        )
        .route(
            "/oauth/register",
            post(oauth::register).options(oauth::preflight),
        )
        .route("/oauth/authorize", get(oauth::authorize))
        .route("/oauth/token", post(oauth::token).options(oauth::preflight))
        .route(
            "/oauth/revoke",
            post(oauth::revoke).options(oauth::preflight),
        )
        .route(
            "/oauth/requests/{id}",
            get(oauth::request_status).post(oauth::decide_in_browser),
        )
        .layer(DefaultBodyLimit::max(256 * 1024))
        .layer(axum::middleware::map_response(private_headers))
        .with_state(state)
}

/// Every answer, pages and APIs alike: a gateway is private, so search engines
/// must not index it, browsers must not frame it, and HTTPS sticks.
async fn private_headers(mut response: Response) -> Response {
    let h = response.headers_mut();
    h.insert(
        "x-robots-tag",
        HeaderValue::from_static("noindex, nofollow, noarchive, nosnippet, noimageindex"),
    );
    h.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    h.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    h.insert(
        header::STRICT_TRANSPORT_SECURITY,
        HeaderValue::from_static("max-age=63072000; includeSubDomains"),
    );
    h.insert(
        "permissions-policy",
        HeaderValue::from_static(
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
        ),
    );
    response
}

async fn file_download(State(state): State<Arc<AppState>>, Path(token): Path<String>) -> Response {
    let Some((name, mime, bytes)) = state.transfers.download(&token) else {
        return error(
            StatusCode::NOT_FOUND,
            "this link has expired or never existed",
        );
    };
    // The name came from the phone: keep only safe characters in the header.
    let safe: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || " ._-()".contains(c) {
                c
            } else {
                '_'
            }
        })
        .collect();
    let mime = if latch_protocol::validate::is_valid_mime(&mime) {
        mime
    } else {
        "application/octet-stream".into()
    };
    (
        [
            (axum::http::header::CONTENT_TYPE, mime),
            (
                axum::http::header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"{safe}\""),
            ),
            (axum::http::header::CACHE_CONTROL, "no-store".into()),
            // A phone file is never a page of this gateway (the owner console lives here).
            (axum::http::header::X_CONTENT_TYPE_OPTIONS, "nosniff".into()),
            (
                axum::http::header::CONTENT_SECURITY_POLICY,
                "sandbox; default-src 'none'".into(),
            ),
        ],
        bytes,
    )
        .into_response()
}

async fn file_upload(
    State(state): State<Arc<AppState>>,
    Path(token): Path<String>,
    body: axum::body::Body,
) -> Response {
    use crate::transfers::TransferError;
    // Only a real, empty upload link reads a body (up to 64 MB) into memory.
    if !state.transfers.upload_open(&token) {
        return error(
            StatusCode::NOT_FOUND,
            "this link has expired, never existed, or was already used",
        );
    }
    let body = match axum::body::to_bytes(body, crate::transfers::MAX_TRANSFER_BYTES).await {
        Ok(bytes) => Vec::from(bytes),
        Err(_) => {
            return error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "the file is larger than 64 MB",
            );
        }
    };
    let size = body.len();
    match state.transfers.put_upload(&token, body) {
        Ok(()) => Json(json!({ "ok": true, "upload_id": token, "size": size })).into_response(),
        Err(TransferError::TooLarge) => error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "the file is larger than 64 MB",
        ),
        Err(TransferError::Full) => error(
            StatusCode::SERVICE_UNAVAILABLE,
            "the gateway holds too many files right now; try again soon",
        ),
        Err(TransferError::AlreadyUploaded) => error(
            StatusCode::CONFLICT,
            "this upload link was already used; ask for a new one",
        ),
        Err(TransferError::Unknown) => error(
            StatusCode::NOT_FOUND,
            "this link has expired or never existed",
        ),
    }
}

const LINK_CHUNK_BYTES: usize = 256 * 1024;

async fn link_upload(
    State(state): State<Arc<AppState>>,
    Path(token): Path<String>,
    headers: HeaderMap,
    body: axum::body::Body,
) -> Response {
    use crate::links::LinkError;
    use futures_util::StreamExt;
    use tokio::io::AsyncWriteExt;
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
    let path = match state.links.begin_upload(&token, declared) {
        Ok(path) => path,
        Err(LinkError::TooLarge) => return too_large(),
        Err(LinkError::Used) => {
            return error(
                StatusCode::CONFLICT,
                "this link was already used; ask for a new one",
            );
        }
        Err(LinkError::Full) => {
            return error(
                StatusCode::SERVICE_UNAVAILABLE,
                "the gateway holds too many files right now; try again in a few minutes",
            );
        }
        Err(LinkError::Unknown) => {
            return error(
                StatusCode::NOT_FOUND,
                "this link has expired or never existed",
            );
        }
        Err(LinkError::Io) => {
            return error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "the gateway could not store the file",
            );
        }
    };
    // Released on every way out but success, including a dropped connection.
    let mut claim = UploadClaim {
        links: &state.links,
        token: &token,
        done: false,
    };
    let failed = |status: StatusCode, message: &str| error(status, message);
    let Ok(mut file) = tokio::fs::File::create(&path).await else {
        return failed(
            StatusCode::INTERNAL_SERVER_ERROR,
            "the gateway could not store the file",
        );
    };
    let mut size = 0u64;
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        let Ok(chunk) = chunk else {
            return failed(StatusCode::BAD_REQUEST, "the upload was interrupted");
        };
        size += chunk.len() as u64;
        if size > state.links.max_bytes {
            return too_large();
        }
        if file.write_all(&chunk).await.is_err() {
            return failed(
                StatusCode::INTERNAL_SERVER_ERROR,
                "the gateway could not store the file",
            );
        }
    }
    if file.flush().await.is_err() || declared.is_some_and(|n| n != size) {
        return failed(StatusCode::BAD_REQUEST, "the upload was incomplete");
    }
    match state.links.finish_upload(&token, size) {
        Ok(()) => {
            claim.done = true;
            Json(json!({ "ok": true })).into_response()
        }
        Err(_) => error(
            StatusCode::CONFLICT,
            "this link was already used; ask for a new one",
        ),
    }
}

/// An upload in progress; dropping it unfinished frees the link and its space.
struct UploadClaim<'a> {
    links: &'a crate::links::Links,
    token: &'a str,
    done: bool,
}

impl Drop for UploadClaim<'_> {
    fn drop(&mut self) {
        if !self.done {
            self.links.abort_upload(self.token);
        }
    }
}

async fn link_download(State(state): State<Arc<AppState>>, Path(token): Path<String>) -> Response {
    use tokio::io::AsyncReadExt;
    let Some((path, size)) = state.links.stored(&token) else {
        return error(
            StatusCode::NOT_FOUND,
            "this link has expired, never existed, or nothing was uploaded to it yet",
        );
    };
    let Ok(file) = tokio::fs::File::open(&path).await else {
        return error(StatusCode::NOT_FOUND, "this link's file is gone");
    };
    let stream = futures_util::stream::unfold(file, |mut file| async move {
        let mut buf = vec![0u8; LINK_CHUNK_BYTES];
        match file.read(&mut buf).await {
            Ok(0) => None,
            Ok(n) => {
                buf.truncate(n);
                Some((Ok::<_, std::io::Error>(Bytes::from(buf)), file))
            }
            Err(e) => Some((Err(e), file)),
        }
    });
    (
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_owned()),
            (header::CONTENT_LENGTH, size.to_string()),
            (header::CACHE_CONTROL, "no-store".to_owned()),
            // Ciphertext only; never a page of this gateway.
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff".to_owned()),
            (
                header::CONTENT_SECURITY_POLICY,
                "sandbox; default-src 'none'".to_owned(),
            ),
        ],
        axum::body::Body::from_stream(stream),
    )
        .into_response()
}

fn error(status: StatusCode, message: &str) -> Response {
    (status, Json(json!({ "error": message }))).into_response()
}

fn unauthorized() -> Response {
    let mut response = error(StatusCode::UNAUTHORIZED, "missing or wrong bearer token");
    response
        .headers_mut()
        .insert(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
    response
}

/// The owner key is `LATCH_ADMIN_TOKEN` until the owner rotates it (ADR-038).
/// After a rotation only the issued key works, for as long as `LATCH_ADMIN_TOKEN`
/// stays the token it was issued under; changing `LATCH_ADMIN_TOKEN` is the way
/// back in, and its first use forgets the rotated key.
fn is_admin(state: &AppState, headers: &HeaderMap) -> bool {
    let Some(token) = secret::bearer(headers.get(header::AUTHORIZATION)) else {
        return false;
    };
    let env = &state.config.admin_token;
    let mut store = state.store();
    match store.owner_key() {
        Some(r) if secret::token_matches_hash(env, &r.admin_token_sha256) => {
            secret::token_matches_hash(token, &r.key_sha256)
        }
        Some(_) => {
            if !secret::secrets_equal(token, env) {
                return false;
            }
            if let Err(e) = store.set_owner_key(None) {
                tracing::error!(error = %e, "could not forget the rotated owner key");
            }
            tracing::info!("LATCH_ADMIN_TOKEN changed: the rotated owner key no longer works");
            true
        }
        None => secret::secrets_equal(token, env),
    }
}

/// Accepts the optional `LATCH_MCP_TOKEN` or any client token created in the console.
/// The AI app a credential belongs to, with the owner's limits for it (R18).
fn mcp_caller(state: &AppState, token: Option<&str>) -> Option<crate::Caller> {
    let token = token?;
    let by_env = state
        .config
        .mcp_token
        .as_deref()
        .is_some_and(|expected| secret::secrets_equal(token, expected));
    if by_env {
        return Some(crate::Caller {
            key: "env".into(),
            apps: None,
        });
    }
    // OAuth access tokens; revoking the app in the owner list ends them at once.
    let app = if token.starts_with("lat_") {
        Some(state.oauth().access_app(token, now_ms())?)
    } else {
        None
    };
    let mut store = state.store();
    let record = match app {
        Some(app) => {
            store.use_client(&app, now_ms()).then_some(())?;
            store.client(&app)?.clone()
        }
        None => store.authenticate_client(token, now_ms())?.clone(),
    };
    Some(crate::Caller {
        key: record.id,
        apps: record.apps,
    })
}

/// What an AI app reads when it is over its budget; the same text on both gateways.
pub fn rate_limited_text(per_minute: u32) -> String {
    format!(
        "rate_limited: this AI app made more than {per_minute} tool calls in a minute. Wait a moment, then continue; the owner can raise the limit with LATCH_CLIENT_CALLS_PER_MINUTE."
    )
}

// ---- The public page: static, no scripts, no forms ----

fn static_file(body: &'static str, content_type: &'static str) -> Response {
    let mut response = body.into_response();
    let h = response.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(
            "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        ),
    );
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

async fn page() -> Response {
    static_file(PAGE_HTML, "text/html; charset=utf-8")
}

async fn page_css() -> Response {
    static_file(PAGE_CSS, "text/css; charset=utf-8")
}

async fn robots_txt() -> Response {
    static_file(ROBOTS_TXT, "text/plain; charset=utf-8")
}

/// Public, so it tells nothing about the owner: not even whether a phone is online.
async fn health() -> Json<Value> {
    Json(json!({
        "status": "ok",
        "version": env!("CARGO_PKG_VERSION"),
        "protocol": latch_protocol::PROTOCOL_VERSION,
    }))
}

// ---- MCP ----

async fn mcp_other() -> Response {
    let mut response = error(
        StatusCode::METHOD_NOT_ALLOWED,
        "this server does not offer a server-to-client stream; use POST",
    );
    response
        .headers_mut()
        .insert(header::ALLOW, HeaderValue::from_static("POST"));
    response
}

async fn mcp_post(State(state): State<Arc<AppState>>, headers: HeaderMap, body: Bytes) -> Response {
    let token = secret::bearer(headers.get(header::AUTHORIZATION)).map(str::to_owned);
    mcp_serve(&state, &headers, token.as_deref(), body).await
}

/// Secret-link form, `/mcp/<client token>`, for MCP clients whose connector
/// settings accept only a URL. The link is a credential: treat it like a password.
async fn mcp_post_link(
    State(state): State<Arc<AppState>>,
    Path(token): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    mcp_serve(&state, &headers, Some(&token), body).await
}

async fn mcp_serve(
    state: &Arc<AppState>,
    headers: &HeaderMap,
    token: Option<&str>,
    body: Bytes,
) -> Response {
    if !state.config.mcp_http {
        return error(
            StatusCode::NOT_FOUND,
            "MCP over HTTP is disabled in stdio mode",
        );
    }
    if let Some(origin) = headers.get(header::ORIGIN) {
        let origin = origin.to_str().unwrap_or_default().trim_end_matches('/');
        if !state.config.allowed_origins.iter().any(|o| o == origin) {
            return error(
                StatusCode::FORBIDDEN,
                "origin not allowed; see LATCH_ALLOWED_ORIGINS",
            );
        }
    }
    let Some(caller) = mcp_caller(state, token) else {
        return oauth::mcp_unauthorized(state, headers, token.is_some());
    };
    if let Some(version) = headers.get("mcp-protocol-version") {
        let version = version.to_str().unwrap_or_default();
        if !mcp::SUPPORTED_VERSIONS.contains(&version) {
            return error(StatusCode::BAD_REQUEST, "unsupported MCP-Protocol-Version");
        }
    }
    let message: Value = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({"jsonrpc": "2.0", "id": null, "error": {"code": -32700, "message": "parse error"}})),
            )
                .into_response();
        }
    };
    // R18: each AI app gets its own budget of tool calls per minute.
    if message["method"] == "tools/call" && !state.count_call(&caller.key, now_ms()) {
        return Json(json!({
            "jsonrpc": "2.0",
            "id": message["id"],
            "result": {
                "content": [{ "type": "text", "text": rate_limited_text(state.calls_per_minute) }],
                "isError": true,
            },
        }))
        .into_response();
    }
    match crate::CALLER
        .scope(caller, mcp::handle(state, message))
        .await
    {
        Some(reply) => Json(reply).into_response(),
        None => StatusCode::ACCEPTED.into_response(),
    }
}

// ---- Pairing (called by phones) ----

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PairRequest {
    code: String,
    platform: String,
    model: String,
}

fn bounded(value: &str, max: usize) -> bool {
    !value.trim().is_empty() && value.chars().count() <= max && !value.chars().any(char::is_control)
}

async fn pair(State(state): State<Arc<AppState>>, Json(request): Json<PairRequest>) -> Response {
    if !bounded(&request.code, 16)
        || !bounded(&request.platform, 16)
        || !bounded(&request.model, 64)
    {
        return error(
            StatusCode::BAD_REQUEST,
            "code, platform, and model are required",
        );
    }
    let now = now_ms();
    let name = match state.pairings().redeem(&request.code, now) {
        Ok(name) => name,
        Err(crate::pairing::PairingError::RateLimited) => {
            return error(
                StatusCode::TOO_MANY_REQUESTS,
                "too many wrong codes; wait a minute",
            );
        }
        Err(_) => {
            return error(
                StatusCode::FORBIDDEN,
                "that pairing code is wrong or has expired",
            );
        }
    };
    let token = secret::new_token("ldt");
    let record = DeviceRecord {
        id: secret::new_id("d"),
        name: name.clone(),
        model: request.model.trim().to_owned(),
        platform: request.platform.trim().to_owned(),
        token_sha256: secret::sha256_hex(&token),
        paired_at_ms: now,
        last_seen_ms: None,
        device_key: None,
    };
    let device_id = record.id.clone();
    // A phone that pairs again (reinstalled app, cleared data) left its old
    // entry behind: drop offline entries with the same name and model.
    let replaced: Vec<String> = state
        .store()
        .devices()
        .iter()
        .filter(|d| same_phone(d, &record) && !state.devices.is_online(&d.id))
        .map(|d| d.id.clone())
        .collect();
    if let Err(e) = state.store().insert(record) {
        tracing::error!(error = %e, "could not persist paired device");
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not save the pairing",
        );
    }
    for old in &replaced {
        if let Err(e) = state.store().remove(old) {
            tracing::warn!(error = %e, "could not remove the earlier pairing");
        }
        state
            .devices
            .kick(old, "replaced by a newer pairing of this phone");
    }
    tracing::info!(device_id, replaced = replaced.len(), "device paired");
    Json(json!({
        "device_id": device_id,
        "name": name,
        "token": token,
        "protocol": latch_protocol::PROTOCOL_VERSION,
        "device_path": "/v1/device",
    }))
    .into_response()
}

/// Two pairings of the same phone: same name, model, and platform. Only
/// offline entries are ever replaced, so a second identical phone that is
/// connected keeps its pairing.
fn same_phone(a: &DeviceRecord, b: &DeviceRecord) -> bool {
    a.name == b.name && a.model == b.model && a.platform == b.platform
}

// ---- Owner console API ----

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CreatePairing {
    name: String,
}

fn gateway_url(state: &AppState, headers: &HeaderMap) -> String {
    if let Some(url) = &state.config.public_url {
        return url.clone();
    }
    let host = headers
        .get("x-forwarded-host")
        .or_else(|| headers.get(header::HOST))
        .and_then(|h| h.to_str().ok())
        .unwrap_or("localhost");
    let proto = headers
        .get("x-forwarded-proto")
        .and_then(|h| h.to_str().ok())
        .unwrap_or("http");
    format!("{proto}://{host}")
}

async fn admin_create_pairing(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(request): Json<CreatePairing>,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    if !bounded(&request.name, 40) {
        return error(StatusCode::BAD_REQUEST, "name must be 1-40 characters");
    }
    match state
        .pairings()
        .create(request.name.trim().to_owned(), now_ms())
    {
        Ok((code, expires_at_ms)) => Json(json!({
            "code": code,
            "expires_at_ms": expires_at_ms,
            "gateway_url": gateway_url(&state, &headers),
        }))
        .into_response(),
        Err(e) => error(StatusCode::TOO_MANY_REQUESTS, &e.to_string()),
    }
}

async fn admin_devices(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    let online = state.devices.online();
    let devices: Vec<Value> = state
        .store()
        .devices()
        .iter()
        .map(|d| {
            let live = online.iter().find(|o| o.device_id == d.id);
            json!({
                "id": d.id,
                "name": d.name,
                "model": d.model,
                "platform": d.platform,
                "paired_at_ms": d.paired_at_ms,
                "last_seen_ms": d.last_seen_ms,
                "connected": live.is_some(),
                "live": live,
            })
        })
        .collect();
    Json(json!({ "devices": devices, "server_time_ms": now_ms() })).into_response()
}

async fn admin_revoke(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    let removed = match state.store().remove(&id) {
        Ok(removed) => removed,
        Err(e) => {
            // Already removed in memory: cut the live connection anyway.
            state.devices.kick(&id, "revoked by the gateway owner");
            tracing::error!(error = %e, "could not persist revocation");
            return error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "revoked for now, but could not save the revocation to disk",
            );
        }
    };
    if !removed {
        return error(StatusCode::NOT_FOUND, "no such device");
    }
    state.devices.kick(&id, "revoked by the gateway owner");
    tracing::info!(device_id = %id, "device revoked");
    StatusCode::NO_CONTENT.into_response()
}

/// Issues a new owner key, shown only in this answer; the key used to ask stops
/// working at once (roadmap R19, ADR-038).
async fn admin_rotate_owner_key(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    let key = secret::new_token("lok");
    let rotated_at_ms = now_ms();
    let record = OwnerKeyRecord {
        key_sha256: secret::sha256_hex(&key),
        admin_token_sha256: secret::sha256_hex(&state.config.admin_token),
        rotated_at_ms,
    };
    if let Err(e) = state.store().set_owner_key(Some(record)) {
        tracing::error!(error = %e, "could not save the new owner key");
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not save the new owner key; the old one still works",
        );
    }
    tracing::info!("owner key rotated");
    let mut response =
        Json(json!({ "owner_key": key, "rotated_at_ms": rotated_at_ms })).into_response();
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// Roadmap R3: the Vercel gateway reports its database round trip here so the
/// app can warn about a far-away database. This gateway keeps its state in its
/// own memory, so there is no round trip to report.
async fn admin_speed(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    Json(json!({ "store_ms": 0, "slow_ms": 150, "slow": false, "region": null })).into_response()
}

async fn admin_audit(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    Json(json!({ "events": state.audit.recent(200) })).into_response()
}

// ---- MCP client credentials ----

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CreateClient {
    name: String,
    /// R18: the only phone apps this AI app may use; absent for no limit.
    #[serde(default)]
    apps: Option<Vec<String>>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ClientLimits {
    /// `null` removes the limit.
    apps: Option<Vec<String>>,
}

/// At most 100 package names, each a valid Android package name.
fn apps_ok(apps: &Option<Vec<String>>) -> bool {
    apps.as_ref().is_none_or(|list| {
        list.len() <= 100
            && list.iter().all(|p| {
                (1..=255).contains(&p.len())
                    && p.bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
            })
    })
}

const APPS_INVALID: &str = "apps must be a list of at most 100 package names (letters, digits, dots, underscores), or null";

/// R18: limits an AI app to some phone apps, or lifts the limit.
async fn admin_limit_client(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    let Ok(limits) = serde_json::from_slice::<ClientLimits>(&body) else {
        return error(StatusCode::BAD_REQUEST, APPS_INVALID);
    };
    if !apps_ok(&limits.apps) {
        return error(StatusCode::BAD_REQUEST, APPS_INVALID);
    }
    let saved = state.store().set_client_apps(&id, limits.apps);
    match saved {
        Ok(true) => StatusCode::NO_CONTENT.into_response(),
        Ok(false) => error(StatusCode::NOT_FOUND, "no such client"),
        Err(e) => {
            tracing::error!(error = %e, "could not persist client limits");
            error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "could not save the limits",
            )
        }
    }
}

async fn admin_clients(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    let clients: Vec<Value> = state
        .store()
        .clients()
        .iter()
        .map(|c| {
            json!({
                "id": c.id,
                "name": c.name,
                "created_at_ms": c.created_at_ms,
                "last_used_ms": c.last_used_ms,
                "kind": c.kind.as_deref().unwrap_or("key"),
                "apps": c.apps,
            })
        })
        .collect();
    Json(json!({ "clients": clients })).into_response()
}

/// AI apps waiting for the owner's approval in the Latch app (OAuth sign-in).
async fn admin_oauth_requests(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    Json(oauth::owner_requests(&state)).into_response()
}

#[derive(Deserialize)]
struct OAuthDecision {
    approve: bool,
}

async fn admin_oauth_decide(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Bytes,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    let Ok(decision) = serde_json::from_slice::<OAuthDecision>(&body) else {
        return error(StatusCode::BAD_REQUEST, "approve must be true or false");
    };
    if oauth::owner_decide(&state, &id, decision.approve) {
        StatusCode::NO_CONTENT.into_response()
    } else {
        error(
            StatusCode::NOT_FOUND,
            "that request expired or was already decided",
        )
    }
}

/// Creates an MCP client token. The token is returned exactly once.
async fn admin_create_client(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(request): Json<CreateClient>,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    if !bounded(&request.name, 40) {
        return error(StatusCode::BAD_REQUEST, "name must be 1-40 characters");
    }
    if !apps_ok(&request.apps) {
        return error(StatusCode::BAD_REQUEST, APPS_INVALID);
    }
    if state.store().clients().len() >= 50 {
        return error(
            StatusCode::TOO_MANY_REQUESTS,
            "revoke unused clients first (limit 50)",
        );
    }
    let token = secret::new_token("lmt");
    let record = ClientRecord {
        id: secret::new_id("m"),
        name: request.name.trim().to_owned(),
        token_sha256: secret::sha256_hex(&token),
        created_at_ms: now_ms(),
        last_used_ms: None,
        kind: None,
        oauth_client_id: None,
        apps: request.apps,
    };
    let id = record.id.clone();
    if let Err(e) = state.store().insert_client(record) {
        tracing::error!(error = %e, "could not persist client");
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not save the client",
        );
    }
    tracing::info!(client_id = %id, "mcp client created");
    Json(json!({
        "id": id,
        "token": token,
        "mcp_url": format!("{}/mcp", gateway_url(&state, &headers)),
    }))
    .into_response()
}

async fn admin_revoke_client(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !is_admin(&state, &headers) {
        return unauthorized();
    }
    match state.store().remove_client(&id) {
        Ok(true) => {
            tracing::info!(client_id = %id, "mcp client revoked");
            StatusCode::NO_CONTENT.into_response()
        }
        Ok(false) => error(StatusCode::NOT_FOUND, "no such client"),
        Err(e) => {
            tracing::error!(error = %e, "could not persist client revocation");
            error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "revoked for now, but could not save the revocation to disk",
            )
        }
    }
}
