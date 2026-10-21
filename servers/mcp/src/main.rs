// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! `latch-gateway` command-line entry point.

use std::process::ExitCode;
use std::sync::Arc;

use latch_gateway::terminal::{self, PhoneSetup, Style, Summary};
use latch_gateway::{AppState, Config, mcp, now_ms, router, secret, store::Store, wifi};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

const USAGE: &str = "\
latch-gateway — connect MCP-capable AI clients to phones you control

USAGE:
    latch-gateway [serve]     Run the HTTP gateway (MCP at /mcp, phones at /v1/device, console at /)
    latch-gateway stdio       Speak MCP on stdin/stdout and serve phones over HTTP in the background
    latch-gateway pair        Show a new QR code and setup code for a phone on this Wi-Fi
                              (--open: the same code, big, in the browser)
                              (the gateway must be running; uses LATCH_ADMIN_TOKEN)
    latch-gateway status      Is the gateway running? Its version, phones, Wi-Fi address, and data folder
                              (uses LATCH_ADMIN_TOKEN for the phones; exit code 1 when not running)
    latch-gateway usb         Connect phones on a USB cable for the lowest mirror delay (needs adb and
                              USB debugging; --watch: keep connecting phones as they are plugged in)
    latch-gateway gen-token   Print a new random token for LATCH_ADMIN_TOKEN or LATCH_MCP_TOKEN
    latch-gateway check-phone [--url URL] [--device ID] [--quick] [--no-prompts] [--report FILE]
                              Check a connected phone end to end over this gateway's MCP
                              (needs LATCH_MCP_TOKEN; URL defaults to http://127.0.0.1:8787/mcp)
                              and write a Markdown report with no screen content in it
    latch-gateway --version

ENVIRONMENT:
    LATCH_ADMIN_TOKEN       required; owner console and pairing (>= 32 chars)
    LATCH_MCP_TOKEN         required for serve; bearer token for MCP clients (>= 32 chars)
    LATCH_BIND              listen address (default 127.0.0.1:8787, or 0.0.0.0:$PORT if PORT is set)
    LATCH_DATA_DIR          where devices.json lives (default ./latch-data)
    LATCH_PUBLIC_URL        public https URL shown to phones when pairing
    LATCH_ALLOWED_ORIGINS   comma-separated browser origins allowed to call /mcp
    LATCH_WIFI              1: also serve phones on this Wi-Fi with TLS, on 0.0.0.0:8788
    LATCH_WIFI_BIND         the Wi-Fi listener's address instead (implies LATCH_WIFI)
    LATCH_NEARBY            0: turn off Find my computer (linking with no QR code)
    LATCH_NAME              this computer's name in the app's Find my computer list

Installed with install-gateway.sh, the `latch` command wraps these:
    latch start | pair | status | usb | update | uninstall | autostart on|off | stop | restart | logs
    RUST_LOG                log filter (default info)
";

/// Short, local-time log lines: `14:02:07  INFO phone connected device_id=…`.
/// Must run before the runtime starts threads: reading the local UTC offset
/// is only sound while the process has one thread.
fn init_logging() {
    let offset = time::UtcOffset::current_local_offset().unwrap_or(time::UtcOffset::UTC);
    let timer = tracing_subscriber::fmt::time::OffsetTime::new(
        offset,
        time::macros::format_description!("[hour]:[minute]:[second]"),
    );
    // Logs go to stderr so stdout stays clean for MCP in stdio mode.
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                // rustls reports every refused TLS handshake (the phone's search for a
                // moved gateway makes many); they are not the owner's problem.
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,rustls=off")),
        )
        .with_writer(std::io::stderr)
        .with_timer(timer)
        .with_target(false)
        .compact()
        // Colour codes only help humans at a terminal; container log collectors get plain text.
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stderr()))
        .init();
}

fn main() -> ExitCode {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "serve".into());
    if mode == "serve" || mode == "stdio" {
        init_logging();
    }
    match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime.block_on(run(mode)),
        Err(e) => {
            eprintln!("cannot start: {e}");
            ExitCode::FAILURE
        }
    }
}

