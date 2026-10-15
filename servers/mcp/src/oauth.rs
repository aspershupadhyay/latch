// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! OAuth 2.1 for MCP clients (roadmap R17), the same flow as the Vercel
//! gateway (`servers/vercel/src/oauth.ts`), so any MCP client connects with
//! only the server URL: it discovers this server (RFC 9728, RFC 8414),
//! registers itself (RFC 7591), and the owner approves it on the phone in the
//! Latch app. The owner key is never typed into a browser: a page that asks
//! for it is a phishing page. Authorization code + PKCE (S256) only; public
//! clients only.
//!
//! Every approval becomes an AI app in the owner's list, so revoking it there
//! cuts its tokens at once. Tokens are random and stored as hashes.
//! Registrations and refresh tokens survive a restart (the store file);
//! sign-in requests, codes, and access tokens live in memory.
//!
//! Not offered here: client ID metadata documents, which would make this
//! gateway fetch URLs on a client's say-so. Clients fall back to registering.

use std::collections::HashMap;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::store::{ClientRecord, OAuthClient, RefreshRecord};
use crate::{AppState, now_ms, pairing, secret};

pub const SCOPE: &str = "phone";
const REQUEST_TTL_MS: u64 = 10 * 60 * 1000;
const DECIDED_TTL_MS: u64 = 2 * 60 * 1000;
const CODE_TTL_MS: u64 = 5 * 60 * 1000;
const ACCESS_TTL_S: u64 = 3600;
const REFRESH_TTL_MS: u64 = 90 * 24 * 3600 * 1000;
const MAX_REGISTERED: usize = 200;
/// Keeps a flood of registrations from pushing out an app that is signing in right now.
const MAX_REGISTRATIONS_PER_MINUTE: u32 = 30;
const MAX_PENDING: usize = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Status {
    Pending,
    Approved,
    Denied,
}

impl Status {
    fn as_str(self) -> &'static str {
        match self {
            Status::Pending => "pending",
            Status::Approved => "approved",
            Status::Denied => "denied",
        }
    }
}

#[derive(Debug, Clone)]
struct AuthRequest {
    id: String,
    client_id: String,
    client_name: String,
    redirect_uri: String,
    code_challenge: String,
    state: Option<String>,
    /// Short code shown on the consent page and in the app, so the owner approves the right request.
    matching: String,
    created_at_ms: u64,
    expires_at_ms: u64,
    /// This gateway's issuer URL, returned as `iss` (RFC 9207).
    iss: String,
    status: Status,
    /// Where the browser goes next, once decided. Holds a single-use, PKCE-bound code.
    redirect: Option<String>,
}

#[derive(Debug, Clone)]
struct CodeRecord {
    client_id: String,
    redirect_uri: String,
    code_challenge: String,
    app_id: String,
    expires_at_ms: u64,
}

/// Sign-in state that does not need to survive a restart.
#[derive(Debug, Default)]
pub struct OAuth {
    requests: HashMap<String, AuthRequest>,
    /// By SHA-256 of the code.
    codes: HashMap<String, CodeRecord>,
    /// By SHA-256 of the token: the app id and when it expires.
    access: HashMap<String, (String, u64)>,
    /// Registrations in the current minute.
    registrations: (u64, u32),
}

impl OAuth {
    fn sweep(&mut self, now: u64) {
        self.requests.retain(|_, r| r.expires_at_ms > now);
        self.codes.retain(|_, c| c.expires_at_ms > now);
        self.access.retain(|_, (_, exp)| *exp > now);
    }

    /// The approved app an access token belongs to, if the token is live.
    pub fn access_app(&mut self, token: &str, now: u64) -> Option<String> {
        self.sweep(now);
        self.access
            .get(&secret::sha256_hex(token))
            .map(|(app, _)| app.clone())
    }

    fn pending(&mut self, now: u64) -> Vec<AuthRequest> {
        self.sweep(now);
        let mut out: Vec<AuthRequest> = self
            .requests
            .values()
            .filter(|r| r.status == Status::Pending)
            .cloned()
            .collect();
        out.sort_by_key(|r| r.created_at_ms);
        out
    }
}

// ---- Responses ----

fn cors(response: &mut Response) {
    let h = response.headers_mut();
    h.insert(
        header::ACCESS_CONTROL_ALLOW_ORIGIN,
        HeaderValue::from_static("*"),
    );
    h.insert(
        header::ACCESS_CONTROL_ALLOW_HEADERS,
        HeaderValue::from_static("authorization, content-type, mcp-protocol-version"),
    );
    h.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET, POST, OPTIONS"),
    );
}

