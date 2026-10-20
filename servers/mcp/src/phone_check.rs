// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! `latch-gateway check-phone` (roadmap R29 f): the real-device check, scripted.
//!
//! Runs on the computer that runs the gateway and talks to it over MCP, like
//! any AI app would, so it checks the whole path: gateway, Wi-Fi, the phone
//! app, its accessibility service, and full-access ADB. Steps that need the
//! owner's hands (Wi-Fi off and on, Stop, a reboot) are asked for one at a
//! time and can be skipped. The result is a Markdown report for the release
//! gate (R21) that holds no screen content, names, or command output beyond
//! the shell's user id: it is meant to be pasted into an issue.

use std::fmt::Write as _;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Where and how to reach the gateway.
#[derive(Debug, Clone)]
pub struct Target {
    /// `host:port` of the gateway's MCP listener.
    pub addr: String,
    /// Path of the MCP endpoint, normally `/mcp`.
    pub path: String,
    pub token: String,
    /// The phone to check, when more than one is connected.
    pub device_id: Option<String>,
}

impl Target {
    /// From `http://host:port/mcp`. Only plain http: run the check on the
    /// computer that runs the gateway, where it listens on 127.0.0.1.
    pub fn parse(url: &str, token: &str) -> Result<Self, String> {
        let rest = url.strip_prefix("http://").ok_or_else(|| {
            "use the gateway's local http address, e.g. http://127.0.0.1:8787/mcp, on the computer that runs it"
                .to_owned()
        })?;
        let (addr, path) = match rest.find('/') {
            Some(i) => (&rest[..i], &rest[i..]),
            None => (rest, "/mcp"),
        };
        if addr.is_empty() || token.len() < 32 {
            return Err("give the gateway address and its MCP token (LATCH_MCP_TOKEN)".into());
        }
        Ok(Self {
            addr: addr.to_owned(),
            path: path.to_owned(),
            token: token.to_owned(),
            device_id: None,
        })
    }
}

/// The owner, for the steps that need them.
pub trait Owner {
    /// Shows `what` and waits until the owner did it (true) or skips it (false).
    fn ask(&mut self, what: &str) -> bool;
    /// Progress for the person watching.
    fn say(&mut self, line: &str);
}

/// Asks on the terminal: Enter to continue, `s` then Enter to skip.
pub struct Terminal;

impl Owner for Terminal {
    fn ask(&mut self, what: &str) -> bool {
        println!("\n>>> {what}\n    Press Enter when done, or type s and Enter to skip.");
        let mut line = String::new();
        if std::io::stdin().read_line(&mut line).is_err() {
            return false;
        }
        !line.trim().eq_ignore_ascii_case("s")
    }

    fn say(&mut self, line: &str) {
        println!("{line}");
    }
}

/// Skips every step that needs the owner (`--no-prompts`, and tests).
pub struct Unattended;

impl Owner for Unattended {
    fn ask(&mut self, _: &str) -> bool {
        false
    }

    fn say(&mut self, _: &str) {}
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    Pass,
    Fail(String),
    Skip(String),
}

#[derive(Debug, Clone)]
pub struct Step {
    pub name: &'static str,
    /// The roadmap rows this step is evidence for.
    pub rows: &'static str,
    pub outcome: Outcome,
    /// A short fact worth recording (a size, a time); never screen content.
    pub note: String,
    pub ms: u128,
}

/// What the check found, ready to write out.
#[derive(Debug, Clone, Default)]
pub struct Report {
    pub gateway: String,
    pub phone: String,
    pub steps: Vec<Step>,
}

impl Report {
    pub fn passed(&self) -> bool {
        self.steps
            .iter()
            .all(|s| !matches!(s.outcome, Outcome::Fail(_)))
    }

