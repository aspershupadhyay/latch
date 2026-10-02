//! End-to-end: real gateway on a TCP port, the fake phone over WebSocket, and
//! MCP requests exactly as a client would send them.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use latch_fake_device::{Approval, Control, FakeState, Shared};
use latch_gateway::{AppState, Config, router, store::Store};
use latch_protocol::Capability;
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

const ADMIN: &str = "admin-token-0123456789abcdef0123456789";
const MCP: &str = "mcp-token-0123456789abcdef0123456789abc";

struct Gateway {
    addr: std::net::SocketAddr,
    #[allow(dead_code)]
    state: Arc<AppState>,
}

async fn start_gateway() -> Gateway {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    let config = Config {
        bind: addr,
        data_dir: std::path::PathBuf::new(),
        admin_token: ADMIN.into(),
        mcp_token: Some(MCP.into()),
        mcp_http: true,
        public_url: None,
        allowed_origins: vec!["https://allowed.example".into()],
    };
    let mut state = AppState::new(config, Store::ephemeral());
    state.settle_ms = 0;
    let state = Arc::new(state);
    let app = router(state.clone());
    tokio::spawn(async move { axum::serve(listener, app).await });
    Gateway { addr, state }
}

/// Minimal HTTP/1.1 client: enough for JSON requests in tests.
async fn http(
    gw: &Gateway,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: Option<Value>,
    extra: &[(&str, &str)],
) -> (u16, Value) {
    let body = body.map(|b| b.to_string()).unwrap_or_default();
    let mut request = format!(
        "{method} {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: {}\r\n",
        gw.addr,
        body.len()
    );
    if let Some(t) = token {
        request.push_str(&format!("Authorization: Bearer {t}\r\n"));
    }
    for (k, v) in extra {
        request.push_str(&format!("{k}: {v}\r\n"));
    }
    request.push_str("\r\n");
    request.push_str(&body);
    let mut stream = tokio::net::TcpStream::connect(gw.addr)
        .await
        .expect("connect");
    stream.write_all(request.as_bytes()).await.expect("write");
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.expect("read");
    let text = String::from_utf8_lossy(&raw).into_owned();
    let (head, rest) = text.split_once("\r\n\r\n").expect("http response");
    let status: u16 = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .expect("status");
    let chunked = head
        .to_ascii_lowercase()
        .contains("transfer-encoding: chunked");
    let payload = if chunked {
        dechunk(rest)
    } else {
        rest.to_owned()
    };
    (
        status,
        serde_json::from_str(&payload).unwrap_or(Value::Null),
    )
}

fn dechunk(mut s: &str) -> String {
    let mut out = String::new();
    while let Some((size, rest)) = s.split_once("\r\n") {
        let n = usize::from_str_radix(size.trim(), 16).unwrap_or(0);
        if n == 0 {
            break;
        }
        out.push_str(&rest[..n]);
        s = &rest[n + 2..];
    }
    out
}

async fn rpc(gw: &Gateway, method: &str, params: Value) -> Value {
    let (status, body) = http(
        gw,
        "POST",
        "/mcp",
        Some(MCP),
        Some(json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params})),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{body}");
    body
}

