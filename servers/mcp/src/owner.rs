// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The `latch` command, built into the gateway (ADR-069): what an owner's
//! computer needs around `serve`. Keys are made once in `~/.latch/latch.env`,
//! readable by the owner only, and handed to the gateway; on Windows the
//! gateway also keeps itself running from the owner's Startup folder. So a
//! package that installs nothing but this program (winget, as `latch`) still
//! gives the whole command. The `latch` scripts in `packaging/` call these too.
//!
//! The process never changes its own environment (`unsafe_code` is forbidden):
//! commands that need the keys run the gateway again with them set.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};
use std::time::Duration;

/// winget's package identifier (packaging/render.sh).
pub const WINGET_ID: &str = "AspershUpadhyay.Latch";
const STARTUP_NAME: &str = "Latch gateway.vbs";

/// `LATCH_HOME`, or `.latch` in the owner's home folder.
pub fn latch_home() -> PathBuf {
    if let Some(home) = std::env::var_os("LATCH_HOME").filter(|h| !h.is_empty()) {
        return PathBuf::from(home);
    }
    let base = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    base.join(".latch")
}

pub fn keys_file(home: &Path) -> PathBuf {
    home.join("latch.env")
}

/// `KEY=VALUE` lines, as written on Windows, or `export KEY="VALUE"` as the
/// shell installers write them. Comments and anything else are skipped.
pub fn parse_keys(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|line| {
            let line = line.trim();
            let line = line.strip_prefix("export ").unwrap_or(line);
            let (key, value) = line.split_once('=')?;
            let key = key.trim();
            if key.is_empty() || !key.chars().all(|c| c.is_ascii_uppercase() || c == '_') {
                return None;
            }
            let value = value.trim();
            let value = value
                .strip_prefix('"')
                .and_then(|v| v.strip_suffix('"'))
                .unwrap_or(value);
            Some((key.to_owned(), value.to_owned()))
        })
        .collect()
}

/// The keys file's text for this system.
pub fn keys_text(home: &Path, admin: &str, mcp: &str, windows: bool) -> String {
    let data = home.join("data");
    if windows {
        format!(
            "# Latch gateway keys. Keep this file private.\r\nLATCH_ADMIN_TOKEN={admin}\r\nLATCH_MCP_TOKEN={mcp}\r\nLATCH_DATA_DIR={}\r\nLATCH_WIFI=1\r\n",
            data.display()
        )
    } else {
        format!(
            "# Latch gateway keys. Keep this file private.\nexport LATCH_ADMIN_TOKEN={admin}\nexport LATCH_MCP_TOKEN={mcp}\nexport LATCH_DATA_DIR=\"{}\"\nexport LATCH_WIFI=1\n",
            data.display()
        )
    }
}

/// Makes the keys once, readable by the owner only; true when it made them.
pub fn ensure_keys(home: &Path) -> io::Result<bool> {
    let file = keys_file(home);
    if file.exists() {
        return Ok(false);
    }
    fs::create_dir_all(home)?;
    restrict_dir(home);
    let text = keys_text(
        home,
        &crate::secret::new_token("lgt"),
        &crate::secret::new_token("lgt"),
        cfg!(windows),
    );
    // Written beside it and renamed, so a reader never sees half a file.
    let partial = home.join("latch.env.new");
    write_private(&partial, &text)?;
    fs::rename(&partial, &file)?;
    eprintln!(
        "Made your gateway keys in {} (readable by you only).",
        file.display()
    );
    Ok(true)
}

#[cfg(unix)]
fn restrict_dir(dir: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = fs::set_permissions(dir, fs::Permissions::from_mode(0o700));
}

