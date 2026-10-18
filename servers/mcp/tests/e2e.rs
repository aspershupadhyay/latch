// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! End-to-end: real gateway on a TCP port, the fake phone over WebSocket, and
//! MCP requests exactly as a client would send them.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use latch_fake_device::transfers;
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
    start_gateway_with(|_| {}).await
}

async fn start_gateway_with(adjust: impl FnOnce(&mut AppState)) -> Gateway {
    start_gateway_on(ADMIN, Store::ephemeral(), adjust).await
}

async fn start_gateway_on(
    admin_token: &str,
    store: Store,
    adjust: impl FnOnce(&mut AppState),
) -> Gateway {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    let config = Config {
        bind: addr,
        data_dir: std::path::PathBuf::new(),
        admin_token: admin_token.into(),
        mcp_token: Some(MCP.into()),
        mcp_http: true,
        public_url: None,
        allowed_origins: vec!["https://allowed.example".into()],
        wifi_bind: None,
    };
    let mut state = AppState::new(config, store);
    state.settle_ms = 0;
    // Answer with a transfer's progress at once, so transfer_status is exercised.
    state.transfer_budget_ms = 0;
    adjust(&mut state);
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

/// Raw bytes in and out, for file links (protocol 1.6).
/// The link path, key, and counter a tool answer hands the computer.
fn link_in(gw: &Gateway, text: &str) -> (String, latch_protocol::FileLink) {
    let word_after = |key: &str| -> String {
        let start = text.find(key).unwrap_or_else(|| panic!("{key} in {text}")) + key.len();
        text[start..]
            .chars()
            .take_while(|c| c.is_ascii_hexdigit())
            .collect()
    };
    let url = text
        .split(['"', ' ', '\n'])
        .find(|w| w.starts_with("http://") && w.contains("/v1/blobs/"))
        .unwrap_or_else(|| panic!("link in {text}"));
    let path = url
        .split_once(&gw.addr.to_string())
        .map(|(_, p)| p.to_owned())
        .expect("path");
    let link = latch_protocol::FileLink {
        url: url.to_owned(),
        headers: Default::default(),
        key_hex: word_after("-K "),
        iv_hex: word_after("-iv "),
    };
    (path, link)
}

async fn http_bytes(gw: &Gateway, method: &str, path: &str, body: &[u8]) -> (u16, Vec<u8>) {
    let head = format!(
        "{method} {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",
        gw.addr,
        body.len()
    );
    let mut stream = tokio::net::TcpStream::connect(gw.addr)
        .await
        .expect("connect");
    stream.write_all(head.as_bytes()).await.expect("write");
    stream.write_all(body).await.expect("write body");
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.expect("read");
    let split = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .expect("http response");
    let status: u16 = String::from_utf8_lossy(&raw[..split])
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .expect("status");
    (status, raw[split + 4..].to_vec())
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
    connect_phone_named(gw, enabled, transport, "Test phone").await
}

async fn connect_phone_named(
    gw: &Gateway,
    enabled: &[Capability],
    transport: Transport,
    name: &str,
) -> Phone {
    connect_phone_as(gw, FakeState::new(enabled), transport, name).await
}

/// Pairs and connects a fake phone whose state was set up by the test.
async fn connect_phone_as(
    gw: &Gateway,
    fake: FakeState,
    transport: Transport,
    name: &str,
) -> Phone {
    let (status, body) = http(
        gw,
        "POST",
        "/v1/admin/pairings",
        Some(ADMIN),
        Some(json!({ "name": name })),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{body}");
    let code = body["code"].as_str().expect("code").to_owned();
    let (_, token) = latch_fake_device::pair(&format!("http://{}", gw.addr), &code)
        .await
        .expect("pair");
    let state: Shared = Arc::new(Mutex::new(fake));
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
            "diagnose",
            "observe",
            "tap",
            "type_text",
            "scroll_to",
            "wait_for",
            "scroll",
            "swipe",
            "pinch",
            "gesture",
            "press",
            "list_apps",
            "launch_app",
            "open_link",
            "device_status",
            "ask_owner",
            "get_activity",
            "finish_task",
            "phone_control",
            "adb",
            "list_files",
            "read_file",
            "get_file_link",
            "upload_link",
            "transfer_status",
            "write_file",
            "create_folder",
            "rename_file",
            "delete_file",
            "set_clipboard",
            "share_to_app",
            "save_skill",
            "list_skills",
            "run_skill",
            "run_steps",
            "delete_skill",
            "answer_approval"
        ]
    );
    for forbidden in ["shell", "exec", "install"] {
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
    assert_eq!(health["status"], "ok");
}

/// Raw GET: status line and headers (lower-cased) plus the body as text.
async fn get_raw(gw: &Gateway, path: &str) -> (u16, String, String) {
    let request = format!(
        "GET {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
        gw.addr
    );
    let mut stream = tokio::net::TcpStream::connect(gw.addr)
        .await
        .expect("connect");
    stream.write_all(request.as_bytes()).await.expect("write");
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.expect("read");
    let text = String::from_utf8_lossy(&raw).into_owned();
    let (head, body) = text.split_once("\r\n\r\n").expect("http response");
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .expect("status");
    (status, head.to_ascii_lowercase(), body.to_string())
}

#[tokio::test]
async fn the_gateway_is_private_to_browsers_and_search_engines() {
    let gw = start_gateway().await;
    // The page at "/" asks for nothing and runs nothing.
    let (status, head, body) = get_raw(&gw, "/").await;
    assert_eq!(status, 200);
    assert!(head.contains("content-security-policy: default-src 'none'"));
    assert!(head.contains("script-src 'none'"));
    for absent in [
        "<form",
        "<input",
        "<script",
        "password",
        "LATCH_ADMIN_TOKEN",
    ] {
        assert!(!body.contains(absent), "the page must not contain {absent}");
    }
    // The old console with its owner-key box is gone.
    assert_eq!(get_raw(&gw, "/admin.js").await.0, 404);
    let (status, _, robots) = get_raw(&gw, "/robots.txt").await;
    assert_eq!(status, 200);
    assert!(robots.contains("Disallow: /"));
    // Every answer, page or API, found or not, says noindex and cannot be framed.
    for path in [
        "/",
        "/gateway.css",
        "/healthz",
        "/v1/info",
        "/v1/admin/devices",
        "/mcp",
        "/nope",
    ] {
        let (_, head, _) = get_raw(&gw, path).await;
        assert!(
            head.contains("x-robots-tag: noindex"),
            "{path} lacks noindex"
        );
        assert!(
            head.contains("x-frame-options: deny"),
            "{path} can be framed"
        );
        assert!(
            head.contains("strict-transport-security"),
            "{path} lacks HSTS"
        );
    }
    // Public health tells nothing about the owner's phones.
    let (_, _, health) = get_raw(&gw, "/healthz").await;
    assert!(!health.contains("devices"));
}

#[tokio::test]
async fn agent_navigates_settings_and_toggles_wifi() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    let (_, devices, _) = call(&gw, "list_devices", json!({})).await;
    assert!(devices.contains("connected"), "{devices}");

    // Same text as the Vercel gateway (servers/vercel/test/e2e.test.ts).
    let (is_error, diagnosis, _) = call(&gw, "diagnose", json!({})).await;
    assert!(!is_error, "{diagnosis}");
    assert!(
        diagnosis.contains(": connected, app protocol "),
        "{diagnosis}"
    );
    assert!(
        diagnosis.ends_with("  Ready.\nVerdict: ready (1 phone usable).\n"),
        "{diagnosis}"
    );
    let (is_error, unknown, _) = call(&gw, "diagnose", json!({"device_id": "d_nope"})).await;
    assert!(
        is_error && unknown.contains("no phone d_nope is paired"),
        "{unknown}"
    );

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
    // Protocol 1.9 effect, same text as the Vercel gateway.
    assert!(
        screen.starts_with("Done; another app is now in front. The screen after the action:"),
        "{screen}"
    );
    // R5: a tap that changes nothing comes back as the changes since the full screen.
    let full = screen;
    let base_id = observation_id(&full);
    let (is_error, changes, _) = call(
        &gw,
        "tap",
        json!({"observation_id": base_id, "element_id": element(&full, "Display")}),
    )
    .await;
    assert!(!is_error, "{changes}");
    assert!(
        changes.contains(&format!(
            "Changes since observation {base_id}: elements not listed here are unchanged and keep their ids.\n(no element changed)\n"
        )),
        "{changes}"
    );
    // An unchanged element keeps its id: its id from the full screen works with the new observation.
    let (is_error, network, _) = call(&gw, "tap", json!({"observation_id": observation_id(&changes), "element_id": element(&full, "Network & internet")})).await;
    assert!(!is_error && network.contains("\"Wi-Fi\""), "{network}");
    call(&gw, "press", json!({"button": "back"})).await;
    // An explicit observe is always in full.
    let (is_error, screen, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    assert!(
        !is_error && screen.contains("\"Network & internet\"") && !screen.contains("Changes since"),
        "{screen}"
    );

    let (_, screen, _) = call(&gw, "tap", json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Network & internet")})).await;
    assert!(screen.contains("\"Wi-Fi\""), "{screen}");
    assert!(screen.contains("checked"), "{screen}");
    // Protocol 1.16 (ADR-044): the app's state words and headings reach the AI.
    assert!(screen.contains("Switch \"Wi-Fi\" state=\"On\""), "{screen}");
    assert!(
        screen.contains("TextView \"Network & internet\" @(40,100,1040,200) [heading]"),
        "{screen}"
    );
    // Numbered marks are asked of the phone only with a screenshot.
    let (is_error, marked, images) = call(&gw, "observe", json!({"marks": true})).await;
    assert!(!is_error && images == 1, "{marked}");
    let (_, unmarked, images) =
        call(&gw, "observe", json!({"marks": true, "screenshot": false})).await;
    assert_eq!(images, 0, "{unmarked}");
    {
        let s = phone.state.lock().expect("lock");
        assert_eq!(
            s.executed
                .iter()
                .filter(|c| *c == "ui.observe+marks")
                .count(),
            1
        );
    }
    let screen = unmarked;

    let (is_error, screen, _) = call(
        &gw,
        "tap",
        json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Wi-Fi")}),
    )
    .await;
    assert!(!is_error, "{screen}");
    assert!(screen.contains("unchecked"), "{screen}");
    assert!(screen.contains("state=\"Off\""), "{screen}");
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
    assert!(apps.starts_with("1 of 4 launchable apps on "), "{apps}");
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
    // This form ignores Enter: the agent hears so instead of a plain "Done."
    let (_, screen3, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    let (is_error, text, _) = call(
        &gw,
        "type_text",
        json!({"observation_id": observation_id(&screen3), "element_id": "n1", "text": "alice", "submit": true}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("Typed, but Enter did nothing visible"),
        "{text}"
    );
    let _ = screen;

    let s = phone.state.lock().expect("lock");
    assert_eq!(s.phone.username, "alice");
    // Policy stopped both sensitive attempts before they reached the phone; both "alice" ones did.
    assert_eq!(s.executed.iter().filter(|c| *c == "input.type").count(), 2);
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
    // The same phone again: it keeps its device key, as a phone keeps its keystore key.
    let mut same = FakeState::new(&Capability::ALL);
    same.device_key = phone.state.lock().expect("lock").device_key.clone();
    let state: Shared = Arc::new(Mutex::new(same));
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

#[tokio::test]
async fn full_access_adb_runs_commands_without_extra_restrictions() {
    let gw = start_gateway().await;

    // Off by default: with the capability disabled, the command is refused and
    // nothing runs on the phone.
    let off = connect_phone(&gw, &[Capability::UiObserve]).await;
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "id"})).await;
    assert!(is_error && text.contains("permission_missing"), "{text}");
    assert!(off.state.lock().expect("lock").adb_commands.is_empty());
    off.task.abort();

    // Owner enables full-access ADB.
    let phone = connect_phone(&gw, &[Capability::AdbShell]).await;

    // Arbitrary command pass-through with its real exit code and stdout.
    let (is_error, text, _) =
        call(&gw, "adb", json!({"command": "getprop ro.product.model"})).await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("exit code 0") && text.contains("Pixel 8"),
        "{text}"
    );

    // A nonzero exit and stderr come straight back.
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "nope --flag"})).await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("exit code 127") && text.contains("not found"),
        "{text}"
    );

    // stdin is piped through.
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "cat", "stdin": "ping\n"})).await;
    assert!(!is_error && text.contains("ping"), "{text}");

    // Large output is chunked by the phone and rejoined by the gateway into one
    // complete result; the phone ran the command exactly once.
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "seq 500"})).await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("\n1\n") && text.contains("\n500\n") && text.contains("exit code 0"),
        "{text}"
    );
    assert_eq!(
        phone
            .state
            .lock()
            .expect("lock")
            .adb_commands
            .iter()
            .filter(|c| *c == "seq 500")
            .count(),
        1
    );

    // Protocol 1.18: binary output as base64; a picture comes back as an image.
    let (is_error, text, images) = call(
        &gw,
        "adb",
        json!({"command": "screencap -p", "base64": true}),
    )
    .await;
    assert!(!is_error && text.contains("image/png"), "{text}");
    assert_eq!(images, 1);
    let (is_error, text, images) =
        call(&gw, "adb", json!({"command": "echo hi", "base64": true})).await;
    assert!(
        !is_error && text.contains("--- stdout (base64) ---\naGkK"),
        "{text}"
    );
    assert_eq!(images, 0);

    // No Latch-imposed deadline (ADR-046): only one the caller asks for.
    let (is_error, text, _) = call(
        &gw,
        "adb",
        json!({"command": "echo timed", "timeout_ms": 5000}),
    )
    .await;
    assert!(!is_error, "{text}");
    {
        let s = phone.state.lock().expect("lock");
        let deadlines = &s.adb_deadlines;
        assert!(
            deadlines[..deadlines.len() - 1]
                .iter()
                .all(|d| *d == latch_gateway::devices::NO_DEADLINE_MS),
            "{deadlines:?}"
        );
        assert_eq!(deadlines.last(), Some(&5000));
    }

    // No per-command approval, even with "approve every action" on.
    {
        let mut s = phone.state.lock().expect("lock");
        s.session.approve_every_action = true;
        s.approval_requests.clear();
    }
    phone.control.send(Control::PushState).await.expect("push");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "echo hi"})).await;
    assert!(!is_error && text.contains("hi"), "{text}");
    assert!(
        phone
            .state
            .lock()
            .expect("lock")
            .approval_requests
            .is_empty(),
        "full-access ADB must not ask per command"
    );

    // Keeps working past the ordinary session timer (ADR-046), while an
    // ordinary tool is refused once the session has ended.
    {
        let mut s = phone.state.lock().expect("lock");
        s.session.expires_at_ms = 1; // long past
    }
    phone.control.send(Control::PushState).await.expect("push");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "echo still-here"})).await;
    assert!(!is_error && text.contains("still-here"), "{text}");

    // Owner Stop still blocks it.
    phone.control.send(Control::Stop).await.expect("stop");
    phone.task.await.expect("join").expect("clean stop");
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (is_error, text, _) = call(&gw, "adb", json!({"command": "id"})).await;
    assert!(is_error && text.contains("device_unavailable"), "{text}");
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
    // The same phone again: it keeps its device key, as a phone keeps its keystore key.
    let mut same = FakeState::new(&Capability::ALL);
    same.device_key = phone.state.lock().expect("lock").device_key.clone();
    let state: Shared = Arc::new(Mutex::new(same));
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
    assert!(!is_error && screen.starts_with("Done."), "{screen}");
    let s = phone.state.lock().expect("lock");
    assert_eq!(s.phone.sent_messages, ["on my way"]);
    assert_eq!(
        s.approval_requests.last().map(String::as_str),
        Some("Type 9 characters into “Message” and press Enter in org.latch.demo.chat")
    );
    drop(s);
    phone.task.abort();
}