async fn run(mode: String) -> ExitCode {
    match mode.as_str() {
        "gen-token" => {
            println!("{}", secret::new_token("lgt"));
            return ExitCode::SUCCESS;
        }
        "--version" | "-V" => {
            println!("latch-gateway {}", env!("CARGO_PKG_VERSION"));
            return ExitCode::SUCCESS;
        }
        "--help" | "-h" | "help" => {
            print!("{USAGE}");
            return ExitCode::SUCCESS;
        }
        "check-phone" => return check_phone().await,
        "pair" => return pair().await,
        "usb" => return usb().await,
        "status" => return status().await,
        "serve" | "stdio" => {}
        other => {
            eprintln!("unknown command: {other}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    }
    let stdio = mode == "stdio";

    let config = match Config::from_env(stdio) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("configuration error: {e}");
            return ExitCode::from(2);
        }
    };
    let store = match Store::open(&config.data_dir) {
        Ok(s) => s,
        Err(e) => {
            eprintln!(
                "cannot open data directory {}: {e}",
                config.data_dir.display()
            );
            return ExitCode::FAILURE;
        }
    };
    let bind = config.bind;
    let state = Arc::new(AppState::new(config, store));

    let listener = match tokio::net::TcpListener::bind(bind).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("cannot listen on {bind}: {e}");
            return ExitCode::FAILURE;
        }
    };
    tracing::debug!(%bind, mode, "listening");

    let app = router(state.clone());
    // Expired file links lose their keys and stored bytes within a minute, even when idle.
    let sweeper = {
        let state = state.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(std::time::Duration::from_secs(60));
            loop {
                tick.tick().await;
                state.links.sweep();
                state.transfers.sweep();
            }
        })
    };
    let mut summary = Summary {
        version: env!("CARGO_PKG_VERSION"),
        stdio,
        bind,
        data_dir: &state.config.data_dir,
        phone: None,
        wifi_without_address: None,
    };
    let wifi = match state.config.wifi_bind {
        Some(addr) => match start_wifi(&state, addr, app.clone()).await {
            Ok((task, port, pin, address)) => {
                match address {
                    Some(address) => summary.phone = phone_setup(&state, address),
                    None => summary.wifi_without_address = Some((port, pin)),
                }
                Some(task)
            }
            Err(e) => {
                eprintln!("cannot serve phones on the Wi-Fi at {addr}: {e}");
                return ExitCode::FAILURE;
            }
        },
        None => None,
    };
    eprint!("{}", terminal::summary(Style::detect(), &summary));
    // ADR-060: in a terminal, phones from "Find my computer" are approved here.
    if !stdio
        && std::io::IsTerminal::is_terminal(&std::io::stdin())
        && state.config.wifi_bind.is_some()
    {
        tokio::spawn(terminal::approve_nearby(
            state.config.bind,
            state.config.admin_token.clone(),
            Style::detect(),
            None,
        ));
    }
    // No Nagle delay: mirror frames and taps are small and must go out at once.
    let listener = axum::serve::ListenerExt::tap_io(listener, |tcp| {
        let _ = tcp.set_nodelay(true);
    });
    let server = axum::serve(listener, app).with_graceful_shutdown(shutdown_signal());

    if stdio {
        let http = tokio::spawn(async move { server.await });
        serve_stdio(state).await;
        http.abort();
        if let Some(wifi) = wifi {
            wifi.abort();
        }
        sweeper.abort();
        return ExitCode::SUCCESS;
    }
    let result = server.await;
    if let Some(wifi) = wifi {
        wifi.abort();
    }
    sweeper.abort();
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("server error: {e}");
            ExitCode::FAILURE
        }
    }
}