fn json_response(status: StatusCode, body: Value, cache: &'static str) -> Response {
    let mut response = (status, axum::Json(body)).into_response();
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static(cache));
    cors(&mut response);
    response
}

fn ok_json(status: StatusCode, body: Value) -> Response {
    json_response(status, body, "no-store")
}

fn oauth_error(status: StatusCode, error: &str, description: &str) -> Response {
    ok_json(
        status,
        json!({ "error": error, "error_description": description }),
    )
}

fn location(status: StatusCode, target: &str) -> Response {
    let mut response = status.into_response();
    if let Ok(value) = HeaderValue::from_str(target) {
        response.headers_mut().insert(header::LOCATION, value);
    }
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// Where clients reach this gateway: `LATCH_PUBLIC_URL`, else the request's own host.
pub fn base_url(state: &AppState, headers: &HeaderMap) -> String {
    if let Some(url) = &state.config.public_url {
        return url.trim_end_matches('/').to_owned();
    }
    let get = |name: &str| {
        headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.split(',').next().unwrap_or_default().trim().to_owned())
            .filter(|v| !v.is_empty())
    };
    let host = get("x-forwarded-host")
        .or_else(|| get("host"))
        .filter(|h| {
            h.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-.:[]".contains(&b))
        })
        .unwrap_or_else(|| state.config.bind.to_string());
    let proto = match get("x-forwarded-proto").as_deref() {
        Some("https") => "https",
        _ => "http",
    };
    format!("{proto}://{host}")
}

/// 401 for MCP that tells clients where to sign in (MCP authorization, RFC 9728).
pub fn mcp_unauthorized(state: &AppState, headers: &HeaderMap, had_token: bool) -> Response {
    let metadata = format!(
        "{}/.well-known/oauth-protected-resource",
        base_url(state, headers)
    );
    let challenge = format!(
        "Bearer resource_metadata=\"{metadata}\", scope=\"{SCOPE}\"{}",
        if had_token {
            ", error=\"invalid_token\""
        } else {
            ""
        }
    );
    let mut response = ok_json(
        StatusCode::UNAUTHORIZED,
        json!({
            "error": "unauthorized",
            "error_description": "Sign in: add this server by its URL in your AI app and approve it in the Latch app, or use a key from the Latch app.",
        }),
    );
    if let Ok(value) = HeaderValue::from_str(&challenge) {
        response
            .headers_mut()
            .insert(header::WWW_AUTHENTICATE, value);
    }
    response.headers_mut().insert(
        header::ACCESS_CONTROL_EXPOSE_HEADERS,
        HeaderValue::from_static("www-authenticate"),
    );
    response
}

// ---- Discovery ----