#[tokio::test]
async fn pinch_drag_and_double_tap_reach_the_phone() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let (_, screen, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    let (is_error, screen, _) = call(
        &gw,
        "pinch",
        json!({"observation_id": observation_id(&screen), "zoom": "in"}),
    )
    .await;
    // Protocol 1.9: the fake launcher does not zoom, and the phone says so.
    assert!(
        !is_error && screen.starts_with("Done, but the screen did not visibly change"),
        "{screen}"
    );
    let (is_error, screen, _) = call(&gw, "swipe", json!({"observation_id": observation_id(&screen), "from_x": 500, "from_y": 400, "to_x": 500, "to_y": 900, "hold_ms": 600})).await;
    assert!(!is_error, "{screen}");
    let (is_error, screen, _) = call(&gw, "tap", json!({"observation_id": observation_id(&screen), "element_id": element(&screen, "Chat"), "double": true})).await;
    assert!(!is_error, "{screen}");
    let (is_error, text, _) = call(
        &gw,
        "pinch",
        json!({"observation_id": observation_id(&screen), "zoom": "sideways"}),
    )
    .await;
    assert!(
        is_error && text.contains("zoom must be in or out"),
        "{text}"
    );
    let executed = phone.state.lock().expect("lock").executed.clone();
    assert!(executed.iter().any(|c| c == "input.pinch"), "{executed:?}");
    phone.task.abort();
}

