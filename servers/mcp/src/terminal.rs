// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! What `latch-gateway` prints for the person at the terminal (ADR-054):
//! a short start-up summary and, for phones on the Wi-Fi, a QR code the
//! Latch app scans to connect with no key to copy.
//!
//! Everything here goes to stderr, so stdio mode keeps stdout for MCP. The QR
//! code holds the Wi-Fi address, the certificate pin, and a single-use setup
//! code that expires; it never holds the owner key or the MCP token.

use std::fmt::Write as _;
use std::path::Path;

use qrcode::{Color, EcLevel, QrCode};

use crate::pairing::OWNER_CODE_TTL_MS;

/// What the phone needs to connect: `https://IP:PORT#pin=…&code=…`.
/// The app reads the address and pin as before and pairs with the code.
pub fn setup_link(address: &str, code: &str) -> String {
    let compact: String = code.chars().filter(char::is_ascii_alphanumeric).collect();
    format!("{address}&code={compact}")
}

/// `text` as a QR code in half-block characters, two modules per character
/// row, dark on light with a quiet zone, so it scans on dark and light
/// terminals alike. With `color` false (NO_COLOR), light modules are drawn
/// as full blocks instead, which reads correctly on a dark terminal.
pub fn qr(text: &str, color: bool) -> Option<String> {
    let code = QrCode::with_error_correction_level(text.as_bytes(), EcLevel::L).ok()?;
    let width = code.width();
    let quiet = 2;
    let size = width + 2 * quiet;
    let colors = code.to_colors();
    let dark = |x: usize, y: usize| -> bool {
        x >= quiet
            && y >= quiet
            && x < width + quiet
            && y < width + quiet
            && colors[(y - quiet) * width + (x - quiet)] == Color::Dark
    };
    let mut out = String::new();
    for row in (0..size).step_by(2) {
        out.push_str("    ");
        if color {
            // Black on white, whatever the terminal's own colours are.
            out.push_str("\x1b[38;5;16;48;5;231m");
        }
        for x in 0..size {
            let (top, bottom) = (dark(x, row), row + 1 < size && dark(x, row + 1));
            let (top, bottom) = if color {
                (top, bottom)
            } else {
                (!top, !bottom && row + 1 < size)
            };
            out.push(match (top, bottom) {
                (true, true) => '█',
                (true, false) => '▀',
                (false, true) => '▄',
                (false, false) => ' ',
            });
        }
        if color {
            out.push_str("\x1b[0m");
        }
        out.push('\n');
    }
    Some(out)
}

/// The NO_COLOR convention (no-color.org): set and not empty.
fn no_color() -> bool {
    std::env::var_os("NO_COLOR").is_some_and(|v| !v.is_empty())
}

/// Styling for the summary: bold and dim on a terminal, plain otherwise.
#[derive(Clone, Copy)]
pub struct Style {
    pub ansi: bool,
}

impl Style {
    /// ANSI styling when stderr is a terminal and NO_COLOR is not set.
    pub fn detect() -> Self {
        Self {
            ansi: std::io::IsTerminal::is_terminal(&std::io::stderr()) && !no_color(),
        }
    }

    fn wrap(self, code: &str, text: &str) -> String {
        if self.ansi {
            format!("\x1b[{code}m{text}\x1b[0m")
        } else {
            text.to_owned()
        }
    }

    pub fn bold(self, text: &str) -> String {
        self.wrap("1", text)
    }

    pub fn dim(self, text: &str) -> String {
        self.wrap("2", text)
    }

    pub fn accent(self, text: &str) -> String {
        self.wrap("1;36", text)
    }
}

/// A phone setup code to show: the Wi-Fi address with pin, and the code.
pub struct PhoneSetup {
    pub address: String,
    pub code: String,
}

/// The "connect your phone" block: steps, QR code, and the typed fallback.
pub fn phone_block(style: Style, setup: &PhoneSetup) -> String {
    let mut out = String::new();
    let link = setup_link(&setup.address, &setup.code);
    let minutes = OWNER_CODE_TTL_MS / 60_000;
    let _ = writeln!(out, "  {}", style.bold("Connect your phone"));
    let _ = writeln!(
        out,
        "    In the Latch app: {} → {} → {}, then point the camera here.\n",
        style.bold("Set up Latch"),
        style.bold("Computer on Wi-Fi"),
        style.bold("Scan QR code")
    );
    match qr(&link, !no_color()) {
        Some(code) => out.push_str(&code),
        None => out.push_str("    (the QR code could not be drawn; enter the details below)\n"),
    }
    let _ = writeln!(out);
    let _ = writeln!(
        out,
        "    {}  {}",
        style.dim("Can't scan? Address"),
        setup.address
    );
    let _ = writeln!(
        out,
        "    {}  {}  {}",
        style.dim("           Setup code"),
        style.accent(&setup.code),
        style.dim(&format!("single use, expires in {minutes} min"))
    );
    let _ = writeln!(
        out,
        "    {}",
        style.dim("New code any time: latch-gateway pair")
    );
    out
}

/// What the gateway prints once it is listening.
pub struct Summary<'a> {
    pub version: &'a str,
    pub stdio: bool,
    pub bind: std::net::SocketAddr,
    pub data_dir: &'a Path,
    pub phone: Option<PhoneSetup>,
    /// The Wi-Fi listener runs but this computer has no private address.
    pub wifi_without_address: Option<u16>,
}