pub async fn protected_resource(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Response {
    let base = base_url(&state, &headers);
    json_response(
        StatusCode::OK,
        json!({
            "resource": format!("{base}/mcp"),
            "authorization_servers": [base],
            "scopes_supported": [SCOPE],
            "bearer_methods_supported": ["header"],
            "resource_name": "Latch phone control",
        }),
        "public, max-age=300",
    )
}

pub async fn authorization_server(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Response {
    let base = base_url(&state, &headers);
    json_response(
        StatusCode::OK,
        json!({
            "issuer": base,
            "authorization_endpoint": format!("{base}/oauth/authorize"),
            "token_endpoint": format!("{base}/oauth/token"),
            "registration_endpoint": format!("{base}/oauth/register"),
            "revocation_endpoint": format!("{base}/oauth/revoke"),
            "scopes_supported": [SCOPE],
            "response_types_supported": ["code"],
            "response_modes_supported": ["query"],
            "grant_types_supported": ["authorization_code", "refresh_token"],
            "code_challenge_methods_supported": ["S256"],
            "token_endpoint_auth_methods_supported": ["none"],
            "revocation_endpoint_auth_methods_supported": ["none"],
            "client_id_metadata_document_supported": false,
            "authorization_response_iss_parameter_supported": true,
        }),
        "public, max-age=300",
    )
}

pub async fn preflight() -> Response {
    let mut response = StatusCode::NO_CONTENT.into_response();
    cors(&mut response);
    response.headers_mut().insert(
        header::ACCESS_CONTROL_MAX_AGE,
        HeaderValue::from_static("86400"),
    );
    response
}

// ---- Registration (RFC 7591) ----

pub async fn register(State(state): State<Arc<AppState>>, body: Bytes) -> Response {
    let Ok(body) = serde_json::from_slice::<Value>(&body) else {
        return oauth_error(
            StatusCode::BAD_REQUEST,
            "invalid_client_metadata",
            "expected a JSON body",
        );
    };
    let uris: Option<Vec<String>> = body["redirect_uris"].as_array().and_then(|a| {
        a.iter()
            .map(|u| {
                u.as_str()
                    .filter(|u| redirect_allowed(u))
                    .map(str::to_owned)
            })
            .collect()
    });
    let Some(uris) = uris.filter(|u| !u.is_empty() && u.len() <= 10) else {
        return oauth_error(
            StatusCode::BAD_REQUEST,
            "invalid_redirect_uri",
            "redirect_uris must be https, a loopback http address, or an app scheme",
        );
    };
    if !matches!(
        body.get("token_endpoint_auth_method"),
        None | Some(Value::Null)
    ) && body["token_endpoint_auth_method"] != "none"
    {
        return oauth_error(
            StatusCode::BAD_REQUEST,
            "invalid_client_metadata",
            "only public clients are supported (token_endpoint_auth_method: none)",
        );
    }
    let now = now_ms();
    {
        let mut oauth = state.oauth();
        let minute = now / 60_000;
        if oauth.registrations.0 != minute {
            oauth.registrations = (minute, 0);
        }
        oauth.registrations.1 += 1;
        if oauth.registrations.1 > MAX_REGISTRATIONS_PER_MINUTE {
            return oauth_error(
                StatusCode::TOO_MANY_REQUESTS,
                "temporarily_unavailable",
                "too many registrations; try again in a minute",
            );
        }
    }
    let client = OAuthClient {
        client_id: secret::new_id("lc"),
        client_name: clean_name(body["client_name"].as_str()),
        redirect_uris: uris,
        created_at_ms: now,
    };
    if state
        .store()
        .register_oauth_client(client.clone(), MAX_REGISTERED)
        .is_err()
    {
        return oauth_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "server_error",
            "could not save the registration",
        );
    }
    ok_json(
        StatusCode::CREATED,
        json!({
            "client_id": client.client_id,
            "client_id_issued_at": client.created_at_ms / 1000,
            "client_name": client.client_name,
            "redirect_uris": client.redirect_uris,
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
            "token_endpoint_auth_method": "none",
        }),
    )
}

// ---- Authorization ----

/// GET /oauth/authorize: validate, then show the consent page.
pub async fn authorize(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let base = base_url(&state, &headers);
    let get = |k: &str| q.get(k).map(String::as_str);
    let client = get("client_id").and_then(|id| state.store().oauth_client(id));
    // Without a trusted redirect target, errors must not be redirected (RFC 6749 §4.1.2.1).
    let Some(client) = client else {
        return page(
            StatusCode::BAD_REQUEST,
            "Unknown app",
            "This app is not registered with this gateway. Try connecting again from the app.",
        );
    };
    let Some(redirect) = match_redirect(&client.redirect_uris, get("redirect_uri").unwrap_or(""))
    else {
        return page(
            StatusCode::BAD_REQUEST,
            "Wrong return address",
            "The app asked to return to an address it did not register.",
        );
    };
    let state_param = get("state").map(str::to_owned);
    let fail = |error: &str, description: &str| {
        location(
            StatusCode::FOUND,
            &with_params(
                &redirect,
                &[
                    ("error", Some(error)),
                    ("error_description", Some(description)),
                    ("state", state_param.as_deref()),
                    ("iss", Some(&base)),
                ],
            ),
        )
    };
    if get("response_type") != Some("code") {
        return fail(
            "unsupported_response_type",
            "only response_type=code is supported",
        );
    }
    let challenge = get("code_challenge").unwrap_or_default();
    if get("code_challenge_method") != Some("S256") || !pkce_value(challenge) {
        return fail(
            "invalid_request",
            "PKCE with code_challenge_method=S256 is required",
        );
    }
    if let Some(resource) = get("resource")
        && !same_resource(resource, &base)
    {
        return fail(
            "invalid_target",
            "this server only issues tokens for its own /mcp endpoint",
        );
    }
    let now = now_ms();
    let request = {
        let mut oauth = state.oauth();
        if oauth.pending(now).len() >= MAX_PENDING {
            return fail(
                "temporarily_unavailable",
                "too many sign-in requests are waiting; try again in a few minutes",
            );
        }
        let request = AuthRequest {
            id: secret::new_token("lar"),
            client_id: client.client_id.clone(),
            client_name: client.client_name.clone(),
            redirect_uri: redirect.clone(),
            code_challenge: challenge.to_owned(),
            state: state_param.clone(),
            matching: pairing::random_code(4),
            created_at_ms: now,
            expires_at_ms: now + REQUEST_TTL_MS,
            iss: base.clone(),
            status: Status::Pending,
            redirect: None,
        };
        oauth.requests.insert(request.id.clone(), request.clone());
        request
    };
    consent_page(&request)
}

fn request_id_ok(id: &str) -> bool {
    id.strip_prefix("lar_")
        .is_some_and(|h| h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit()))
}