#[tokio::test]
async fn free_form_gestures_reach_the_phone() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let (_, screen, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    // Protocol 1.18: a two-finger rotate.
    let rotate = json!([
        { "points": [{"x": 400, "y": 1200}, {"x": 450, "y": 1100}, {"x": 540, "y": 1050}], "duration_ms": 400 },
        { "points": [{"x": 680, "y": 1200}, {"x": 630, "y": 1300}, {"x": 540, "y": 1350}], "duration_ms": 400 }
    ]);
    let (is_error, screen, _) = call(
        &gw,
        "gesture",
        json!({"observation_id": observation_id(&screen), "strokes": rotate}),
    )
    .await;
    assert!(!is_error, "{screen}");
    assert_eq!(phone.state.lock().expect("lock").gestures, [2]);

    // Bad shapes are refused before the phone sees them.
    let too_many: Vec<Value> = (0..11)
        .map(|i| json!({"points": [{"x": 100 + i * 50, "y": 500}], "duration_ms": 100}))
        .collect();
    let (is_error, text, _) = call(
        &gw,
        "gesture",
        json!({"observation_id": observation_id(&screen), "strokes": too_many}),
    )
    .await;
    assert!(is_error && text.contains("1 to 10 fingers"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "gesture",
        json!({"observation_id": observation_id(&screen), "strokes": "swipe up"}),
    )
    .await;
    assert!(
        is_error && text.contains("strokes must be a list"),
        "{text}"
    );
    assert_eq!(phone.state.lock().expect("lock").gestures, [2]);
    phone.task.abort();
}

/// Regression from the 2026-10-02 speed test: the agent picked a paired phone
/// that was not connected. Connected phones come first, and the error names them.
#[tokio::test]
async fn offline_phones_are_listed_last_and_errors_name_the_connected_one() {
    let gw = start_gateway().await;
    let old = connect_phone_named(&gw, &Capability::ALL, Transport::WebSocket, "Old phone").await;
    let old_id = old
        .state
        .lock()
        .expect("lock")
        .device_id
        .clone()
        .expect("id");
    old.control.send(Control::Stop).await.expect("stop");
    old.task.await.expect("join").expect("clean stop");
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let new_id = phone
        .state
        .lock()
        .expect("lock")
        .device_id
        .clone()
        .expect("id");
    tokio::time::sleep(Duration::from_millis(100)).await;

    let (_, devices, _) = call(&gw, "list_devices", json!({})).await;
    let lines: Vec<&str> = devices.lines().collect();
    assert_eq!(
        lines[0], "Latch gateway, protocol 1.20, 38 tools.",
        "{devices}"
    );
    let lines = &lines[1..];
    assert!(
        lines[0].contains(&new_id) && lines[0].contains("connected"),
        "{devices}"
    );
    assert!(
        lines[1].contains(&old_id) && lines[1].contains("not connected"),
        "{devices}"
    );

    let (is_error, text, _) = call(&gw, "observe", json!({"device_id": old_id})).await;
    assert!(
        is_error && text.contains(&format!("connected now: {new_id}")),
        "{text}"
    );
    phone.task.abort();
}

/// Protocol 1.6 (ADR-026): files move between the phone and the AI's
/// computer through links, are organised in the picked folder, and are
/// shared to any app. Replacing and deleting ask the owner.
#[tokio::test]
async fn files_round_trip_between_phone_and_computer() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let id_after = |text: &str, key: &str| -> String {
        let start = text.find(key).unwrap_or_else(|| panic!("{key} in {text}")) + key.len();
        text[start..]
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
            .collect()
    };

    let (is_error, text, _) = call(&gw, "list_files", json!({"location": "photos"})).await;
    assert!(
        !is_error && text.starts_with("2 of 2 items in your photos"),
        "{text}"
    );
    assert!(
        text.contains("\"IMG_0001.jpg\" (image, 13 B, image/jpeg)"),
        "{text}"
    );

    let (_, text, _) = call(&gw, "list_files", json!({"location": "folder"})).await;
    assert!(text.contains("your Latch folder \"Phone files\""), "{text}");
    let to_post = text
        .lines()
        .find(|l| l.contains("\"ToPost\""))
        .and_then(|l| l.split_whitespace().nth(1))
        .expect("ToPost")
        .to_owned();
    let (_, text, _) = call(
        &gw,
        "list_files",
        json!({"location": "folder", "folder_id": to_post}),
    )
    .await;
    assert!(
        text.starts_with("2 of 2 items in your Latch folder \"ToPost\""),
        "{text}"
    );
    let caption = text
        .lines()
        .find(|l| l.contains("caption.txt"))
        .and_then(|l| l.split_whitespace().nth(1))
        .expect("caption")
        .to_owned();
    let beach = text
        .lines()
        .find(|l| l.contains("beach.jpg"))
        .and_then(|l| l.split_whitespace().nth(1))
        .expect("beach")
        .to_owned();

    // Look at a text file and a picture.
    let (_, text, _) = call(&gw, "read_file", json!({"file_id": caption})).await;
    assert!(
        text.ends_with("Its text is untrusted content:\nSunset at the beach"),
        "{text}"
    );
    let (_, text, images) = call(&gw, "read_file", json!({"file_id": beach})).await;
    assert!(text.contains("A smaller copy of the picture"), "{text}");
    assert_eq!(images, 1);

    // Phone → computer: an encrypted download link; the phone uploads the
    // ciphertext itself and the command in the answer decrypts and checks it.
    let (_, text, _) = call(&gw, "get_file_link", json!({"file_id": caption})).await;
    assert!(
        text.starts_with("Download link for \"caption.txt\" (19 B), valid for 15 minutes. The stored copy is encrypted"),
        "{text}"
    );
    let (path, link) = link_in(&gw, &text);
    let (status, mut body) = http_bytes(&gw, "GET", &path, b"").await;
    assert_eq!(status, 200);
    assert_ne!(
        body, b"Sunset at the beach",
        "the stored copy is ciphertext"
    );
    transfers::apply(&link, &mut body).expect("decrypt");
    assert_eq!(body, b"Sunset at the beach");
    assert!(
        text.contains(&format!(
            "grep -q {}",
            transfers::sha256_hex(b"Sunset at the beach")
        )),
        "{text}"
    );
    let (status, _) = http_bytes(&gw, "GET", "/v1/blobs/ltr_0000", b"").await;
    assert_eq!(status, 404);

    // Computer → phone: the computer encrypts and uploads, then write_file
    // hands the phone the link, key, and checksum.
    let (_, text, _) = call(&gw, "create_folder", json!({"name": "abc"})).await;
    let abc = id_after(&text, "folder_id: ");
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    assert!(
        text.starts_with("Upload link for one file (valid for 15 minutes, up to 2.0 GB)."),
        "{text}"
    );
    let upload = id_after(&text, "upload_id=\"");
    let (path, link) = link_in(&gw, &text);
    let big: Vec<u8> = (0..700_000u32).map(|i| (i % 251) as u8).collect();
    let sha = transfers::sha256_hex(&big);
    let mut sealed = big.clone();
    transfers::apply(&link, &mut sealed).expect("encrypt");
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "folder", "name": "early.pdf", "upload_id": upload, "sha256": sha}),
    )
    .await;
    assert!(is_error && text.contains("nothing was uploaded"), "{text}");
    let (status, _) = http_bytes(&gw, "PUT", &path, &sealed).await;
    assert_eq!(status, 200);
    let (status, _) = http_bytes(&gw, "PUT", &path, &sealed).await;
    assert_eq!(status, 409, "a link is filled once");
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "folder", "folder_id": abc, "name": "report.pdf", "upload_id": upload}),
    )
    .await;
    assert!(is_error && text.contains("sha256 is required"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "folder", "folder_id": abc, "name": "report.pdf", "upload_id": upload, "sha256": sha}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("Saved \"report.pdf\" to your Latch folder (684 KB)."),
        "{text}"
    );
    {
        let s = phone.state.lock().expect("lock");
        let saved = s
            .files
            .files
            .iter()
            .find(|f| f.name == "report.pdf")
            .expect("saved");
        assert_eq!(saved.data, big, "decrypted and checked on the phone");
        assert_eq!(saved.mime.as_deref(), Some("application/pdf"));
        assert!(s.approval_requests.is_empty(), "{:?}", s.approval_requests);
    }
    let (status, _) = http_bytes(&gw, "GET", &path, b"").await;
    assert_eq!(
        status, 404,
        "the stored copy is deleted once the phone has it"
    );
    // The upload was saved once.
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "folder", "name": "again.pdf", "upload_id": upload, "sha256": sha}),
    )
    .await;
    assert!(is_error && text.contains("upload_id is unknown"), "{text}");

    // A file that changed on the way is never kept.
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    let upload = id_after(&text, "upload_id=\"");
    let (path, link) = link_in(&gw, &text);
    let mut sealed = b"original".to_vec();
    transfers::apply(&link, &mut sealed).expect("encrypt");
    sealed[0] ^= 1;
    http_bytes(&gw, "PUT", &path, &sealed).await;
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "name": "tampered.txt", "upload_id": upload, "sha256": transfers::sha256_hex(b"original")}),
    )
    .await;
    assert!(
        is_error && text.contains("did not match its sha256"),
        "{text}"
    );
    assert!(
        !phone
            .state
            .lock()
            .expect("lock")
            .files
            .files
            .iter()
            .any(|f| f.name == "tampered.txt")
    );

    // A slow transfer answers with its progress; transfer_status finishes it.
    phone.state.lock().expect("lock").transfer_delay_ms = 1_800;
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    let upload = id_after(&text, "upload_id=\"");
    let (path, link) = link_in(&gw, &text);
    let mut sealed = b"slow file".to_vec();
    transfers::apply(&link, &mut sealed).expect("encrypt");
    http_bytes(&gw, "PUT", &path, &sealed).await;
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "name": "slow.txt", "upload_id": upload, "sha256": transfers::sha256_hex(b"slow file")}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("Still saving \"slow.txt\" on the phone: 0 B of 9 B."),
        "{text}"
    );
    let transfer = id_after(&text, "transfer_id=\"");
    let (is_error, text, _) = call(&gw, "transfer_status", json!({"transfer_id": transfer})).await;
    assert!(
        !is_error && text.starts_with("Saved \"slow.txt\" to Downloads (9 B)."),
        "{text}"
    );
    phone.state.lock().expect("lock").transfer_delay_ms = 0;

    // The clipboard takes a caption to paste.
    let (is_error, text, _) = call(&gw, "set_clipboard", json!({"text": "Hello from Latch"})).await;
    assert!(
        !is_error && text.starts_with("Copied 16 characters"),
        "{text}"
    );
    assert_eq!(
        phone.state.lock().expect("lock").clipboard,
        ["Hello from Latch"]
    );

    // The phone's activity log, filtered by kind, and the end of the task.
    let (is_error, text, _) =
        call(&gw, "get_activity", json!({"limit": 3, "kinds": ["file"]})).await;
    assert!(!is_error, "{text}");
    assert!(
        text.starts_with("Activity on the phone, newest first (3 of "),
        "{text}"
    );
    assert!(text.contains(" file by AI: Ran file."), "{text}");
    assert!(!text.contains("clipboard"), "{text}");
    let (is_error, text, _) = call(&gw, "get_activity", json!({"kinds": ["photos"]})).await;
    assert!(
        is_error && text.contains("kinds must be a list of"),
        "{text}"
    );
    let (is_error, text, _) =
        call(&gw, "finish_task", json!({"summary": "Copied a caption"})).await;
    assert!(
        !is_error && text.starts_with("Done. The cursor is gone"),
        "{text}"
    );
    assert_eq!(
        phone.state.lock().expect("lock").tasks_done,
        ["Copied a caption"]
    );
    // Roadmap R14: the latest task as a timeline, ending at finish_task.
    let (is_error, text, _) = call(&gw, "get_activity", json!({"task": "latest"})).await;
    assert!(!is_error, "{text}");
    assert!(
        text.starts_with("Latest AI task on the phone, oldest first: finished, "),
        "{text}"
    );
    assert!(text.contains("\n- +0.0 s "), "{text}");
    assert!(text.ends_with(" task by AI: Ran task.done\n"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "get_activity",
        json!({"task": "latest", "kinds": ["file"]}),
    )
    .await;
    assert!(
        is_error && text.contains("task cannot be combined"),
        "{text}"
    );

    // Roadmap R16: phone controls. Off-screen ones answer in words; a panel brings the screen.
    let (is_error, text, _) = call(
        &gw,
        "phone_control",
        json!({"control": "volume", "percent": 40}),
    )
    .await;
    assert!(!is_error, "{text}");
    assert_eq!(text, "Done: Set the media volume to 40%.");
    let (is_error, text, _) = call(
        &gw,
        "phone_control",
        json!({"control": "panel", "panel": "wifi"}),
    )
    .await;
    assert!(!is_error, "{text}");
    assert!(text.contains("app com.android.settings"), "{text}");
    for (args, says) in [
        (
            json!({"control": "brightness", "percent": 101}),
            "percent must be 0-100",
        ),
        (json!({"control": "flashlight"}), "on must be true or false"),
        (json!({"control": "airplane_mode"}), "control must be"),
    ] {
        let (is_error, text, _) = call(&gw, "phone_control", args).await;
        assert!(is_error && text.contains(says), "{text}");
    }
    assert_eq!(
        phone.state.lock().expect("lock").controls,
        [
            latch_protocol::PhoneControl::Volume {
                stream: latch_protocol::VolumeStream::Media,
                percent: 40
            },
            latch_protocol::PhoneControl::Panel {
                panel: latch_protocol::SettingsPanel::Wifi
            },
        ]
    );

    // Inline text into Downloads/abc; the same name gets a free name.
    let (_, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "subfolder": "abc", "name": "todo.txt", "text": "milk"}),
    )
    .await;
    assert!(
        text.starts_with("Saved \"todo.txt\" to Downloads (\"abc\") (4 B)."),
        "{text}"
    );
    let (_, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "subfolder": "abc", "name": "todo.txt", "text": "eggs"}),
    )
    .await;
    assert!(
        text.contains("\"todo (1).txt\"") && text.contains("got a new name"),
        "{text}"
    );
    let todo = id_after(&text, "file_id: ");

    // Replacing and deleting ask the owner first.
    let (is_error, text, _) = call(&gw, "write_file", json!({"location": "downloads", "subfolder": "abc", "name": "todo.txt", "text": "bread", "overwrite": true})).await;
    assert!(!is_error, "{text}");
    let (is_error, text, _) = call(
        &gw,
        "rename_file",
        json!({"file_id": todo, "name": "shopping.txt"}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("Renamed to \"shopping.txt\""),
        "{text}"
    );
    let (is_error, text, _) = call(&gw, "delete_file", json!({"file_id": todo})).await;
    assert!(!is_error && text == "Deleted.", "{text}");
    {
        let s = phone.state.lock().expect("lock");
        assert_eq!(
            s.approval_requests,
            [
                "Replace “todo.txt” in Downloads",
                "Delete a file from your phone"
            ]
        );
    }
    // Photos the owner did not save through Latch cannot be deleted.
    let (_, text, _) = call(
        &gw,
        "list_files",
        json!({"location": "photos", "query": "0002"}),
    )
    .await;
    let photo = text
        .lines()
        .find(|l| l.contains("IMG_0002"))
        .and_then(|l| l.split_whitespace().nth(1))
        .expect("photo")
        .to_owned();
    let (is_error, text, _) = call(&gw, "delete_file", json!({"file_id": photo})).await;
    assert!(is_error && text.contains("policy_refused"), "{text}");

    // Share to any app, with a caption.
    let (is_error, text, _) = call(
        &gw,
        "share_to_app",
        json!({"package": "com.google.android.youtube", "file_ids": [beach, photo], "text": "Sunset"}),
    )
    .await;
    assert!(
        !is_error
            && text.starts_with("Opened com.google.android.youtube's share screen with 2 files."),
        "{text}"
    );
    {
        let s = phone.state.lock().expect("lock");
        assert_eq!(
            s.shared,
            [(
                "com.google.android.youtube".to_owned(),
                vec!["beach.jpg".to_owned(), "IMG_0002.jpg".to_owned()],
                Some("Sunset".to_owned())
            )]
        );
    }
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "photos", "name": "x.txt", "text": "no"}),
    )
    .await;
    assert!(
        is_error && text.contains("photos take images and videos only"),
        "{text}"
    );
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "folder", "name": "../escape.txt", "text": "no"}),
    )
    .await;
    assert!(is_error && text.contains("invalid_request"), "{text}");
    phone.task.abort();
}

#[tokio::test]
async fn file_tools_need_their_capabilities() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &[Capability::UiObserve]).await;
    let (is_error, text, _) = call(&gw, "list_files", json!({"location": "photos"})).await;
    assert!(is_error && text.contains("file.read"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "folder", "name": "a.txt", "text": "a"}),
    )
    .await;
    assert!(is_error && text.contains("file.write"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "share_to_app",
        json!({"package": "com.x.android", "file_ids": ["f_0001"]}),
    )
    .await;
    assert!(is_error && text.contains("app.share"), "{text}");
    assert!(phone.state.lock().expect("lock").executed.is_empty());
    phone.task.abort();
}

/// The phone's same-Wi-Fi address in a tool answer (protocol 1.14).
fn local_url_in(text: &str) -> String {
    text.split(['"', ' ', '\n'])
        .find(|w| w.starts_with("http://127.0.0.1:") && !w.contains("/v1/"))
        .unwrap_or_else(|| panic!("Wi-Fi address in {text}"))
        .to_owned()
}

/// R23 (protocol 1.14, ADR-041): with fast transfers on, files go straight
/// between the computer and the phone on the same Wi-Fi, encrypted the same
/// way; the gateway's copy is the fallback, and phones that refuse never
/// change the answer.
#[tokio::test]
async fn same_wifi_transfers_go_straight_to_the_phone_and_fall_back() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let no_headers = std::collections::BTreeMap::new();

    // Off by default: the plain gateway link, and the phone keeps no address.
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    assert!(!text.contains("--connect-timeout"), "{text}");
    assert!(phone.state.lock().expect("lock").local_slots.is_empty());

    phone.state.lock().expect("lock").local_transfers = true;
    let id_after = |text: &str, key: &str| -> String {
        let start = text.find(key).unwrap_or_else(|| panic!("{key} in {text}")) + key.len();
        text[start..]
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
            .collect()
    };

    // Computer → phone over the Wi-Fi: the gateway never holds the file.
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    assert!(
        text.contains("On the phone's Wi-Fi it goes straight to the phone"),
        "{text}"
    );
    let upload = id_after(&text, "upload_id=\"");
    let local = local_url_in(&text);
    let (path, link) = link_in(&gw, &text);
    let plain = b"holiday video".to_vec();
    let mut sealed = plain.clone();
    transfers::apply(&link, &mut sealed).expect("encrypt");
    let (status, _) = transfers::http_bytes("PUT", &local, &no_headers, &sealed)
        .await
        .expect("put");
    assert_eq!(status, 201);
    let (status, _) = http_bytes(&gw, "GET", &path, b"").await;
    assert_eq!(status, 404, "nothing went through the gateway");
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "name": "holiday.mp4", "upload_id": upload, "sha256": transfers::sha256_hex(&plain)}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("Saved \"holiday.mp4\" to Downloads (13 B)."),
        "{text}"
    );
    let file = id_after(&text, "file_id: ");

    // The Wi-Fi try failed, so the command sent it through the gateway.
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    let upload = id_after(&text, "upload_id=\"");
    let (path, link) = link_in(&gw, &text);
    let mut sealed = b"via the cloud".to_vec();
    transfers::apply(&link, &mut sealed).expect("encrypt");
    let (status, _) = http_bytes(&gw, "PUT", &path, &sealed).await;
    assert_eq!(status, 200);
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "name": "cloud.txt", "upload_id": upload, "sha256": transfers::sha256_hex(b"via the cloud")}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("Saved \"cloud.txt\""),
        "{text}"
    );

    // Nothing sent either way.
    let (_, text, _) = call(&gw, "upload_link", json!({})).await;
    let upload = id_after(&text, "upload_id=\"");
    let (is_error, text, _) = call(
        &gw,
        "write_file",
        json!({"location": "downloads", "name": "none.txt", "upload_id": upload, "sha256": transfers::sha256_hex(b"x")}),
    )
    .await;
    assert!(is_error && text.contains("nothing was uploaded"), "{text}");

    // Phone → computer over the Wi-Fi, once.
    let (_, text, _) = call(&gw, "get_file_link", json!({"file_id": file})).await;
    assert!(
        text.starts_with("Download link for \"holiday.mp4\" (13 B), valid for 15 minutes and for one download, straight from the phone"),
        "{text}"
    );
    assert!(text.contains("lane=cloud"), "{text}");
    let local = local_url_in(&text);
    let mut key = link.clone();
    key.key_hex = text.split("-K ").nth(1).expect("key")[..64].to_owned();
    key.iv_hex = text.split("-iv ").nth(1).expect("iv")[..32].to_owned();
    let (status, mut body) = transfers::http_bytes("GET", &local, &no_headers, b"")
        .await
        .expect("get");
    assert_eq!(status, 200);
    assert_ne!(body, plain, "ciphertext on the Wi-Fi too");
    transfers::apply(&key, &mut body).expect("decrypt");
    assert_eq!(body, plain);
    assert!(
        transfers::http_bytes("GET", &local, &no_headers, b"")
            .await
            .is_err(),
        "the address closes after one transfer"
    );

    // lane=cloud: through the gateway, as before.
    let (_, text, _) = call(
        &gw,
        "get_file_link",
        json!({"file_id": file, "lane": "cloud"}),
    )
    .await;
    assert!(text.contains("The stored copy is encrypted"), "{text}");
    let (path, link) = link_in(&gw, &text);
    let (status, mut body) = http_bytes(&gw, "GET", &path, b"").await;
    assert_eq!(status, 200);
    transfers::apply(&link, &mut body).expect("decrypt");
    assert_eq!(body, plain);
    let (is_error, text, _) = call(
        &gw,
        "get_file_link",
        json!({"file_id": file, "lane": "usb"}),
    )
    .await;
    assert!(
        is_error && text.contains("lane must be auto or cloud"),
        "{text}"
    );
    phone.task.abort();
}