/// Calls a tool and returns (is_error, joined text, image count).
async fn call(gw: &Gateway, tool: &str, args: Value) -> (bool, String, usize) {
    let body = rpc(gw, "tools/call", json!({"name": tool, "arguments": args})).await;
    let result = &body["result"];
    let content = result["content"].as_array().cloned().unwrap_or_default();
    let text = content
        .iter()
        .filter_map(|c| c["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    let images = content.iter().filter(|c| c["type"] == "image").count();
    (result["isError"].as_bool().unwrap_or(false), text, images)
}

fn observation_id(text: &str) -> String {
    text.lines()
        .rev()
        .find_map(|l| l.strip_prefix("observation_id: "))
        .and_then(|l| l.split_whitespace().next())
        .expect("observation id in text")
        .to_owned()
}

/// Finds the element id on the line whose quoted text matches exactly.
fn element(text: &str, label: &str) -> String {
    let needle = format!("\"{label}\"");
    let line = text
        .lines()
        .rev()
        .find(|l| l.contains(&needle))
        .unwrap_or_else(|| panic!("{label} not in:\n{text}"));
    let start = line.find('[').expect("[") + 1;
    let end = line.find(']').expect("]");
    line[start..end].to_owned()
}

struct Phone {
    state: Shared,
    control: mpsc::Sender<Control>,
    task: tokio::task::JoinHandle<Result<(), latch_fake_device::FakeError>>,
    token: String,
}

#[derive(Clone, Copy, PartialEq)]
enum Transport {
    WebSocket,
    Poll,
}

async fn connect_phone(gw: &Gateway, enabled: &[Capability]) -> Phone {
    connect_phone_with(gw, enabled, Transport::WebSocket).await
}

async fn connect_phone_with(gw: &Gateway, enabled: &[Capability], transport: Transport) -> Phone {
    let (status, body) = http(
        gw,
        "POST",
        "/v1/admin/pairings",
        Some(ADMIN),
        Some(json!({"name": "Test phone"})),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{body}");
    let code = body["code"].as_str().expect("code").to_owned();
    let (_, token) = latch_fake_device::pair(&format!("http://{}", gw.addr), &code)
        .await
        .expect("pair");
    let state: Shared = Arc::new(Mutex::new(FakeState::new(enabled)));
    let (control, rx) = mpsc::channel(4);
    let task = {
        let state = state.clone();
        let token = token.clone();
        let addr = gw.addr;
        tokio::spawn(async move {
            match transport {
                Transport::WebSocket => {
                    let url = format!("ws://{addr}/v1/device");
                    latch_fake_device::run(&url, &token, state, rx).await
                }
                Transport::Poll => {
                    let base = format!("http://{addr}");
                    latch_fake_device::run_poll(&base, &token, state, rx).await
                }
            }
        })
    };
    for _ in 0..100 {
        if state.lock().expect("lock").device_id.is_some() {
            return Phone {
                state,
                control,
                task,
                token,
            };
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("fake phone did not connect");
}

#[tokio::test]
async fn mcp_handshake_and_discovery() {
    let gw = start_gateway().await;
    let init = rpc(&gw, "initialize", json!({"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})).await;
    assert_eq!(init["result"]["protocolVersion"], "2025-06-18");
    assert!(init["result"]["capabilities"]["tools"].is_object());

    let (status, _) = http(
        &gw,
        "POST",
        "/mcp",
        Some(MCP),
        Some(json!({"jsonrpc": "2.0", "method": "notifications/initialized"})),
        &[],
    )
    .await;
    assert_eq!(status, 202);

    let tools = rpc(&gw, "tools/list", json!({})).await;
    let names: Vec<&str> = tools["result"]["tools"]
        .as_array()
        .expect("tools")
        .iter()
        .filter_map(|t| t["name"].as_str())
        .collect();
    assert_eq!(
        names,
        [
            "list_devices",
            "observe",
            "tap",
            "type_text",
            "scroll_to",
            "wait_for",
            "scroll",
            "swipe",
            "press",
            "list_apps",
            "launch_app"
        ]
    );
    for forbidden in ["shell", "exec", "install", "read_file"] {
        assert!(!names.iter().any(|n| n.contains(forbidden)));
    }

    let unknown = rpc(&gw, "tools/call", json!({"name": "shell", "arguments": {}})).await;
    assert_eq!(unknown["error"]["code"], -32602);
    let (is_error, text, _) = call(&gw, "observe", json!({})).await;
    assert!(is_error && text.contains("device_unavailable"), "{text}");
}

#[tokio::test]
async fn http_boundary_rejects_bad_credentials_and_origins() {
    let gw = start_gateway().await;
    let ping = json!({"jsonrpc": "2.0", "id": 1, "method": "ping"});
    assert_eq!(
        http(&gw, "POST", "/mcp", None, Some(ping.clone()), &[])
            .await
            .0,
        401
    );
    assert_eq!(
        http(
            &gw,
            "POST",
            "/mcp",
            Some("wrong-token"),
            Some(ping.clone()),
            &[]
        )
        .await
        .0,
        401
    );
    assert_eq!(
        http(&gw, "POST", "/mcp", Some(ADMIN), Some(ping.clone()), &[])
            .await
            .0,
        401
    );
    assert_eq!(
        http(
            &gw,
            "POST",
            "/mcp",
            Some(MCP),
            Some(ping.clone()),
            &[("Origin", "https://evil.example")]
        )
        .await
        .0,
        403
    );
    assert_eq!(
        http(
            &gw,
            "POST",
            "/mcp",
            Some(MCP),
            Some(ping.clone()),
            &[("Origin", "https://allowed.example")]
        )
        .await
        .0,
        200
    );
    assert_eq!(
        http(
            &gw,
            "POST",
            "/mcp",
            Some(MCP),
            Some(ping.clone()),
            &[("MCP-Protocol-Version", "1999-01-01")]
        )
        .await
        .0,
        400
    );
    assert_eq!(http(&gw, "GET", "/mcp", Some(MCP), None, &[]).await.0, 405);
    assert_eq!(
        http(&gw, "GET", "/v1/admin/devices", Some(MCP), None, &[])
            .await
            .0,
        401
    );
    assert_eq!(
        http(
            &gw,
            "POST",
            "/v1/pair",
            None,
            Some(json!({"code": "AAAA-AAAA", "platform": "x", "model": "y"})),
            &[]
        )
        .await
        .0,
        403
    );
    // A device token cannot be invented.
    let mut request =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(format!(
            "ws://{}/v1/device",
            gw.addr
        ))
        .expect("req");
    request.headers_mut().insert(
        "Authorization",
        "Bearer ldt_forged".parse().expect("header"),
    );
    assert!(tokio_tungstenite::connect_async(request).await.is_err());
    let (status, health) = http(&gw, "GET", "/healthz", None, None, &[]).await;
    assert_eq!(status, 200);
    assert_eq!(health["devices_connected"], 0);
}

#[tokio::test]
async fn agent_navigates_settings_and_toggles_wifi() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    let (_, devices, _) = call(&gw, "list_devices", json!({})).await;
    assert!(devices.contains("connected"), "{devices}");

    let (is_error, screen, images) = call(&gw, "observe", json!({})).await;
    assert!(!is_error, "{screen}");
    assert_eq!(
        images, 1,
        "screenshot expected when screen.capture is enabled"
    );
    assert!(screen.contains("Untrusted screen content"));

    let (is_error, screen, _) = call(&gw, "tap", json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Settings")})).await;
    assert!(!is_error, "{screen}");
    assert!(screen.contains("app com.android.settings"), "{screen}");

    let (_, screen, _) = call(&gw, "tap", json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Network & internet")})).await;
    assert!(screen.contains("\"Wi-Fi\""), "{screen}");
    assert!(screen.contains("checked"), "{screen}");

    let (is_error, screen, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Wi-Fi")}),
    )
    .await;
    assert!(!is_error, "{screen}");
    assert!(screen.contains("unchecked"), "{screen}");
    assert!(!phone.state.lock().expect("lock").phone.wifi_on);

    let (_, screen, _) = call(&gw, "press", json!({"button": "back"})).await;
    let (is_error, screen, _) = call(
        &gw,
        "scroll",
        json!({"observation_id": observation_id(&screen), "direction": "down"}),
    )
    .await;
    assert!(!is_error && screen.contains("About phone"), "{screen}");

    let (status, audit) = http(&gw, "GET", "/v1/admin/audit", Some(ADMIN), None, &[]).await;
    assert_eq!(status, 200);
    let audit_text = audit.to_string();
    assert!(audit_text.contains("input.tap"));
    assert!(
        !audit_text.contains("Wi-Fi"),
        "audit must not contain screen text"
    );
    phone.task.abort();
}

#[tokio::test]
async fn sending_a_message_waits_for_the_owner() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    let (is_error, apps, _) = call(&gw, "list_apps", json!({"query": " CHAT "})).await;
    assert!(!is_error, "{apps}");
    assert!(apps.starts_with("1 of 3 launchable apps on "), "{apps}");
    assert!(
        apps.contains("match \"chat\"") && apps.contains("org.latch.demo.chat"),
        "{apps}"
    );
    assert!(!apps.contains("com.android.settings"), "{apps}");

    let (_, screen, _) = call(&gw, "launch_app", json!({"package": "org.latch.demo.chat"})).await;
    let (_, screen, _) = call(&gw, "type_text", json!({"observation_id": observation_id(&screen), "element_id": "n1", "text": "hello from the agent"})).await;
    assert!(screen.contains("hello from the agent"), "{screen}");

    // Owner denies first.
    phone.state.lock().expect("lock").approval = Approval::Deny;
    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Send")}),
    )
    .await;
    assert!(is_error && text.contains("user_denied"), "{text}");
    assert!(
        phone
            .state
            .lock()
            .expect("lock")
            .phone
            .sent_messages
            .is_empty()
    );

    // The denied observation is spent; observe again and the owner approves.
    let (_, screen, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    phone.state.lock().expect("lock").approval = Approval::Approve;
    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Send")}),
    )
    .await;
    assert!(!is_error, "{text}");

    let s = phone.state.lock().expect("lock");
    assert_eq!(s.phone.sent_messages, ["hello from the agent"]);
    assert_eq!(
        s.approval_requests.last().map(String::as_str),
        Some("Tap “Send” in org.latch.demo.chat")
    );
    // Harmless steps did not ask the owner.
    assert_eq!(s.approval_requests.len(), 2);
    drop(s);
    phone.task.abort();
}

#[tokio::test]
async fn unanswered_approval_expires() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    phone.state.lock().expect("lock").approval = Approval::Ignore;
    let (_, screen, _) = call(&gw, "launch_app", json!({"package": "org.latch.demo.chat"})).await;
    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Send")}),
    )
    .await;
    assert!(is_error && text.contains("confirmation_expired"), "{text}");
    phone.task.abort();
}

#[tokio::test]
async fn stale_observations_and_sensitive_fields_are_refused() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    let (_, first, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    let old = observation_id(&first);
    let (_, screen, _) = call(
        &gw,
        "launch_app",
        json!({"package": "org.latch.demo.login"}),
    )
    .await;
    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": old, "element_id": "n1"}),
    )
    .await;
    assert!(is_error && text.contains("stale_observation"), "{text}");

    let (_, screen2, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    assert!(
        screen2.contains("<sensitive, redacted, not actionable>"),
        "{screen2}"
    );
    let id = observation_id(&screen2);
    let (is_error, text, _) = call(
        &gw,
        "type_text",
        json!({"observation_id": id, "element_id": "n2", "text": "hunter2"}),
    )
    .await;
    assert!(is_error && text.contains("sensitive_target"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": id, "element_id": "n2"}),
    )
    .await;
    assert!(is_error && text.contains("sensitive_target"), "{text}");
    let (is_error, _, _) = call(
        &gw,
        "type_text",
        json!({"observation_id": id, "element_id": "n1", "text": "alice"}),
    )
    .await;
    assert!(!is_error);
    let _ = screen;

    let s = phone.state.lock().expect("lock");
    assert_eq!(s.phone.username, "alice");
    // Policy stopped both sensitive attempts before they reached the phone.
    assert_eq!(s.executed.iter().filter(|c| *c == "input.type").count(), 1);
    assert_eq!(s.executed.iter().filter(|c| *c == "input.tap").count(), 0);
    drop(s);
    phone.task.abort();
}

#[tokio::test]
async fn disabled_capabilities_are_enforced() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &[Capability::UiObserve]).await;

    let (is_error, screen, images) = call(&gw, "observe", json!({})).await;
    assert!(!is_error, "{screen}");
    assert_eq!(images, 0);
    assert!(
        screen.contains("has not enabled screen.capture"),
        "{screen}"
    );

    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": "n1"}),
    )
    .await;
    assert!(is_error && text.contains("permission_missing"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "launch_app",
        json!({"package": "com.android.settings"}),
    )
    .await;
    assert!(is_error && text.contains("permission_missing"), "{text}");
    assert!(
        !phone
            .state
            .lock()
            .expect("lock")
            .executed
            .iter()
            .any(|c| c == "input.tap")
    );
    phone.task.abort();
}

#[tokio::test]
async fn pause_stop_and_revoke_take_effect() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    phone.state.lock().expect("lock").session.paused = true;
    phone.control.send(Control::PushState).await.expect("push");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, text, _) = call(&gw, "observe", json!({})).await;
    assert!(is_error && text.contains("paused"), "{text}");

    phone.state.lock().expect("lock").session.paused = false;
    phone.control.send(Control::PushState).await.expect("push");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, _, _) = call(&gw, "observe", json!({})).await;
    assert!(!is_error);

    // Owner presses Stop on the phone.
    phone.control.send(Control::Stop).await.expect("stop");
    phone.task.await.expect("join").expect("clean stop");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, text, _) = call(&gw, "observe", json!({})).await;
    assert!(is_error && text.contains("device_unavailable"), "{text}");

    // Reconnect, then the gateway owner revokes the device.
    let state: Shared = Arc::new(Mutex::new(FakeState::new(&Capability::ALL)));
    let (_tx, rx) = mpsc::channel(1);
    let url = format!("ws://{}/v1/device", gw.addr);
    let task = {
        let (state, token, url) = (state.clone(), phone.token.clone(), url.clone());
        tokio::spawn(async move { latch_fake_device::run(&url, &token, state, rx).await })
    };
    let device_id = loop {
        if let Some(id) = state.lock().expect("lock").device_id.clone() {
            break id;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    };
    let (status, _) = http(
        &gw,
        "DELETE",
        &format!("/v1/admin/devices/{device_id}"),
        Some(ADMIN),
        None,
        &[],
    )
    .await;
    assert_eq!(status, 204);
    tokio::time::timeout(Duration::from_secs(5), task)
        .await
        .expect("device disconnected")
        .expect("join")
        .expect("ok");
    assert!(state.lock().expect("lock").revoked);
    let (is_error, text, _) = call(&gw, "observe", json!({})).await;
    assert!(is_error && text.contains("device_unavailable"), "{text}");

    // The revoked token can no longer connect.
    let (_tx, rx) = mpsc::channel(1);
    let again = latch_fake_device::run(&url, &phone.token, state, rx).await;
    assert!(again.is_err());
}

// ---- HTTP long-poll transport (protocol 1.1), used by serverless gateways ----

#[tokio::test]
async fn poll_transport_runs_the_full_agent_loop() {
    let gw = start_gateway().await;
    let phone = connect_phone_with(&gw, &Capability::ALL, Transport::Poll).await;

    let (status, info) = http(&gw, "GET", "/v1/info", None, None, &[]).await;
    assert_eq!(status, 200);
    assert_eq!(info["protocol"], latch_protocol::PROTOCOL_VERSION);
    assert!(
        info["transports"]
            .as_array()
            .expect("transports")
            .contains(&json!("poll"))
    );

    let (is_error, screen, images) = call(&gw, "observe", json!({})).await;
    assert!(!is_error, "{screen}");
    assert_eq!(images, 1);
    let (_, screen, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Chat")}),
    )
    .await;
    let (_, screen, _) = call(&gw, "type_text", json!({"observation_id": observation_id(&screen), "element_id": "n1", "text": "over long-poll"})).await;
    let (is_error, text, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Send")}),
    )
    .await;
    assert!(!is_error, "{text}");
    let s = phone.state.lock().expect("lock");
    assert_eq!(s.phone.sent_messages, ["over long-poll"]);
    assert_eq!(
        s.approval_requests.last().map(String::as_str),
        Some("Tap “Send” in org.latch.demo.chat")
    );
    drop(s);
    phone.task.abort();
}

#[tokio::test]
async fn poll_transport_honours_pause_stop_and_revoke() {
    let gw = start_gateway().await;
    let phone = connect_phone_with(&gw, &Capability::ALL, Transport::Poll).await;

    phone.state.lock().expect("lock").session.paused = true;
    phone.control.send(Control::PushState).await.expect("push");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, text, _) = call(&gw, "observe", json!({})).await;
    assert!(is_error && text.contains("paused"), "{text}");

    phone.control.send(Control::Stop).await.expect("stop");
    phone.task.await.expect("join").expect("clean stop");
    let (is_error, text, _) = call(&gw, "observe", json!({})).await;
    assert!(is_error && text.contains("device_unavailable"), "{text}");

    // Reconnect over poll, then revoke: the phone learns it on its next poll.
    let state: Shared = Arc::new(Mutex::new(FakeState::new(&Capability::ALL)));
    let (_tx, rx) = mpsc::channel(1);
    let base = format!("http://{}", gw.addr);
    let task = {
        let (state, token, base) = (state.clone(), phone.token.clone(), base.clone());
        tokio::spawn(async move { latch_fake_device::run_poll(&base, &token, state, rx).await })
    };
    let device_id = loop {
        if let Some(id) = state.lock().expect("lock").device_id.clone() {
            break id;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    };
    let (status, _) = http(
        &gw,
        "DELETE",
        &format!("/v1/admin/devices/{device_id}"),
        Some(ADMIN),
        None,
        &[],
    )
    .await;
    assert_eq!(status, 204);
    tokio::time::timeout(Duration::from_secs(10), task)
        .await
        .expect("phone noticed")
        .expect("join")
        .expect("ok");
    assert!(state.lock().expect("lock").revoked);
    let (status, _) = latch_fake_device::http(
        &base,
        "POST",
        "/v1/device/hello",
        Some(&phone.token),
        Some("{}"),
    )
    .await
    .expect("http");
    assert_eq!(status, 401);
}

#[tokio::test]
async fn poll_endpoints_reject_wrong_credentials_and_stale_connections() {
    let gw = start_gateway().await;
    let base = format!("http://{}", gw.addr);
    let (status, _) = latch_fake_device::http(
        &base,
        "GET",
        "/v1/device/poll?connection=k_0",
        Some("ldt_forged"),
        None,
    )
    .await
    .expect("http");
    assert_eq!(status, 401);
    let phone = connect_phone_with(&gw, &Capability::ALL, Transport::Poll).await;
    let (status, _) = latch_fake_device::http(
        &base,
        "GET",
        "/v1/device/poll?connection=k_999999",
        Some(&phone.token),
        None,
    )
    .await
    .expect("http");
    assert_eq!(status, 409);
    let (status, _) = latch_fake_device::http(
        &base,
        "POST",
        "/v1/device/messages?connection=k_999999",
        Some(&phone.token),
        Some(r#"{"type":"bye","reason":"x"}"#),
    )
    .await
    .expect("http");
    assert_eq!(status, 409);
    phone.task.abort();
}

#[tokio::test]
async fn console_created_client_tokens_grant_and_lose_mcp_access() {
    let gw = start_gateway().await;
    let ping = json!({"jsonrpc": "2.0", "id": 1, "method": "ping"});
    let (status, created) = http(
        &gw,
        "POST",
        "/v1/admin/clients",
        Some(ADMIN),
        Some(json!({"name": "Claude"})),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{created}");
    let token = created["token"].as_str().expect("token").to_owned();
    assert!(created["mcp_url"].as_str().expect("url").ends_with("/mcp"));
    assert_eq!(
        http(&gw, "POST", "/mcp", Some(&token), Some(ping.clone()), &[])
            .await
            .0,
        200
    );

    let (_, list) = http(&gw, "GET", "/v1/admin/clients", Some(ADMIN), None, &[]).await;
    assert!(
        !list.to_string().contains(&token),
        "token must never be listed"
    );
    assert!(list["clients"][0]["last_used_ms"].is_u64());

    // Secret-link form for clients that only accept a URL.
    let link = format!("/mcp/{token}");
    assert_eq!(
        http(&gw, "POST", &link, None, Some(ping.clone()), &[])
            .await
            .0,
        200
    );
    assert_eq!(
        http(
            &gw,
            "POST",
            &format!("/mcp/{ADMIN}"),
            None,
            Some(ping.clone()),
            &[]
        )
        .await
        .0,
        401
    );

    let id = created["id"].as_str().expect("id");
    assert_eq!(
        http(
            &gw,
            "DELETE",
            &format!("/v1/admin/clients/{id}"),
            Some(ADMIN),
            None,
            &[]
        )
        .await
        .0,
        204
    );
    assert_eq!(
        http(&gw, "POST", "/mcp", Some(&token), Some(ping), &[])
            .await
            .0,
        401
    );
    // Client tokens cannot administer the gateway.
    assert_eq!(
        http(&gw, "GET", "/v1/admin/clients", Some(&token), None, &[])
            .await
            .0,
        401
    );
}

#[tokio::test]
async fn one_call_tools_save_round_trips() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    // wait_for answers from the screen at hand, and its observation can be acted on.
    let (is_error, screen, images) = call(&gw, "wait_for", json!({"text": "settings"})).await;
    assert!(!is_error, "{screen}");
    assert!(screen.starts_with("\"settings\" is on screen."), "{screen}");
    assert_eq!(images, 0, "wait_for takes no screenshot unless asked");
    let (is_error, screen, _) = call(&gw, "tap", json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Settings")})).await;
    assert!(!is_error, "{screen}");
    let (_, missing, _) = call(
        &gw,
        "wait_for",
        json!({"text": "Bluetooth", "timeout_ms": 100}),
    )
    .await;
    assert!(
        missing.contains("did not appear within 100 ms"),
        "{missing}"
    );

    // scroll_to brings a list item into view in one call.
    let (is_error, screen, _) = call(
        &gw,
        "scroll_to",
        json!({"observation_id": observation_id(&missing), "text": "about phone"}),
    )
    .await;
    assert!(!is_error, "{screen}");
    assert!(screen.starts_with("Found \"about phone\"."), "{screen}");
    assert!(screen.contains("\"About phone\""), "{screen}");

    // Actions come back with the new screen from the same phone command (observe_after).
    {
        let s = phone.state.lock().expect("lock");
        let observes = s.executed.iter().filter(|c| *c == "ui.observe").count();
        let commands = s.executed.len();
        assert_eq!(commands, 6, "{:?}", s.executed);
        assert_eq!(
            observes, 2,
            "one per action, inside the action: {:?}",
            s.executed
        );
    }

    // type_text + submit sends in one call, and sending asks the owner first.
    let (_, screen, _) = call(&gw, "launch_app", json!({"package": "org.latch.demo.chat"})).await;
    let (is_error, screen, _) = call(&gw, "type_text", json!({"observation_id": observation_id(&screen), "element_id": "n1", "text": "on my way", "submit": true})).await;
    assert!(!is_error, "{screen}");
    let s = phone.state.lock().expect("lock");
    assert_eq!(s.phone.sent_messages, ["on my way"]);
    assert_eq!(
        s.approval_requests.last().map(String::as_str),
        Some("Type 9 characters into “Message” and press Enter in org.latch.demo.chat")
    );
    drop(s);
    phone.task.abort();
}