    pub fn markdown(&self, at_unix_s: u64) -> String {
        let mut out = String::new();
        let _ = writeln!(out, "# Latch phone check, {}\n", utc(at_unix_s));
        let _ = writeln!(out, "- Gateway: {}", self.gateway);
        let _ = writeln!(out, "- Phone: {}", self.phone);
        let (pass, fail, skip) =
            self.steps
                .iter()
                .fold((0, 0, 0), |(p, f, s), step| match step.outcome {
                    Outcome::Pass => (p + 1, f, s),
                    Outcome::Fail(_) => (p, f + 1, s),
                    Outcome::Skip(_) => (p, f, s + 1),
                });
        let _ = writeln!(
            out,
            "- Result: {pass} passed, {fail} failed, {skip} skipped\n"
        );
        out.push_str("| Step | Roadmap | Result | Time | Note |\n|---|---|---|---|---|\n");
        for s in &self.steps {
            let (result, why) = match &s.outcome {
                Outcome::Pass => ("PASS", String::new()),
                Outcome::Fail(why) => ("**FAIL**", why.clone()),
                Outcome::Skip(why) => ("skipped", why.clone()),
            };
            let note = [s.note.as_str(), why.as_str()]
                .iter()
                .filter(|t| !t.is_empty())
                .copied()
                .collect::<Vec<_>>()
                .join("; ")
                .replace('|', "/")
                .replace('\n', " ");
            let _ = writeln!(
                out,
                "| {} | {} | {result} | {:.1} s | {note} |",
                s.name,
                s.rows,
                s.ms as f64 / 1000.0
            );
        }
        out.push_str(
            "\nNo screen content, names, or command output is recorded here. \
             Attach this file to the R21 release-gate issue.\n",
        );
        out
    }
}

/// A tool call's text and whether it failed.
struct Answer {
    error: bool,
    text: String,
    images: Vec<String>,
}

struct Client {
    target: Target,
    id: u64,
}

impl Client {
    async fn call(&mut self, tool: &str, mut args: Value) -> Result<Answer, String> {
        if let (Some(device), Some(map)) = (&self.target.device_id, args.as_object_mut())
            && tool != "list_devices"
            && tool != "diagnose"
        {
            map.insert("device_id".into(), json!(device));
        }
        self.id += 1;
        let body = json!({
            "jsonrpc": "2.0", "id": self.id, "method": "tools/call",
            "params": { "name": tool, "arguments": args },
        })
        .to_string();
        let reply = post(&self.target, &body).await?;
        let reply: Value = serde_json::from_str(&reply)
            .map_err(|_| "the gateway's answer is not JSON; is the address the MCP endpoint?")?;
        if let Some(error) = reply.get("error") {
            return Err(format!(
                "the gateway refused the call: {}",
                error["message"]
            ));
        }
        let result = &reply["result"];
        let content = result["content"].as_array().cloned().unwrap_or_default();
        Ok(Answer {
            error: result["isError"].as_bool().unwrap_or(false),
            text: content
                .iter()
                .filter_map(|c| c["text"].as_str())
                .collect::<Vec<_>>()
                .join("\n"),
            images: content
                .iter()
                .filter(|c| c["type"] == "image")
                .filter_map(|c| c["mimeType"].as_str().map(str::to_owned))
                .collect(),
        })
    }
}

/// One HTTP/1.1 request to the gateway's local listener, answer body as text.
async fn post(target: &Target, body: &str) -> Result<String, String> {
    let (status, body) = post_local(&target.addr, &target.path, &target.token, body).await?;
    if status == "401" {
        return Err("the gateway refused the token: use its LATCH_MCP_TOKEN".into());
    }
    if status != "200" {
        return Err(format!("the gateway answered HTTP {status}"));
    }
    Ok(body)
}

/// POSTs JSON to a gateway listening on `addr` (plain http, this computer),
/// with a bearer token; returns the HTTP status and the answer body.
pub(crate) async fn post_local(
    addr: &str,
    path: &str,
    token: &str,
    body: &str,
) -> Result<(String, String), String> {
    request_local("POST", addr, path, token, body).await
}

/// One request with the owner token to the gateway on this computer: (status, body).
pub(crate) async fn request_local(
    method: &str,
    addr: &str,
    path: &str,
    token: &str,
    body: &str,
) -> Result<(String, String), String> {
    let mut stream = tokio::net::TcpStream::connect(addr)
        .await
        .map_err(|e| format!("cannot reach the gateway at {addr}: {e}"))?;
    let head = format!(
        "{method} {path} HTTP/1.1\r\nHost: {addr}\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\n\
         Accept: application/json\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",
        body.len()
    );
    stream
        .write_all(head.as_bytes())
        .await
        .and(stream.write_all(body.as_bytes()).await)
        .map_err(|e| format!("lost the gateway: {e}"))?;
    let mut raw = Vec::new();
    stream
        .read_to_end(&mut raw)
        .await
        .map_err(|e| format!("lost the gateway: {e}"))?;
    let split = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or("the gateway sent no HTTP answer")?;
    let headers = String::from_utf8_lossy(&raw[..split]).to_ascii_lowercase();
    let status = headers.split_whitespace().nth(1).unwrap_or("").to_owned();
    let body = String::from_utf8_lossy(&raw[split + 4..]).into_owned();
    let body = if headers.contains("transfer-encoding: chunked") {
        dechunk(&body)
    } else {
        body
    };
    Ok((status, body))
}