/// Protocol 1.5: the AI hands a step to the owner (log in, unlock) and gets
/// their answer and the screen afterwards. It is never asked about itself,
/// even with "Ask me before every action".
#[tokio::test]
async fn ask_owner_hands_a_step_to_the_owner() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    phone
        .state
        .lock()
        .expect("lock")
        .session
        .approve_every_action = true;
    let (is_error, text, _) = call(
        &gw,
        "ask_owner",
        json!({"message": "Please log in to Instagram, then tap Done."}),
    )
    .await;
    assert!(
        !is_error && text.starts_with("The owner says it's done. The screen after the action:"),
        "{text}"
    );
    {
        let s = phone.state.lock().expect("lock");
        assert_eq!(
            s.owner_questions,
            ["Please log in to Instagram, then tap Done."]
        );
        assert!(s.approval_requests.is_empty(), "{:?}", s.approval_requests);
    }

    phone.state.lock().expect("lock").owner_reply = latch_protocol::OwnerReply::Cant;
    let (is_error, text, _) = call(&gw, "ask_owner", json!({"message": "Unlock the phone"})).await;
    assert!(
        !is_error && text.starts_with("The owner says they can't do it now."),
        "{text}"
    );

    let (is_error, text, _) = call(&gw, "ask_owner", json!({"message": "  "})).await;
    assert!(is_error && text.contains("invalid_request"), "{text}");
    phone.task.abort();
}

/// Regression from the 2026-10-04 phone run: every re-pairing of the same
/// phone left an offline entry behind (eight entries for two phones).
#[tokio::test]
async fn pairing_again_replaces_the_offline_entry_of_the_same_phone() {
    let gw = start_gateway().await;
    let id_of = |p: &Phone| p.state.lock().expect("lock").device_id.clone().expect("id");
    let first = connect_phone(&gw, &Capability::ALL).await;
    let first_id = id_of(&first);
    first.control.send(Control::Stop).await.expect("stop");
    first.task.await.expect("join").expect("clean stop");
    tokio::time::sleep(Duration::from_millis(50)).await;

    // The same phone pairs again: its offline entry goes.
    let second = connect_phone(&gw, &Capability::ALL).await;
    let second_id = id_of(&second);
    // An identical phone that pairs while the other is connected keeps both.
    let third = connect_phone(&gw, &Capability::ALL).await;
    let third_id = id_of(&third);
    tokio::time::sleep(Duration::from_millis(50)).await;

    let (status, body) = http(&gw, "GET", "/v1/admin/devices", Some(ADMIN), None, &[]).await;
    assert_eq!(status, 200, "{body}");
    let ids: Vec<&str> = body["devices"]
        .as_array()
        .expect("devices")
        .iter()
        .map(|d| d["id"].as_str().expect("id"))
        .collect();
    assert!(!ids.contains(&first_id.as_str()), "{body}");
    assert!(
        ids.contains(&second_id.as_str()) && ids.contains(&third_id.as_str()),
        "{body}"
    );
    second.task.abort();
    third.task.abort();
}

/// Regression from the 2026-10-03 phone run: wait_for matched the text the
/// agent had just typed into a search box. Input fields never count.
#[tokio::test]
async fn wait_for_ignores_text_in_input_fields() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let (_, screen, _) = call(&gw, "launch_app", json!({"package": "org.latch.demo.chat"})).await;
    let (is_error, _, _) = call(&gw, "type_text", json!({"observation_id": observation_id(&screen), "element_id": "n1", "text": "draft only"})).await;
    assert!(!is_error);
    let (_, text, _) = call(
        &gw,
        "wait_for",
        json!({"text": "draft only", "timeout_ms": 100}),
    )
    .await;
    assert!(text.contains("did not appear within 100 ms"), "{text}");
    phone.task.abort();
}

/// ADR-031: a skill saved once runs again with a different cart in one call,
/// picks the right product among look-alikes, and hands a missing product back.
#[tokio::test]
async fn skills_replay_with_new_inputs_and_hand_back_what_differs() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;
    let skill = json!({
        "name": "order_groceries",
        "description": "Add items to the cart in the shop app",
        "params": [{ "name": "items", "type": "list", "fields": ["name", "qty"] }],
        "steps": [
            { "do": "launch_app", "package": "org.latch.demo.shop" },
            { "for_each": "items", "steps": [
                { "do": "type", "target": "Search products", "text": "{item.name}", "submit": true },
                { "do": "tap", "target": "ADD || +", "near": "{item.name}" },
                { "do": "tap", "target": "+", "near": "{item.name}", "repeat": "{item.qty-1}" }
            ]},
            { "do": "tap", "target": "Not now", "optional": true }
        ]
    });
    let (is_error, text, _) = call(&gw, "save_skill", json!({ "skill": skill })).await;
    assert!(
        !is_error && text.contains("Saved skill \"order_groceries\""),
        "{text}"
    );
    let (_, list, _) = call(&gw, "list_skills", json!({})).await;
    assert!(list.contains("items: list of {name, qty}"), "{list}");

    // Today: bread and three paneer. "bread" matches two products; near picks the searched one.
    let (is_error, text, _) = call(&gw, "run_skill", json!({
        "name": "order_groceries",
        "params": { "items": [{ "name": "White bread", "qty": 1 }, { "name": "Paneer", "qty": 3 }] }
    })).await;
    assert!(!is_error && text.contains("all 8 steps done"), "{text}");
    {
        let state = phone.state.lock().expect("lock");
        assert_eq!(
            state.phone.cart,
            vec![0, 1, 3, 0, 0],
            "White bread ×1, Paneer ×3"
        );
    }

    // Tomorrow: a different, longer cart, same skill.
    let (is_error, text, _) = call(&gw, "run_skill", json!({
        "name": "order_groceries",
        "params": { "items": [{ "name": "Tomato", "qty": 2 }, { "name": "Milk", "qty": 1 }, { "name": "Brown bread", "qty": 2 }] }
    })).await;
    assert!(!is_error && text.contains("all 11 steps done"), "{text}");
    assert_eq!(
        phone.state.lock().expect("lock").phone.cart,
        vec![2, 1, 3, 2, 1]
    );

    // A product the shop does not have: the run stops at that step and returns the screen.
    let (is_error, text, _) = call(
        &gw,
        "run_skill",
        json!({
            "name": "order_groceries",
            "params": { "items": [{ "name": "Mango", "qty": 1 }, { "name": "Milk", "qty": 1 }] }
        }),
    )
    .await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("stopped: could not find \"ADD || +\" near \"Mango\""),
        "{text}"
    );
    assert!(text.contains("from_step 4"), "{text}");
    assert!(
        text.contains("observation_id"),
        "the screen comes back: {text}"
    );
    // The AI skips Mango and continues; Milk goes in.
    let (is_error, text, _) = call(
        &gw,
        "run_skill",
        json!({
            "name": "order_groceries",
            "params": { "items": [{ "name": "Mango", "qty": 1 }, { "name": "Milk", "qty": 1 }] },
            "from_step": 5
        }),
    )
    .await;
    assert!(!is_error && text.contains("all 8 steps done"), "{text}");
    assert_eq!(phone.state.lock().expect("lock").phone.cart[4], 2);

    // Bad input is refused before anything runs.
    let (is_error, text, _) = call(&gw, "run_skill", json!({ "name": "order_groceries", "params": { "items": [{ "name": "Milk", "qty": "two" }] } })).await;
    assert!(is_error && text.contains("is not a whole number"), "{text}");
    let (is_error, _, _) = call(
        &gw,
        "save_skill",
        json!({ "skill": { "name": "x", "description": "y", "steps": [{ "do": "fly" }] } }),
    )
    .await;
    assert!(is_error);

    // Skills cannot get around the owner: a send in the chat app still waits for approval.
    let chat = json!({
        "name": "say_hi", "description": "Send a message",
        "params": [{ "name": "message", "type": "text" }],
        "steps": [
            { "do": "launch_app", "package": "org.latch.demo.chat" },
            { "do": "type", "target": "Message", "text": "{message}" },
            { "do": "tap", "target": "Send" }
        ]
    });
    call(&gw, "save_skill", json!({ "skill": chat })).await;
    phone.state.lock().expect("lock").approval = Approval::Deny;
    let (_, text, _) = call(
        &gw,
        "run_skill",
        json!({ "name": "say_hi", "params": { "message": "hi" } }),
    )
    .await;
    assert!(text.contains("stopped: user_denied"), "{text}");
    assert!(
        phone
            .state
            .lock()
            .expect("lock")
            .phone
            .sent_messages
            .is_empty()
    );

    let (is_error, _, _) = call(&gw, "delete_skill", json!({ "name": "say_hi" })).await;
    assert!(!is_error);
    let (_, list, _) = call(&gw, "list_skills", json!({})).await;
    assert!(list.starts_with("1 skills"), "{list}");
}

/// Roadmap R4: steps sent together run in one call, each through the same
/// rules as a single tool call (the owner is still asked before Send), save
/// nothing, and stop with the screen when one does not match.
#[tokio::test]
async fn run_steps_does_several_steps_in_one_call_under_the_same_rules() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    let (is_error, text, _) = call(
        &gw,
        "run_steps",
        json!({ "steps": [
            { "do": "launch_app", "package": "org.latch.demo.chat" },
            { "do": "type", "target": "Message", "text": "On my way" },
            { "do": "tap", "target": "Send" }
        ]}),
    )
    .await;
    assert!(
        !is_error && text.contains("Ran your steps: all 3 steps done."),
        "{text}"
    );
    {
        let state = phone.state.lock().expect("lock");
        assert_eq!(
            state.phone.sent_messages.last().map(String::as_str),
            Some("On my way")
        );
        assert!(
            state.approval_requests.iter().any(|t| t.contains("Send")),
            "the owner was asked before Send: {:?}",
            state.approval_requests
        );
    }
    let (_, skills, _) = call(&gw, "list_skills", json!({})).await;
    assert!(!skills.contains("\"steps\""), "nothing saved: {skills}");

    // A step that does not match stops the run and hands the screen back.
    let (is_error, text, _) = call(
        &gw,
        "run_steps",
        json!({ "steps": [
            { "do": "launch_app", "package": "com.android.settings" },
            { "do": "tap", "target": "Teleport" }
        ]}),
    )
    .await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("stopped: could not find \"Teleport\""),
        "{text}"
    );
    assert!(
        text.contains("run_steps with from_step 3 and the same steps"),
        "{text}"
    );
    assert!(text.contains("observation_id"), "{text}");

    // Refused before anything runs: bad steps and params are not for run_steps.
    let (is_error, text, _) = call(&gw, "run_steps", json!({ "steps": [{ "do": "fly" }] })).await;
    assert!(is_error, "{text}");
    let (is_error, text, _) = call(&gw, "run_steps", json!({ "steps": [] })).await;
    assert!(is_error, "{text}");
}

