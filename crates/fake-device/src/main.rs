//! `latch-fake-device` — a simulated phone for trying Latch without hardware.
//!
//!     latch-fake-device http://127.0.0.1:8787 ABCD-EFGH
//!
//! Pairs with the code, enables every capability except screenshots-of-real-pixels
//! (it sends a 1x1 placeholder), and serves commands until stopped.

use std::process::ExitCode;
use std::sync::{Arc, Mutex};

use latch_fake_device::{Control, FakeState, pair, run};
use latch_protocol::Capability;

#[tokio::main]
async fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [base, code] = args.as_slice() else {
        eprintln!("usage: latch-fake-device <http://gateway:port> <PAIRING-CODE>");
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
    let state = Arc::new(Mutex::new(FakeState::new(&Capability::ALL)));
    let ws = format!(
        "{}/v1/device",
        base.replacen("http://", "ws://", 1).trim_end_matches('/')
    );
    let (tx, rx) = tokio::sync::mpsc::channel(4);
    tokio::spawn(async move {
        let _ = tokio::signal::ctrl_c().await;
        let _ = tx.send(Control::Stop).await;
    });
    match run(&ws, &token, state, rx).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e}");
            ExitCode::FAILURE
        }
    }
}
