// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The phone-facing HTTP long-poll binding (protocol 1.1).
//!
//! Same messages as the WebSocket channel, carried over plain HTTPS so phones
//! can talk to serverless hosts that cannot keep sockets open:
//!
//! * `POST /v1/device/hello` — body `hello`, answer `welcome` with a `connection` id.
//! * `GET  /v1/device/poll?connection=…` — next gateway message, or 204 after up to 25 s.
//! * `POST /v1/device/messages?connection=…` — body `result`, `state`, or `bye`.
//!
//! 401 means the credential is unknown or revoked; 409 means the connection
//! was replaced or expired and the phone must send `hello` again.

use std::sync::Arc;
use std::time::Duration;

use axum::Json;
use axum::body::Bytes;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use latch_protocol::{DeviceToGateway, GatewayToDevice, PROTOCOL_VERSION, validate};
use serde::Deserialize;
use serde_json::json;

use crate::{AppState, now_ms, secret};

const MAX_POLL_WAIT: Duration = Duration::from_secs(25);

fn error(status: StatusCode, message: &str) -> Response {
    (status, Json(json!({ "error": message }))).into_response()
}

/// Resolves the device a request's bearer token belongs to.
pub(crate) fn authenticate(state: &AppState, headers: &HeaderMap) -> Option<String> {
    secret::bearer(headers.get(header::AUTHORIZATION))
        .and_then(|token| state.store().authenticate(token).map(|d| d.id.clone()))
}

fn unknown_device() -> Response {
    error(
        StatusCode::UNAUTHORIZED,
        "unknown or revoked device credential",
    )
}

fn replaced() -> Response {
    error(
        StatusCode::CONFLICT,
        "connection replaced or expired; send hello again",
    )
}

#[derive(Deserialize)]
pub struct ConnectionQuery {
    connection: String,
    /// Seconds to wait for a message, at most 25.
    #[serde(default)]
    wait: Option<u64>,
}

fn connection_id(query: &ConnectionQuery) -> Option<u64> {
    query.connection.strip_prefix("k_")?.parse().ok()
}

pub async fn hello(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let Some(device_id) = authenticate(&state, &headers) else {
        return unknown_device();
    };
    let hello = match serde_json::from_slice::<DeviceToGateway>(&body) {
        Ok(DeviceToGateway::Hello(hello)) => hello,
        _ => return error(StatusCode::BAD_REQUEST, "expected a hello message"),
    };
    if let Err(e) = validate::hello(&hello) {
        tracing::warn!(device_id, code = %e.code, "rejected hello");
        return error(StatusCode::UNPROCESSABLE_ENTITY, &e.message);
    }
    let platform = hello.device.platform.clone();
    let conn_id = state.devices.register_poll(&device_id, hello);
    state.store().touch(&device_id, now_ms());
    tracing::info!(device_id, platform, transport = "poll", "device connected");
    Json(GatewayToDevice::Welcome {
        protocol: PROTOCOL_VERSION.into(),
        device_id,
        server_time_ms: now_ms(),
        connection: Some(format!("k_{conn_id}")),
    })
    .into_response()
}

pub async fn poll(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<ConnectionQuery>,
) -> Response {
    let Some(device_id) = authenticate(&state, &headers) else {
        return unknown_device();
    };
    let Some(conn_id) = connection_id(&query) else {
        return replaced();
    };
    let Some(queue) = state.devices.poll_queue(&device_id, conn_id) else {
        return replaced();
    };
    let wait = query
        .wait
        .map_or(MAX_POLL_WAIT, Duration::from_secs)
        .min(MAX_POLL_WAIT);
    // Only one poll per connection may wait; a second one gets an immediate retry.
    let Ok(mut rx) = queue.try_lock() else {
        return StatusCode::NO_CONTENT.into_response();
    };
    let message = tokio::time::timeout(wait, rx.recv()).await;
    // Refresh presence after a long wait so the gateway does not expire a live phone.
    state.devices.touch(&device_id, conn_id);
    match message {
        Ok(Some(message)) => Json(message).into_response(),
        // The registry dropped this connection (replaced, expired, or revoked).
        Ok(None) => replaced(),
        Err(_) => StatusCode::NO_CONTENT.into_response(),
    }
}

pub async fn messages(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<ConnectionQuery>,
    body: Bytes,
) -> Response {
    let Some(device_id) = authenticate(&state, &headers) else {
        return unknown_device();
    };
    let Some(conn_id) = connection_id(&query) else {
        return replaced();
    };
    if !state.devices.touch(&device_id, conn_id) {
        return replaced();
    }
    match serde_json::from_slice::<DeviceToGateway>(&body) {
        Ok(DeviceToGateway::Result { id, outcome }) => {
            state.devices.resolve(&device_id, conn_id, &id, outcome);
        }
        Ok(DeviceToGateway::State {
            capabilities,
            session,
            device_time_ms,
        }) => {
            state
                .devices
                .update_state(&device_id, conn_id, capabilities, session, device_time_ms);
        }
        Ok(DeviceToGateway::Bye { .. }) => {
            state.devices.unregister(&device_id, conn_id);
            state.store().touch(&device_id, now_ms());
            tracing::info!(device_id, "device said bye");
        }
        Ok(DeviceToGateway::ApprovalRequest(request)) => {
            if validate::approval_request(&request).is_ok() {
                state
                    .devices
                    .approval_requested(&device_id, conn_id, &request.command_id);
            }
        }
        Ok(DeviceToGateway::Hello(_)) => {
            return error(StatusCode::BAD_REQUEST, "send hello to /v1/device/hello");
        }
        // Do not echo the body: it may contain screen content.
        Err(_) => return error(StatusCode::BAD_REQUEST, "unparseable device message"),
    }
    StatusCode::NO_CONTENT.into_response()
}

/// Public discovery document: lets a phone or installer check that an address
/// is a Latch gateway and which transports it offers.
pub async fn info() -> Response {
    Json(json!({
        "product": "latch-gateway",
        "implementation": "rust",
        "version": env!("CARGO_PKG_VERSION"),
        "protocol": PROTOCOL_VERSION,
        "transports": ["poll", "websocket"],
        "mcp_path": "/mcp",
    }))
    .into_response()
}

/// Upper bound for `/v1/device/messages` bodies (screenshots dominate).
pub const MAX_MESSAGE_BYTES: usize = validate::MAX_FRAME_BYTES;