/// Roadmap R8: starter skills are listed with their recipes but nothing is
/// saved (or can run) until an AI saves one.
#[tokio::test]
async fn starter_skills_are_listed_but_not_saved() {
    let gw = start_gateway().await;
    let _phone = connect_phone(&gw, &Capability::ALL).await;
    let (is_error, text, _) = call(&gw, "list_skills", json!({ "starters": true })).await;
    assert!(
        !is_error && text.contains("web_search") && text.contains("whatsapp_message"),
        "{text}"
    );
    assert!(text.contains("none runs until you save it"), "{text}");
    let (_, saved, _) = call(&gw, "list_skills", json!({})).await;
    assert!(saved.starts_with("No skills saved yet"), "{saved}");
    let (is_error, text, _) = call(
        &gw,
        "run_skill",
        json!({ "name": "web_search", "params": { "query": "x" } }),
    )
    .await;
    assert!(is_error && text.contains("no skill named"), "{text}");
}

/// One tool call with a given credential (an AI app's own key).
async fn call_as(gw: &Gateway, token: &str, tool: &str, args: Value) -> (bool, String) {
    let (status, body) = http(
        gw,
        "POST",
        "/mcp",
        Some(token),
        Some(json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args}})),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{body}");
    let text = body["result"]["content"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|c| c["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    (body["result"]["isError"].as_bool().unwrap_or(false), text)
}

#[tokio::test]
async fn an_ai_app_limited_to_some_apps_cannot_use_or_see_others() {
    let gw = start_gateway().await;
    let _phone = connect_phone(&gw, &Capability::ALL).await;
    let (status, created) = http(
        &gw,
        "POST",
        "/v1/admin/clients",
        Some(ADMIN),
        Some(json!({ "name": "Settings helper", "apps": ["com.android.settings"] })),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{created}");
    let token = created["token"].as_str().expect("token").to_owned();
    let id = created["id"].as_str().expect("id").to_owned();

    // Inside its limit: works as usual.
    let (is_error, text) = call_as(
        &gw,
        &token,
        "launch_app",
        json!({"package": "com.android.settings"}),
    )
    .await;
    assert!(
        !is_error && text.contains("app com.android.settings"),
        "{text}"
    );
    // Outside: refused before the phone is asked, and the phone did nothing.
    let (is_error, text) = call_as(
        &gw,
        &token,
        "launch_app",
        json!({"package": "org.latch.demo.chat"}),
    )
    .await;
    assert!(
        is_error && text.contains("policy_refused") && text.contains("org.latch.demo.chat"),
        "{text}"
    );
    // Going home is allowed, but the home screen is not shown to it.
    let (is_error, text) = call_as(&gw, &token, "press", json!({"button": "home"})).await;
    assert!(is_error && text.contains("not shown"), "{text}");
    assert!(!text.contains("[n1]"), "no screen content: {text}");
    let (is_error, text) = call_as(&gw, &token, "observe", json!({"screenshot": false})).await;
    assert!(
        is_error && text.contains("not shown") && !text.contains("[n1]"),
        "{text}"
    );

    // The owner sees the limit, and lifting it gives the AI app the phone's own rules again.
    let (_, clients) = http(&gw, "GET", "/v1/admin/clients", Some(ADMIN), None, &[]).await;
    assert_eq!(
        clients["clients"][0]["apps"],
        json!(["com.android.settings"])
    );
    let (status, _) = http(
        &gw,
        "PATCH",
        &format!("/v1/admin/clients/{id}"),
        Some(ADMIN),
        Some(json!({ "apps": ["bad package!"] })),
        &[],
    )
    .await;
    assert_eq!(status, 400);
    let (status, _) = http(
        &gw,
        "PATCH",
        &format!("/v1/admin/clients/{id}"),
        Some(ADMIN),
        Some(json!({ "apps": null })),
        &[],
    )
    .await;
    assert_eq!(status, 204);
    let (is_error, text) = call_as(
        &gw,
        &token,
        "launch_app",
        json!({"package": "org.latch.demo.chat"}),
    )
    .await;
    assert!(!is_error, "{text}");
    // The gateway's own token has no limit.
    let (is_error, text, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    assert!(!is_error, "{text}");
}

#[tokio::test]
async fn each_ai_app_has_its_own_budget_of_calls() {
    let gw = start_gateway_with(|s| s.calls_per_minute = 3).await;
    let (_, created) = http(
        &gw,
        "POST",
        "/v1/admin/clients",
        Some(ADMIN),
        Some(json!({ "name": "Busy app" })),
        &[],
    )
    .await;
    let token = created["token"].as_str().expect("token").to_owned();
    for _ in 0..3 {
        let (_, text) = call_as(&gw, &token, "list_devices", json!({})).await;
        assert!(text.starts_with("Latch gateway"), "{text}");
    }
    let (is_error, text) = call_as(&gw, &token, "list_devices", json!({})).await;
    assert!(
        is_error
            && text
                .starts_with("rate_limited: this AI app made more than 3 tool calls in a minute."),
        "{text}"
    );
    // Another AI app is not slowed down by the first.
    let (is_error, text, _) = call(&gw, "list_devices", json!({})).await;
    assert!(!is_error, "{text}");
}

/// A hello as a phone builds it, with or without a device key.
fn hello_with(
    key: Option<&latch_fake_device::signing::DeviceKey>,
) -> latch_protocol::DeviceToGateway {
    latch_protocol::DeviceToGateway::Hello(latch_protocol::Hello {
        protocol: latch_protocol::PROTOCOL_VERSION.into(),
        device: latch_protocol::DeviceDescriptor {
            platform: "fake".into(),
            os_version: "1".into(),
            model: "Latch fake phone".into(),
            app_version: "0.1.0".into(),
        },
        capabilities: vec![],
        session: latch_protocol::SessionInfo {
            expires_at_ms: latch_gateway::now_ms() + 60_000,
            approve_every_action: false,
            paused: false,
            remote_approvals: false,
        },
        device_time_ms: None,
        device_key: key.map(latch_fake_device::signing::DeviceKey::public_b64),
        battery_restricted: None,
    })
}

fn sealed(
    key: &latch_fake_device::signing::DeviceKey,
    connection: &str,
    message: &latch_protocol::DeviceToGateway,
) -> Value {
    serde_json::from_str(&key.seal(connection, message).expect("seal")).expect("json")
}

#[tokio::test]
async fn phones_sign_their_messages_and_a_stolen_token_is_not_enough() {
    let gw = start_gateway().await;
    let phone = connect_phone_with(&gw, &Capability::ALL, Transport::Poll).await;
    let key = phone
        .state
        .lock()
        .expect("lock")
        .device_key
        .clone()
        .expect("key");
    let id = phone
        .state
        .lock()
        .expect("lock")
        .device_id
        .clone()
        .expect("id");
    // The gateway learned the phone's key from its first signed hello, and the phone works.
    assert_eq!(
        gw.state.store().get(&id).and_then(|d| d.device_key.clone()),
        Some(key.public_b64())
    );
    let (is_error, text, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    assert!(!is_error, "{text}");

    // A stolen token alone: an unsigned hello is refused.
    let plain = serde_json::to_value(hello_with(None)).expect("json");
    let (status, body) = http(
        &gw,
        "POST",
        "/v1/device/hello",
        Some(&phone.token),
        Some(plain),
        &[],
    )
    .await;
    assert_eq!(status, 401, "{body}");
    assert!(body.to_string().contains("signs its messages"), "{body}");
    // A stolen token with the thief's own key: refused as a changed key.
    let thief = latch_fake_device::signing::DeviceKey::generate();
    let (status, body) = http(
        &gw,
        "POST",
        "/v1/device/hello",
        Some(&phone.token),
        Some(sealed(&thief, "", &hello_with(Some(&thief)))),
        &[],
    )
    .await;
    assert_eq!(status, 401, "{body}");
    assert!(body.to_string().contains("key changed"), "{body}");
    // A key in an unsigned hello proves nothing.
    let unproven = serde_json::to_value(hello_with(Some(&thief))).expect("json");
    let (status, _) = http(
        &gw,
        "POST",
        "/v1/device/hello",
        Some(&phone.token),
        Some(unproven),
        &[],
    )
    .await;
    assert_eq!(status, 401);

    // The real phone says hello again and gets a new connection.
    let (status, welcome) = http(
        &gw,
        "POST",
        "/v1/device/hello",
        Some(&phone.token),
        Some(sealed(&key, "", &hello_with(Some(&key)))),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{welcome}");
    let connection = welcome["connection"]
        .as_str()
        .expect("connection")
        .to_owned();
    let path = format!("/v1/device/messages?connection={connection}");
    let state = latch_protocol::DeviceToGateway::State {
        capabilities: vec![],
        session: latch_protocol::SessionInfo {
            expires_at_ms: latch_gateway::now_ms() + 60_000,
            approve_every_action: false,
            paused: false,
            remote_approvals: false,
        },
        device_time_ms: None,
    };
    // Unsigned, or signed for another connection (a replay): refused.
    let (status, _) = http(
        &gw,
        "POST",
        &path,
        Some(&phone.token),
        Some(serde_json::to_value(&state).expect("json")),
        &[],
    )
    .await;
    assert_eq!(status, 401);
    let (status, _) = http(
        &gw,
        "POST",
        &path,
        Some(&phone.token),
        Some(sealed(&key, "k_0", &state)),
        &[],
    )
    .await;
    assert_eq!(status, 401);
    // Signed for this connection: accepted.
    let (status, body) = http(
        &gw,
        "POST",
        &path,
        Some(&phone.token),
        Some(sealed(&key, &connection, &state)),
        &[],
    )
    .await;
    assert_eq!(status, 204, "{body}");
}

#[tokio::test]
async fn a_phone_from_before_signatures_still_connects() {
    let gw = start_gateway().await;
    let (_, body) = http(
        &gw,
        "POST",
        "/v1/admin/pairings",
        Some(ADMIN),
        Some(json!({ "name": "Old app" })),
        &[],
    )
    .await;
    let code = body["code"].as_str().expect("code").to_owned();
    let (_, token) = latch_fake_device::pair(&format!("http://{}", gw.addr), &code)
        .await
        .expect("pair");
    let mut old = FakeState::new(&Capability::ALL);
    old.device_key = None;
    let state: Shared = Arc::new(Mutex::new(old));
    let (_tx, rx) = mpsc::channel(1);
    let task = {
        let (state, base) = (state.clone(), format!("http://{}", gw.addr));
        tokio::spawn(async move { latch_fake_device::run_poll(&base, &token, state, rx).await })
    };
    for _ in 0..200 {
        if state.lock().expect("lock").device_id.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let id = state
        .lock()
        .expect("lock")
        .device_id
        .clone()
        .expect("connected");
    assert!(
        gw.state
            .store()
            .get(&id)
            .and_then(|d| d.device_key.clone())
            .is_none()
    );
    let (is_error, text, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    assert!(!is_error, "{text}");
    task.abort();
}

/// R19 (ADR-038): a rotation issues a new owner key once and the old one stops
/// working; changing LATCH_ADMIN_TOKEN restores access after a restart.
#[tokio::test]
async fn the_owner_key_rotates_and_a_new_admin_token_recovers_access() {
    let dir = std::env::temp_dir().join(format!("latch-owner-key-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let gw = start_gateway_on(ADMIN, Store::open(&dir).expect("store"), |_| {}).await;

    let (status, _) = http(&gw, "POST", "/v1/admin/owner-key", None, None, &[]).await;
    assert_eq!(status, 401);
    let (status, _) = http(&gw, "POST", "/v1/admin/owner-key", Some(MCP), None, &[]).await;
    assert_eq!(status, 401, "an AI app's key cannot rotate the owner key");

    // The app sends `{}` (OkHttp needs a body on POST); curl sends none.
    let (status, body) = http(
        &gw,
        "POST",
        "/v1/admin/owner-key",
        Some(ADMIN),
        Some(json!({})),
        &[],
    )
    .await;
    assert_eq!(status, 200);
    let first = body["owner_key"].as_str().expect("owner_key").to_owned();
    assert!(first.starts_with("lok_") && first.len() == 4 + 64);
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(ADMIN), None, &[]).await;
    assert_eq!(
        status, 401,
        "LATCH_ADMIN_TOKEN stops working after a rotation"
    );
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(&first), None, &[]).await;
    assert_eq!(status, 200);

    // Rotating again ends the key used to ask.
    let (status, body) = http(&gw, "POST", "/v1/admin/owner-key", Some(&first), None, &[]).await;
    assert_eq!(status, 200);
    let second = body["owner_key"].as_str().expect("owner_key").to_owned();
    assert_ne!(first, second);
    for old in [first.as_str(), ADMIN] {
        let (status, _) = http(&gw, "GET", "/v1/admin/clients", Some(old), None, &[]).await;
        assert_eq!(status, 401);
    }
    let (status, _) = http(&gw, "GET", "/v1/admin/clients", Some(&second), None, &[]).await;
    assert_eq!(status, 200);
    // The new key is not on disk, only its hash.
    let saved = std::fs::read_to_string(dir.join("devices.json")).expect("devices.json");
    assert!(!saved.contains(&second) && !saved.contains(ADMIN));

    // A restart with the same LATCH_ADMIN_TOKEN keeps the rotated key.
    drop(gw);
    let gw = start_gateway_on(ADMIN, Store::open(&dir).expect("store"), |_| {}).await;
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(ADMIN), None, &[]).await;
    assert_eq!(status, 401);
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(&second), None, &[]).await;
    assert_eq!(status, 200);

    // Recovery: the owner lost the key and sets a new LATCH_ADMIN_TOKEN.
    const NEW_ADMIN: &str = "recovered-admin-token-0123456789abcdef";
    drop(gw);
    let gw = start_gateway_on(NEW_ADMIN, Store::open(&dir).expect("store"), |_| {}).await;
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(&second), None, &[]).await;
    assert_eq!(
        status, 401,
        "the rotated key ends with the token it was issued under"
    );
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(NEW_ADMIN), None, &[]).await;
    assert_eq!(status, 200);
    // Going back to the old token later does not bring the rotated key back.
    drop(gw);
    let gw = start_gateway_on(ADMIN, Store::open(&dir).expect("store"), |_| {}).await;
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(&second), None, &[]).await;
    assert_eq!(status, 401);
    let (status, _) = http(&gw, "GET", "/v1/admin/devices", Some(ADMIN), None, &[]).await;
    assert_eq!(status, 200);
    let _ = std::fs::remove_dir_all(&dir);
}

/// R3: the app asks the owner API whether the gateway's database is far away.
#[tokio::test]
async fn the_owner_api_reports_no_database_round_trip() {
    let gw = start_gateway().await;
    let (status, _) = http(&gw, "GET", "/v1/admin/speed", Some(MCP), None, &[]).await;
    assert_eq!(status, 401);
    let (status, body) = http(&gw, "GET", "/v1/admin/speed", Some(ADMIN), None, &[]).await;
    assert_eq!(status, 200);
    assert_eq!(
        body,
        json!({ "store_ms": 0, "slow_ms": 150, "slow": false, "region": null })
    );
}

/// R13 (protocol 1.13): a phone that says Android's battery optimisation is on
/// gets the fix in `diagnose`; one that says nothing does not.
#[tokio::test]
async fn diagnose_names_battery_optimisation_from_the_hello() {
    let gw = start_gateway().await;
    let mut restricted = FakeState::new(&Capability::ALL);
    restricted.battery_restricted = Some(true);
    let _a = connect_phone_as(&gw, restricted, Transport::Poll, "Restricted").await;
    let (is_error, text, _) = call(&gw, "diagnose", json!({})).await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("battery optimisation is on for Latch"),
        "{text}"
    );
    assert!(text.contains("Ready."), "a warning, not a blocker: {text}");

    let gw = start_gateway().await;
    let _b = connect_phone(&gw, &Capability::ALL).await;
    let (_, text, _) = call(&gw, "diagnose", json!({})).await;
    assert!(!text.contains("battery optimisation"), "{text}");
}

/// A redeploy restarts the gateway: paired phones come back with their saved
/// credential, and AI apps the owner added keep working, without pairing again.
#[tokio::test]
async fn phones_and_ai_apps_survive_a_gateway_restart() {
    let dir = std::env::temp_dir().join(format!("latch-restart-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let gw = start_gateway_on(ADMIN, Store::open(&dir).expect("store"), |_| {}).await;
    let phone = connect_phone_with(&gw, &Capability::ALL, Transport::Poll).await;
    let device_id = phone
        .state
        .lock()
        .expect("lock")
        .device_id
        .clone()
        .expect("id");
    let (status, client) = http(
        &gw,
        "POST",
        "/v1/admin/clients",
        Some(ADMIN),
        Some(json!({ "name": "Desktop AI" })),
        &[],
    )
    .await;
    assert_eq!(status, 200, "{client}");
    let client_token = client["token"].as_str().expect("token").to_owned();
    phone.task.abort();

    // The same data directory, a new process.
    let gw = start_gateway_on(ADMIN, Store::open(&dir).expect("store"), |_| {}).await;
    // The phone keeps its token and its signing key (ADR-037), like the app's keystore.
    let mut fake = FakeState::new(&Capability::ALL);
    fake.device_key = phone.state.lock().expect("lock").device_key.take();
    let state: Shared = Arc::new(Mutex::new(fake));
    let (_control, rx) = mpsc::channel(4);
    let task = {
        let (state, token, base) = (
            state.clone(),
            phone.token.clone(),
            format!("http://{}", gw.addr),
        );
        tokio::spawn(async move { latch_fake_device::run_poll(&base, &token, state, rx).await })
    };
    for _ in 0..100 {
        if state.lock().expect("lock").device_id.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(!task.is_finished(), "the phone could not reconnect");
    assert_eq!(
        state.lock().expect("lock").device_id.as_deref(),
        Some(device_id.as_str())
    );
    let (is_error, text, _) = call(&gw, "observe", json!({"screenshot": false})).await;
    assert!(!is_error, "{text}");

    let (status, body) = http(
        &gw,
        "POST",
        "/mcp",
        Some(&client_token),
        Some(json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})),
        &[("MCP-Protocol-Version", "2025-06-18")],
    )
    .await;
    assert_eq!(status, 200, "the AI app's key still works: {body}");
    task.abort();
    let _ = std::fs::remove_dir_all(&dir);
}

/// Audit finding: anyone could fill the sign-in list (10 waiting requests) and
/// keep the owner's own AI app from asking. Same rule as the Vercel gateway.
#[tokio::test]
async fn nobody_can_fill_the_sign_in_list() {
    let gw = start_gateway().await;
    let register = |name: String| {
        let gw = &gw;
        async move {
            let (status, body) = http(
                gw,
                "POST",
                "/oauth/register",
                None,
                Some(json!({"client_name": name, "redirect_uris": ["https://app.example/cb"]})),
                &[],
            )
            .await;
            assert_eq!(status, 201, "{body}");
            body["client_id"].as_str().expect("client_id").to_owned()
        }
    };
    let ask = |client: String| {
        let gw = &gw;
        async move {
            let path = format!(
                "/oauth/authorize?response_type=code&client_id={client}&redirect_uri=https%3A%2F%2Fapp.example%2Fcb&code_challenge={}&code_challenge_method=S256&state=s",
                "a".repeat(43)
            );
            let (status, _, _) = get_raw(gw, &path).await;
            assert_eq!(status, 200);
        }
    };
    let waiting = || async {
        let (_, body) = http(
            &gw,
            "GET",
            "/v1/admin/oauth/requests",
            Some(ADMIN),
            None,
            &[],
        )
        .await;
        body["requests"]
            .as_array()
            .expect("requests")
            .iter()
            .map(|r| r["client_name"].as_str().unwrap_or_default().to_owned())
            .collect::<Vec<_>>()
    };

    // Signing in again replaces an app's waiting request instead of adding one.
    let spammer = register("Spam 0".into()).await;
    for _ in 0..5 {
        ask(spammer.clone()).await;
    }
    assert_eq!(waiting().await.iter().filter(|n| *n == "Spam 0").count(), 1);

    // A flood of apps never refuses a new one: the oldest waiting request makes room.
    for i in 1..=12 {
        ask(register(format!("Spam {i}")).await).await;
        tokio::time::sleep(Duration::from_millis(2)).await;
    }
    ask(register("Owner's app".into()).await).await;
    let now = waiting().await;
    assert!(now.len() <= 10, "{now:?}");
    assert!(now.contains(&"Owner's app".to_owned()), "{now:?}");
    assert!(
        !now.contains(&"Spam 0".to_owned()),
        "the oldest went first: {now:?}"
    );
}

#[tokio::test]
async fn more_buttons_links_status_and_controls() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &Capability::ALL).await;

    // Roadmap R25: the new system buttons come back with the screen like back and home.
    for button in ["split_screen", "dpad_down", "lock_screen"] {
        let (is_error, text, _) = call(&gw, "press", json!({"button": button})).await;
        assert!(!is_error, "{button}: {text}");
        assert!(text.contains("observation_id"), "{text}");
    }
    // The shade shows one-time codes and message previews: never a button.
    for button in ["power_off", "notifications", "quick_settings"] {
        let (is_error, text, _) = call(&gw, "press", json!({"button": button})).await;
        assert!(
            is_error && text.contains("button must be one of back, home"),
            "{text}"
        );
    }
    {
        use latch_protocol::GlobalAction::*;
        assert_eq!(
            phone.state.lock().expect("lock").pressed,
            [SplitScreen, DpadDown, LockScreen]
        );
    }

    // The status reads in plain words, and the lock screen shows up in it.
    let (is_error, text, _) = call(&gw, "device_status", json!({})).await;
    assert!(!is_error, "{text}");
    for line in [
        "- battery: 81%, not charging",
        "- network: Wi-Fi",
        "- free storage: 11.7 GB",
        "- orientation: portrait",
        "- lock screen: showing (only the owner can unlock; use ask_owner)",
        "- do not disturb: off",
        "- screen: 1080x2400 px",
    ] {
        assert!(text.contains(line), "{line} in {text}");
    }

    // A web link opens straight in the app; a fresh launch starts from the first screen.
    let (is_error, text, _) = call(
        &gw,
        "open_link",
        json!({"url": "https://shop.example/item/42", "package": "org.latch.demo.shop"}),
    )
    .await;
    assert!(!is_error, "{text}");
    assert!(text.contains("app org.latch.demo.shop"), "{text}");
    let (is_error, text, _) = call(
        &gw,
        "launch_app",
        json!({"package": "org.latch.demo.chat", "fresh": true}),
    )
    .await;
    assert!(
        !is_error && text.contains("app org.latch.demo.chat"),
        "{text}"
    );
    for (url, says) in [
        ("intent://x#Intent;end", "intent: links cannot be opened"),
        ("file:///sdcard/a.txt", "file: links cannot be opened"),
        ("https://a.example/a b", "printable ASCII"),
        ("example.com", "url needs a scheme"),
    ] {
        let (is_error, text, _) = call(
            &gw,
            "open_link",
            json!({"url": url, "package": "org.latch.demo.shop"}),
        )
        .await;
        assert!(is_error && text.contains(says), "{url}: {text}");
    }
    {
        let s = phone.state.lock().expect("lock");
        assert_eq!(
            s.links,
            [(
                "https://shop.example/item/42".to_owned(),
                "org.latch.demo.shop".to_owned()
            )]
        );
        assert_eq!(s.fresh_launches, ["org.latch.demo.chat"]);
        assert!(s.approval_requests.is_empty(), "{:?}", s.approval_requests);
    }

    // A message link prefills a message to someone: the owner approves it first.
    phone.state.lock().expect("lock").approval = Approval::Deny;
    let (is_error, text, _) = call(
        &gw,
        "open_link",
        json!({"url": "sms:+15550100?body=hi", "package": "org.latch.demo.chat"}),
    )
    .await;
    assert!(is_error && text.contains("user_denied"), "{text}");
    // A payment link is asked about every time, like any payment.
    let (is_error, text, _) = call(
        &gw,
        "open_link",
        json!({"url": "upi://pay?pa=shop@bank&am=10", "package": "org.latch.demo.shop"}),
    )
    .await;
    assert!(is_error && text.contains("user_denied"), "{text}");
    {
        let s = phone.state.lock().expect("lock");
        assert_eq!(s.approval_requests.len(), 2, "{:?}", s.approval_requests);
        assert!(
            s.approval_requests[1].contains("upi://pay"),
            "{:?}",
            s.approval_requests
        );
        assert_eq!(s.links.len(), 1, "nothing denied was opened");
    }
    phone.state.lock().expect("lock").approval = Approval::Approve;

    // The new controls: off-screen ones answer in words, a settings page brings the screen.
    for (args, says) in [
        (
            json!({"control": "ringer", "mode": "vibrate"}),
            "Done: Set the ringer to vibrate.",
        ),
        (
            json!({"control": "do_not_disturb", "on": true}),
            "Done: Turn Do Not Disturb on.",
        ),
        (
            json!({"control": "rotation", "mode": "portrait"}),
            "Done: Lock the screen to portrait.",
        ),
        (
            json!({"control": "screen_timeout", "seconds": 600}),
            "Done: Set the screen timeout to 10 min.",
        ),
    ] {
        let (is_error, text, _) = call(&gw, "phone_control", args).await;
        assert!(!is_error && text == says, "{text}");
    }
    let (is_error, text, _) = call(
        &gw,
        "phone_control",
        json!({"control": "panel", "panel": "battery"}),
    )
    .await;
    assert!(!is_error && text.contains("observation_id"), "{text}");
    for (args, says) in [
        (
            json!({"control": "screen_timeout", "seconds": 5}),
            "seconds must be between 15 and 1800",
        ),
        (
            json!({"control": "ringer", "mode": "loud"}),
            "mode must be normal, vibrate, or silent",
        ),
        (
            json!({"control": "panel", "panel": "developer"}),
            "panel must be one of wifi",
        ),
    ] {
        let (is_error, text, _) = call(&gw, "phone_control", args).await;
        assert!(is_error && text.contains(says), "{text}");
    }
    let (_, text, _) = call(&gw, "device_status", json!({})).await;
    assert!(text.contains("- do not disturb: on"), "{text}");
}

