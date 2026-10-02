//! A simulated phone that speaks the Latch device protocol.
//!
//! It enforces the same device-side rules a real phone must: capability
//! toggles, session pause and expiry, latest-observation freshness, sensitive
//! fields, and owner approval. Tests drive it through a real gateway.

pub mod screens;

use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use futures_util::{SinkExt, StreamExt};
use latch_protocol::{
    ActionResult, AppList, Capability, CapabilityState, CapabilityStatus, Command, CommandEnvelope,
    DeviceDescriptor, DeviceInfo, DeviceToGateway, ErrorCode, GatewayToDevice, GlobalAction, Hello,
    Observation, Outcome, PROTOCOL_VERSION, ProtocolError, ScreenInfo, Screenshot, SessionInfo,
    Target, validate,
};
use screens::{Effect, Phone, Screen};
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest, http::HeaderValue};

/// 1x1 transparent PNG; the fake device has no real pixels.
const PIXEL_PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Approval {
    /// The simulated owner approves every request.
    Approve,
    /// The simulated owner denies every request.
    Deny,
    /// Nobody answers; the request expires.
    Ignore,
}

#[derive(Debug, thiserror::Error)]
pub enum FakeError {
    #[error("connection failed: {0}")]
    Connect(String),
    #[error("pairing failed: {0}")]
    Pairing(String),
    #[error("protocol violation: {0}")]
    Protocol(String),
}

/// Observable state, shared with tests.
#[derive(Debug, Clone)]
pub struct FakeState {
    pub phone: Phone,
    pub capabilities: Vec<CapabilityState>,
    pub session: SessionInfo,
    pub approval: Approval,
    /// Titles of every approval request shown to the simulated owner.
    pub approval_requests: Vec<String>,
    /// Every command the device executed (not refused).
    pub executed: Vec<String>,
    pub device_id: Option<String>,
    pub revoked: bool,
    latest: Option<(String, Screen, Vec<screens::Node>)>,
    observation_counter: u64,
}

impl FakeState {
    pub fn new(enabled: &[Capability]) -> Self {
        Self {
            phone: Phone::default(),
            capabilities: Capability::ALL
                .iter()
                .map(|&capability| CapabilityState {
                    capability,
                    status: if capability == Capability::DeviceInfo || enabled.contains(&capability)
                    {
                        CapabilityStatus::Enabled
                    } else {
                        CapabilityStatus::Disabled
                    },
                })
                .collect(),
            session: SessionInfo {
                expires_at_ms: now_ms() + 60 * 60 * 1000,
                approve_every_action: false,
                paused: false,
            },
            approval: Approval::Approve,
            approval_requests: Vec::new(),
            executed: Vec::new(),
            device_id: None,
            revoked: false,
            latest: None,
            observation_counter: 0,
        }
    }

    fn enabled(&self, capability: Capability) -> bool {
        self.capabilities
            .iter()
            .any(|c| c.capability == capability && c.status == CapabilityStatus::Enabled)
    }
}

pub type Shared = Arc<Mutex<FakeState>>;

fn lock(state: &Shared) -> std::sync::MutexGuard<'_, FakeState> {
    state.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn err(code: ErrorCode, message: &str) -> ProtocolError {
    ProtocolError::new(code, message)
}

