// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! `latch-fake-device` — a simulated phone for trying Latch without hardware.
//!
//!     latch-fake-device http://127.0.0.1:8787 ABCD-EFGH [--poll]
//!
//! `--poll` uses the HTTP long-poll transport (required by serverless gateways).
//! `--local-transfers` switches on same-Wi-Fi transfers (protocol 1.14), served on 127.0.0.1.
//! `--lose-answer <command>` runs the first such command (e.g. `nav.global`), then drops the
//! connection before answering and reconnects, as a flaky network does (protocol 1.19).
//!
//! Pairs with the code, enables every capability except screenshots-of-real-pixels
//! (it sends a 1x1 placeholder), and serves commands until stopped.

use std::process::ExitCode;
use std::sync::{Arc, Mutex};

use latch_fake_device::{Control, FakeState, pair, run, run_poll};
use latch_protocol::Capability;

#[tokio::main]
async fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let poll = args.iter().any(|a| a == "--poll");
    let local_transfers = args.iter().any(|a| a == "--local-transfers");
    let lose_answer_to = args
        .iter()
        .position(|a| a == "--lose-answer")
        .and_then(|i| args.get(i + 1))
        .cloned();
    let positional: Vec<&String> = args
        .iter()
        .filter(|a| !a.starts_with("--") && Some(*a) != lose_answer_to.as_ref())
        .collect();
    let [base, code] = positional.as_slice() else {
        eprintln!(
            "usage: latch-fake-device <http://gateway:port> <PAIRING-CODE> [--poll] [--local-transfers] [--lose-answer <command>]"
        );
        return ExitCode::from(2);
    };
    let (device_id, token) = match pair(base, code).await {
        Ok(p) => p,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::FAILURE;
        }
    };
    eprintln!("paired as {device_id}; serving commands (Ctrl-C to stop)");
    let mut fake = FakeState::new(&Capability::ALL);
    fake.local_transfers = local_transfers;
    fake.lose_answer_to = lose_answer_to;
    let state = Arc::new(Mutex::new(fake));
    let ws = format!(
        "{}/v1/device",
        base.replacen("http://", "ws://", 1).trim_end_matches('/')
    );
    let (tx, mut rx) = tokio::sync::mpsc::channel(4);
    tokio::spawn(async move {
        let _ = tokio::signal::ctrl_c().await;
        let _ = tx.send(Control::Stop).await;
    });
    let result = loop {
        let lost = state.lock().map_or(0, |s| s.lost_answers);
        // Each connection gets its own control channel; Ctrl-C is passed on so it says bye.
        let (inner_tx, inner_rx) = tokio::sync::mpsc::channel(4);
        let serve = async {
            if poll {
                run_poll(base, &token, state.clone(), inner_rx).await
            } else {
                run(&ws, &token, state.clone(), inner_rx).await
            }
        };
        tokio::pin!(serve);
        let mut stopping = false;
        let result = loop {
            tokio::select! {
                result = &mut serve => break result,
                _ = rx.recv(), if !stopping => {
                    stopping = true;
                    let _ = inner_tx.send(Control::Stop).await;
                }
            }
        };
        // An answer was lost on purpose: connect again, as the app does.
        if stopping || result.is_err() || state.lock().map_or(0, |s| s.lost_answers) == lost {
            break result;
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e}");
            ExitCode::FAILURE
        }
    }
}