fn dechunk(mut s: &str) -> String {
    let mut out = String::new();
    while let Some((size, rest)) = s.split_once("\r\n") {
        let n = usize::from_str_radix(size.trim(), 16).unwrap_or(0);
        if n == 0 || rest.len() < n {
            break;
        }
        out.push_str(&rest[..n]);
        s = rest.get(n + 2..).unwrap_or("");
    }
    out
}

/// What the steps share.
struct Run<'a> {
    client: Client,
    owner: &'a mut dyn Owner,
    report: Report,
    quick: bool,
}

impl Run<'_> {
    fn record(
        &mut self,
        name: &'static str,
        rows: &'static str,
        started: Instant,
        outcome: Outcome,
        note: String,
    ) {
        let mark = match &outcome {
            Outcome::Pass => "PASS".to_owned(),
            Outcome::Fail(why) => format!("FAIL: {why}"),
            Outcome::Skip(why) => format!("skipped: {why}"),
        };
        self.owner.say(&format!("  {name}: {mark}"));
        self.report.steps.push(Step {
            name,
            rows,
            outcome,
            note,
            ms: started.elapsed().as_millis(),
        });
    }

    /// Runs `adb` and follows `continue_from` to the end; returns the answer
    /// text of the last call, all stdout joined, and the image types seen.
    async fn adb(
        &mut self,
        command: &str,
        base64: bool,
    ) -> Result<(String, String, Vec<String>), String> {
        let mut args = json!({ "command": command });
        if base64 {
            args["base64"] = json!(true);
        }
        let mut stdout = String::new();
        loop {
            let answer = self.client.call("adb", args).await?;
            if answer.error {
                return Err(first_line(&answer.text));
            }
            if let Some(out) = answer.text.split_once("--- stdout ---\n").map(|(_, o)| o) {
                // stdout ends where stderr or the "more output" note begins.
                let end = ["\n--- stderr ---", "\nOutput reached "]
                    .iter()
                    .filter_map(|m| out.find(m))
                    .min()
                    .unwrap_or(out.len());
                stdout.push_str(&out[..end]);
            }
            match answer.text.split_once("continue_from=\"") {
                Some((_, rest)) => {
                    let cursor = rest.split('"').next().unwrap_or("").to_owned();
                    args = json!({ "continue_from": cursor });
                }
                None => return Ok((answer.text, stdout, answer.images)),
            }
        }
    }

    /// Waits until the phone is connected (true) or `limit` passes.
    async fn connected_within(&mut self, limit: Duration) -> bool {
        let until = Instant::now() + limit;
        while Instant::now() < until {
            if let Ok(a) = self.client.call("diagnose", json!({})).await
                && a.text.contains("Verdict: ready")
            {
                return true;
            }
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
        false
    }
}

fn first_line(text: &str) -> String {
    text.lines()
        .next()
        .unwrap_or("")
        .chars()
        .take(160)
        .collect()
}

