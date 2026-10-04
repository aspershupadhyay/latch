// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! `latch-gateway` command-line entry point.

use std::process::ExitCode;
use std::sync::Arc;

use latch_gateway::{AppState, Config, mcp, router, secret, store::Store};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

const USAGE: &str = "\
latch-gateway — connect MCP-capable AI clients to phones you control

USAGE:
    latch-gateway [serve]     Run the HTTP gateway (MCP at /mcp, phones at /v1/device, console at /)
    latch-gateway stdio       Speak MCP on stdin/stdout and serve phones over HTTP in the background
    latch-gateway gen-token   Print a new random token for LATCH_ADMIN_TOKEN or LATCH_MCP_TOKEN
    latch-gateway --version

ENVIRONMENT:
    LATCH_ADMIN_TOKEN       required; owner console and pairing (>= 32 chars)
    LATCH_MCP_TOKEN         required for serve; bearer token for MCP clients (>= 32 chars)
    LATCH_BIND              listen address (default 127.0.0.1:8787, or 0.0.0.0:$PORT if PORT is set)
    LATCH_DATA_DIR          where devices.json lives (default ./latch-data)
    LATCH_PUBLIC_URL        public https URL shown to phones when pairing
    LATCH_ALLOWED_ORIGINS   comma-separated browser origins allowed to call /mcp
    RUST_LOG                log filter (default info)
";

fn init_logging() {
    // Logs go to stderr so stdout stays clean for MCP in stdio mode.
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .with_writer(std::io::stderr)
        // Colour codes only help humans at a terminal; container log collectors get plain text.
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stderr()))
        .init();
}

#[tokio::main]
async fn main() -> ExitCode {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "serve".into());
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
        "serve" | "stdio" => {}
        other => {
            eprintln!("unknown command: {other}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    }
    let stdio = mode == "stdio";
    init_logging();

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
    tracing::info!(%bind, mode, "latch gateway listening");

    let app = router(state.clone());
    let server = axum::serve(listener, app).with_graceful_shutdown(shutdown_signal());

    if stdio {
        let http = tokio::spawn(async move { server.await });
        serve_stdio(state).await;
        http.abort();
        return ExitCode::SUCCESS;
    }
    match server.await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("server error: {e}");
            ExitCode::FAILURE
        }
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
