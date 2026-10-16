// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The phone-facing WebSocket channel: `GET /v1/device`.
//!
//! Phones always dial out to the gateway, so they work behind NAT, carrier
//! networks, and firewalls without any inbound port on the phone.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::State;
use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use futures_util::{SinkExt, StreamExt};
use latch_protocol::{DeviceToGateway, GatewayToDevice, PROTOCOL_VERSION, validate};
use tokio::sync::mpsc;

use crate::{AppState, now_ms, secret, signing};

const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const PING_EVERY: Duration = Duration::from_secs(20);
/// A phone that sends nothing (not even a pong) for this long is gone.
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub async fn upgrade(
    State(state): State<Arc<AppState>>,
    wifi: Option<axum::Extension<crate::wifi::WifiOrigin>>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let device_id = secret::bearer(headers.get(header::AUTHORIZATION))
        .and_then(|token| state.store().authenticate(token).map(|d| d.id.clone()));
    let Some(device_id) = device_id else {
        return (
            StatusCode::UNAUTHORIZED,
            "unknown or revoked device credential",
        )
            .into_response();
    };
    state
        .devices
        .set_link_base(&device_id, wifi.map(|axum::Extension(o)| o.0));
    ws.max_message_size(validate::MAX_FRAME_BYTES)
        .max_frame_size(validate::MAX_FRAME_BYTES)
        .on_upgrade(move |socket| serve(state, device_id, socket))
}

fn close(code: u16, reason: &'static str) -> Message {
    Message::Close(Some(CloseFrame {
        code,
        reason: reason.into(),
    }))
}

fn encode(message: &GatewayToDevice) -> Option<Message> {
    serde_json::to_string(message)
        .ok()
        .map(|json| Message::Text(json.into()))
}

async fn serve(state: Arc<AppState>, device_id: String, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();

    let registered = state
        .store()
        .get(&device_id)
        .and_then(|d| d.device_key.clone());
    let hello = match tokio::time::timeout(HELLO_TIMEOUT, stream.next()).await {
        Ok(Some(Ok(Message::Text(text)))) => {
            match signing::open_hello(text.as_bytes(), registered.as_deref()) {
                Ok((hello, new_key)) => match validate::hello(&hello) {
                    Ok(()) => {
                        if let Some(key) = new_key
                            && state.store().set_device_key(&device_id, key).is_err()
                        {
                            let _ = sink
                                .send(close(1011, "could not save the device key"))
                                .await;
                            return;
                        }
                        hello
                    }
                    Err(e) => {
                        tracing::warn!(device_id, code = %e.code, "rejected hello");
                        let _ = sink.send(close(4002, "incompatible hello")).await;
                        return;
                    }
                },
                Err(signing::Refused::Malformed) => {
                    let _ = sink.send(close(4001, "expected hello")).await;
                    return;
                }
                Err(refused) => {
                    tracing::warn!(device_id, ?refused, "refused hello");
                    let _ = sink.send(close(4004, refused.message())).await;
                    return;
                }
            }
        }
        _ => {
            let _ = sink.send(close(4001, "expected hello")).await;
            return;
        }
    };

    // Register first so the welcome can name the connection that signed
    // messages are bound to (1.12); commands wait in the channel until the
    // writer starts after the welcome.
    let (tx, mut rx) = mpsc::channel::<GatewayToDevice>(32);
    let platform = hello.device.platform.clone();
    let conn_id = state.devices.register(&device_id, hello, tx);
    let connection = format!("w_{conn_id}");
    let welcome = GatewayToDevice::Welcome {
        protocol: PROTOCOL_VERSION.into(),
        device_id: device_id.clone(),
        server_time_ms: now_ms(),
        connection: Some(connection.clone()),
    };
    let sent = match encode(&welcome) {
        Some(welcome) => sink.send(welcome).await.is_ok(),
        None => false,
    };
    if !sent {
        state.devices.unregister(&device_id, conn_id);
        return;
    }
    // The key this phone signs with, if it has one (just registered or from before).
    let key = state
        .store()
        .get(&device_id)
        .and_then(|d| d.device_key.clone());
    state.store().touch(&device_id, now_ms());
    tracing::info!(device_id, platform, "device connected");

    let writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(PING_EVERY);
        ping.tick().await;
        loop {
            tokio::select! {
                message = rx.recv() => match message {
                    Some(message) => {
                        let revoked = matches!(message, GatewayToDevice::Revoked { .. });
                        let Some(frame) = encode(&message) else { continue };
                        if sink.send(frame).await.is_err() {
                            break;
                        }
                        if revoked {
                            let _ = sink.send(close(4003, "revoked")).await;
                            break;
                        }
                    }
                    // The registry dropped this connection (replaced or revoked).
                    None => {
                        let _ = sink.send(close(1000, "closed by gateway")).await;
                        break;
                    }
                },
                _ = ping.tick() => {
                    if sink.send(Message::Ping(Vec::new().into())).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    loop {
        let frame = match tokio::time::timeout(IDLE_TIMEOUT, stream.next()).await {
            Ok(Some(Ok(frame))) => frame,
            _ => break,
        };
        let text = match frame {
            Message::Text(text) => text,
            Message::Close(_) => break,
            // Pings are answered by the WebSocket layer; pongs only prove liveness.
            Message::Ping(_) | Message::Pong(_) => continue,
            Message::Binary(_) => {
                tracing::warn!(device_id, "binary frame from device; closing");
                break;
            }
        };
        let message = match signing::open_message(text.as_bytes(), key.as_deref(), &connection) {
            Ok(message) => message,
            Err(refused) => {
                // Do not echo the frame: it may contain screen content.
                tracing::warn!(device_id, ?refused, "refused frame from device; closing");
                break;
            }
        };
        match message {
            DeviceToGateway::Result { id, outcome } => {
                state.devices.resolve(&device_id, conn_id, &id, outcome);
            }
            DeviceToGateway::State {
                capabilities,
                session,
                device_time_ms,
            } => {
                state.devices.update_state(
                    &device_id,
                    conn_id,
                    capabilities,
                    session,
                    device_time_ms,
                );
            }
            DeviceToGateway::Bye { reason } => {
                tracing::info!(device_id, reason = %reason.chars().take(32).collect::<String>(), "device said bye");
                break;
            }
            DeviceToGateway::ApprovalRequest(request) => {
                if validate::approval_request(&request).is_ok() {
                    state
                        .devices
                        .approval_requested(&device_id, conn_id, &request.command_id);
                }
            }
            DeviceToGateway::Hello(_) | DeviceToGateway::Signed { .. } => {
                tracing::warn!(device_id, "duplicate hello; closing");
                break;
            }
        }
    }

    state.devices.unregister(&device_id, conn_id);
    state.store().touch(&device_id, now_ms());
    writer.abort();
    tracing::info!(device_id, "device disconnected");
}