/// GET /oauth/requests/<id>: the consent page polls this until the owner decides.
pub async fn request_status(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Response {
    let mut oauth = state.oauth();
    oauth.sweep(now_ms());
    match oauth.requests.get(&id).filter(|_| request_id_ok(&id)) {
        None => ok_json(StatusCode::NOT_FOUND, json!({ "status": "expired" })),
        Some(r) => ok_json(
            StatusCode::OK,
            json!({ "status": r.status.as_str(), "redirect": r.redirect }),
        ),
    }
}

/// POST /oauth/requests/<id>: deny from the browser. Approving happens only in the Latch app.
pub async fn decide_in_browser(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    body: Bytes,
) -> Response {
    let pending = {
        let mut oauth = state.oauth();
        oauth.sweep(now_ms());
        oauth
            .requests
            .get(&id)
            .filter(|r| request_id_ok(&id) && r.status == Status::Pending)
            .cloned()
    };
    let Some(request) = pending else {
        return page(
            StatusCode::GONE,
            "Request expired",
            "Start connecting again from your AI app.",
        );
    };
    let form = parse_params(&String::from_utf8_lossy(&body));
    if form.get("decision").map(String::as_str) != Some("deny") {
        return consent_page(&request);
    }
    match decide(&state, &id, false) {
        Some(redirect) => location(StatusCode::SEE_OTHER, &redirect),
        None => page(
            StatusCode::GONE,
            "Request expired",
            "Start connecting again from your AI app.",
        ),
    }
}

/// Approves or denies a pending request; the browser's next address, or None when it is gone.
fn decide(state: &AppState, id: &str, approve: bool) -> Option<String> {
    let request = {
        let mut oauth = state.oauth();
        oauth.sweep(now_ms());
        oauth
            .requests
            .get(id)
            .filter(|r| r.status == Status::Pending)
            .cloned()?
    };
    let now = now_ms();
    let (status, redirect) = if approve {
        let app = ClientRecord {
            id: secret::new_id("m"),
            name: request.client_name.clone(),
            token_sha256: String::new(),
            created_at_ms: now,
            last_used_ms: None,
            kind: Some("oauth".into()),
            oauth_client_id: Some(request.client_id.clone()),
        };
        let app_id = app.id.clone();
        state.store().insert_client(app).ok()?;
        let code = secret::new_token("lac");
        state.oauth().codes.insert(
            secret::sha256_hex(&code),
            CodeRecord {
                client_id: request.client_id.clone(),
                redirect_uri: request.redirect_uri.clone(),
                code_challenge: request.code_challenge.clone(),
                app_id,
                expires_at_ms: now + CODE_TTL_MS,
            },
        );
        (
            Status::Approved,
            with_params(
                &request.redirect_uri,
                &[
                    ("code", Some(&code)),
                    ("state", request.state.as_deref()),
                    ("iss", Some(&request.iss)),
                ],
            ),
        )
    } else {
        (
            Status::Denied,
            with_params(
                &request.redirect_uri,
                &[
                    ("error", Some("access_denied")),
                    ("error_description", Some("the owner declined")),
                    ("state", request.state.as_deref()),
                    ("iss", Some(&request.iss)),
                ],
            ),
        )
    };
    // Keep the decision briefly so the waiting consent page can pick it up.
    if let Some(r) = state.oauth().requests.get_mut(id) {
        r.status = status;
        r.redirect = Some(redirect.clone());
        r.expires_at_ms = now + DECIDED_TTL_MS;
    }
    Some(redirect)
}

/// Owner API: requests waiting for approval in the app.
pub fn owner_requests(state: &AppState) -> Value {
    let requests: Vec<Value> = state
        .oauth()
        .pending(now_ms())
        .iter()
        .map(|r| {
            json!({
                "id": r.id,
                "client_name": r.client_name,
                "match": r.matching,
                "created_at_ms": r.created_at_ms,
                "return_to": host_of(&r.redirect_uri),
            })
        })
        .collect();
    json!({ "requests": requests })
}

/// Owner API: approve or deny from the app. False when the request no longer waits.
pub fn owner_decide(state: &AppState, id: &str, approve: bool) -> bool {
    decide(state, id, approve).is_some()
}

// ---- Tokens ----

pub async fn token(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let params = read_params(&headers, &body);
    let get = |k: &str| params.get(k).map(String::as_str);
    let now = now_ms();
    match get("grant_type") {
        Some("authorization_code") => {
            let code = get("code").unwrap_or_default();
            let saved = {
                let mut oauth = state.oauth();
                oauth.sweep(now);
                oauth.codes.remove(&secret::sha256_hex(code))
            };
            let Some(saved) = saved.filter(|_| !code.is_empty()) else {
                return oauth_error(
                    StatusCode::BAD_REQUEST,
                    "invalid_grant",
                    "the code is wrong, used, or expired",
                );
            };
            if get("client_id") != Some(saved.client_id.as_str()) {
                return oauth_error(
                    StatusCode::BAD_REQUEST,
                    "invalid_grant",
                    "the code was issued to another client",
                );
            }
            if let Some(redirect) = get("redirect_uri")
                && redirect != saved.redirect_uri
            {
                return oauth_error(
                    StatusCode::BAD_REQUEST,
                    "invalid_grant",
                    "redirect_uri does not match",
                );
            }
            let verifier = get("code_verifier").unwrap_or_default();
            if !verifier_ok(verifier) || s256(verifier) != saved.code_challenge {
                return oauth_error(
                    StatusCode::BAD_REQUEST,
                    "invalid_grant",
                    "PKCE verification failed",
                );
            }
            issue(&state, &saved.client_id, &saved.app_id, now)
        }
        Some("refresh_token") => {
            let token = get("refresh_token").unwrap_or_default();
            let saved = if token.is_empty() {
                None
            } else {
                state
                    .store()
                    .take_refresh(&secret::sha256_hex(token), now)
                    .ok()
                    .flatten()
            };
            let Some(saved) = saved else {
                return oauth_error(
                    StatusCode::BAD_REQUEST,
                    "invalid_grant",
                    "the refresh token is wrong, used, or expired",
                );
            };
            if let Some(client_id) = get("client_id")
                && client_id != saved.client_id
            {
                return oauth_error(
                    StatusCode::BAD_REQUEST,
                    "invalid_grant",
                    "the token was issued to another client",
                );
            }
            issue(&state, &saved.client_id, &saved.app_id, now)
        }
        _ => oauth_error(
            StatusCode::BAD_REQUEST,
            "unsupported_grant_type",
            "use authorization_code or refresh_token",
        ),
    }
}

fn issue(state: &AppState, client_id: &str, app_id: &str, now: u64) -> Response {
    if !state.store().client_exists(app_id) {
        return oauth_error(
            StatusCode::BAD_REQUEST,
            "invalid_grant",
            "access was revoked",
        );
    }
    let access = secret::new_token("lat");
    let refresh = secret::new_token("lrt");
    state.oauth().access.insert(
        secret::sha256_hex(&access),
        (app_id.to_owned(), now + ACCESS_TTL_S * 1000),
    );
    let saved = state.store().put_refresh(
        secret::sha256_hex(&refresh),
        RefreshRecord {
            client_id: client_id.to_owned(),
            app_id: app_id.to_owned(),
            expires_at_ms: now + REFRESH_TTL_MS,
        },
    );
    if saved.is_err() {
        return oauth_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "server_error",
            "could not save the token",
        );
    }
    ok_json(
        StatusCode::OK,
        json!({
            "access_token": access,
            "token_type": "Bearer",
            "expires_in": ACCESS_TTL_S,
            "refresh_token": refresh,
            "scope": SCOPE,
        }),
    )
}