/// Device-side execution of one command. Mirrors what the Android app does.
pub fn handle(
    state: &mut FakeState,
    envelope: &CommandEnvelope,
) -> Result<serde_json::Value, ProtocolError> {
    validate::command(&envelope.command)?;
    if state.session.paused {
        return Err(err(ErrorCode::DeviceUnavailable, "session paused"));
    }
    if state.session.expires_at_ms <= now_ms() {
        return Err(err(ErrorCode::DeviceUnavailable, "session ended"));
    }
    for capability in envelope.command.required_capabilities() {
        if !state.enabled(capability) {
            return Err(err(
                ErrorCode::PermissionMissing,
                "capability is off on the phone",
            ));
        }
    }

    let approval_needed = envelope.confirm.is_some()
        || (state.session.approve_every_action && envelope.command.is_action());
    if approval_needed {
        let title = envelope
            .confirm
            .as_ref()
            .map_or_else(|| envelope.command.name().to_owned(), |c| c.title.clone());
        state.approval_requests.push(title);
        match state.approval {
            Approval::Approve => {}
            Approval::Deny => return Err(err(ErrorCode::UserDenied, "the owner denied it")),
            Approval::Ignore => return Err(err(ErrorCode::ConfirmationExpired, "nobody answered")),
        }
    }

    // Actions must cite the latest observation of the screen that is still showing.
    let resolved = match envelope.command.observation_id() {
        None => None,
        Some(id) => match &state.latest {
            Some((latest_id, screen, nodes))
                if latest_id == id && *screen == state.phone.screen =>
            {
                Some(nodes)
            }
            _ => return Err(err(ErrorCode::StaleObservation, "the screen changed")),
        },
    };

    let target_effect = |target: &Target| -> Result<Effect, ProtocolError> {
        let nodes = resolved.ok_or_else(|| err(ErrorCode::Internal, "no observation"))?;
        let found = match target {
            Target::Element { element } => nodes.iter().find(|n| &n.node.id == element),
            Target::Point { x, y } => nodes
                .iter()
                .filter(|n| n.node.bounds.contains(*x, *y))
                .min_by_key(|n| n.node.bounds.area()),
        };
        let node = found.ok_or_else(|| err(ErrorCode::TargetNotFound, "no such element"))?;
        if node.node.sensitive {
            return Err(err(ErrorCode::SensitiveTarget, "sensitive element"));
        }
        Ok(node.effect.clone())
    };

    let done = |state: &mut FakeState| {
        state.executed.push(envelope.command.name().to_owned());
        serde_json::to_value(ActionResult {
            package: Some(screens::package_of(&state.phone.screen).into()),
        })
        .map_err(|_| err(ErrorCode::Internal, "encode"))
    };

    match &envelope.command {
        Command::DeviceInfo {} => serde_json::to_value(DeviceInfo {
            device: descriptor(),
            screen: screen_info(),
            package: Some(screens::package_of(&state.phone.screen).into()),
            capabilities: state.capabilities.clone(),
            session: state.session,
        })
        .map_err(|_| err(ErrorCode::Internal, "encode")),
        Command::Observe {
            include_screenshot,
            max_nodes,
        } => {
            state.observation_counter += 1;
            let id = format!("o_{}", state.observation_counter);
            let nodes = state.phone.render();
            let truncated = nodes.len() > *max_nodes as usize;
            let ui: Vec<_> = nodes
                .iter()
                .take(*max_nodes as usize)
                .map(|n| n.node.clone())
                .collect();
            let observation = Observation {
                observation_id: id.clone(),
                captured_at_ms: now_ms(),
                package: Some(screens::package_of(&state.phone.screen).into()),
                screen: screen_info(),
                redacted_count: ui.iter().filter(|n| n.sensitive).count() as u32,
                nodes: ui,
                screenshot: include_screenshot.then(|| Screenshot {
                    mime: "image/png".into(),
                    width: 1,
                    height: 1,
                    data_base64: PIXEL_PNG.into(),
                }),
                truncated,
            };
            state.latest = Some((id, state.phone.screen.clone(), nodes));
            state.executed.push("ui.observe".into());
            serde_json::to_value(observation).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::Tap { target, .. } => {
            match target_effect(target)? {
                Effect::Open(screen) => state.phone.open(screen),
                Effect::ToggleWifi => state.phone.wifi_on = !state.phone.wifi_on,
                Effect::SendMessage if !state.phone.draft.is_empty() => {
                    let draft = std::mem::take(&mut state.phone.draft);
                    state.phone.sent_messages.push(draft);
                }
                _ => {}
            }
            state.latest = None;
            done(state)
        }
        Command::TypeText { element, text, .. } => {
            match target_effect(&Target::Element {
                element: element.clone(),
            })? {
                Effect::EditDraft => state.phone.draft = text.clone(),
                Effect::EditUsername => state.phone.username = text.clone(),
                _ => return Err(err(ErrorCode::InvalidRequest, "not editable")),
            }
            state.latest = None;
            done(state)
        }
        Command::Swipe { .. } => {
            if state.phone.screen == Screen::Settings {
                state.phone.settings_scrolled = true;
            }
            state.latest = None;
            done(state)
        }
        Command::Global { action } => {
            match action {
                GlobalAction::Back => state.phone.back(),
                GlobalAction::Home | GlobalAction::Recents => state.phone.home(),
            }
            state.latest = None;
            done(state)
        }
        Command::ListApps {} => serde_json::to_value(AppList {
            apps: screens::apps(),
        })
        .map_err(|_| err(ErrorCode::Internal, "encode")),
        Command::LaunchApp { package } => {
            let screen = screens::APPS
                .iter()
                .find(|(p, _, _)| p == package)
                .map(|(_, _, s)| s.clone())
                .ok_or_else(|| {
                    err(
                        ErrorCode::TargetNotFound,
                        "no launchable app with that package",
                    )
                })?;
            state.phone.home();
            state.phone.open(screen);
            state.latest = None;
            done(state)
        }
    }
}

fn descriptor() -> DeviceDescriptor {
    DeviceDescriptor {
        platform: "fake".into(),
        os_version: "1".into(),
        model: "Latch fake phone".into(),
        app_version: env!("CARGO_PKG_VERSION").into(),
    }
}

fn screen_info() -> ScreenInfo {
    ScreenInfo {
        width: screens::WIDTH,
        height: screens::HEIGHT,
        rotation: 0,
    }
}

/// Minimal HTTP/1.1 request over plain TCP, for local tests and demos only.
/// Returns the status code and body.
pub async fn http(
    base_url: &str,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: Option<&str>,
) -> Result<(u16, String), FakeError> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let io = |e: std::io::Error| FakeError::Connect(e.to_string());
    let host = base_url
        .strip_prefix("http://")
        .ok_or_else(|| FakeError::Connect("the fake device only supports http:// gateways".into()))?
        .trim_end_matches('/');
    let body = body.unwrap_or("");
    let auth = token
        .map(|t| format!("Authorization: Bearer {t}\r\n"))
        .unwrap_or_default();
    let request = format!(
        "{method} {path} HTTP/1.1\r\nHost: {host}\r\n{auth}Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let mut stream = tokio::net::TcpStream::connect(host).await.map_err(io)?;
    stream.write_all(request.as_bytes()).await.map_err(io)?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.map_err(io)?;
    let response = String::from_utf8_lossy(&raw).into_owned();
    let (head, rest) = response
        .split_once("\r\n\r\n")
        .ok_or_else(|| FakeError::Connect("malformed response".into()))?;
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| FakeError::Connect("malformed status line".into()))?;
    let body = if head
        .to_ascii_lowercase()
        .contains("transfer-encoding: chunked")
    {
        dechunk(rest)
    } else {
        rest.to_owned()
    };
    Ok((status, body))
}