/// Runs every step and returns the report. Never panics on a phone that
/// misbehaves: a failed step is recorded and the next one runs.
pub async fn run(mut target: Target, owner: &mut dyn Owner, quick: bool) -> Result<Report, String> {
    let mut probe = Client {
        target: target.clone(),
        id: 0,
    };
    let devices = probe.call("list_devices", json!({})).await?;
    let mut report = Report {
        gateway: first_line(&devices.text),
        ..Report::default()
    };
    // Only the model and the app's protocol: the phone's name may be personal.
    let diagnosis = probe.call("diagnose", json!({})).await?.text;
    let phones: Vec<&str> = diagnosis
        .lines()
        .filter(|l| l.starts_with("- d_") && l.contains(": connected"))
        .collect();
    let line = match (phones.as_slice(), &target.device_id) {
        (_, Some(id)) => phones
            .iter()
            .find(|l| l[2..].starts_with(id.as_str()))
            .copied(),
        ([only], None) => Some(*only),
        ([], None) => None,
        (_, None) => {
            return Err(
                "more than one phone is connected: pass --device <id> (see list_devices)".into(),
            );
        }
    };
    let Some(line) = line else {
        return Err("no phone is connected: open Latch on the phone and press Start".into());
    };
    if target.device_id.is_none() {
        target.device_id = line[2..].split_whitespace().next().map(str::to_owned);
    }
    let model = line
        .split_once("\" (")
        .and_then(|(_, r)| r.split_once(')'))
        .map_or("unknown model", |(m, _)| m);
    let app = line
        .rsplit_once("app protocol ")
        .map_or("?", |(_, v)| v.trim_end_matches('.'));
    report.phone = format!("{model}, app protocol {app}");

    let mut run = Run {
        client: Client { target, id: 0 },
        owner,
        report,
        quick,
    };
    run.owner.say(&format!(
        "Checking {}. This takes a few minutes.",
        run.report.phone
    ));

    // 1. Ready, and the screen can be read.
    let started = Instant::now();
    let ready = diagnosis.contains("Verdict: ready");
    run.record(
        "Phone ready",
        "R13",
        started,
        if ready {
            Outcome::Pass
        } else {
            Outcome::Fail("diagnose does not say ready; run diagnose for the fixes".into())
        },
        String::new(),
    );

    let started = Instant::now();
    let observed = run.client.call("observe", json!({})).await;
    let mut screen: Option<(String, u32, u32)> = None;
    let outcome = match &observed {
        Ok(a) if !a.error => {
            screen = observation_header(&a.text);
            if screen.is_some() {
                Outcome::Pass
            } else {
                Outcome::Fail("no observation in the answer".into())
            }
        }
        Ok(a) => Outcome::Fail(first_line(&a.text)),
        Err(e) => Outcome::Fail(e.clone()),
    };
    let note = screen
        .as_ref()
        .map(|(_, w, h)| format!("{w}x{h}"))
        .unwrap_or_default();
    run.record("Read the screen", "R1", started, outcome, note);

    // 2. A two-finger rotate around the middle of the screen (R28).
    let started = Instant::now();
    let outcome = match &screen {
        None => Outcome::Skip("needs a screen reading".into()),
        Some((obs, w, h)) => {
            let strokes = rotate(*w, *h);
            match run
                .client
                .call(
                    "gesture",
                    json!({ "observation_id": obs, "strokes": strokes }),
                )
                .await
            {
                Ok(a) if !a.error => Outcome::Pass,
                Ok(a) => Outcome::Fail(first_line(&a.text)),
                Err(e) => Outcome::Fail(e),
            }
        }
    };
    run.record("Rotate gesture", "R28", started, outcome, String::new());

    // 3. Full-access ADB: who it runs as (R27).
    let started = Instant::now();
    let (outcome, note) = match run.adb("id", false).await {
        Ok((_, out, _)) if out.contains("uid=2000(shell)") => {
            (Outcome::Pass, "uid=2000(shell)".to_owned())
        }
        Ok((_, out, _)) => (
            Outcome::Fail(format!(
                "expected uid=2000(shell), got {}",
                first_line(&out)
            )),
            String::new(),
        ),
        Err(e) => (Outcome::Fail(e), String::new()),
    };
    let adb_on = outcome == Outcome::Pass;
    run.record("ADB identity (adb id)", "R27", started, outcome, note);
    let skip_adb = || Outcome::Skip("full ADB is not working (step above)".into());

    // 4. Large output arrives whole, through continuation (R27).
    let started = Instant::now();
    let outcome = if !adb_on {
        skip_adb()
    } else {
        match run.adb("seq 1 100000", false).await {
            // Exactly 588 895 bytes. Each piece the gateway hands over may end mid-line,
            // and it adds a newline there, so allow one extra byte per piece.
            Ok((_, out, _)) => {
                let pieces = out.len().div_ceil(256 * 1024);
                let whole = (SEQ_BYTES..=SEQ_BYTES + pieces).contains(&out.len());
                if whole && out.starts_with("1\n2\n") && out.trim_end().ends_with("\n99999\n100000")
                {
                    Outcome::Pass
                } else {
                    Outcome::Fail(format!("got {} of {SEQ_BYTES} bytes", out.len()))
                }
            }
            Err(e) => Outcome::Fail(e),
        }
    };
    run.record(
        "ADB large output (seq 1 100000)",
        "R27",
        started,
        outcome,
        "588 895 bytes over continue_from".into(),
    );

    // 5. Binary output: a screenshot through ADB comes back as a PNG (R28).
    let started = Instant::now();
    let outcome = if !adb_on {
        skip_adb()
    } else {
        match run.adb("screencap -p", true).await {
            Ok((_, _, images)) if images.iter().any(|m| m == "image/png") => Outcome::Pass,
            Ok((text, _, _)) => Outcome::Fail(format!("no PNG image: {}", first_line(&text))),
            Err(e) => Outcome::Fail(e),
        }
    };
    // The picture itself is not kept: it is the owner's screen.
    run.record(
        "ADB screenshot as image (screencap -p)",
        "R28",
        started,
        outcome,
        "image not saved".into(),
    );

    // 6. A long-running command (R27).
    let started = Instant::now();
    let outcome = if !adb_on {
        skip_adb()
    } else if run.quick {
        Outcome::Skip("--quick".into())
    } else {
        match run.adb("sleep 25; echo finished", false).await {
            Ok((_, out, _))
                if out.contains("finished") && started.elapsed() >= Duration::from_secs(25) =>
            {
                Outcome::Pass
            }
            Ok((text, _, _)) => Outcome::Fail(first_line(&text)),
            Err(e) => Outcome::Fail(e),
        }
    };
    run.record(
        "ADB long-running (sleep 25)",
        "R27",
        started,
        outcome,
        String::new(),
    );

    // 6b. A job: starts at once, keeps running on the phone, collected later (R29 d).
    let started = Instant::now();
    let outcome = if !adb_on {
        skip_adb()
    } else {
        match run
            .client
            .call(
                "adb",
                json!({ "command": "sleep 3; echo job-done", "background": true }),
            )
            .await
        {
            Ok(a) if !a.error && a.text.starts_with("Started job ") => {
                let id = a
                    .text
                    .split_whitespace()
                    .nth(2)
                    .unwrap_or_default()
                    .to_owned();
                match run
                    .client
                    .call("adb", json!({ "job": id, "wait_ms": 30_000 }))
                    .await
                {
                    Ok(done)
                        if done
                            .text
                            .starts_with(&format!("job {id} finished, exit code 0"))
                            && done.text.contains("job-done") =>
                    {
                        Outcome::Pass
                    }
                    Ok(done) => Outcome::Fail(first_line(&done.text)),
                    Err(e) => Outcome::Fail(e),
                }
            }
            Ok(a) => Outcome::Fail(first_line(&a.text)),
            Err(e) => Outcome::Fail(e),
        }
    };
    run.record(
        "ADB job, collected later",
        "R29d",
        started,
        outcome,
        String::new(),
    );

    // 7. An answer survives a Wi-Fi drop and nothing runs twice (R29 a). ADB cannot be
    //    used here: Android turns wireless debugging off with Wi-Fi. A question to the
    //    owner can: it stays open on the phone while Wi-Fi is off, the owner's Done
    //    must still reach this call afterwards, and a second run would ask twice.
    let started = Instant::now();
    let outcome = if !run.owner.ask(
        "Wi-Fi drop: after you press Enter, the phone shows a question from Latch. While it is open, \
         turn the phone's Wi-Fi OFF, count to five, turn it back ON, wait until Latch says it is \
         connected, then tap Done on the question.",
    ) {
        Outcome::Skip("the owner skipped it".into())
    } else {
        let asked = run
            .client
            .call("ask_owner", json!({ "message": "Latch check: turn Wi-Fi off, count to five, turn it on, then tap Done." }))
            .await;
        match asked {
            Ok(a) if !a.error && a.text.contains("it's done") => {
                if run.owner.ask("Did the question appear only once? Press Enter for yes, or type s and Enter for no.") {
                    Outcome::Pass
                } else {
                    Outcome::Fail("the owner saw the question more than once".into())
                }
            }
            Ok(a) => Outcome::Fail(first_line(&a.text)),
            Err(e) => Outcome::Fail(e),
        }
    };
    run.record(
        "Answer survives a Wi-Fi drop, runs once",
        "R29a",
        started,
        outcome,
        String::new(),
    );

    // 8. Stop: the owner's emergency stop ends access at once.
    let started = Instant::now();
    let outcome = if !run
        .owner
        .ask("Stop: press Stop on the phone (the Latch notification or the app).")
    {
        Outcome::Skip("the owner skipped it".into())
    } else {
        match run.client.call("observe", json!({})).await {
            Ok(a) if a.error => {
                let restarted = run.owner.ask("Now press Start in Latch again.")
                    && run.connected_within(Duration::from_secs(120)).await;
                if restarted {
                    Outcome::Pass
                } else {
                    Outcome::Fail(
                        "refused after Stop, but the phone did not come back after Start".into(),
                    )
                }
            }
            Ok(_) => Outcome::Fail("the phone still answered after Stop".into()),
            Err(e) => Outcome::Fail(e),
        }
    };
    run.record(
        "Stop ends access; Start brings it back",
        "R27, R21",
        started,
        outcome,
        String::new(),
    );

    // 9. Reconnect after a reboot, without a cable (R27).
    let started = Instant::now();
    let outcome = if !adb_on {
        skip_adb()
    } else if !run.owner.ask(
        "Reboot: the check will restart the phone with `adb reboot`. After it starts again, unlock it, \
         open Latch, and press Start. Wireless debugging must be on (Settings → Developer options). The check waits up to 10 minutes.",
    ) {
        Outcome::Skip("the owner skipped it".into())
    } else {
        // The phone goes down mid-answer; whatever comes back is fine.
        let _ = run.adb("reboot", false).await;
        tokio::time::sleep(Duration::from_secs(20)).await;
        if !run.connected_within(Duration::from_secs(600)).await {
            Outcome::Fail("the phone did not reconnect within 10 minutes".into())
        } else {
            match run.adb("id", false).await {
                Ok((_, out, _)) if out.contains("uid=2000(shell)") => Outcome::Pass,
                Ok((text, _, _)) => Outcome::Fail(format!("reconnected, but adb failed: {}", first_line(&text))),
                Err(e) => Outcome::Fail(e),
            }
        }
    };
    run.record(
        "Reconnect after reboot, ADB again",
        "R27",
        started,
        outcome,
        String::new(),
    );

    Ok(run.report)
}