/// The start-up summary: what runs where, and how to connect a phone.
pub fn summary(style: Style, s: &Summary<'_>) -> String {
    let local = format!(
        "http://{}",
        s.bind.to_string().replace("0.0.0.0", "127.0.0.1")
    );
    let rule = style.dim(&"─".repeat(64));
    let mut out = String::new();
    let _ = writeln!(out);
    let _ = writeln!(
        out,
        "  {} {}  {}",
        style.accent("Latch gateway"),
        style.dim(s.version),
        style.bold("ready")
    );
    let _ = writeln!(out, "  {rule}");
    match &s.phone {
        Some(setup) => out.push_str(&phone_block(style, setup)),
        None => match s.wifi_without_address {
            Some(port) => {
                let _ = writeln!(out, "  {}", style.bold("Connect your phone"));
                let _ = writeln!(
                    out,
                    "    This computer has no address on a home Wi-Fi yet. Join one, then run {}.",
                    style.bold("latch-gateway pair")
                );
                let _ = writeln!(
                    out,
                    "    {}",
                    style.dim(&format!("Phones connect on port {port}."))
                );
            }
            None => {
                let _ = writeln!(out, "  {}", style.bold("Connect your phone"));
                let _ = writeln!(
                    out,
                    "    Pair from the console at {local}/, or start with {} for phones on this Wi-Fi.",
                    style.bold("LATCH_WIFI=1")
                );
            }
        },
    }
    let _ = writeln!(out, "  {rule}");
    let row = |out: &mut String, label: &str, value: &str| {
        let _ = writeln!(out, "  {}  {value}", style.dim(&format!("{label:<12}")));
    };
    if s.stdio {
        row(&mut out, "AI app", "connected over stdio (this process)");
    } else {
        row(
            &mut out,
            "AI apps",
            &format!(
                "{local}/mcp  {}",
                style.dim("(bearer token: LATCH_MCP_TOKEN)")
            ),
        );
    }
    row(&mut out, "Live mirror", &format!("{local}/mirror"));
    row(&mut out, "Console", &format!("{local}/"));
    row(&mut out, "Data", &s.data_dir.display().to_string());
    let _ = writeln!(out, "  {rule}");
    let _ = writeln!(
        out,
        "  {}",
        style.dim("Press Ctrl+C to stop. Activity appears below.")
    );
    let _ = writeln!(out);
    out
}

/// `latch-gateway pair`: asks the gateway running on this computer for a new
/// setup code, with the owner token from the environment.
pub async fn request_owner_code(
    bind: std::net::SocketAddr,
    admin_token: &str,
) -> Result<String, String> {
    let addr = bind.to_string().replace("0.0.0.0", "127.0.0.1");
    let (status, body) = crate::phone_check::post_local(
        &addr,
        "/v1/admin/pairings",
        admin_token,
        r#"{"owner":true}"#,
    )
    .await
    .map_err(|e| format!("{e}. Is the gateway running? Start it with latch-start."))?;
    match status.as_str() {
        "200" => serde_json::from_str::<serde_json::Value>(&body)
            .ok()
            .and_then(|v| v["code"].as_str().map(str::to_owned))
            .ok_or_else(|| "the gateway sent an unexpected answer".to_owned()),
        "401" => Err(format!(
            "the gateway at {addr} uses a different LATCH_ADMIN_TOKEN than this terminal. \
             Run this from latch-start's environment (source ~/.latch/latch.env), or stop the other gateway."
        )),
        "429" => Err("too many setup codes are waiting; wait a few minutes".into()),
        other => Err(format!("the gateway answered HTTP {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_link_carries_address_pin_and_compact_code() {
        let link = setup_link("https://192.168.1.5:8788#pin=abc", "K7QX-M2PD");
        assert_eq!(link, "https://192.168.1.5:8788#pin=abc&code=K7QXM2PD");
    }

    #[test]
    fn a_real_setup_link_fits_a_small_qr_code() {
        let pin = "A".repeat(43);
        let link = setup_link(
            &format!("https://192.168.100.200:8788#pin={pin}"),
            "ABCD-EFGH",
        );
        let drawn = qr(&link, true).expect("drawn");
        let rows = drawn.lines().count();
        // Version 5 (37 modules) plus the quiet zone, two modules per row.
        assert!(rows <= 21, "{rows} rows");
        assert!(drawn.contains('█'));
    }

    #[test]
    fn the_summary_never_shows_a_key() {
        let style = Style { ansi: false };
        let text = summary(
            style,
            &Summary {
                version: "0.1.0",
                stdio: false,
                bind: "127.0.0.1:8787".parse().expect("addr"),
                data_dir: Path::new("/tmp/latch"),
                phone: Some(PhoneSetup {
                    address: "https://192.168.1.5:8788#pin=abc".into(),
                    code: "K7QX-M2PD".into(),
                }),
                wifi_without_address: None,
            },
        );
        assert!(text.contains("K7QX-M2PD"));
        assert!(text.contains("http://127.0.0.1:8787/mcp"));
        assert!(!text.contains("lgt_") && !text.contains("lok_"));
    }
}
