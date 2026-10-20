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
    ExitCode::SUCCESS
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