/// RFC 7009: always 200, whether or not the token existed.
pub async fn revoke(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let params = read_params(&headers, &body);
    if let Some(token) = params.get("token").filter(|t| !t.is_empty()) {
        let sha = secret::sha256_hex(token);
        state.oauth().access.remove(&sha);
        let _ = state.store().take_refresh(&sha, now_ms());
    }
    ok_json(StatusCode::OK, json!({}))
}

// ---- Helpers ----

fn s256(verifier: &str) -> String {
    base64url(&Sha256::digest(verifier.as_bytes()))
}

fn base64url(bytes: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |n, (i, b)| n | (u32::from(*b) << (16 - 8 * i)));
        for i in 0..=chunk.len() {
            out.push(A[(n >> (18 - 6 * i) & 63) as usize] as char);
        }
    }
    out
}

fn pkce_value(s: &str) -> bool {
    (43..=128).contains(&s.len())
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn verifier_ok(s: &str) -> bool {
    (43..=128).contains(&s.len())
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-._~".contains(&b))
}

fn clean_name(value: Option<&str>) -> String {
    let name: String = value
        .unwrap_or_default()
        .chars()
        .filter(|c| !c.is_control())
        .collect();
    let name = name.trim();
    if name.is_empty() {
        "An AI app".into()
    } else {
        name.chars().take(40).collect()
    }
}

/// The parts of a redirect URI this gateway looks at.
struct Uri<'a> {
    scheme: String,
    host: String,
    path: &'a str,
    query: &'a str,
}