/// R24: the TLS listener for phones on the same Wi-Fi. Returns its port, the
/// certificate pin, and, when this computer has a private address, the
/// address with pin for the app.
async fn start_wifi(
    state: &Arc<AppState>,
    addr: std::net::SocketAddr,
    app: axum::Router,
) -> std::io::Result<(tokio::task::JoinHandle<()>, u16, String, Option<String>)> {
    let tls = wifi::load_or_create(&state.config.data_dir)?;
    let listener = tokio::net::TcpListener::bind(addr).await?;
    let port = listener.local_addr()?.port();
    let address = wifi::lan_ip().map(|ip| wifi::phone_address(ip, port, &tls.pin));
    // R56: the mirror's frames over UDP on the same port; without it they stay on the WebSocket.
    match tokio::net::UdpSocket::bind(std::net::SocketAddr::new(addr.ip(), port)).await {
        Ok(udp) => state.udp.start(udp),
        Err(e) => tracing::warn!(error = %e, "no UDP for the mirror; it uses its WebSocket"),
    }
    tracing::debug!(%addr, "serving phones on the Wi-Fi");
    Ok((
        tokio::spawn(wifi::serve(listener, tls.config, app)),
        port,
        tls.pin,
        address,
    ))
}

/// A single-use setup code for the owner's phone, shown as a QR code (ADR-054).
fn phone_setup(state: &AppState, address: String) -> Option<PhoneSetup> {
    match state.pairings.lock() {
        Ok(mut pairings) => match pairings.create_owner(now_ms()) {
            Ok((code, _)) => {
                let mut setup = PhoneSetup {
                    address,
                    code,
                    page: None,
                };
                setup.page = terminal::write_qr_page(&state.config.data_dir, &setup);
                Some(setup)
            }
            Err(e) => {
                tracing::warn!(error = %e, "no setup code");
                None
            }
        },
        Err(_) => None,
    }
}

/// `latch-gateway usb` (R57): puts this gateway's Wi-Fi port on each cabled
/// phone's own 127.0.0.1 (`adb reverse`); the app finds its pinned key there
/// and moves its links to the cable. `--watch` keeps doing it as phones come and go.
async fn usb() -> ExitCode {
    let style = Style::detect();
    let port = std::env::var("LATCH_WIFI_BIND")
        .ok()
        .and_then(|b| b.parse::<std::net::SocketAddr>().ok())
        .map_or(wifi::DEFAULT_BIND.port(), |a| a.port());
    let Some(adb) = latch_gateway::usb::adb() else {
        eprintln!(
            "latch-gateway usb: adb was not found. Install Android's platform tools (https://developer.android.com/tools/releases/platform-tools), or set ADB to its path."
        );
        return ExitCode::FAILURE;
    };
    if tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .is_err()
    {
        eprintln!(
            "latch-gateway usb: nothing answers on port {port} here. Start the gateway with its Wi-Fi listener first (latch start)."
        );
        return ExitCode::FAILURE;
    }
    let watch = std::env::args().skip(2).any(|a| a == "--watch");
    let mut done: Vec<String> = Vec::new();
    let mut told_unauthorized = false;
    let mut told_none = false;
    loop {
        match latch_gateway::usb::connect(&adb, port, &done) {
            Ok(round) => {
                for serial in &round.connected {
                    eprintln!(
                        "  {} {serial}: the app moves to the cable within a few seconds.",
                        style.accent("Connected over USB:")
                    );
                }
                for (serial, why) in &round.failed {
                    eprintln!("  Could not connect {serial}: {why}");
                }
                if !round.unauthorized.is_empty() && !told_unauthorized {
                    told_unauthorized = true;
                    eprintln!(
                        "  Unlock the phone and allow USB debugging for this computer, then run this again."
                    );
                }
                done.extend(round.connected);
            }
            Err(e) => {
                eprintln!("latch-gateway usb: {e}");
                return ExitCode::FAILURE;
            }
        }
        if !watch {
            break;
        }
        if done.is_empty() && !told_none {
            told_none = true;
            eprintln!(
                "  Waiting for a phone on a USB cable (with USB debugging on). Ctrl+C to stop."
            );
        }
        tokio::time::sleep(std::time::Duration::from_secs(3)).await;
    }
    if done.is_empty() {
        eprintln!(
            "  No phone on a USB cable. Plug it in, turn on USB debugging (Developer options), and allow this computer."
        );
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}

/// `latch-gateway pair`: a new setup code from the gateway running here.
async fn pair() -> ExitCode {
    let config = match Config::from_env(true) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("latch-gateway pair: {e}");
            return ExitCode::from(2);
        }
    };
    let port = config.wifi_bind.unwrap_or(wifi::DEFAULT_BIND).port();
    let pin = match wifi::existing_pin(&config.data_dir) {
        Ok(pin) => pin,
        Err(_) => {
            eprintln!(
                "latch-gateway pair: no Wi-Fi certificate in {}. Start the gateway with LATCH_WIFI=1 (latch-start does) and use the same LATCH_DATA_DIR.",
                config.data_dir.display()
            );
            return ExitCode::FAILURE;
        }
    };
    let Some(ip) = wifi::lan_ip() else {
        eprintln!(
            "latch-gateway pair: this computer has no address on a home Wi-Fi. Join one and try again."
        );
        return ExitCode::FAILURE;
    };
    let code = match terminal::request_owner_code(config.bind, &config.admin_token).await {
        Ok(code) => code,
        Err(e) => {
            eprintln!("latch-gateway pair: {e}");
            return ExitCode::FAILURE;
        }
    };
    let mut setup = PhoneSetup {
        address: wifi::phone_address(ip, port, &pin),
        code,
        page: None,
    };
    setup.page = terminal::write_qr_page(&config.data_dir, &setup);
    eprintln!();
    eprint!("{}", terminal::phone_block(Style::detect(), &setup));
    eprintln!();
    // `pair --open`: the same code, big, in the browser (a terminal font can break a code up).
    if std::env::args().skip(2).any(|a| a == "--open") {
        match &setup.page {
            Some(page) if terminal::open_in_browser(page) => {
                eprintln!("    Opened the big code in your browser.\n");
            }
            _ => eprintln!("    The big code could not be opened; use the link above.\n"),
        }
    }
    // ADR-060: wait here for phones from "Find my computer" while the code is valid.
    if std::io::IsTerminal::is_terminal(&std::io::stdin()) {
        eprintln!(
            "    Waiting for a phone (Scan, or Find my computer in the app). Ctrl+C when done.\n"
        );
        let until = std::time::Instant::now()
            + std::time::Duration::from_millis(latch_gateway::pairing::OWNER_CODE_TTL_MS);
        terminal::approve_nearby(
            config.bind,
            config.admin_token.clone(),
            Style::detect(),
            Some(until),
        )
        .await;
    }
    ExitCode::SUCCESS
}

