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

use crate::{AppState, now_ms, secret};

const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const PING_EVERY: Duration = Duration::from_secs(20);
/// A phone that sends nothing (not even a pong) for this long is gone.
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub async fn upgrade(
    State(state): State<Arc<AppState>>,
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

    let hello = match tokio::time::timeout(HELLO_TIMEOUT, stream.next()).await {
        Ok(Some(Ok(Message::Text(text)))) => match serde_json::from_str::<DeviceToGateway>(&text) {
            Ok(DeviceToGateway::Hello(hello)) => match validate::hello(&hello) {
                Ok(()) => hello,
                Err(e) => {
                    tracing::warn!(device_id, code = %e.code, "rejected hello");
                    let _ = sink.send(close(4002, "incompatible hello")).await;
                    return;
                }
            },
            _ => {
                let _ = sink.send(close(4001, "expected hello")).await;
                return;
            }
        },
        _ => {
            let _ = sink.send(close(4001, "expected hello")).await;
            return;
        }
    };

    let welcome = GatewayToDevice::Welcome {
        protocol: PROTOCOL_VERSION.into(),
        device_id: device_id.clone(),
        server_time_ms: now_ms(),
    };
    let Some(welcome) = encode(&welcome) else {
        return;
    };
    if sink.send(welcome).await.is_err() {
        return;
    }

    let (tx, mut rx) = mpsc::channel::<GatewayToDevice>(32);
    let platform = hello.device.platform.clone();
    let conn_id = state.devices.register(&device_id, hello, tx);
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
        match serde_json::from_str::<DeviceToGateway>(&text) {
            Ok(DeviceToGateway::Result { id, outcome }) => {
                state.devices.resolve(&device_id, conn_id, &id, outcome);
            }
            Ok(DeviceToGateway::State {
                capabilities,
                session,
                device_time_ms,
            }) => {
                state.devices.update_state(
                    &device_id,
                    conn_id,
                    capabilities,
                    session,
                    device_time_ms,
                );
            }
            Ok(DeviceToGateway::Bye { reason }) => {
                tracing::info!(device_id, reason = %reason.chars().take(32).collect::<String>(), "device said bye");
                break;
            }
            Ok(DeviceToGateway::Hello(_)) => {
                tracing::warn!(device_id, "duplicate hello; closing");
                break;
            }
            Err(_) => {
                // Do not echo the frame: it may contain screen content.
                tracing::warn!(device_id, "unparseable frame from device; closing");
                break;
            }
        }
    }

    state.devices.unregister(&device_id, conn_id);
    state.store().touch(&device_id, now_ms());
    writer.abort();
    tracing::info!(device_id, "device disconnected");
}
