//! HTTP routes.

use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::store::{ClientRecord, DeviceRecord};
use crate::{AppState, device_http, device_ws, mcp, now_ms, secret};

const ADMIN_HTML: &str = include_str!("admin/index.html");
const ADMIN_JS: &str = include_str!("admin/admin.js");
const ADMIN_CSS: &str = include_str!("admin/admin.css");

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/", get(admin_page))
        .route("/admin.js", get(admin_js))
        .route("/admin.css", get(admin_css))
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
        .route("/v1/admin/devices", get(admin_devices))
        .route("/v1/admin/devices/{id}", delete(admin_revoke))
        .route("/v1/admin/pairings", post(admin_create_pairing))
        .route("/v1/admin/audit", get(admin_audit))
        .route(
            "/v1/admin/clients",
            get(admin_clients).post(admin_create_client),
        )
        .route("/v1/admin/clients/{id}", delete(admin_revoke_client))
        .layer(DefaultBodyLimit::max(256 * 1024))
        .with_state(state)
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

fn is_admin(state: &AppState, headers: &HeaderMap) -> bool {
    secret::bearer(headers.get(header::AUTHORIZATION))
        .is_some_and(|t| secret::secrets_equal(t, &state.config.admin_token))
}

/// Accepts the optional `LATCH_MCP_TOKEN` or any client token created in the console.
fn is_mcp_client(state: &AppState, token: Option<&str>) -> bool {
    let Some(token) = token else {
        return false;
    };
    let by_env = state
        .config
        .mcp_token
        .as_deref()
        .is_some_and(|expected| secret::secrets_equal(token, expected));
    let by_client = state.store().authenticate_client(token, now_ms()).is_some();
    by_env || by_client
}

// ---- Static owner console ----

fn with_security_headers(mut response: Response, content_type: &'static str) -> Response {
    let h = response.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        ),
    );
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    h.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

async fn admin_page() -> Response {
    with_security_headers(ADMIN_HTML.into_response(), "text/html; charset=utf-8")
}

async fn admin_js() -> Response {
    with_security_headers(ADMIN_JS.into_response(), "text/javascript; charset=utf-8")
}

async fn admin_css() -> Response {
    with_security_headers(ADMIN_CSS.into_response(), "text/css; charset=utf-8")
}

async fn health(State(state): State<Arc<AppState>>) -> Json<Value> {
    Json(json!({
        "status": "ok",
        "version": env!("CARGO_PKG_VERSION"),
        "protocol": latch_protocol::PROTOCOL_VERSION,
        "devices_connected": state.devices.online().len(),
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
    if !is_mcp_client(state, token) {
        return unauthorized();
    }
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
    match mcp::handle(state, message).await {
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
    };
    let device_id = record.id.clone();
    if let Err(e) = state.store().insert(record) {
        tracing::error!(error = %e, "could not persist paired device");
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "could not save the pairing",
        );
    }
    tracing::info!(device_id, "device paired");
    Json(json!({
        "device_id": device_id,
        "name": name,
        "token": token,
        "protocol": latch_protocol::PROTOCOL_VERSION,
        "device_path": "/v1/device",
    }))
    .into_response()
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
            })
        })
        .collect();
    Json(json!({ "clients": clients })).into_response()
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