/// `latch-gateway status`: what runs on this computer, in a few lines.
async fn status() -> ExitCode {
    let style = Style::detect();
    let bind = Config::from_env(true).map(|c| c.bind).unwrap_or_else(|_| {
        std::env::var("LATCH_BIND")
            .ok()
            .and_then(|b| b.parse().ok())
            .unwrap_or_else(|| std::net::SocketAddr::from(([127, 0, 0, 1], 8787)))
    });
    let token = std::env::var("LATCH_ADMIN_TOKEN").unwrap_or_default();
    let addr = bind.to_string().replace("0.0.0.0", "127.0.0.1");
    let data_dir = std::path::PathBuf::from(
        std::env::var("LATCH_DATA_DIR").unwrap_or_else(|_| "./latch-data".into()),
    );
    let row =
        |label: &str, value: &str| eprintln!("  {}  {value}", style.dim(&format!("{label:<10}")));
    eprintln!();
    let health =
        latch_gateway::phone_check::request_local("GET", &addr, "/healthz", &token, "").await;
    let running = match &health {
        Ok((code, body)) if code == "200" => {
            let version = serde_json::from_str::<serde_json::Value>(body)
                .ok()
                .and_then(|v| v["version"].as_str().map(str::to_owned))
                .unwrap_or_default();
            row(
                "Gateway",
                &format!("{} {}  at http://{addr}", style.bold("running"), version),
            );
            true
        }
        _ => {
            row(
                "Gateway",
                &format!("{}  (start it with latch start)", style.bold("not running")),
            );
            false
        }
    };
    row("Installed", env!("CARGO_PKG_VERSION"));
    if running && !token.is_empty() {
        match latch_gateway::phone_check::request_local(
            "GET",
            &addr,
            "/v1/admin/devices",
            &token,
            "",
        )
        .await
        {
            Ok((code, body)) if code == "200" => {
                let list = serde_json::from_str::<serde_json::Value>(&body).unwrap_or_default();
                let devices = list["devices"].as_array().cloned().unwrap_or_default();
                let online = devices.iter().filter(|d| d["connected"] == true).count();
                let names: Vec<_> = devices.iter().filter_map(|d| d["name"].as_str()).collect();
                row(
                    "Phones",
                    &format!(
                        "{} paired, {online} connected{}",
                        devices.len(),
                        if names.is_empty() {
                            String::new()
                        } else {
                            format!("  ({})", names.join(", "))
                        }
                    ),
                );
            }
            Ok((code, _)) if code == "401" => row(
                "Phones",
                "unknown: this terminal's LATCH_ADMIN_TOKEN is not the gateway's",
            ),
            _ => row("Phones", "unknown"),
        }
    }
    match (wifi::lan_ip(), wifi::existing_pin(&data_dir)) {
        (Some(ip), Ok(_)) => row(
            "Wi-Fi",
            &format!("phones reach it at {ip}:{}", wifi::DEFAULT_BIND.port()),
        ),
        (None, Ok(_)) => row("Wi-Fi", "this computer has no address on a home Wi-Fi"),
        _ => row("Wi-Fi", "off (latch start turns it on)"),
    }
    row("Data", &data_dir.display().to_string());
    eprintln!();
    if running {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

/// Newline-delimited JSON-RPC on stdin/stdout, per the MCP stdio transport.
async fn serve_stdio(state: Arc<AppState>) {
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    let mut stdout = tokio::io::stdout();
    while let Ok(Some(line)) = lines.next_line().await {
        if line.trim().is_empty() {
            continue;
        }
        let reply = match serde_json::from_str(&line) {
            Ok(message) => mcp::handle(&state, message).await,
            Err(_) => Some(serde_json::json!({
                "jsonrpc": "2.0", "id": null,
                "error": { "code": -32700, "message": "parse error" }
            })),
        };
        if let Some(reply) = reply {
            let mut out = reply.to_string();
            out.push('\n');
            if stdout.write_all(out.as_bytes()).await.is_err() || stdout.flush().await.is_err() {
                break;
            }
        }
    }
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut s) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            s.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    tracing::info!("shutting down");
}

/// `latch-gateway check-phone`: the scripted real-device check (roadmap R29 f).
async fn check_phone() -> ExitCode {
    use latch_gateway::phone_check::{self, Owner, Target, Terminal, Unattended};
    let args: Vec<String> = std::env::args().skip(2).collect();
    let value = |flag: &str| {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    let has = |flag: &str| args.iter().any(|a| a == flag);
    let url = value("--url").unwrap_or_else(|| {
        let bind = std::env::var("LATCH_BIND").unwrap_or_else(|_| "127.0.0.1:8787".into());
        format!("http://{}/mcp", bind.replace("0.0.0.0", "127.0.0.1"))
    });
    let token = value("--token")
        .or_else(|| std::env::var("LATCH_MCP_TOKEN").ok())
        .unwrap_or_default();
    let mut target = match Target::parse(&url, &token) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("check-phone: {e}");
            return ExitCode::from(2);
        }
    };
    target.device_id = value("--device");
    let mut terminal = Terminal;
    let mut unattended = Unattended;
    let owner: &mut dyn Owner = if has("--no-prompts") {
        &mut unattended
    } else {
        &mut terminal
    };
    let report = match phone_check::run(target, owner, has("--quick")).await {
        Ok(r) => r,
        Err(e) => {
            eprintln!("check-phone: {e}");
            return ExitCode::FAILURE;
        }
    };
    let at = phone_check::now_s();
    let path = value("--report").unwrap_or_else(|| {
        format!(
            "latch-phone-check-{}.md",
            phone_check::utc(at)
                .replace([' ', ':'], "-")
                .trim_end_matches("-UTC")
        )
    });
    if let Err(e) = std::fs::write(&path, report.markdown(at)) {
        eprintln!("check-phone: could not write {path}: {e}");
        return ExitCode::FAILURE;
    }
    println!(
        "\n{} Report: {path}",
        if report.passed() {
            "Every step that ran passed."
        } else {
            "Some steps failed; the report says which."
        }
    );
    if report.passed() {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}