fn dechunk(mut s: &str) -> String {
    let mut out = String::new();
    while let Some((size, rest)) = s.split_once("\r\n") {
        let n = usize::from_str_radix(size.trim(), 16).unwrap_or(0);
        if n == 0 || rest.len() < n {
            break;
        }
        out.push_str(&rest[..n]);
        s = rest[n..].trim_start_matches("\r\n");
    }
    out
}

/// Exchanges a pairing code for a device token over plain HTTP.
///
/// Deliberately minimal (no TLS): the fake device is for local tests and demos.
pub async fn pair(base_url: &str, code: &str) -> Result<(String, String), FakeError> {
    let body = serde_json::json!({ "code": code, "platform": "fake", "model": "Latch fake phone" })
        .to_string();
    let (status, body) = http(base_url, "POST", "/v1/pair", None, Some(&body))
        .await
        .map_err(|e| FakeError::Pairing(e.to_string()))?;
    if status != 200 {
        return Err(FakeError::Pairing(format!("HTTP {status}")));
    }
    let json: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| FakeError::Pairing(e.to_string()))?;
    let field = |k: &str| {
        json.get(k)
            .and_then(|v| v.as_str())
            .map(str::to_owned)
            .ok_or_else(|| FakeError::Pairing(format!("response lacks {k}")))
    };
    Ok((field("device_id")?, field("token")?))
}