/// Serves the same fake phone again (its state, its credential), as the app
/// does after a network drop.
fn reconnect(
    gw: &Gateway,
    state: &Shared,
    token: &str,
    transport: Transport,
) -> mpsc::Sender<Control> {
    let (control, rx) = mpsc::channel(4);
    let (state, token, addr) = (state.clone(), token.to_owned(), gw.addr);
    tokio::spawn(async move {
        match transport {
            Transport::WebSocket => {
                latch_fake_device::run(&format!("ws://{addr}/v1/device"), &token, state, rx).await
            }
            Transport::Poll => {
                latch_fake_device::run_poll(&format!("http://{addr}"), &token, state, rx).await
            }
        }
    });
    control
}

/// ADR-048: the phone pressed Home, then the network dropped before its answer
/// arrived. After the phone reconnects the gateway sends the same command
/// again; the phone answers from memory, so the AI gets the answer and Home
/// was pressed exactly once.
#[tokio::test]
async fn a_lost_answer_arrives_after_a_reconnect_and_the_action_runs_once() {
    for transport in [Transport::WebSocket, Transport::Poll] {
        let gw = start_gateway().await;
        let Phone {
            state,
            task,
            token,
            control: _stay,
        } = connect_phone_with(&gw, &[Capability::NavGlobal], transport).await;
        state.lock().expect("lock").drop_next_answer = true;
        let (answer, _control) =
            tokio::join!(call(&gw, "press", json!({"button": "home"})), async {
                task.await.expect("join").expect("phone dropped cleanly");
                reconnect(&gw, &state, &token, transport)
            });
        let (is_error, text, _) = answer;
        assert!(!is_error, "{text}");
        let s = state.lock().expect("lock");
        assert_eq!(
            s.pressed,
            vec![latch_protocol::GlobalAction::Home],
            "pressed once"
        );
        assert_eq!(
            s.replayed.len(),
            1,
            "answered from memory after the reconnect"
        );
    }
}