fn parse_uri(uri: &str) -> Option<Uri<'_>> {
    if uri.len() > 512
        || uri.contains('#')
        || uri.chars().any(|c| c.is_whitespace() || c.is_control())
    {
        return None;
    }
    let (scheme, rest) = uri.split_once(':')?;
    let mut chars = scheme.chars();
    if !chars.next()?.is_ascii_lowercase()
        || !chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "+.-".contains(c))
    {
        return None;
    }
    let (host, after) = match rest.strip_prefix("//") {
        Some(r) => {
            let end = r.find(['/', '?']).unwrap_or(r.len());
            let authority = &r[..end];
            if authority.contains('@') {
                return None;
            }
            let host = if authority.starts_with('[') {
                &authority[..authority.find(']')? + 1]
            } else {
                authority.split(':').next().unwrap_or_default()
            };
            (host.to_ascii_lowercase(), &r[end..])
        }
        None => (String::new(), rest),
    };
    let (path, query) = after.split_once('?').unwrap_or((after, ""));
    Some(Uri {
        scheme: scheme.to_owned(),
        host,
        path: if path.is_empty() { "/" } else { path },
        query,
    })
}

const LOOPBACK: [&str; 3] = ["localhost", "127.0.0.1", "[::1]"];
const BLOCKED_SCHEMES: [&str; 6] = ["javascript", "data", "file", "vbscript", "blob", "about"];

/// https anywhere, http only on loopback (RFC 8252), or a native app's own scheme. Never fragments.
pub fn redirect_allowed(uri: &str) -> bool {
    let Some(u) = parse_uri(uri) else {
        return false;
    };
    match u.scheme.as_str() {
        s if BLOCKED_SCHEMES.contains(&s) => false,
        "https" => !u.host.is_empty(),
        "http" => LOOPBACK.contains(&u.host.as_str()),
        _ => true,
    }
}

/// Exact match, except that loopback redirects may use any port (RFC 8252 §7.3).
pub fn match_redirect(registered: &[String], requested: &str) -> Option<String> {
    if registered.iter().any(|r| r == requested) {
        return Some(requested.to_owned());
    }
    let want = parse_uri(requested)?;
    if want.scheme != "http" || !LOOPBACK.contains(&want.host.as_str()) {
        return None;
    }
    registered
        .iter()
        .filter_map(|r| parse_uri(r))
        .any(|have| {
            have.scheme == "http"
                && have.host == want.host
                && have.path == want.path
                && have.query == want.query
        })
        .then(|| requested.to_owned())
}

fn same_resource(resource: &str, base: &str) -> bool {
    let resource = resource.trim_end_matches('/');
    resource == format!("{base}/mcp") || resource == base
}

fn host_of(uri: &str) -> String {
    match parse_uri(uri) {
        Some(u) if !u.host.is_empty() => {
            // The host with its port, as the browser shows it.
            let rest = uri.split_once("//").map_or("", |(_, r)| r);
            rest[..rest.find(['/', '?']).unwrap_or(rest.len())].to_owned()
        }
        Some(u) => u.scheme,
        None => "an app".into(),
    }
}

