// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! R57: the phone over a USB cable, for the lowest mirror delay (`latch usb`).
//!
//! `adb reverse tcp:PORT tcp:PORT` makes the phone's own 127.0.0.1:PORT lead
//! through the cable to this computer's Wi-Fi listener. The app looks for its
//! gateway's pinned key there every few seconds and moves its links to it
//! (`UsbGateway` in the app); the pin, not the address, proves it is this
//! gateway. Wireless stays the default: this is optional, for a desk with a
//! cable. It needs `adb` (Android platform tools) and USB debugging on the phone.

use std::path::PathBuf;
use std::process::Command;

/// Serials of the phones `adb devices` lists as ready (not unauthorized or offline).
pub fn ready_devices(adb_devices_output: &str) -> Vec<String> {
    adb_devices_output
        .lines()
        .skip_while(|l| !l.starts_with("List of devices"))
        .skip(1)
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?;
            (parts.next() == Some("device")).then(|| serial.to_owned())
        })
        .collect()
}

/// A phone on a cable: wireless debugging lists `IP:PORT` or an `adb-…._adb-tls-connect._tcp`
/// name, and reversing over it would only go through the Wi-Fi again.
pub fn on_cable(serial: &str) -> bool {
    !serial.contains(':') && !serial.contains("._adb-tls")
}

/// Serials `adb devices` lists as waiting for the owner to allow this computer.
pub fn unauthorized_devices(adb_devices_output: &str) -> Vec<String> {
    adb_devices_output
        .lines()
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?;
            (parts.next() == Some("unauthorized")).then(|| serial.to_owned())
        })
        .collect()
}

/// The arguments that put the gateway's `port` on phone `serial`'s 127.0.0.1.
pub fn reverse_args(serial: &str, port: u16) -> Vec<String> {
    vec![
        "-s".into(),
        serial.into(),
        "reverse".into(),
        format!("tcp:{port}"),
        format!("tcp:{port}"),
    ]
}

/// `adb`: `$ADB`, else on the PATH, else the Android SDK's usual places.
pub fn adb() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("ADB").map(PathBuf::from) {
        return Some(p);
    }
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    let on_path = std::env::var_os("PATH").and_then(|path| {
        std::env::split_paths(&path)
            .map(|d| d.join(exe))
            .find(|p| p.is_file())
    });
    if on_path.is_some() {
        return on_path;
    }
    let home = std::env::var_os("HOME").map(PathBuf::from);
    let sdk_roots = [
        std::env::var_os("ANDROID_HOME").map(PathBuf::from),
        std::env::var_os("ANDROID_SDK_ROOT").map(PathBuf::from),
        home.as_ref().map(|h| h.join("Library/Android/sdk")),
        home.as_ref().map(|h| h.join("Android/Sdk")),
        std::env::var_os("LOCALAPPDATA").map(|d| PathBuf::from(d).join("Android/Sdk")),
    ];
    sdk_roots
        .into_iter()
        .flatten()
        .map(|r| r.join("platform-tools").join(exe))
        .find(|p| p.is_file())
}

/// What one round did, for the terminal.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Round {
    pub connected: Vec<String>,
    pub failed: Vec<(String, String)>,
    pub unauthorized: Vec<String>,
}

/// Lists phones and puts `port` on each ready one's 127.0.0.1, skipping `done`.
pub fn connect(adb: &std::path::Path, port: u16, done: &[String]) -> Result<Round, String> {
    let out = Command::new(adb)
        .arg("devices")
        .output()
        .map_err(|e| format!("could not run adb: {e}"))?;
    let listing = String::from_utf8_lossy(&out.stdout);
    let mut round = Round {
        unauthorized: unauthorized_devices(&listing),
        ..Round::default()
    };
    for serial in ready_devices(&listing).into_iter().filter(|s| on_cable(s)) {
        if done.contains(&serial) {
            continue;
        }
        match Command::new(adb).args(reverse_args(&serial, port)).output() {
            Ok(o) if o.status.success() => round.connected.push(serial),
            Ok(o) => {
                let why = String::from_utf8_lossy(&o.stderr).trim().to_owned();
                round.failed.push((serial, why));
            }
            Err(e) => round.failed.push((serial, e.to_string())),
        }
    }
    Ok(round)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_ready_phones_are_connected() {
        let listing = "* daemon started successfully\nList of devices attached\nR58N123ABC\tdevice\nemulator-5554\toffline\n0A1B2C\tunauthorized\n192.168.1.9:5555\tdevice\n\n";
        assert_eq!(
            ready_devices(listing),
            vec!["R58N123ABC".to_owned(), "192.168.1.9:5555".to_owned()]
        );
        assert_eq!(unauthorized_devices(listing), vec!["0A1B2C".to_owned()]);
        assert!(ready_devices("List of devices attached\n\n").is_empty());
        assert!(ready_devices("").is_empty());
        assert!(on_cable("R58N123ABC"));
        assert!(!on_cable("192.168.1.9:5555"), "wireless debugging");
        assert!(!on_cable("adb-R58N-AbCdEf._adb-tls-connect._tcp"));
    }

    #[test]
    fn the_reverse_command_puts_the_port_on_the_phone() {
        assert_eq!(
            reverse_args("R58N", 8788),
            ["-s", "R58N", "reverse", "tcp:8788", "tcp:8788"]
        );
    }
}