/// A phone before 1.19 might run a command twice, so it is not sent again:
/// the call fails as it always did, and says the action may have happened.
#[tokio::test]
async fn a_phone_before_1_19_is_never_sent_a_command_twice() {
    let gw = start_gateway().await;
    let mut fake = FakeState::new(&[Capability::NavGlobal]);
    fake.hello_protocol = Some("1.18".into());
    fake.drop_next_answer = true;
    let Phone {
        state,
        task,
        token,
        control: _stay,
    } = connect_phone_as(&gw, fake, Transport::WebSocket, "Old phone").await;
    let (answer, _control) = tokio::join!(call(&gw, "press", json!({"button": "home"})), async {
        task.await.expect("join").expect("phone dropped cleanly");
        reconnect(&gw, &state, &token, Transport::WebSocket)
    });
    let (is_error, text, _) = answer;
    assert!(is_error);
    assert!(text.contains("may already have happened"), "{text}");
    // Let the reconnect finish: nothing arrives again.
    tokio::time::sleep(Duration::from_millis(300)).await;
    let s = state.lock().expect("lock");
    assert_eq!(s.pressed.len(), 1);
    assert!(s.replayed.is_empty());
}

/// A phone that drops off mid-command and stays away: the call fails once the
/// reconnect grace is over, and tells the AI to look before repeating.
#[tokio::test]
async fn a_phone_that_does_not_come_back_fails_after_the_grace() {
    let gw = start_gateway_with(|s| {
        s.devices
            .reconnect_grace_ms
            .store(300, std::sync::atomic::Ordering::Relaxed);
    })
    .await;
    let phone = connect_phone(&gw, &[Capability::NavGlobal]).await;
    phone.state.lock().expect("lock").drop_next_answer = true;
    let started = std::time::Instant::now();
    let (is_error, text, _) = call(&gw, "press", json!({"button": "home"})).await;
    assert!(is_error);
    assert!(text.contains("observe before repeating it"), "{text}");
    assert!(
        started.elapsed() < Duration::from_secs(5),
        "failed after the grace, not the deadline"
    );
}

/// R29 (f): `latch-gateway check-phone` against the fake phone. The steps that
/// need the owner's hands are skipped; everything else passes, and the report
/// names the model but never the phone's name or what was on its screen.
#[tokio::test]
async fn the_phone_check_passes_on_the_fake_phone_and_its_report_keeps_nothing_personal() {
    use latch_gateway::phone_check::{self, Outcome, Target, Unattended};
    let gw = start_gateway().await;
    let _phone = connect_phone_named(
        &gw,
        &Capability::ALL,
        Transport::WebSocket,
        "Sparsh's phone",
    )
    .await;
    let target = Target::parse(&format!("http://{}/mcp", gw.addr), MCP).expect("target");
    let report = phone_check::run(target, &mut Unattended, true)
        .await
        .expect("check ran");
    let outcome = |name: &str| {
        report
            .steps
            .iter()
            .find(|s| s.name.starts_with(name))
            .map(|s| s.outcome.clone())
            .unwrap_or_else(|| panic!("no step {name}"))
    };
    for name in [
        "Phone ready",
        "Read the screen",
        "Rotate gesture",
        "ADB identity",
        "ADB large output",
        "ADB screenshot",
        "ADB job",
    ] {
        assert_eq!(outcome(name), Outcome::Pass, "{name}: {report:?}");
    }
    for name in [
        "ADB long-running",
        "Answer survives",
        "Stop ends",
        "Reconnect after reboot",
    ] {
        assert!(
            matches!(outcome(name), Outcome::Skip(_)),
            "{name} needs the owner or --quick"
        );
    }
    assert!(report.passed());
    let markdown = report.markdown(phone_check::now_s());
    assert!(
        markdown.contains("Phone: Latch fake phone, app protocol 1.20"),
        "{markdown}"
    );
    assert!(!markdown.contains("Sparsh"), "no phone name: {markdown}");
    assert!(
        markdown.contains("| Rotate gesture | R28 | PASS |"),
        "{markdown}"
    );
}

/// R29 (d), ADR-049: a command that runs longer than one call waits starts as
/// a job, keeps running on the phone, and is collected later, exit code and
/// all; a job can be cancelled; an older phone is told to update.
#[tokio::test]
async fn an_adb_job_starts_runs_on_the_phone_and_is_collected_later() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &[Capability::AdbShell]).await;

    let (is_error, started, _) = call(
        &gw,
        "adb",
        json!({"command": "sleep 1; echo built", "background": true}),
    )
    .await;
    assert!(!is_error, "{started}");
    let job = started
        .split_whitespace()
        .nth(2)
        .expect("Started job <id>")
        .to_owned();
    assert!(
        started.starts_with(&format!("Started job {job} on the phone.")),
        "{started}"
    );

    let (_, now, _) = call(&gw, "adb", json!({"job": job})).await;
    assert!(
        now.starts_with(&format!("job {job} still running")),
        "{now}"
    );

    let (is_error, done, _) = call(&gw, "adb", json!({"job": job, "wait_ms": 5000})).await;
    assert!(!is_error, "{done}");
    assert!(
        done.starts_with(&format!("job {job} finished, exit code 0")),
        "{done}"
    );
    assert!(done.contains("--- stdout ---\nbuilt\n"), "{done}");

    // Collected and finished: the id is gone.
    let (is_error, gone, _) = call(&gw, "adb", json!({"job": job})).await;
    assert!(is_error && gone.contains("no such adb job"), "{gone}");

    let (_, long, _) = call(
        &gw,
        "adb",
        json!({"command": "sleep 60; echo never", "background": true}),
    )
    .await;
    let long = long.split_whitespace().nth(2).expect("id").to_owned();
    let (is_error, cancelled, _) = call(&gw, "adb", json!({"job": long, "cancel": true})).await;
    assert!(
        !is_error && cancelled.starts_with(&format!("job {long} cancelled")),
        "{cancelled}"
    );

    let (is_error, both, _) = call(&gw, "adb", json!({"command": "id", "job": job})).await;
    assert!(
        is_error && both.contains("exactly one of command, continue_from, or job"),
        "{both}"
    );
    drop(phone);

    // A phone before 1.20 cannot hold a job.
    let gw = start_gateway().await;
    let mut old = FakeState::new(&[Capability::AdbShell]);
    old.hello_protocol = Some("1.19".into());
    let _old = connect_phone_as(&gw, old, Transport::WebSocket, "Old phone").await;
    let (is_error, refused, _) =
        call(&gw, "adb", json!({"command": "id", "background": true})).await;
    assert!(
        is_error && refused.contains("too old for adb.shell"),
        "{refused}"
    );
}

/// R29 (e): a chaos run. The link loses answers (the connection drops),
/// sends answers twice, delivers commands twice, and adds delays, from a
/// fixed seed. Every call still gets its answer, and every action happens
/// exactly once and in order (ADR-048), on both transports.
#[tokio::test]
async fn under_a_flaky_link_every_call_is_answered_and_every_action_happens_once() {
    for (transport, seed) in [
        (Transport::WebSocket, 0x5eed_0001),
        (Transport::Poll, 0x5eed_0002),
    ] {
        let gw = start_gateway().await;
        let mut fake = FakeState::new(&[Capability::ClipboardWrite]);
        fake.chaos = Some(latch_fake_device::Chaos::new(seed));
        let Phone {
            state,
            task,
            token,
            control: _first,
        } = connect_phone_as(&gw, fake, transport, "Chaos phone").await;
        // The phone reconnects whenever the link drops, as the app does.
        let looper = {
            let (state, addr) = (state.clone(), gw.addr);
            tokio::spawn(async move {
                let _ = task.await;
                let mut controls = Vec::new();
                loop {
                    let (tx, rx) = mpsc::channel(4);
                    controls.push(tx);
                    let _ = match transport {
                        Transport::WebSocket => {
                            latch_fake_device::run(
                                &format!("ws://{addr}/v1/device"),
                                &token,
                                state.clone(),
                                rx,
                            )
                            .await
                        }
                        Transport::Poll => {
                            latch_fake_device::run_poll(
                                &format!("http://{addr}"),
                                &token,
                                state.clone(),
                                rx,
                            )
                            .await
                        }
                    };
                }
            })
        };
        let wanted: Vec<String> = (0..40).map(|i| format!("chaos {i}")).collect();
        for text in &wanted {
            let (is_error, answer, _) = call(&gw, "set_clipboard", json!({ "text": text })).await;
            assert!(!is_error, "{text}: {answer}");
        }
        looper.abort();
        let s = state.lock().expect("lock");
        assert_eq!(s.clipboard, wanted, "each action once, in order");
        let chaos = s.chaos.as_ref().expect("chaos");
        // The run really was flaky.
        assert!(
            chaos.lost > 0 && chaos.duplicated_answers > 0 && chaos.duplicated_commands > 0,
            "{chaos:?}"
        );
    }
}

// ---- Live mirror (R30, ADR-051) ----

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;
use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::tungstenite::Message as WsMessage;

const VIEWER_KEY: &str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO-_";

/// What arrives next on a mirror socket: text, a frame, or the close code.
#[derive(Debug, PartialEq)]
enum Got {
    Text(Value),
    Frame(Vec<u8>),
    Closed(u16),
}

async fn next(ws: &mut Ws) -> Got {
    loop {
        let message = tokio::time::timeout(Duration::from_secs(5), ws.next())
            .await
            .expect("a mirror message in time");
        match message {
            Some(Ok(WsMessage::Text(t))) => {
                return Got::Text(serde_json::from_str(&t).expect("json"));
            }
            Some(Ok(WsMessage::Binary(b))) => return Got::Frame(b.to_vec()),
            Some(Ok(WsMessage::Close(frame))) => {
                return Got::Closed(frame.map_or(1005, |f| u16::from(f.code)));
            }
            Some(Ok(_)) => continue,
            Some(Err(_)) | None => return Got::Closed(1006),
        }
    }
}