fn percent_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn percent_decode(s: &str) -> String {
    fn hex(b: u8) -> Option<u8> {
        (b as char).to_digit(16).map(|d| d as u8)
    }
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => out.push(b' '),
            b'%' => match (
                bytes.get(i + 1).and_then(|b| hex(*b)),
                bytes.get(i + 2).and_then(|b| hex(*b)),
            ) {
                (Some(hi), Some(lo)) => {
                    out.push(hi << 4 | lo);
                    i += 2;
                }
                _ => out.push(b'%'),
            },
            b => out.push(b),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// `application/x-www-form-urlencoded` pairs; the first value of a key wins.
fn parse_params(text: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for pair in text.split('&').filter(|p| !p.is_empty()) {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        out.entry(percent_decode(k))
            .or_insert_with(|| percent_decode(v));
    }
    out
}

/// Token and revocation requests: form encoded, or JSON from some clients.
fn read_params(headers: &HeaderMap, body: &[u8]) -> HashMap<String, String> {
    let json = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.contains("application/json"));
    if json {
        return serde_json::from_slice::<serde_json::Map<String, Value>>(body)
            .map(|m| {
                m.into_iter()
                    .filter_map(|(k, v)| v.as_str().map(|s| (k, s.to_owned())))
                    .collect()
            })
            .unwrap_or_default();
    }
    parse_params(&String::from_utf8_lossy(body))
}