/// Bytes printed by `seq 1 100000`.
const SEQ_BYTES: usize = 588_895;

/// `(observation_id, width, height)` from an observation's header line.
fn observation_header(text: &str) -> Option<(String, u32, u32)> {
    let line = text.lines().find(|l| l.starts_with("observation_id: "))?;
    let id = line
        .strip_prefix("observation_id: ")?
        .split_whitespace()
        .next()?
        .to_owned();
    let size = line.split("screen ").nth(1)?.split_whitespace().next()?;
    let (w, h) = size.split_once('x')?;
    Some((id, w.parse().ok()?, h.parse().ok()?))
}

/// Two fingers turning a quarter circle around the middle of the screen.
fn rotate(w: u32, h: u32) -> Value {
    let (cx, cy) = (f64::from(w) / 2.0, f64::from(h) / 2.0);
    let r = f64::from(w.min(h)) / 5.0;
    let finger = |start: f64| {
        let points: Vec<Value> = (0..=8)
            .map(|i| {
                let a = start + std::f64::consts::FRAC_PI_2 * f64::from(i) / 8.0;
                json!({ "x": (cx + r * a.cos()).round() as i64, "y": (cy + r * a.sin()).round() as i64 })
            })
            .collect();
        json!({ "points": points, "duration_ms": 600 })
    };
    json!([finger(0.0), finger(std::f64::consts::PI)])
}

/// `2026-10-17 20:15 UTC` from Unix seconds.
pub fn utc(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!(
        "{y:04}-{m:02}-{d:02} {:02}:{:02} UTC",
        rem / 3_600,
        rem % 3_600 / 60
    )
}

/// Unix seconds now.
pub fn now_s() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_and_headers_parse() {
        assert_eq!(utc(0), "1970-01-01 00:00 UTC");
        assert_eq!(utc(1_792_268_100), "2026-10-17 20:15 UTC");
        assert_eq!(
            observation_header(
                "x\nobservation_id: o_12 · device d_1 · app com.x · screen 1080x2400\n"
            ),
            Some(("o_12".into(), 1080, 2400))
        );
        assert!(Target::parse("https://latch.example/mcp", &"t".repeat(40)).is_err());
        let t = Target::parse("http://127.0.0.1:8787/mcp", &"t".repeat(40)).expect("parses");
        assert_eq!(
            (t.addr.as_str(), t.path.as_str()),
            ("127.0.0.1:8787", "/mcp")
        );
    }
}