/// The next text message of kind `t`, skipping others.
async fn next_of(ws: &mut Ws, t: &str) -> Value {
    loop {
        match next(ws).await {
            Got::Text(v) if v["t"] == t => return v,
            Got::Closed(code) => panic!("closed ({code}) waiting for {t}"),
            _ => {}
        }
    }
}

async fn send_json(ws: &mut Ws, value: Value) {
    ws.send(WsMessage::Text(value.to_string().into()))
        .await
        .expect("send");
}

/// Opens the phone's mirror socket and starts a mirror with `key`, signed with
/// the fake phone's device key as the app signs it. Returns the socket and `ready`.
async fn start_mirror(gw: &Gateway, phone: &Phone, key: &str, signed: bool) -> (Ws, Got) {
    let mut request =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(format!(
            "ws://{}/v1/mirror/phone",
            gw.addr
        ))
        .expect("request");
    request.headers_mut().insert(
        "authorization",
        format!("Bearer {}", phone.token).parse().expect("header"),
    );
    let (mut ws, _) = tokio_tungstenite::connect_async(request)
        .await
        .expect("mirror socket");
    let challenge = next_of(&mut ws, "challenge").await;
    assert_eq!(challenge["version"], 1);
    let connection = challenge["connection"].as_str().expect("connection");
    let start = json!({"t": "start", "version": 1, "key": key}).to_string();
    let device_key = phone.state.lock().expect("lock").device_key.clone();
    let message = match (signed, device_key) {
        (true, Some(k)) => k.seal_text(connection, &start),
        _ => start,
    };
    ws.send(WsMessage::Text(message.into()))
        .await
        .expect("start");
    let ready = next(&mut ws).await;
    (ws, ready)
}

async fn open_viewer(gw: &Gateway) -> Ws {
    let (ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/v1/mirror/view", gw.addr))
        .await
        .expect("viewer socket");
    ws
}

async fn join(gw: &Gateway, how: Value) -> (Ws, Got) {
    let mut ws = open_viewer(gw).await;
    let mut message = json!({"t": "join", "codecs": ["h264", "jpeg"]});
    for (k, v) in how.as_object().expect("object") {
        message[k] = v.clone();
    }
    send_json(&mut ws, message).await;
    let answer = next(&mut ws).await;
    (ws, answer)
}

fn frame(key: bool, ts: u64, payload: &[u8]) -> Vec<u8> {
    let mut f = vec![1, u8::from(key)];
    f.extend_from_slice(&ts.to_be_bytes());
    f.extend_from_slice(payload);
    f
}

#[tokio::test]
async fn the_mirror_shows_the_phone_in_the_owners_browser_and_takes_input_back() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &[]).await;

    // A phone with a device key must sign its start.
    let (_unsigned, refused) = start_mirror(&gw, &phone, VIEWER_KEY, false).await;
    assert_eq!(refused, Got::Closed(4401));

    let (mut ph, ready) = start_mirror(&gw, &phone, VIEWER_KEY, true).await;
    let Got::Text(ready) = ready else {
        panic!("ready: {ready:?}")
    };
    assert_eq!(ready["t"], "ready");
    let code = ready["code"].as_str().expect("code").to_owned();
    assert!(code.len() == 6 && code.bytes().all(|b| b.is_ascii_digit()));
    assert_eq!(ready["urls"][0], format!("http://{}/mirror", gw.addr));

    // A wrong code and a made-up link are refused without saying more.
    let wrong = if code == "000000" { "000001" } else { "000000" };
    let (_w, got) = join(&gw, json!({"code": wrong})).await;
    assert_eq!(got, Got::Closed(4401));
    let (_w, got) = join(
        &gw,
        json!({"key": "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"}),
    )
    .await;
    assert_eq!(got, Got::Closed(4401));

    // The code joins once and is exchanged for the key, for reconnects.
    let (mut viewer, joined) = join(&gw, json!({"code": code})).await;
    let Got::Text(joined) = joined else {
        panic!("joined: {joined:?}")
    };
    assert_eq!(joined["t"], "joined");
    assert_eq!(joined["key"], VIEWER_KEY);
    assert_eq!(joined["device"], "Test phone");
    assert_eq!(joined["codec"], "h264");
    assert_eq!(next_of(&mut ph, "code").await["code"], Value::Null);
    let on = next_of(&mut ph, "viewer").await;
    assert_eq!(
        (on["on"].clone(), on["codec"].clone()),
        (json!(true), json!("h264"))
    );
    let (_w, got) = join(&gw, json!({"code": code})).await;
    assert_eq!(got, Got::Closed(4401), "a code works once");

    // Screen size and frames reach the browser byte for byte.
    send_json(&mut ph, json!({"t": "screen", "w": 1080, "h": 2400})).await;
    assert_eq!(next_of(&mut viewer, "screen").await["w"], 1080);
    let key_frame = frame(
        true,
        1,
        &[0, 0, 0, 1, 0x67, 0x42, 0xe0, 0x1f, 0, 0, 1, 0x65, 9],
    );
    let delta = frame(false, 2, &[0, 0, 1, 0x41, 7]);
    ph.send(WsMessage::Binary(key_frame.clone().into()))
        .await
        .expect("frame");
    ph.send(WsMessage::Binary(delta.clone().into()))
        .await
        .expect("frame");
    assert_eq!(next(&mut viewer).await, Got::Frame(key_frame));
    assert_eq!(next(&mut viewer).await, Got::Frame(delta));

    // Input goes to the phone as validated, rebuilt messages; anything else is dropped.
    send_json(&mut viewer, json!({"t": "shell", "command": "id"})).await;
    send_json(
        &mut viewer,
        json!({"t": "touch", "phase": "down", "x": 10, "y": 20, "extra": 1}),
    )
    .await;
    send_json(
        &mut viewer,
        json!({"t": "touch", "phase": "down", "x": 10, "y": 20}),
    )
    .await;
    send_json(&mut viewer, json!({"t": "text", "text": "hi"})).await;
    send_json(&mut viewer, json!({"t": "control", "action": "pause"})).await;
    assert_eq!(
        next(&mut ph).await,
        Got::Text(json!({"t": "touch", "phase": "down", "x": 10, "y": 20}))
    );
    assert_eq!(
        next(&mut ph).await,
        Got::Text(json!({"t": "text", "text": "hi"}))
    );
    assert_eq!(
        next(&mut ph).await,
        Got::Text(json!({"t": "control", "action": "pause"}))
    );

    // The link takes over from another window; the first one is told why.
    let (mut second, got) = join(&gw, json!({"key": VIEWER_KEY})).await;
    assert!(
        matches!(got, Got::Text(ref v) if v["t"] == "joined"),
        "{got:?}"
    );
    assert_eq!(next(&mut viewer).await, Got::Closed(4409));
    assert_eq!(
        next_of(&mut second, "screen").await["h"],
        2400,
        "the latest screen size first"
    );

    // The phone's network drops: the browser waits, and the picture comes back.
    drop(ph);
    assert_eq!(next_of(&mut second, "phone").await["online"], false);
    let (mut ph, ready) = start_mirror(&gw, &phone, VIEWER_KEY, true).await;
    assert!(
        matches!(ready, Got::Text(ref v) if v["t"] == "ready"),
        "{ready:?}"
    );
    assert_eq!(next_of(&mut second, "phone").await["online"], true);
    assert_eq!(next_of(&mut ph, "viewer").await["on"], true);

    // Ending on the phone ends it in the browser, and the link stops working.
    send_json(&mut ph, json!({"t": "end"})).await;
    assert_eq!(next(&mut second).await, Got::Closed(4410));
    let (_w, got) = join(&gw, json!({"key": VIEWER_KEY})).await;
    assert_eq!(got, Got::Closed(4401));
}

#[tokio::test]
async fn a_slow_browser_skips_to_a_key_frame_instead_of_falling_behind() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &[]).await;
    let (mut ph, ready) = start_mirror(&gw, &phone, VIEWER_KEY, true).await;
    let Got::Text(ready) = ready else {
        panic!("{ready:?}")
    };
    let (mut viewer, _) = join(&gw, json!({"code": ready["code"]})).await;
    next_of(&mut ph, "viewer").await;

    // The browser reads nothing while the phone sends far more than any buffer holds.
    let payload = vec![7u8; 256 * 1024];
    ph.send(WsMessage::Binary(frame(true, 0, &payload).into()))
        .await
        .expect("send");
    let mut ts = 0u64;
    let mut asked = false;
    while ts < 400 {
        ts += 1;
        ph.send(WsMessage::Binary(frame(false, ts, &payload).into()))
            .await
            .expect("send");
        if let Ok(Some(Ok(WsMessage::Text(t)))) =
            tokio::time::timeout(Duration::from_millis(1), ph.next()).await
            && t.contains("\"sync\"")
        {
            asked = true;
            break;
        }
    }
    assert!(
        asked,
        "the phone is asked for a key frame once frames are dropped"
    );

    // Now the browser reads while the phone keeps streaming, answering each
    // request with a key frame. Frames arrive in order; after a gap, the next
    // one is always a key frame, never a delta the browser could not decode.
    let mut last: Option<u64> = None;
    let mut healed = false;
    let mut sync_wanted = true;
    for _ in 0..2_000 {
        ts += 1;
        let key = std::mem::take(&mut sync_wanted);
        ph.send(WsMessage::Binary(frame(key, ts, b"small").into()))
            .await
            .expect("send");
        while let Ok(Some(Ok(m))) = tokio::time::timeout(Duration::from_millis(1), ph.next()).await
        {
            if matches!(m, WsMessage::Text(ref t) if t.contains("\"sync\"")) {
                sync_wanted = true;
            }
        }
        while let Ok(Some(Ok(m))) =
            tokio::time::timeout(Duration::from_millis(1), viewer.next()).await
        {
            let WsMessage::Binary(f) = m else { continue };
            let got = u64::from_be_bytes(f[2..10].try_into().expect("ts"));
            let is_key = f[1] & 1 == 1;
            if let Some(prev) = last {
                assert!(got > prev, "in order");
                if got != prev + 1 {
                    assert!(
                        is_key,
                        "after a gap ({prev} → {got}) the next frame is a key frame"
                    );
                    healed = true;
                }
            }
            last = Some(got);
        }
        if healed && last == Some(ts) {
            return;
        }
    }
    panic!("the browser never caught up (healed: {healed}, last: {last:?}, sent: {ts})");
}

#[tokio::test]
async fn the_mirror_page_is_locked_down_and_other_sites_cannot_connect() {
    let gw = start_gateway().await;
    let (status, head, body) = get_raw(&gw, "/mirror").await;
    assert_eq!(status, 200);
    assert!(
        head.contains("script-src 'self'") && head.contains("connect-src 'self'"),
        "{head}"
    );
    assert!(head.contains("frame-ancestors 'none'"));
    assert!(body.contains("/mirror.js"));
    let (status, head, _) = get_raw(&gw, "/mirror.js").await;
    assert_eq!(status, 200);
    assert!(head.contains("content-type: text/javascript"));

    let mut request =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(format!(
            "ws://{}/v1/mirror/view",
            gw.addr
        ))
        .expect("request");
    request
        .headers_mut()
        .insert("origin", "https://evil.example".parse().expect("origin"));
    assert!(tokio_tungstenite::connect_async(request).await.is_err());

    // A phone's mirror needs its device token.
    let mut request =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(format!(
            "ws://{}/v1/mirror/phone",
            gw.addr
        ))
        .expect("request");
    request
        .headers_mut()
        .insert("authorization", "Bearer dev_wrong".parse().expect("header"));
    assert!(tokio_tungstenite::connect_async(request).await.is_err());
}

#[tokio::test]
async fn revoking_the_phone_ends_its_mirror() {
    let gw = start_gateway().await;
    let phone = connect_phone(&gw, &[]).await;
    let (mut ph, ready) = start_mirror(&gw, &phone, VIEWER_KEY, true).await;
    let Got::Text(ready) = ready else {
        panic!("{ready:?}")
    };
    let (mut viewer, _) = join(&gw, json!({"code": ready["code"]})).await;
    next_of(&mut ph, "viewer").await;
    let device_id = phone
        .state
        .lock()
        .expect("lock")
        .device_id
        .clone()
        .expect("id");
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
    assert_eq!(next(&mut viewer).await, Got::Closed(4410));
    loop {
        if let Got::Closed(_) = next(&mut ph).await {
            break;
        }
    }
}