/// Owner actions a test can trigger while the device is connected.
#[derive(Debug)]
pub enum Control {
    /// Send the current capabilities and session (after changing them in [`FakeState`]).
    PushState,
    /// Press Stop: say bye and disconnect.
    Stop,
}

/// Connects to `ws_url` (e.g. `ws://127.0.0.1:8787/v1/device`) and serves
/// commands until the gateway closes the connection, revokes the device, or a
/// [`Control::Stop`] arrives.
pub async fn run(
    ws_url: &str,
    token: &str,
    state: Shared,
    mut control: tokio::sync::mpsc::Receiver<Control>,
) -> Result<(), FakeError> {
    let mut request = ws_url
        .into_client_request()
        .map_err(|e| FakeError::Connect(e.to_string()))?;
    let auth = HeaderValue::from_str(&format!("Bearer {token}"))
        .map_err(|e| FakeError::Connect(e.to_string()))?;
    request.headers_mut().insert("Authorization", auth);
    let (socket, _) = tokio_tungstenite::connect_async(request)
        .await
        .map_err(|e| FakeError::Connect(e.to_string()))?;
    let (mut sink, mut stream) = socket.split();

    let hello = {
        let s = lock(&state);
        DeviceToGateway::Hello(Hello {
            protocol: PROTOCOL_VERSION.into(),
            device: descriptor(),
            capabilities: s.capabilities.clone(),
            session: s.session,
            device_time_ms: Some(now_ms()),
        })
    };
    send(&mut sink, &hello).await?;

    loop {
        let frame = tokio::select! {
            frame = stream.next() => frame,
            action = control.recv() => {
                match action {
                    Some(Control::PushState) => {
                        let message = {
                            let s = lock(&state);
                            DeviceToGateway::State {
                                capabilities: s.capabilities.clone(),
                                session: s.session,
                                device_time_ms: Some(now_ms()),
                            }
                        };
                        send(&mut sink, &message).await?;
                    }
                    Some(Control::Stop) | None => {
                        send(&mut sink, &DeviceToGateway::Bye { reason: "user_stopped".into() }).await?;
                        let _ = sink.close().await;
                        return Ok(());
                    }
                }
                continue;
            }
        };
        let Some(frame) = frame else { break };
        let frame = frame.map_err(|e| FakeError::Connect(e.to_string()))?;
        let text = match frame {
            Message::Text(text) => text,
            Message::Close(_) => break,
            _ => continue,
        };
        let message: GatewayToDevice = serde_json::from_str(&text)
            .map_err(|e| FakeError::Protocol(format!("bad gateway frame: {e}")))?;
        match message {
            GatewayToDevice::Welcome { device_id, .. } => lock(&state).device_id = Some(device_id),
            GatewayToDevice::Command(envelope) => {
                let outcome = match handle(&mut lock(&state), &envelope) {
                    Ok(data) => Outcome::Ok { data },
                    Err(error) => Outcome::Error { error },
                };
                send(
                    &mut sink,
                    &DeviceToGateway::Result {
                        id: envelope.id,
                        outcome,
                    },
                )
                .await?;
            }
            GatewayToDevice::Cancel { .. } => {}
            GatewayToDevice::Revoked { .. } => {
                lock(&state).revoked = true;
                break;
            }
        }
    }
    Ok(())
}