#[cfg(windows)]
fn restrict_dir(dir: &Path) {
    // Only this user, for the folder and everything made in it.
    let user = std::env::var("USERNAME").unwrap_or_default();
    let _ = Command::new("icacls")
        .arg(dir)
        .args(["/inheritance:r", "/grant:r", &format!("{user}:(OI)(CI)F")])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

#[cfg(not(any(unix, windows)))]
fn restrict_dir(_dir: &Path) {}

fn write_private(path: &Path, text: &str) -> io::Result<()> {
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    options.open(path)?.write_all(text.as_bytes())
}

/// The keys to hand to the gateway, made first when `create`. Variables the
/// caller already set win, as before: the file only fills in what is missing.
pub fn keys_env(home: &Path, create: bool) -> io::Result<Vec<(String, String)>> {
    if create {
        ensure_keys(home)?;
    }
    let text = match fs::read_to_string(keys_file(home)) {
        Ok(text) => text,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e),
    };
    Ok(parse_keys(&text)
        .into_iter()
        .filter(|(k, _)| std::env::var_os(k).is_none())
        .collect())
}

/// Who put this program here, so update and uninstall can hand over to it.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Installer {
    WinGet,
    Scoop,
    Homebrew,
    Script,
}

pub fn installed_by(exe: &Path) -> Installer {
    let path = exe.to_string_lossy().replace('\\', "/").to_lowercase();
    if path.contains("/winget/packages/") || path.contains("/winget/links/") {
        Installer::WinGet
    } else if path.contains("/scoop/apps/") {
        Installer::Scoop
    } else if path.contains("/cellar/") {
        Installer::Homebrew
    } else {
        Installer::Script
    }
}

/// This program's own file, through any link: winget puts a link named `latch`
/// on the PATH, and the Startup entry and installer checks need the real file.
fn this_exe() -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("latch-gateway"));
    match fs::canonicalize(&exe) {
        Ok(real) => plain_path(real),
        Err(_) => exe,
    }
}