fn with_params(target: &str, params: &[(&str, Option<&str>)]) -> String {
    let mut out = target.to_owned();
    let mut sep = if target.contains('?') { '&' } else { '?' };
    for (k, v) in params {
        if let Some(v) = v {
            out.push(sep);
            out.push_str(k);
            out.push('=');
            out.push_str(&percent_encode(v));
            sep = '&';
        }
    }
    out
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

const STYLE: &str = r#"
:root{color-scheme:dark light;--bg:#09090b;--card:#131316;--line:#2a2a2f;--text:#fafafa;--muted:#a1a1aa;--accent:#34d399;--ink:#fafafa;--on-ink:#09090b;--danger:#f87171}
@media (prefers-color-scheme:light){:root{--bg:#f7f7f5;--card:#fff;--line:#e4e4e2;--text:#0a0a0b;--muted:#5b5b63;--accent:#047857;--ink:#0a0a0b;--on-ink:#fafafa;--danger:#dc2626}}
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:16px}
main{width:100%;max-width:420px;background:var(--card);border:1px solid var(--line);border-radius:24px;padding:28px}
.mark{width:44px;height:44px;border-radius:14px;display:grid;place-items:center;background:color-mix(in srgb,var(--accent) 16%,transparent);color:var(--accent);margin-bottom:16px}
h1{font-size:22px;line-height:1.3;margin:0 0 6px}p{margin:0 0 14px;color:var(--muted)}
.code{display:flex;gap:8px;margin:18px 0}.code span{flex:1;text-align:center;font:600 26px/1 ui-monospace,Menlo,monospace;padding:14px 0;border-radius:14px;background:var(--bg);border:1px solid var(--line)}
.wait{display:flex;align-items:center;gap:10px;color:var(--muted);font-size:14px;margin:6px 0 18px}.dot{width:8px;height:8px;border-radius:50%;background:var(--accent);animation:p 1.4s infinite}@keyframes p{50%{opacity:.25}}
@media (prefers-reduced-motion:reduce){.dot{animation:none}}
button{width:100%;padding:13px;border-radius:14px;border:0;font:600 15px/1 inherit;cursor:pointer}.ghost{background:transparent;color:var(--danger);margin-top:8px}
.foot{font-size:12px;margin:16px 0 0}"#;

const SHIELD: &str = r#"<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 3v5c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/></svg>"#;

fn html(status: StatusCode, body: &str, nonce: Option<&str>) -> Response {
    let script = nonce.map_or("'none'".to_owned(), |n| format!("'nonce-{n}'"));
    let text = format!(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"referrer\" content=\"no-referrer\"><meta name=\"robots\" content=\"noindex, nofollow\"><title>Latch</title><style>{STYLE}</style></head><body><main>{body}</main></body></html>"
    );
    let mut response = (status, text).into_response();
    let h = response.headers_mut();
    h.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/html; charset=utf-8"),
    );
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    if let Ok(csp) = HeaderValue::from_str(&format!(
        "default-src 'none'; style-src 'unsafe-inline'; script-src {script}; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
    )) {
        h.insert(header::CONTENT_SECURITY_POLICY, csp);
    }
    response
}

fn page(status: StatusCode, title: &str, text: &str) -> Response {
    html(
        status,
        &format!(
            "<div class=\"mark\">{SHIELD}</div><h1>{}</h1><p>{}</p>",
            esc(title),
            esc(text)
        ),
        None,
    )
}

fn consent_page(request: &AuthRequest) -> Response {
    let nonce = secret::hex(&secret::random_bytes::<16>());
    let code: String = request
        .matching
        .chars()
        .map(|c| format!("<span>{}</span>", esc(&c.to_string())))
        .collect();
    let action = format!("/oauth/requests/{}", request.id);
    let action_json = serde_json::to_string(&action).unwrap_or_default();
    html(
        StatusCode::OK,
        &format!(
            r#"
<div class="mark">{SHIELD}</div>
<h1>{name} wants to use your phone</h1>
<p>Through your Latch gateway, only within what you allow in the app. It returns to <b>{host}</b>.</p>
<p style="margin:0;color:var(--text);font-weight:600">Approve in the Latch app</p>
<p style="margin:0">Open Latch → Connect. Check the code matches:</p>
<div class="code" aria-label="Match code">{code}</div>
<div class="wait" role="status"><span class="dot"></span><span id="s">Waiting for your approval…</span></div>
<form method="post" action="{action}"><button class="ghost" name="decision" value="deny">Deny</button></form>
<p class="foot">Not you? Deny. Nothing is shared until you approve in the app. Latch never asks for your owner key in a browser.</p>
<script nonce="{nonce}">
(async function poll(){{try{{const r=await fetch({action_json},{{cache:"no-store"}});const j=await r.json();
if(j.redirect){{document.getElementById("s").textContent=j.status==="approved"?"Approved. Returning to the app…":"Declined.";location.replace(j.redirect);return}}
if(j.status==="expired"){{document.getElementById("s").textContent="This request expired. Start again from your AI app.";return}}}}catch(e){{}}
setTimeout(poll,1500)}})();
</script>"#,
            name = esc(&request.client_name),
            host = esc(&host_of(&request.redirect_uri)),
        ),
        Some(&nonce),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn s256_matches_rfc_7636_appendix_b() {
        assert_eq!(
            s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
        assert_eq!(base64url(b"f"), "Zg");
        assert_eq!(base64url(b"fo"), "Zm8");
        assert_eq!(base64url(b"foo"), "Zm9v");
    }

    #[test]
    fn redirects_follow_the_vercel_gateway_rules() {
        for ok in [
            "https://app.example/cb",
            "http://127.0.0.1:53682/callback",
            "http://localhost/cb",
            "http://[::1]:8080/cb",
            "cursor://anysphere.cursor-retrieval/oauth",
        ] {
            assert!(redirect_allowed(ok), "{ok}");
        }
        for bad in [
            "http://evil.example/cb",
            "https://app.example/cb#frag",
            "javascript:alert(1)",
            "data:text/html,x",
            "https://user@app.example/cb",
            "not a url",
            "HTTPS://app.example/cb",
        ] {
            assert!(!redirect_allowed(bad), "{bad}");
        }
        let registered = vec!["http://127.0.0.1:1/callback".to_owned()];
        assert!(match_redirect(&registered, "http://127.0.0.1:53682/callback").is_some());
        assert!(match_redirect(&registered, "http://127.0.0.1:53682/other").is_none());
        assert!(match_redirect(&registered, "http://localhost:53682/callback").is_none());
        let https = vec!["https://app.example/cb".to_owned()];
        assert!(match_redirect(&https, "https://app.example/cb").is_some());
        assert!(match_redirect(&https, "https://app.example:444/cb").is_none());
    }

    #[test]
    fn params_round_trip() {
        let url = with_params(
            "https://app.example/cb?x=1",
            &[
                ("code", Some("a b&c")),
                ("state", None),
                ("iss", Some("http://h")),
            ],
        );
        assert_eq!(
            url,
            "https://app.example/cb?x=1&code=a%20b%26c&iss=http%3A%2F%2Fh"
        );
        let back = parse_params(url.split_once('?').map(|(_, q)| q).unwrap_or_default());
        assert_eq!(back["code"], "a b&c");
        assert_eq!(back["iss"], "http://h");
        assert_eq!(parse_params("a=%zz&b=+")["a"], "%zz");
        assert_eq!(
            host_of("http://127.0.0.1:53682/callback"),
            "127.0.0.1:53682"
        );
        assert_eq!(host_of("cursor://x/y"), "x");
    }
}
