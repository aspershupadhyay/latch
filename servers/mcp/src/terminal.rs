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

/// White border around the code, in modules: the QR standard asks for 4,
/// and scanners lose a code whose finder patterns touch a dark terminal.
const QUIET: usize = 4;

fn qr_code(text: &str) -> Option<QrCode> {
    QrCode::with_error_correction_level(text.as_bytes(), EcLevel::L).ok()
}

/// `text` as a QR code in half-block characters, two modules per character
/// row, dark on light with a quiet zone, so it scans on dark and light
/// terminals alike. With `color`, a cell whose two modules match is a space
/// painted with the background colour, which fills the whole cell: a font
/// with line spacing then leaves no white stripes through dark areas (a
/// common reason a terminal code does not scan). With `color` false
/// (NO_COLOR), light modules are drawn as full blocks instead, which reads
/// correctly on a dark terminal.
pub fn qr(text: &str, color: bool) -> Option<String> {
    let code = qr_code(text)?;
    let width = code.width();
    let quiet = QUIET;
    let size = width + 2 * quiet;
    let colors = code.to_colors();
    let dark = |x: usize, y: usize| -> bool {
        x >= quiet
            && y >= quiet
            && x < width + quiet
            && y < width + quiet
            && colors[(y - quiet) * width + (x - quiet)] == Color::Dark
    };
    // xterm-256 colours: pure black and pure white, whatever the terminal theme.
    let shade = |is_dark: bool| if is_dark { 16 } else { 231 };
    let mut out = String::new();
    for row in (0..size).step_by(2) {
        out.push_str("    ");
        let (mut fg, mut bg) = (None, None);
        for x in 0..size {
            let (top, bottom) = (dark(x, row), row + 1 < size && dark(x, row + 1));
            if color {
                // Matching modules: a space on the right background. Mixed: the top half in the foreground.
                let want_bg = shade(bottom);
                if bg != Some(want_bg) {
                    let _ = write!(out, "\x1b[48;5;{want_bg}m");
                    bg = Some(want_bg);
                }
                if top == bottom {
                    out.push(' ');
                } else {
                    let want_fg = shade(top);
                    if fg != Some(want_fg) {
                        let _ = write!(out, "\x1b[38;5;{want_fg}m");
                        fg = Some(want_fg);
                    }
                    out.push('▀');
                }
                continue;
            }
            let (top, bottom) = (!top, !bottom && row + 1 < size);
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

/// `text` as an SVG QR code: one path of square modules with a quiet zone.
pub fn qr_svg(text: &str) -> Option<String> {
    let code = qr_code(text)?;
    let width = code.width();
    let size = width + 2 * QUIET;
    let mut path = String::new();
    for (i, c) in code.to_colors().iter().enumerate() {
        if *c == Color::Dark {
            let _ = write!(path, "M{} {}h1v1h-1z", i % width + QUIET, i / width + QUIET);
        }
    }
    Some(format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {size} {size}\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code for the Latch app\"><rect width=\"{size}\" height=\"{size}\" fill=\"#fff\"/><path fill=\"#000\" d=\"{path}\"/></svg>"
    ))
}

fn html_escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// A page with the setup QR code as large as the screen allows, for when the
/// terminal's code is too small or its font breaks it up. It is a local file
/// (no server, nothing reachable from the network) holding the same
/// single-use, expiring code the terminal shows.
pub fn qr_page(setup: &PhoneSetup) -> Option<String> {
    let link = setup_link(&setup.address, &setup.code);
    let svg = qr_svg(&link)?;
    let minutes = OWNER_CODE_TTL_MS / 60_000;
    let code = html_escape(&setup.code);
    let address = html_escape(&setup.address);
    Some(format!(
        r#"<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<meta name="referrer" content="no-referrer">
<title>Connect your phone to Latch</title>
<style>
  :root {{ color-scheme: light; }}
  body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f4f1ec; color: #1b1b1b;
         font: 18px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
  main {{ text-align: center; padding: 24px; }}
  .code {{ width: min(70vh, 86vw); height: min(70vh, 86vw); background: #fff; padding: 0; border-radius: 12px;
          box-shadow: 0 6px 30px rgba(0,0,0,.12); margin: 12px auto 20px; }}
  .code svg {{ width: 100%; height: 100%; display: block; }}
  h1 {{ font-size: 26px; margin: 0; }}
  ol {{ display: inline-block; text-align: left; margin: 8px 0 0; padding-left: 22px; }}
  .typed {{ margin-top: 18px; color: #555; font-size: 15px; word-break: break-all; }}
  .typed b {{ font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 22px; letter-spacing: 2px; color: #1b1b1b; }}
</style>
</head>
<body>
<main>
  <h1>Point your phone's camera here</h1>
  <div class="code">{svg}</div>
  <ol>
    <li>Open <b>Latch</b> on your phone.</li>
    <li>Tap <b>Scan QR code</b> and hold the phone about 20&nbsp;cm from the screen.</li>
  </ol>
  <p class="typed">Can't scan? Tap <b style="font-size:inherit;letter-spacing:0">Enter it by hand</b> and type<br>
    address <span>{address}</span><br>setup code <b>{code}</b> · single use, expires in {minutes} min</p>
</main>
</body>
</html>
"#
    ))
}

/// Writes [qr_page] to `setup-qr.html` in `dir`, readable only by this user.
pub fn write_qr_page(dir: &Path, setup: &PhoneSetup) -> Option<std::path::PathBuf> {
    let page = qr_page(setup)?;
    let path = dir.join("setup-qr.html");
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    let mut file = options.open(&path).ok()?;
    std::io::Write::write_all(&mut file, page.as_bytes()).ok()?;
    Some(path)
}

/// Opens `path` in the computer's default browser; false when that failed.
pub fn open_in_browser(path: &Path) -> bool {
    let mut cmd = if cfg!(target_os = "macos") {
        std::process::Command::new("open")
    } else if cfg!(windows) {
        let mut c = std::process::Command::new("cmd");
        c.args(["/C", "start", ""]);
        c
    } else {
        std::process::Command::new("xdg-open")
    };
    cmd.arg(path)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
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
    /// The same code as a large page in the browser ([write_qr_page]), when written.
    pub page: Option<std::path::PathBuf>,
}

/// The "connect your phone" block: steps, QR code, and the typed fallback.
pub fn phone_block(style: Style, setup: &PhoneSetup) -> String {
    let mut out = String::new();
    let link = setup_link(&setup.address, &setup.code);
    let minutes = OWNER_CODE_TTL_MS / 60_000;
    let _ = writeln!(out, "  {}", style.bold("Connect your phone"));
    let _ = writeln!(
        out,
        "    Open Latch on your phone, tap {}, and point the camera here.\n",
        style.bold("Scan the QR code on my computer")
    );
    match qr(&link, !no_color()) {
        Some(code) => out.push_str(&code),
        None => out.push_str("    (the QR code could not be drawn; enter the details below)\n"),
    }
    if let Some(page) = &setup.page {
        let _ = writeln!(
            out,
            "    {}  file://{}",
            style.dim("Hard to scan? A big code:"),
            page.display()
        );
        let _ = writeln!(
            out,
            "    {}",
            style.dim("(or run latch-gateway pair --open)")
        );
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
    let _ = writeln!(
        out,
        "    {}",
        style.dim("No camera? In the app tap Find my computer; a number to check appears here.")
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
    /// The Wi-Fi listener runs but this computer has no private address:
    /// its port and certificate pin.
    pub wifi_without_address: Option<(u16, String)>,
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
        None => match &s.wifi_without_address {
            Some((port, pin)) => {
                let _ = writeln!(out, "  {}", style.bold("Connect your phone"));
                let _ = writeln!(
                    out,
                    "    This computer has no address on a home Wi-Fi yet. Join one, then run {} for a QR code.",
                    style.bold("latch-gateway pair")
                );
                let _ = writeln!(
                    out,
                    "    {}  https://<this computer's Wi-Fi address>:{port}#pin={pin}",
                    style.dim("Address")
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

/// ADR-060: shows each phone that asks to link from "Find my computer" with
/// its number, and asks the owner, until stdin closes or `until` passes.
/// It talks to the gateway on this computer with the owner token, so it
/// serves both `latch-start` in a terminal and `latch-gateway pair` for a
/// gateway in the background.
pub async fn approve_nearby(
    bind: std::net::SocketAddr,
    admin_token: String,
    style: Style,
    until: Option<std::time::Instant>,
) {
    use tokio::io::AsyncBufReadExt;
    let addr = bind.to_string().replace("0.0.0.0", "127.0.0.1");
    let mut lines = tokio::io::BufReader::new(tokio::io::stdin()).lines();
    let mut asked = std::collections::HashSet::new();
    loop {
        if until.is_some_and(|t| std::time::Instant::now() >= t) {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(800)).await;
        let Ok((status, body)) =
            crate::phone_check::request_local("GET", &addr, "/v1/admin/nearby", &admin_token, "")
                .await
        else {
            continue;
        };
        if status != "200" {
            continue;
        }
        let Ok(list) = serde_json::from_str::<serde_json::Value>(&body) else {
            continue;
        };
        for request in list["requests"].as_array().into_iter().flatten() {
            let (Some(id), Some(device), Some(number)) = (
                request["id"].as_str(),
                request["device"].as_str(),
                request["number"].as_str(),
            ) else {
                continue;
            };
            if !asked.insert(id.to_owned()) {
                continue;
            }
            eprint!("{}", nearby_prompt(style, device, number));
            let answer = match lines.next_line().await {
                Ok(Some(line)) => line,
                _ => return,
            };
            let approve = matches!(answer.trim().to_ascii_lowercase().as_str(), "y" | "yes");
            let path = format!("/v1/admin/nearby/{id}");
            let body = format!(r#"{{"approve":{approve}}}"#);
            let done =
                crate::phone_check::request_local("POST", &addr, &path, &admin_token, &body).await;
            let line = match (approve, done) {
                (true, Ok((s, _))) if s == "204" => style.bold("    Allowed. The phone finishes connecting by itself."),
                (false, Ok((s, _))) if s == "204" => style.dim("    Refused."),
                _ => style.dim("    That request ended before your answer (it lasts 2 minutes). Try again from the phone."),
            };
            eprintln!("{line}\n");
        }
    }
}

/// The question for one phone (ADR-060).
pub fn nearby_prompt(style: Style, device: &str, number: &str) -> String {
    let device: String = device.chars().filter(|c| !c.is_control()).collect();
    format!(
        "\n  {}  {}\n    The phone shows a number. Only if it is {} on the phone in your hand,\n    type {} and press Enter. Anything else refuses. {} ",
        style.accent("A phone wants to connect:"),
        style.bold(&device),
        style.accent(number),
        style.bold("y"),
        style.dim("Allow?"),
    )
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
        // Version 5 (37 modules) plus the 4-module quiet zone, two modules per row.
        assert!(rows <= 23, "{rows} rows");
        assert!(drawn.contains('▀'));
        let plain = qr(&link, false).expect("drawn");
        assert!(plain.contains('█'));
    }

    #[test]
    fn the_terminal_code_decodes_to_the_link_it_draws() {
        // Rebuild the module grid from the coloured drawing and compare with the encoder's.
        let link = setup_link("https://192.168.1.5:8788#pin=abc", "K7QX-M2PD");
        let drawn = qr(&link, true).expect("drawn");
        let code = qr_code(&link).expect("code");
        let width = code.width();
        let size = width + 2 * QUIET;
        let mut grid = vec![vec![false; size]; size];
        for (row, line) in drawn.lines().enumerate() {
            let (mut fg, mut bg) = (false, false);
            let mut x = 0;
            let mut chars = line.trim_start_matches(' ').chars().peekable();
            while let Some(c) = chars.next() {
                if c == '\x1b' {
                    let seq: String = chars.by_ref().take_while(|c| *c != 'm').collect();
                    match seq.as_str() {
                        "[48;5;16" => bg = true,
                        "[48;5;231" => bg = false,
                        "[38;5;16" => fg = true,
                        "[38;5;231" => fg = false,
                        _ => {}
                    }
                    continue;
                }
                let top = if c == '▀' { fg } else { bg };
                grid[2 * row][x] = top;
                if 2 * row + 1 < size {
                    grid[2 * row + 1][x] = bg;
                }
                x += 1;
            }
            assert_eq!(x, size, "row {row} is {x} modules wide");
        }
        let colors = code.to_colors();
        for y in 0..size {
            for x in 0..size {
                let inside =
                    (QUIET..QUIET + width).contains(&x) && (QUIET..QUIET + width).contains(&y);
                let want = inside && colors[(y - QUIET) * width + (x - QUIET)] == Color::Dark;
                assert_eq!(grid[y][x], want, "module {x},{y}");
            }
        }
    }

    #[test]
    fn the_nearby_question_names_the_phone_and_the_number_and_strips_controls() {
        let text = nearby_prompt(Style { ansi: false }, "Pixel\x1b[2J 8", "123 456");
        assert!(text.contains("Pixel[2J 8"));
        assert!(!text.contains('\x1b'));
        assert!(text.contains("123 456"));
        assert!(text.contains("type y"));
    }

    #[test]
    fn the_big_code_page_is_self_contained_and_escaped() {
        let setup = PhoneSetup {
            address: "https://192.168.1.5:8788#pin=a<b".into(),
            code: "K7QX-M2PD".into(),
            page: None,
        };
        let page = qr_page(&setup).expect("page");
        assert!(page.contains("<svg"));
        assert!(page.contains("K7QX-M2PD"));
        assert!(page.contains("pin=a&lt;b"));
        assert!(!page.contains("pin=a<b"));
        assert!(!page.contains("<script"));
        assert!(page.contains("default-src 'none'"));
        let dir = std::env::temp_dir().join(format!("latch-qr-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let path = write_qr_page(&dir, &setup).expect("written");
        assert_eq!(std::fs::read_to_string(&path).expect("read"), page);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).expect("meta").permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }
        let _ = std::fs::remove_dir_all(&dir);
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
                    page: None,
                }),
                wifi_without_address: None,
            },
        );
        assert!(text.contains("K7QX-M2PD"));
        assert!(text.contains("http://127.0.0.1:8787/mcp"));
        assert!(!text.contains("lgt_") && !text.contains("lok_"));
    }
}