/// Same as [`run`], over the HTTP long-poll binding (`/v1/device/hello`,
/// `/poll`, `/messages`) that serverless gateways use.
pub async fn run_poll(
    base_url: &str,
    token: &str,
    state: Shared,
    mut control: tokio::sync::mpsc::Receiver<Control>,
) -> Result<(), FakeError> {
    let protocol = |e: serde_json::Error| FakeError::Protocol(e.to_string());
    let hello = {
        let s = lock(&state);
        DeviceToGateway::Hello(Hello {
            protocol: PROTOCOL_VERSION.into(),
            device: descriptor(),
            capabilities: s.capabilities.clone(),
            session: s.session,
            device_time_ms: Some(now_ms()),
        })
    };
    let hello = serde_json::to_string(&hello).map_err(protocol)?;
    let (status, body) = http(
        base_url,
        "POST",
        "/v1/device/hello",
        Some(token),
        Some(&hello),
    )
    .await?;
    if status != 200 {
        return Err(FakeError::Connect(format!(
            "hello failed with HTTP {status}"
        )));
    }
    let connection = match serde_json::from_str::<GatewayToDevice>(&body).map_err(protocol)? {
        GatewayToDevice::Welcome {
            device_id,
            connection: Some(connection),
            ..
        } => {
            lock(&state).device_id = Some(device_id);
            connection
        }
        _ => {
            return Err(FakeError::Protocol(
                "expected welcome with a connection".into(),
            ));
        }
    };
    let poll_path = format!("/v1/device/poll?connection={connection}&wait=5");
    let post_path = format!("/v1/device/messages?connection={connection}");
    let post = |message: DeviceToGateway| {
        let post_path = post_path.clone();
        async move {
            let text = serde_json::to_string(&message).map_err(protocol)?;
            let (status, _) = http(base_url, "POST", &post_path, Some(token), Some(&text)).await?;
            if status == 204 {
                Ok(())
            } else {
                Err(FakeError::Connect(format!(
                    "message rejected with HTTP {status}"
                )))
            }
        }
    };

    loop {
        let polled = tokio::select! {
            polled = http(base_url, "GET", &poll_path, Some(token), None) => polled?,
            action = control.recv() => {
                match action {
                    Some(Control::PushState) => {
                        let message = {
                            let s = lock(&state);
                            DeviceToGateway::State {
                                capabilities: s.capabilities.clone(),
                                session: s.session,
                                device_time_ms: Some(now_ms()),
                            }
                        };
                        post(message).await?;
                    }
                    Some(Control::Stop) | None => {
                        post(DeviceToGateway::Bye { reason: "user_stopped".into() }).await?;
                        return Ok(());
                    }
                }
                continue;
            }
        };
        match polled {
            (204, _) => continue,
            (200, body) => {
                match serde_json::from_str::<GatewayToDevice>(&body).map_err(protocol)? {
                    GatewayToDevice::Command(envelope) => {
                        let outcome = match handle(&mut lock(&state), &envelope) {
                            Ok(data) => Outcome::Ok { data },
                            Err(error) => Outcome::Error { error },
                        };
                        post(DeviceToGateway::Result {
                            id: envelope.id,
                            outcome,
                        })
                        .await?;
                    }
                    GatewayToDevice::Revoked { .. } => {
                        lock(&state).revoked = true;
                        return Ok(());
                    }
                    GatewayToDevice::Cancel { .. } | GatewayToDevice::Welcome { .. } => {}
                }
            }
            (401, _) => {
                lock(&state).revoked = true;
                return Ok(());
            }
            (status, _) => {
                return Err(FakeError::Connect(format!(
                    "poll failed with HTTP {status}"
                )));
            }
        }
    }
}

async fn send<S>(sink: &mut S, message: &DeviceToGateway) -> Result<(), FakeError>
where
    S: SinkExt<Message> + Unpin,
{
    let text = serde_json::to_string(message).map_err(|e| FakeError::Protocol(e.to_string()))?;
    sink.send(Message::Text(text.into()))
        .await
        .map_err(|_| FakeError::Connect("send failed".into()))
}