/// Windows' canonical form starts with `\\?\`, which scripts cannot use.
pub fn plain_path(path: PathBuf) -> PathBuf {
    match path.to_str().and_then(|p| p.strip_prefix(r"\\?\")) {
        Some(rest) if !rest.starts_with("UNC\\") => PathBuf::from(rest),
        _ => path,
    }
}

/// Runs this program again as `mode` with the keys set, and ends with its exit code.
fn run_with_keys(mode: &str, args: &[String], keys: &[(String, String)]) -> ExitCode {
    let mut command = Command::new(this_exe());
    command.arg(mode).args(args).envs(keys.iter().cloned());
    #[cfg(unix)]
    {
        // Become the gateway, so signals and the exit code are its own.
        let e = std::os::unix::process::CommandExt::exec(&mut command);
        eprintln!("latch: cannot start the gateway: {e}");
        ExitCode::FAILURE
    }
    #[cfg(not(unix))]
    match command.status() {
        Ok(status) => ExitCode::from(status.code().unwrap_or(1).clamp(0, 255) as u8),
        Err(e) => {
            eprintln!("latch: cannot start the gateway: {e}");
            ExitCode::FAILURE
        }
    }
}

/// `pair`, `status`, and `usb` with no keys in the environment: read them from
/// the keys file. `None` when the command can run as it is.
pub fn with_saved_keys(mode: &str, args: &[String]) -> Option<ExitCode> {
    if std::env::var_os("LATCH_ADMIN_TOKEN").is_some() {
        return None;
    }
    let keys = keys_env(&latch_home(), false).ok()?;
    keys.iter()
        .any(|(k, _)| k == "LATCH_ADMIN_TOKEN")
        .then(|| run_with_keys(mode, args, &keys))
}

fn local_addr() -> std::net::SocketAddr {
    std::env::var("LATCH_BIND")
        .ok()
        .and_then(|b| b.parse().ok())
        .map(|a: std::net::SocketAddr| {
            if a.ip().is_unspecified() {
                std::net::SocketAddr::from(([127, 0, 0, 1], a.port()))
            } else {
                a
            }
        })
        .unwrap_or_else(|| std::net::SocketAddr::from(([127, 0, 0, 1], 8787)))
}

fn gateway_answers() -> bool {
    std::net::TcpStream::connect_timeout(&local_addr(), Duration::from_millis(300)).is_ok()
}

/// The owner commands; `None` for every other mode.
pub fn run(mode: &str, args: &[String]) -> Option<ExitCode> {
    let home = latch_home();
    let code = match mode {
        "start" => start(&home, args),
        "run" => match keys_env(&home, true) {
            Ok(keys) => run_with_keys("serve", args, &keys),
            Err(e) => keys_error(&home, e),
        },
        "keys" => match ensure_keys(&home) {
            Ok(_) => {
                println!("{}", keys_file(&home).display());
                ExitCode::SUCCESS
            }
            Err(e) => keys_error(&home, e),
        },
        "autostart" => autostart(&home, args.first().map(String::as_str).unwrap_or("status")),
        "run-background" => run_background(&home),
        "stop" => {
            stop_all(&home);
            eprintln!("Stopped.");
            ExitCode::SUCCESS
        }
        "restart" => {
            stop_all(&home);
            if startup_entry().is_some_and(|p| p.exists()) {
                start_background();
                eprintln!("Restarted in the background.");
            } else {
                eprintln!("Stopped. Start it with: latch start (or latch autostart on)");
            }
            ExitCode::SUCCESS
        }
        "logs" => logs(&home),
        "update" => update(),
        "uninstall" => uninstall(&home, args.iter().any(|a| a == "--all")),
        _ => return None,
    };
    Some(code)
}

fn keys_error(home: &Path, e: io::Error) -> ExitCode {
    eprintln!(
        "latch: cannot make or read your keys in {}: {e}",
        home.display()
    );
    ExitCode::FAILURE
}

fn start(home: &Path, args: &[String]) -> ExitCode {
    let keys = match keys_env(home, true) {
        Ok(keys) => keys,
        Err(e) => return keys_error(home, e),
    };
    let mcp = keys
        .iter()
        .find(|(k, _)| k == "LATCH_MCP_TOKEN")
        .map(|(_, v)| v.clone())
        .or_else(|| std::env::var("LATCH_MCP_TOKEN").ok())
        .unwrap_or_default();
    eprintln!();
    eprintln!("  AI app setup, once (Claude Code):");
    eprintln!(
        "    claude mcp add --transport http latch http://{}/mcp --header \"Authorization: Bearer {mcp}\"",
        local_addr()
    );
    if gateway_answers() {
        eprintln!();
        eprintln!("  The gateway already runs in the background (latch autostart).");
        return run_with_keys("pair", args, &keys);
    }
    run_with_keys("serve", args, &keys)
}

/// The Startup folder entry that starts the gateway at log-in (Windows).
pub fn startup_entry() -> Option<PathBuf> {
    if !cfg!(windows) {
        return None;
    }
    std::env::var_os("APPDATA").map(|appdata| {
        PathBuf::from(appdata)
            .join(r"Microsoft\Windows\Start Menu\Programs\Startup")
            .join(STARTUP_NAME)
    })
}

/// Starts `exe run-background` hidden; does nothing once the program is gone
/// (removed without `latch uninstall`), instead of an error at every log-in.
pub fn startup_script(exe: &Path) -> String {
    let exe = exe.display().to_string().replace('"', "");
    format!(
        "Set fs = CreateObject(\"Scripting.FileSystemObject\")\r\nIf fs.FileExists(\"{exe}\") Then CreateObject(\"WScript.Shell\").Run \"\"\"{exe}\"\" run-background\", 0, False\r\n"
    )
}

fn autostart(home: &Path, what: &str) -> ExitCode {
    let Some(entry) = startup_entry() else {
        eprintln!(
            "On macOS and Linux, background start comes with the latch command from Homebrew or install-gateway.sh: latch autostart {what}"
        );
        return ExitCode::from(2);
    };
    let log = home.join("gateway.log");
    match what {
        "on" => {
            if let Err(e) = ensure_keys(home) {
                return keys_error(home, e);
            }
            if let Err(e) = entry
                .parent()
                .map_or(Ok(()), fs::create_dir_all)
                .and_then(|()| fs::write(&entry, startup_script(&this_exe())))
            {
                eprintln!("latch: cannot write {}: {e}", entry.display());
                return ExitCode::FAILURE;
            }
            stop_all(home);
            start_background();
            eprintln!(
                "The gateway now runs in the background and starts when you log in. Log: {}",
                log.display()
            );
        }
        "off" => {
            let _ = fs::remove_file(&entry);
            stop_all(home);
            eprintln!("The gateway no longer runs in the background.");
        }
        "status" => {
            if entry.exists() {
                println!("on (log: {})", log.display());
            } else {
                println!("off");
            }
        }
        _ => {
            eprintln!("usage: latch autostart on|off|status");
            return ExitCode::from(2);
        }
    }
    ExitCode::SUCCESS
}

/// Starts the background gateway now, through the Startup entry, as log-in does.
/// Not as our own child: Windows would hand it every handle this process may
/// pass on, among them a pipe a caller reads our output from (`latch.ps1`,
/// `| Out-Null`), and that caller would then wait for it forever. wscript
/// starts it without them and exits at once; it gets 10 s to do so.
fn start_background() {
    let Some(entry) = startup_entry().filter(|e| e.exists()) else {
        return;
    };
    unblock(&this_exe());
    let Ok(mut wscript) = Command::new("wscript.exe")
        .arg(&entry)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        eprintln!("latch: cannot run {}", entry.display());
        return;
    };
    for _ in 0..100 {
        if matches!(wscript.try_wait(), Ok(Some(_))) {
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = wscript.kill();
    eprintln!(
        "latch: {} did not finish; the background gateway may not have started",
        entry.display()
    );
}

/// Removes Windows' "downloaded from the internet" mark from this program, as
/// the installer's `Unblock-File` does. winget leaves it on, and with it Windows
/// asks "Open File - Security Warning" before the Startup entry may start the
/// gateway: a question nobody sees at log-in, so it never starts. The owner
/// chose to install this file, and winget checked its SHA-256 first.
fn unblock(exe: &Path) {
    if cfg!(windows) {
        let mut stream = exe.as_os_str().to_owned();
        stream.push(":Zone.Identifier");
        let _ = fs::remove_file(PathBuf::from(stream));
    }
}

/// Keeps the gateway running, as launchd or systemd would, with its output in
/// `gateway.log`. Started hidden by the Startup entry.
fn run_background(home: &Path) -> ExitCode {
    let keys = match keys_env(home, true) {
        Ok(keys) => keys,
        Err(e) => return keys_error(home, e),
    };
    let _ = fs::write(home.join("background.pid"), std::process::id().to_string());
    let log_path = home.join("gateway.log");
    loop {
        let log = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path);
        let mut command = Command::new(this_exe());
        command.arg("serve").envs(keys.iter().cloned());
        if let Ok(log) = log {
            if let Ok(err) = log.try_clone() {
                command.stderr(err);
            }
            command.stdout(log);
        }
        #[cfg(windows)]
        std::os::windows::process::CommandExt::creation_flags(&mut command, 0x0800_0000); // CREATE_NO_WINDOW
        let _ = command.status();
        std::thread::sleep(Duration::from_secs(3));
    }
}

/// Stops the background gateway and any other gateway this user runs, but not
/// the process asking.
fn stop_all(home: &Path) {
    let me = std::process::id();
    let pid_file = home.join("background.pid");
    if let Some(pid) = fs::read_to_string(&pid_file)
        .ok()
        .and_then(|p| p.trim().parse::<u32>().ok())
        .filter(|&p| p != me)
    {
        kill(pid, true);
    }
    let _ = fs::remove_file(&pid_file);
    for pid in gateway_pids() {
        if pid != me {
            kill(pid, false);
        }
    }
}

#[cfg(windows)]
fn gateway_pids() -> Vec<u32> {
    // Its own name, or `latch` when winget or a copy put it on the PATH as that.
    ["latch-gateway.exe", "latch.exe"]
        .iter()
        .flat_map(|image| {
            // CSV lines: "latch-gateway.exe","1234",...
            Command::new("tasklist")
                .args(["/FI", &format!("IMAGENAME eq {image}"), "/FO", "CSV", "/NH"])
                .output()
                .map(|out| {
                    String::from_utf8_lossy(&out.stdout)
                        .lines()
                        .filter_map(|l| l.split(',').nth(1)?.trim_matches('"').parse().ok())
                        .collect::<Vec<u32>>()
                })
                .unwrap_or_default()
        })
        .collect()
}

#[cfg(not(windows))]
fn gateway_pids() -> Vec<u32> {
    Command::new("pgrep")
        .args(["-u", &uid(), "-x", "latch-gateway"])
        .output()
        .map(|out| {
            String::from_utf8_lossy(&out.stdout)
                .lines()
                .filter_map(|l| l.trim().parse().ok())
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(not(windows))]
fn uid() -> String {
    Command::new("id")
        .arg("-u")
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_owned())
        .unwrap_or_default()
}

fn kill(pid: u32, tree: bool) {
    let pid = pid.to_string();
    let mut command = if cfg!(windows) {
        let mut c = Command::new("taskkill");
        c.args(["/F", "/PID", &pid]);
        if tree {
            c.arg("/T");
        }
        c
    } else {
        let mut c = Command::new("kill");
        c.arg(&pid);
        c
    };
    let _ = command.stdout(Stdio::null()).stderr(Stdio::null()).status();
}

fn logs(home: &Path) -> ExitCode {
    let path = home.join("gateway.log");
    let Ok(text) = fs::read_to_string(&path) else {
        eprintln!(
            "No background log yet ({}). With latch start, the log is in that window.",
            path.display()
        );
        return ExitCode::SUCCESS;
    };
    let lines: Vec<&str> = text.lines().collect();
    for line in &lines[lines.len().saturating_sub(100)..] {
        println!("{line}");
    }
    // Follow it, like tail -f, until Ctrl+C.
    let mut seen = text.len() as u64;
    loop {
        std::thread::sleep(Duration::from_millis(500));
        let Ok(mut file) = fs::File::open(&path) else {
            continue;
        };
        let len = file.metadata().map(|m| m.len()).unwrap_or(0);
        if len < seen {
            seen = 0; // started again
        }
        if len > seen {
            use std::io::{Read, Seek, SeekFrom};
            let mut more = String::new();
            if file.seek(SeekFrom::Start(seen)).is_ok() && file.read_to_string(&mut more).is_ok() {
                print!("{more}");
                let _ = io::stdout().flush();
                seen = len;
            }
        }
    }
}

fn update() -> ExitCode {
    match installed_by(&this_exe()) {
        Installer::WinGet => eprintln!(
            "Latch came from winget. Update it with:  latch stop; winget upgrade {WINGET_ID}; latch restart"
        ),
        Installer::Scoop => eprintln!("Latch came from Scoop. Update it with:  scoop update latch"),
        Installer::Homebrew => eprintln!(
            "Latch came from Homebrew. Update it with:  brew upgrade latch && latch restart"
        ),
        Installer::Script => eprintln!(
            "Run the latch command's update (latch update), or the install line again: docs/install.md"
        ),
    }
    ExitCode::SUCCESS
}

fn uninstall(home: &Path, all: bool) -> ExitCode {
    if let Some(entry) = startup_entry() {
        let _ = fs::remove_file(entry);
    }
    stop_all(home);
    if all {
        let _ = fs::remove_dir_all(home);
        eprintln!("Removed your keys and paired phones.");
    } else {
        eprintln!(
            "Your keys and phones stay in {} (latch uninstall --all removes them).",
            home.display()
        );
    }
    eprintln!("Also remove Latch from your AI app, e.g.: claude mcp remove latch");
    match installed_by(&this_exe()) {
        Installer::WinGet => eprintln!(
            "The background gateway is off. Remove the program with:  winget uninstall {WINGET_ID}"
        ),
        Installer::Scoop => eprintln!(
            "The background gateway is off. Remove the program with:  scoop uninstall latch"
        ),
        Installer::Homebrew => eprintln!(
            "The background gateway is off. Remove the program with:  brew uninstall latch"
        ),
        Installer::Script => eprintln!(
            "The background gateway is off. Remove the program with the latch command: latch uninstall"
        ),
    }
    ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_both_key_file_formats() {
        let windows =
            "# keys\r\nLATCH_ADMIN_TOKEN=lgt_a\r\nLATCH_DATA_DIR=C:\\Users\\o\\.latch\\data\r\n";
        assert_eq!(
            parse_keys(windows),
            vec![
                ("LATCH_ADMIN_TOKEN".into(), "lgt_a".into()),
                ("LATCH_DATA_DIR".into(), "C:\\Users\\o\\.latch\\data".into()),
            ]
        );
        let shell = "# keys\nexport LATCH_MCP_TOKEN=lgt_b\nexport LATCH_DATA_DIR=\"/home/o/.latch/data\"\nexport LATCH_WIFI=1\nnot a key\n";
        assert_eq!(
            parse_keys(shell),
            vec![
                ("LATCH_MCP_TOKEN".into(), "lgt_b".into()),
                ("LATCH_DATA_DIR".into(), "/home/o/.latch/data".into()),
                ("LATCH_WIFI".into(), "1".into()),
            ]
        );
    }

    #[test]
    fn written_keys_read_back_on_each_system() {
        for windows in [false, true] {
            let text = keys_text(Path::new("/h/.latch"), "lgt_admin", "lgt_mcp", windows);
            let keys = parse_keys(&text);
            assert!(keys.contains(&("LATCH_ADMIN_TOKEN".into(), "lgt_admin".into())));
            assert!(keys.contains(&("LATCH_MCP_TOKEN".into(), "lgt_mcp".into())));
            assert!(keys.contains(&("LATCH_WIFI".into(), "1".into())));
            assert!(
                keys.iter()
                    .any(|(k, v)| k == "LATCH_DATA_DIR" && v.ends_with("data"))
            );
        }
    }

    #[test]
    fn keys_are_made_once_and_kept() {
        let home =
            std::env::temp_dir().join(format!("latch-owner-test-{}", crate::secret::new_id("t")));
        assert!(ensure_keys(&home).expect("make keys"));
        let first = fs::read_to_string(keys_file(&home)).expect("read keys");
        assert!(!ensure_keys(&home).expect("keep keys"));
        assert_eq!(
            fs::read_to_string(keys_file(&home)).expect("read keys"),
            first
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(keys_file(&home))
                .expect("meta")
                .permissions()
                .mode();
            assert_eq!(mode & 0o077, 0, "keys readable by others");
        }
        let _ = fs::remove_dir_all(&home);
    }

    #[test]
    fn knows_who_installed_it() {
        use Installer::*;
        for (path, by) in [
            (
                r"C:\Users\o\AppData\Local\Microsoft\WinGet\Packages\AspershUpadhyay.Latch_Microsoft.Winget.Source_8wekyb3d8bbwe\latch-gateway.exe",
                WinGet,
            ),
            (
                r"C:\Users\o\scoop\apps\latch\current\latch-gateway.exe",
                Scoop,
            ),
            (
                "/opt/homebrew/Cellar/latch/0.1.0/bin/latch-gateway",
                Homebrew,
            ),
            (
                r"C:\Users\o\AppData\Local\Latch\bin\latch-gateway.exe",
                Script,
            ),
            ("/home/o/.local/bin/latch-gateway", Script),
        ] {
            assert_eq!(installed_by(Path::new(path)), by, "{path}");
        }
    }

    #[test]
    fn plain_paths_for_scripts() {
        assert_eq!(
            plain_path(PathBuf::from(r"\\?\C:\Latch\latch-gateway.exe")),
            PathBuf::from(r"C:\Latch\latch-gateway.exe")
        );
        assert_eq!(
            plain_path(PathBuf::from("/usr/bin/latch-gateway")),
            PathBuf::from("/usr/bin/latch-gateway")
        );
    }

    #[test]
    fn startup_entry_runs_the_gateway_hidden_only_while_it_exists() {
        let script = startup_script(Path::new(r"C:\Latch\latch-gateway.exe"));
        assert!(script.contains(r#"If fs.FileExists("C:\Latch\latch-gateway.exe") Then"#));
        assert!(
            script.contains(r#".Run """C:\Latch\latch-gateway.exe"" run-background", 0, False"#)
        );
    }
}
