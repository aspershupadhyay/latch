// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! A simulated phone that speaks the Latch device protocol.
//!
//! It enforces the same device-side rules a real phone must: capability
//! toggles, session pause and expiry, latest-observation freshness, sensitive
//! fields, and owner approval. Tests drive it through a real gateway.

pub mod screens;

use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use futures_util::{SinkExt, StreamExt};
use latch_protocol::{
    ActionEffect, ActionResult, ActivityActor, ActivityEntry, ActivityKind, ActivityList,
    AdbResult, AppList, Capability, CapabilityState, CapabilityStatus, Command, CommandEnvelope,
    DeviceDescriptor, DeviceInfo, DeviceStatus, DeviceToGateway, ErrorCode, GatewayToDevice,
    GlobalAction, Hello, NetworkKind, Observation, Orientation, Outcome, OwnerReply,
    PROTOCOL_VERSION, PhoneControl, ProtocolError, ScreenInfo, Screenshot, SessionInfo, Target,
    WaitResult, validate,
};
use screens::{Effect, Phone, Screen};

pub mod files;
pub mod signing;
pub mod transfers;
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest, http::HeaderValue};

/// 1x1 transparent PNG; the fake device has no real pixels.
const PIXEL_PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Approval {
    /// The simulated owner approves every request.
    Approve,
    /// The simulated owner denies every request.
    Deny,
    /// Nobody answers; the request expires.
    Ignore,
}

#[derive(Debug, thiserror::Error)]
pub enum FakeError {
    #[error("connection failed: {0}")]
    Connect(String),
    #[error("pairing failed: {0}")]
    Pairing(String),
    #[error("protocol violation: {0}")]
    Protocol(String),
}

/// Observable state, shared with tests.
#[derive(Debug, Clone)]
pub struct FakeState {
    pub phone: Phone,
    pub capabilities: Vec<CapabilityState>,
    pub session: SessionInfo,
    pub approval: Approval,
    /// Titles of every approval request shown to the simulated owner.
    pub approval_requests: Vec<String>,
    /// How the simulated owner answers `owner.ask`.
    pub owner_reply: OwnerReply,
    /// Every `owner.ask` message shown to the simulated owner.
    pub owner_questions: Vec<String>,
    /// Photos, Downloads, and the picked folder (protocol 1.6).
    pub files: files::FakeFiles,
    /// Every `app.share`: package, file names, text.
    pub shared: Vec<(String, Vec<String>, Option<String>)>,
    /// Every command the device executed (not refused).
    pub executed: Vec<String>,
    /// Link transfers of this session (protocol 1.7).
    pub transfers: Vec<transfers::FakeTransfer>,
    /// Transfer work waiting to be started by the connection loop.
    jobs: Vec<transfers::Job>,
    /// How long each transfer waits before moving, so tests can see one running.
    pub transfer_delay_ms: u64,
    /// Every `clipboard.set` text.
    pub clipboard: Vec<String>,
    /// Every `task.done` summary (empty when none was given).
    pub tasks_done: Vec<String>,
    /// Every `device.control` the phone carried out (protocol 1.11).
    pub controls: Vec<PhoneControl>,
    /// Since 1.15: system buttons pressed, links opened (url, package), and
    /// apps launched from their first screen, in order.
    pub pressed: Vec<GlobalAction>,
    pub links: Vec<(String, String)>,
    pub fresh_launches: Vec<String>,
    /// Protocol 1.12: the key this phone signs every message with; `None`
    /// behaves like a phone from before 1.12 (unsigned messages).
    pub device_key: Option<signing::DeviceKey>,
    /// Protocol 1.13: what the hello says about Android's battery optimisation.
    pub battery_restricted: Option<bool>,
    /// Protocol 1.14: the owner switched same-Wi-Fi transfers on (off by
    /// default, like the app); `file.local` then opens a real listener.
    pub local_transfers: bool,
    /// Every same-Wi-Fi slot opened with `file.local`.
    pub local_slots: Vec<transfers::LocalSlot>,
    pub device_id: Option<String>,
    pub revoked: bool,
    /// Since 1.17 (ADR-046): every `adb.shell` command line this session ran,
    /// in order. Output is never stored — ADR-046 keeps ADB results out of logs.
    pub adb_commands: Vec<String>,
    /// Since 1.18: the finger count of each `input.gesture`, in order.
    pub gestures: Vec<usize>,
    /// The envelope deadline of each new `adb.shell` command, in order, so
    /// tests can check gateways impose none of their own (ADR-046).
    pub adb_deadlines: Vec<u32>,
    /// Pending chunked `adb.shell` output, keyed by the cursor given to the client.
    adb_chunks: std::collections::HashMap<String, AdbPending>,
    adb_cursor_seq: u64,
    /// Since 1.19 (ADR-048): the answers to recent commands by id, so a
    /// command the gateway sends again after a reconnect is not run twice.
    answers: Answers,
    /// Every command id answered from memory instead of being run again.
    pub replayed: Vec<String>,
    /// Lose the answer to the next command: run it, then drop the connection
    /// before the answer is sent, as a flaky network does.
    pub drop_next_answer: bool,
    /// Lose the answer to the first command with this name (`latch-fake-device --lose-answer`).
    pub lose_answer_to: Option<String>,
    /// How many answers were lost this way, so the binary knows to reconnect.
    pub lost_answers: u32,
    /// The protocol version the hello announces; `None` for this crate's.
    /// A phone below 1.19 does not remember answers and runs every id it gets.
    pub hello_protocol: Option<String>,
    latest: Option<(String, Screen, Vec<screens::Node>)>,
    observation_counter: u64,
}

/// Recent answers by command id (protocol 1.19, ADR-048), like the app's
/// `RecentCommands`: the last [`Answers::KEPT`] answers, and the ids of older
/// ones, so an id seen again is never run a second time.
#[derive(Debug, Clone, Default)]
struct Answers {
    kept: std::collections::VecDeque<(String, Outcome)>,
    forgotten: std::collections::VecDeque<String>,
}

impl Answers {
    const KEPT: usize = 64;
    const FORGOTTEN: usize = 1024;

    /// What to send for `id` if it already ran: its answer, or `cancelled`
    /// when the answer is no longer kept.
    fn replay(&self, id: &str) -> Option<Outcome> {
        if let Some((_, outcome)) = self.kept.iter().find(|(k, _)| k == id) {
            return Some(outcome.clone());
        }
        self.forgotten.iter().any(|k| k == id).then(|| Outcome::Error {
            error: ProtocolError::new(
                ErrorCode::Cancelled,
                "the phone already received this command and no longer has its answer, so it did not run it again; observe to see what happened",
            ),
        })
    }

    fn remember(&mut self, id: String, outcome: Outcome) {
        self.kept.push_back((id, outcome));
        if self.kept.len() > Self::KEPT
            && let Some((old, _)) = self.kept.pop_front()
        {
            self.forgotten.push_back(old);
            if self.forgotten.len() > Self::FORGOTTEN {
                self.forgotten.pop_front();
            }
        }
    }
}

/// Runs one command, or answers it from memory if this id already ran (1.19).
async fn answer(state: &Shared, envelope: &CommandEnvelope) -> Outcome {
    let remembers = {
        let s = lock(state);
        let minor = s
            .hello_protocol
            .as_deref()
            .map_or(Some(u32::MAX), latch_protocol::minor_version)
            .unwrap_or(0);
        minor >= 19
    };
    if remembers {
        let mut s = lock(state);
        if let Some(outcome) = s.answers.replay(&envelope.id) {
            s.replayed.push(envelope.id.clone());
            return outcome;
        }
    }
    let outcome = match handle_async(state, envelope).await {
        Ok(data) => Outcome::Ok { data },
        Err(error) => Outcome::Error { error },
    };
    if remembers {
        lock(state)
            .answers
            .remember(envelope.id.clone(), outcome.clone());
    }
    outcome
}

/// True once: the test asked to lose this answer (see [`FakeState::drop_next_answer`]
/// and [`FakeState::lose_answer_to`]).
fn lose_answer(state: &Shared, envelope: &CommandEnvelope) -> bool {
    let mut s = lock(state);
    let named = s.lose_answer_to.as_deref() == Some(envelope.command.name());
    if named {
        s.lose_answer_to = None;
    }
    let lose = std::mem::take(&mut s.drop_next_answer) || named;
    if lose {
        s.lost_answers += 1;
    }
    lose
}

/// Output of an `adb.shell` command that did not fit one frame and is being
/// handed back a chunk at a time (protocol 1.17).
#[derive(Debug, Clone)]
struct AdbPending {
    /// The remaining stdout bytes, still to be delivered.
    stdout: Vec<u8>,
    /// The exit status, sent with the final chunk.
    exit_code: i32,
}

impl FakeState {
    pub fn new(enabled: &[Capability]) -> Self {
        Self {
            phone: Phone::default(),
            capabilities: Capability::ALL
                .iter()
                .map(|&capability| CapabilityState {
                    capability,
                    status: if capability == Capability::DeviceInfo || enabled.contains(&capability)
                    {
                        CapabilityStatus::Enabled
                    } else {
                        CapabilityStatus::Disabled
                    },
                })
                .collect(),
            session: SessionInfo {
                expires_at_ms: now_ms() + 60 * 60 * 1000,
                approve_every_action: false,
                paused: false,
                remote_approvals: false,
            },
            approval: Approval::Approve,
            approval_requests: Vec::new(),
            owner_reply: OwnerReply::Done,
            owner_questions: Vec::new(),
            files: files::FakeFiles::default(),
            shared: Vec::new(),
            executed: Vec::new(),
            transfers: Vec::new(),
            jobs: Vec::new(),
            transfer_delay_ms: 0,
            clipboard: Vec::new(),
            tasks_done: Vec::new(),
            controls: Vec::new(),
            pressed: Vec::new(),
            links: Vec::new(),
            fresh_launches: Vec::new(),
            device_key: Some(signing::DeviceKey::generate()),
            battery_restricted: None,
            local_transfers: false,
            local_slots: Vec::new(),
            device_id: None,
            revoked: false,
            adb_commands: Vec::new(),
            adb_deadlines: Vec::new(),
            gestures: Vec::new(),
            adb_chunks: std::collections::HashMap::new(),
            adb_cursor_seq: 0,
            answers: Answers::default(),
            replayed: Vec::new(),
            drop_next_answer: false,
            lose_answer_to: None,
            lost_answers: 0,
            hello_protocol: None,
            latest: None,
            observation_counter: 0,
        }
    }

    fn new_adb_cursor(&mut self) -> String {
        self.adb_cursor_seq += 1;
        format!("adbc_{}", self.adb_cursor_seq)
    }

    fn enabled(&self, capability: Capability) -> bool {
        self.capabilities
            .iter()
            .any(|c| c.capability == capability && c.status == CapabilityStatus::Enabled)
    }
}

pub type Shared = Arc<Mutex<FakeState>>;

fn lock(state: &Shared) -> std::sync::MutexGuard<'_, FakeState> {
    state.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// The phone's own same-Wi-Fi slot that `url` names, if any (protocol 1.14).
fn local_slot(state: &FakeState, url: &str) -> Option<transfers::Slot> {
    state
        .local_slots
        .iter()
        .find(|s| s.url == url)
        .map(|s| s.data.clone())
}

fn err(code: ErrorCode, message: &str) -> ProtocolError {
    ProtocolError::new(code, message)
}

/// Most stdout bytes the fake phone returns in one `adb.shell` frame; larger
/// output is chunked and resumed with a cursor (protocol 1.17).
const FAKE_ADB_CHUNK_BYTES: usize = 1024;

/// A tiny deterministic stand-in for a device shell, enough to exercise the
/// full-access ADB bridge end to end without a real phone: arbitrary command
/// pass-through, stdin, exit codes, and multi-frame output. It is NOT a real
/// shell — the Android executor runs the command against the device's own ADB.
/// Returns (stdout, stderr, exit_code).
/// What the fake `screencap -p` prints: a valid 1x1 PNG.
pub const FAKE_SCREENCAP_PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

fn run_fake_shell(line: &str, stdin: Option<&str>) -> (Vec<u8>, String, i32) {
    let trimmed = line.trim();
    let mut parts = trimmed.split_whitespace();
    let cmd = parts.next().unwrap_or("");
    let rest: Vec<&str> = parts.collect();
    match cmd {
        "" => (Vec::new(), String::new(), 0),
        "true" => (Vec::new(), String::new(), 0),
        "false" => (Vec::new(), String::new(), 1),
        "echo" => (
            format!("{}\n", rest.join(" ")).into_bytes(),
            String::new(),
            0,
        ),
        "cat" => (
            stdin.unwrap_or_default().as_bytes().to_vec(),
            String::new(),
            0,
        ),
        // A 1x1 PNG, so binary output (protocol 1.18 base64) can be checked.
        "screencap" => (
            latch_protocol::b64::decode(FAKE_SCREENCAP_PNG).unwrap_or_default(),
            String::new(),
            0,
        ),
        "id" => (
            b"uid=2000(shell) gid=2000(shell) groups=2000(shell)\n".to_vec(),
            String::new(),
            0,
        ),
        "getprop" => {
            let value = match rest.first().copied() {
                Some("ro.product.model") => "Pixel 8\n",
                Some("ro.build.version.release") => "14\n",
                _ => "\n",
            };
            (value.as_bytes().to_vec(), String::new(), 0)
        }
        "pm" if rest.first() == Some(&"list") && rest.get(1) == Some(&"packages") => (
            b"package:com.android.settings\npackage:com.android.chrome\n".to_vec(),
            String::new(),
            0,
        ),
        "seq" => match parse_seq(&rest) {
            Some((a, b)) => {
                let mut out = String::new();
                for n in a..=b {
                    out.push_str(&n.to_string());
                    out.push('\n');
                }
                (out.into_bytes(), String::new(), 0)
            }
            None => (Vec::new(), "usage: seq [first] last\n".to_owned(), 1),
        },
        other => (
            Vec::new(),
            format!("/system/bin/sh: {other}: inaccessible or not found\n"),
            127,
        ),
    }
}

/// `seq N` or `seq A B`, bounded so the fixture shell cannot be made to run away.
fn parse_seq(args: &[&str]) -> Option<(i64, i64)> {
    let (a, b) = match args {
        [n] => (1, n.parse().ok()?),
        [a, b] => (a.parse().ok()?, b.parse().ok()?),
        _ => return None,
    };
    if a > b || (b - a) > 100_000 {
        return None;
    }
    Some((a, b))
}

/// First frame of a command's result: returns one chunk, and if stdout is
/// larger than a frame, stores the remainder under a cursor for continuation.
fn first_adb_chunk(
    state: &mut FakeState,
    stdout: Vec<u8>,
    stderr: String,
    exit_code: i32,
) -> AdbResult {
    if stdout.len() <= FAKE_ADB_CHUNK_BYTES {
        return AdbResult {
            stdout: String::from_utf8_lossy(&stdout).into_owned(),
            stderr,
            exit_code: Some(exit_code),
            cursor: None,
        };
    }
    let head = stdout[..FAKE_ADB_CHUNK_BYTES].to_vec();
    let tail = stdout[FAKE_ADB_CHUNK_BYTES..].to_vec();
    let cursor = state.new_adb_cursor();
    state.adb_chunks.insert(
        cursor.clone(),
        AdbPending {
            stdout: tail,
            exit_code,
        },
    );
    AdbResult {
        stdout: String::from_utf8_lossy(&head).into_owned(),
        stderr,
        exit_code: None,
        cursor: Some(cursor),
    }
}

/// A later frame for a `continue_from` cursor.
fn next_adb_chunk(state: &mut FakeState, cursor: &str) -> Result<AdbResult, ProtocolError> {
    let mut pending = state
        .adb_chunks
        .remove(cursor)
        .ok_or_else(|| err(ErrorCode::InvalidRequest, "no such adb.shell continuation"))?;
    if pending.stdout.len() <= FAKE_ADB_CHUNK_BYTES {
        return Ok(AdbResult {
            stdout: String::from_utf8_lossy(&pending.stdout).into_owned(),
            stderr: String::new(),
            exit_code: Some(pending.exit_code),
            cursor: None,
        });
    }
    let rest = pending.stdout.split_off(FAKE_ADB_CHUNK_BYTES);
    let head = pending.stdout;
    let exit_code = pending.exit_code;
    let next = state.new_adb_cursor();
    state.adb_chunks.insert(
        next.clone(),
        AdbPending {
            stdout: rest,
            exit_code,
        },
    );
    Ok(AdbResult {
        stdout: String::from_utf8_lossy(&head).into_owned(),
        stderr: String::new(),
        exit_code: None,
        cursor: Some(next),
    })
}

/// Device-side execution of one command. Mirrors what the Android app does.
pub fn handle(
    state: &mut FakeState,
    envelope: &CommandEnvelope,
) -> Result<serde_json::Value, ProtocolError> {
    validate::envelope(envelope)?;
    if state.session.paused {
        return Err(err(ErrorCode::DeviceUnavailable, "session paused"));
    }
    if let Command::AdbShell {
        command_line: Some(_),
        ..
    } = &envelope.command
    {
        state.adb_deadlines.push(envelope.deadline_ms);
    }
    // ADR-046: full-access ADB keeps working past the ordinary session timer;
    // only owner Stop/pause (above) and revoke stop it.
    if !matches!(envelope.command, Command::AdbShell { .. })
        && state.session.expires_at_ms <= now_ms()
    {
        return Err(err(ErrorCode::DeviceUnavailable, "session ended"));
    }
    for capability in envelope.command.required_capabilities() {
        if !state.enabled(capability) {
            return Err(err(
                ErrorCode::PermissionMissing,
                "capability is off on the phone",
            ));
        }
    }

    let approval_needed = envelope.confirm.is_some()
        || (state.session.approve_every_action
            && envelope.command.needs_owner_approval_when_strict());
    if approval_needed {
        let title = envelope
            .confirm
            .as_ref()
            .map_or_else(|| envelope.command.name().to_owned(), |c| c.title.clone());
        state.approval_requests.push(title);
        match state.approval {
            Approval::Approve => {}
            Approval::Deny => return Err(err(ErrorCode::UserDenied, "the owner denied it")),
            Approval::Ignore => return Err(err(ErrorCode::ConfirmationExpired, "nobody answered")),
        }
    }

    // Actions must cite the latest observation of the screen that is still showing.
    let resolved = match envelope.command.observation_id() {
        None => None,
        Some(id) => match &state.latest {
            Some((latest_id, screen, nodes))
                if latest_id == id && *screen == state.phone.screen =>
            {
                Some(nodes)
            }
            _ => return Err(err(ErrorCode::StaleObservation, "the screen changed")),
        },
    };

    let target_effect = |target: &Target| -> Result<Effect, ProtocolError> {
        let nodes = resolved.ok_or_else(|| err(ErrorCode::Internal, "no observation"))?;
        let found = match target {
            Target::Element { element } => nodes.iter().find(|n| &n.node.id == element),
            Target::Point { x, y } => nodes
                .iter()
                .filter(|n| n.node.bounds.contains(*x, *y))
                .min_by_key(|n| n.node.bounds.area()),
        };
        let node = found.ok_or_else(|| err(ErrorCode::TargetNotFound, "no such element"))?;
        if node.node.sensitive {
            return Err(err(ErrorCode::SensitiveTarget, "sensitive element"));
        }
        Ok(node.effect.clone())
    };

    // What the screen looked like before the action (protocol 1.9 `effect`).
    let snapshot = |state: &FakeState| {
        let rendered = state.phone.render();
        let nodes: Vec<&latch_protocol::UiNode> = rendered.iter().map(|n| &n.node).collect();
        (
            screens::package_of(&state.phone.screen).to_owned(),
            serde_json::to_string(&nodes).unwrap_or_default(),
        )
    };
    let before = snapshot(state);

    let done = |state: &mut FakeState| {
        state.executed.push(envelope.command.name().to_owned());
        let mut result = ActionResult {
            package: Some(screens::package_of(&state.phone.screen).into()),
            ..ActionResult::default()
        };
        // Protocol 1.2: observe in the same round trip, under the same rules as ui.observe.
        if let Some(after) = &envelope.observe_after {
            let now = snapshot(state);
            result.effect = Some(if now.0 != before.0 {
                ActionEffect::NewApp
            } else if now.1 != before.1 {
                ActionEffect::Changed
            } else {
                ActionEffect::None
            });
            let allowed = state.enabled(Capability::UiObserve)
                && (!after.include_screenshot || state.enabled(Capability::ScreenCapture));
            if allowed {
                result.observation =
                    Some(observe(state, after.include_screenshot, after.max_nodes));
                state.executed.push("ui.observe".into());
            } else {
                result.observation_error = Some(err(
                    ErrorCode::PermissionMissing,
                    "capability is off on the phone",
                ));
            }
        }
        serde_json::to_value(result).map_err(|_| err(ErrorCode::Internal, "encode"))
    };

    match &envelope.command {
        Command::DeviceInfo {} => serde_json::to_value(DeviceInfo {
            device: descriptor(),
            screen: screen_info(),
            package: Some(screens::package_of(&state.phone.screen).into()),
            capabilities: state.capabilities.clone(),
            session: state.session,
            status: Some(DeviceStatus {
                battery_percent: Some(81),
                charging: Some(false),
                network: Some(NetworkKind::Wifi),
                free_storage_mb: Some(12_000),
                orientation: Some(Orientation::Portrait),
                locked: Some(state.pressed.last() == Some(&GlobalAction::LockScreen)),
                do_not_disturb: Some(
                    state.controls.iter().rev().find_map(|c| match c {
                        PhoneControl::DoNotDisturb { on } => Some(*on),
                        _ => None,
                    }) == Some(true),
                ),
            }),
        })
        .map_err(|_| err(ErrorCode::Internal, "encode")),
        Command::Observe {
            include_screenshot,
            max_nodes,
            marks,
        } => {
            let observation = observe(state, *include_screenshot, *max_nodes);
            // Protocol 1.16: a real phone draws the ids on the screenshot; the fake one records the request.
            state.executed.push(
                if *marks {
                    "ui.observe+marks"
                } else {
                    "ui.observe"
                }
                .into(),
            );
            serde_json::to_value(observation).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::Tap { target, .. } => {
            match target_effect(target)? {
                Effect::Open(screen) => state.phone.open(screen),
                Effect::ToggleWifi => state.phone.wifi_on = !state.phone.wifi_on,
                Effect::AddToCart(i) => state.phone.cart[i] += 1,
                Effect::SendMessage if !state.phone.draft.is_empty() => {
                    let draft = std::mem::take(&mut state.phone.draft);
                    state.phone.sent_messages.push(draft);
                }
                _ => {}
            }
            state.latest = None;
            done(state)
        }
        Command::TypeText {
            element,
            text,
            submit,
            ..
        } => {
            let submitted = match target_effect(&Target::Element {
                element: element.clone(),
            })? {
                // In the chat app the keyboard's action key sends, like most messengers.
                Effect::EditDraft if *submit => {
                    state.phone.sent_messages.push(text.clone());
                    true
                }
                Effect::EditDraft => {
                    state.phone.draft = text.clone();
                    false
                }
                // The shop searches as you type; Enter just confirms.
                Effect::EditSearch => {
                    state.phone.shop_query = text.clone();
                    true
                }
                // The sign-in form ignores Enter, like apps that only send with their own button.
                Effect::EditUsername => {
                    state.phone.username = text.clone();
                    false
                }
                _ => return Err(err(ErrorCode::InvalidRequest, "not editable")),
            };
            state.latest = None;
            let mut value = done(state)?;
            if *submit {
                value["submitted"] = serde_json::Value::Bool(submitted);
            }
            Ok(value)
        }
        Command::Pinch { .. } => {
            state.latest = None;
            done(state)
        }
        Command::Gesture { strokes, .. } => {
            state.gestures.push(strokes.len());
            state.latest = None;
            done(state)
        }
        Command::ListFiles {
            location,
            folder,
            query,
            limit,
            offset,
        } => {
            let list = state.files.list(
                *location,
                folder.as_deref(),
                query.as_deref(),
                *limit,
                *offset,
            )?;
            state.executed.push("file.list".into());
            serde_json::to_value(list).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::PreviewFile { id } => {
            let preview = state.files.preview(id)?;
            state.executed.push("file.preview".into());
            serde_json::to_value(preview).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::ReadFile { id, offset, length } => {
            let chunk = state.files.read(id, *offset, *length)?;
            state.executed.push("file.read".into());
            serde_json::to_value(chunk).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::WriteFile {
            location,
            folder,
            subfolder,
            name,
            mime,
            data_base64,
            append,
            overwrite,
        } => {
            let item = state.files.write(
                *location,
                folder.as_deref(),
                subfolder.as_deref(),
                name,
                mime.as_deref(),
                data_base64,
                *append,
                *overwrite,
            )?;
            state.executed.push("file.write".into());
            serde_json::to_value(item).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::MakeFolder { folder, name } => {
            let item = state.files.mkdir(folder.as_deref(), name)?;
            state.executed.push("file.mkdir".into());
            serde_json::to_value(item).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::RenameFile { id, name } => {
            let item = state.files.rename(id, name)?;
            state.executed.push("file.rename".into());
            serde_json::to_value(item).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::DeleteFile { id } => {
            state.files.delete(id)?;
            state.executed.push("file.delete".into());
            serde_json::to_value(ActionResult::default())
                .map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::Share { package, ids, text } => {
            let names = ids
                .iter()
                .map(|id| state.files.name_of(id))
                .collect::<Result<Vec<_>, _>>()?;
            state.shared.push((package.clone(), names, text.clone()));
            state.latest = None;
            done(state)
        }
        Command::FetchFile {
            location,
            folder,
            subfolder,
            name,
            mime,
            overwrite,
            link,
            sha256,
            size,
        } => {
            let id = format!("t_{:04}", state.transfers.len() + 1);
            state.transfers.push(transfers::FakeTransfer {
                id: id.clone(),
                done_bytes: 0,
                total_bytes: *size,
                destination: Some(transfers::Destination {
                    location: *location,
                    folder: folder.clone(),
                    subfolder: subfolder.clone(),
                    name: name.clone(),
                    mime: mime.clone(),
                    overwrite: *overwrite,
                }),
                item: None,
                sha256: None,
                error: None,
                finished: false,
            });
            state.jobs.push(transfers::Job::Fetch {
                transfer: id.clone(),
                link: (**link).clone(),
                sha256: sha256.clone(),
                local: local_slot(state, &link.url),
            });
            state.executed.push("file.fetch".into());
            transfer_value(state, &id)
        }
        Command::PushFile {
            id,
            link,
            max_bytes,
        } => {
            let (item, data) = state.files.bytes_of(id)?;
            if data.len() as u64 > *max_bytes {
                return Err(err(
                    ErrorCode::InvalidRequest,
                    "the file is larger than this gateway's link limit",
                ));
            }
            let transfer = format!("t_{:04}", state.transfers.len() + 1);
            state.transfers.push(transfers::FakeTransfer {
                id: transfer.clone(),
                done_bytes: 0,
                total_bytes: Some(data.len() as u64),
                destination: None,
                item: Some(item),
                sha256: Some(transfers::sha256_hex(&data)),
                error: None,
                finished: false,
            });
            state.jobs.push(transfers::Job::Push {
                transfer: transfer.clone(),
                link: (**link).clone(),
                data,
                max_bytes: *max_bytes,
                local: local_slot(state, &link.url),
            });
            state.executed.push("file.push".into());
            transfer_value(state, &transfer)
        }
        Command::OpenLocal { direction, .. } => {
            if !state.local_transfers {
                return Err(err(
                    ErrorCode::UnsupportedCapability,
                    "fast transfers on the same Wi-Fi are off on this phone",
                ));
            }
            // A real listener on loopback, standing in for the phone's Wi-Fi address.
            let runtime = tokio::runtime::Handle::try_current()
                .map_err(|_| err(ErrorCode::UnsupportedCapability, "no network here"))?;
            let listener = std::net::TcpListener::bind("127.0.0.1:0")
                .and_then(|l| l.set_nonblocking(true).map(|()| l))
                .map_err(|_| err(ErrorCode::TransportUnavailable, "could not listen"))?;
            let port = listener
                .local_addr()
                .map_err(|_| err(ErrorCode::Internal, "no address"))?
                .port();
            let token = transfers::random_token();
            let slot = transfers::LocalSlot {
                url: format!("http://127.0.0.1:{port}/{token}"),
                direction: *direction,
                data: Default::default(),
            };
            runtime.spawn(transfers::serve_slot(listener, slot.clone(), token));
            let answer = latch_protocol::LocalSlot {
                url: slot.url.clone(),
                expires_at_ms: now_ms() + transfers::LOCAL_SLOT_TTL_MS,
            };
            state.local_slots.push(slot);
            state.executed.push("file.local".into());
            serde_json::to_value(answer).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::TransferStatus {
            transfer, cancel, ..
        } => {
            if *cancel
                && let Some(t) = state
                    .transfers
                    .iter_mut()
                    .find(|t| &t.id == transfer && !t.finished)
            {
                t.finished = true;
                t.error = Some(err(ErrorCode::Cancelled, "the transfer was stopped"));
            }
            transfer_value(state, transfer)
        }
        Command::ListActivity {
            limit,
            kinds,
            since_ms,
        } => {
            // The fake phone's log: one line per command it ran, newest first.
            let entries: Vec<ActivityEntry> = state
                .executed
                .iter()
                .enumerate()
                .rev()
                .map(|(i, name)| ActivityEntry {
                    at_ms: 1_700_000_000_000 + i as u64 * 1_000,
                    kind: if name == "ui.observe" || name == "app.list" {
                        ActivityKind::Screen
                    } else if name.starts_with("file.") {
                        ActivityKind::File
                    } else if name == "task.done" {
                        ActivityKind::Task
                    } else {
                        ActivityKind::Action
                    },
                    summary: format!("Ran {name}"),
                    app: None,
                    by: Some(ActivityActor::Ai),
                })
                .filter(|e| kinds.is_empty() || kinds.contains(&e.kind))
                .filter(|e| since_ms.is_none_or(|since| e.at_ms >= since))
                .collect();
            let total = entries.len() as u32;
            let entries = entries.into_iter().take(*limit as usize).collect();
            serde_json::to_value(ActivityList { entries, total })
                .map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::TaskDone { summary } => {
            state.tasks_done.push(summary.clone().unwrap_or_default());
            state.executed.push("task.done".into());
            serde_json::to_value(ActionResult::default())
                .map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::DeviceControl { control } => {
            state.controls.push(*control);
            state.executed.push("device.control".into());
            if let PhoneControl::Panel { .. } = control {
                // The fake phone shows its network page as every panel.
                state.phone.screen = Screen::Network;
                state.latest = None;
                return done(state);
            }
            serde_json::to_value(ActionResult::default())
                .map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::SetClipboard { text } => {
            state.clipboard.push(text.clone());
            state.executed.push("clipboard.set".into());
            serde_json::to_value(ActionResult::default())
                .map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::AdbShell {
            command_line,
            stdin,
            continue_from,
            base64,
            ..
        } => {
            state.executed.push("adb.shell".into());
            let result = if let Some(cursor) = continue_from {
                next_adb_chunk(state, cursor)?
            } else {
                let line = command_line.as_deref().unwrap_or_default();
                state.adb_commands.push(line.to_owned());
                let (stdout, stderr, exit_code) = run_fake_shell(line, stdin.as_deref());
                // Since 1.18: the exact bytes as base64, chunked like any text.
                let stdout = if *base64 {
                    latch_protocol::b64::encode(&stdout).into_bytes()
                } else {
                    stdout
                };
                first_adb_chunk(state, stdout, stderr, exit_code)
            };
            serde_json::to_value(result).map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::AskOwner { message } => {
            state.owner_questions.push(message.clone());
            let reply = state.owner_reply;
            state.latest = None;
            let mut value = done(state)?;
            value["owner"] =
                serde_json::to_value(reply).map_err(|_| err(ErrorCode::Internal, "encode"))?;
            Ok(value)
        }
        Command::Swipe { .. } => {
            if state.phone.screen == Screen::Settings {
                state.phone.settings_scrolled = true;
            }
            state.latest = None;
            done(state)
        }
        Command::Global { action } => {
            state.pressed.push(*action);
            match action {
                GlobalAction::Back => state.phone.back(),
                GlobalAction::Home | GlobalAction::Recents => state.phone.home(),
                // The fake phone has no shade, lock screen, or gallery: the screen stays.
                _ => {}
            }
            state.latest = None;
            done(state)
        }
        Command::WaitFor {
            text,
            gone,
            max_nodes,
            ..
        } => {
            // The fake screen never changes on its own, so the answer is immediate.
            let present = shows_text(&state.phone.render(), text);
            let observation = observe(state, false, *max_nodes);
            state.executed.push("ui.wait".into());
            serde_json::to_value(WaitResult {
                matched: present != *gone,
                observation,
            })
            .map_err(|_| err(ErrorCode::Internal, "encode"))
        }
        Command::ScrollTo { text, .. } => {
            // Only the settings list is longer than the screen.
            if !shows_text(&state.phone.render(), text) && state.phone.screen == Screen::Settings {
                state.phone.settings_scrolled = true;
            }
            let found = shows_text(&state.phone.render(), text);
            state.latest = None;
            let mut value = done(state)?;
            value["found"] = serde_json::Value::Bool(found);
            Ok(value)
        }
        Command::ListApps {} => serde_json::to_value(AppList {
            apps: screens::apps(),
        })
        .map_err(|_| err(ErrorCode::Internal, "encode")),
        Command::OpenLink { url, package } => {
            let screen = screens::APPS
                .iter()
                .find(|(p, _, _)| p == package)
                .map(|(_, _, s)| s.clone())
                .ok_or_else(|| {
                    err(
                        ErrorCode::TargetNotFound,
                        "no app on the phone opens that link",
                    )
                })?;
            state.links.push((url.clone(), package.clone()));
            state.phone.open(screen);
            state.latest = None;
            done(state)
        }
        Command::LaunchApp { package, fresh } => {
            if *fresh {
                state.fresh_launches.push(package.clone());
            }
            let screen = screens::APPS
                .iter()
                .find(|(p, _, _)| p == package)
                .map(|(_, _, s)| s.clone())
                .ok_or_else(|| {
                    err(
                        ErrorCode::TargetNotFound,
                        "no launchable app with that package",
                    )
                })?;
            state.phone.home();
            state.phone.open(screen);
            state.latest = None;
            done(state)
        }
    }
}

/// Whether a non-sensitive, non-editable element's text or description contains `text`, ignoring case.
fn shows_text(nodes: &[screens::Node], text: &str) -> bool {
    let needle = text.to_lowercase();
    nodes.iter().any(|n| {
        // Input fields hold what the agent typed, not the page answering.
        !n.node.sensitive
            && !n.node.editable
            && [n.node.text.as_deref(), n.node.description.as_deref()]
                .into_iter()
                .flatten()
                .any(|t| t.to_lowercase().contains(&needle))
    })
}

/// Captures the fake screen and makes it the latest observation.
fn observe(state: &mut FakeState, include_screenshot: bool, max_nodes: u32) -> Observation {
    state.observation_counter += 1;
    let id = format!("o_{}", state.observation_counter);
    let nodes = state.phone.render();
    let truncated = nodes.len() > max_nodes as usize;
    let ui: Vec<_> = nodes
        .iter()
        .take(max_nodes as usize)
        .map(|n| n.node.clone())
        .collect();
    let observation = Observation {
        observation_id: id.clone(),
        captured_at_ms: now_ms(),
        package: Some(screens::package_of(&state.phone.screen).into()),
        screen: screen_info(),
        redacted_count: ui.iter().filter(|n| n.sensitive).count() as u32,
        nodes: ui,
        screenshot: include_screenshot.then(|| Screenshot {
            mime: "image/png".into(),
            width: 1,
            height: 1,
            data_base64: PIXEL_PNG.into(),
        }),
        truncated,
    };
    state.latest = Some((id, state.phone.screen.clone(), nodes));
    observation
}

fn descriptor() -> DeviceDescriptor {
    DeviceDescriptor {
        platform: "fake".into(),
        os_version: "1".into(),
        model: "Latch fake phone".into(),
        app_version: env!("CARGO_PKG_VERSION").into(),
    }
}

fn screen_info() -> ScreenInfo {
    ScreenInfo {
        width: screens::WIDTH,
        height: screens::HEIGHT,
        rotation: 0,
    }
}

/// A transfer as `file.fetch`, `file.push`, and `file.transfer` answer it.
fn transfer_value(state: &FakeState, id: &str) -> Result<serde_json::Value, ProtocolError> {
    let t = state.transfers.iter().find(|t| t.id == id).ok_or_else(|| {
        err(
            ErrorCode::TargetNotFound,
            "no transfer with that id in this session",
        )
    })?;
    if let Some(error) = &t.error {
        return Err(error.clone());
    }
    serde_json::to_value(latch_protocol::FileTransfer {
        id: t.id.clone(),
        state: if t.finished {
            latch_protocol::TransferState::Done
        } else {
            latch_protocol::TransferState::Running
        },
        done_bytes: t.done_bytes,
        total_bytes: t.total_bytes,
        item: t.item.clone().filter(|_| t.finished),
        sha256: t
            .sha256
            .clone()
            .filter(|_| t.finished && t.destination.is_none()),
    })
    .map_err(|_| err(ErrorCode::Internal, "encode"))
}

/// How long `file.fetch` and `file.push` wait for a quick transfer before
/// answering "running" (the Android app does the same).
const QUICK_TRANSFER_MS: u64 = 1_500;

/// [`handle`], plus what needs time: `file.transfer` waits up to its
/// `wait_ms`, and transfers run in the background like on a real phone.
pub async fn handle_async(
    state: &Shared,
    envelope: &CommandEnvelope,
) -> Result<serde_json::Value, ProtocolError> {
    let running = |state: &Shared, id: &str| {
        lock(state)
            .transfers
            .iter()
            .any(|t| t.id == id && !t.finished)
    };
    if let Command::TransferStatus {
        transfer,
        wait_ms,
        cancel: false,
    } = &envelope.command
    {
        let until = now_ms() + u64::from(*wait_ms);
        while now_ms() < until && running(state, transfer) {
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
    }
    let result = handle(&mut lock(state), envelope);
    let jobs = std::mem::take(&mut lock(state).jobs);
    let started: Vec<String> = jobs
        .iter()
        .map(|j| match j {
            transfers::Job::Fetch { transfer, .. } | transfers::Job::Push { transfer, .. } => {
                transfer.clone()
            }
        })
        .collect();
    for job in jobs {
        tokio::spawn(run_job(state.clone(), job));
    }
    if let (Ok(_), Some(id)) = (&result, started.first()) {
        let until = now_ms() + QUICK_TRANSFER_MS;
        while now_ms() < until && running(state, id) {
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        return transfer_value(&lock(state), id);
    }
    result
}

async fn run_job(state: Shared, job: transfers::Job) {
    let delay = lock(&state).transfer_delay_ms;
    if delay > 0 {
        tokio::time::sleep(std::time::Duration::from_millis(delay)).await;
    }
    match job {
        transfers::Job::Fetch {
            transfer,
            link,
            sha256,
            local,
        } => {
            let downloaded = match &local {
                Some(slot) => transfers::take_local(slot, &link, &sha256),
                None => transfers::download(&link, &sha256).await,
            };
            let mut s = lock(&state);
            let Some(dest) = s
                .transfers
                .iter()
                .find(|t| t.id == transfer && !t.finished)
                .and_then(|t| t.destination.clone())
            else {
                return; // cancelled meanwhile
            };
            let saved = downloaded.and_then(|data| {
                let len = data.len() as u64;
                s.files
                    .write_bytes(
                        dest.location,
                        dest.folder.as_deref(),
                        dest.subfolder.as_deref(),
                        &dest.name,
                        dest.mime.as_deref(),
                        data,
                        false,
                        dest.overwrite,
                    )
                    .map(|item| (item, len))
            });
            if let Some(t) = s.transfers.iter_mut().find(|t| t.id == transfer) {
                t.finished = true;
                match saved {
                    Ok((item, len)) => {
                        t.item = Some(item);
                        t.done_bytes = len;
                        t.total_bytes = Some(len);
                    }
                    Err(e) => t.error = Some(e),
                }
            }
        }
        transfers::Job::Push {
            transfer,
            link,
            data,
            max_bytes: _,
            local,
        } => {
            let len = data.len() as u64;
            let uploaded = match &local {
                Some(slot) => transfers::fill_local(slot, &link, data),
                None => transfers::upload(&link, data).await,
            };
            let mut s = lock(&state);
            if let Some(t) = s
                .transfers
                .iter_mut()
                .find(|t| t.id == transfer && !t.finished)
            {
                t.finished = true;
                match uploaded {
                    Ok(()) => t.done_bytes = len,
                    Err(e) => t.error = Some(e),
                }
            }
        }
    }
}

/// Minimal HTTP/1.1 request over plain TCP, for local tests and demos only.
/// Returns the status code and body.
pub async fn http(
    base_url: &str,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: Option<&str>,
) -> Result<(u16, String), FakeError> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let io = |e: std::io::Error| FakeError::Connect(e.to_string());
    let host = base_url
        .strip_prefix("http://")
        .ok_or_else(|| FakeError::Connect("the fake device only supports http:// gateways".into()))?
        .trim_end_matches('/');
    let body = body.unwrap_or("");
    let auth = token
        .map(|t| format!("Authorization: Bearer {t}\r\n"))
        .unwrap_or_default();
    let request = format!(
        "{method} {path} HTTP/1.1\r\nHost: {host}\r\n{auth}Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let mut stream = tokio::net::TcpStream::connect(host).await.map_err(io)?;
    stream.write_all(request.as_bytes()).await.map_err(io)?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).await.map_err(io)?;
    let response = String::from_utf8_lossy(&raw).into_owned();
    let (head, rest) = response
        .split_once("\r\n\r\n")
        .ok_or_else(|| FakeError::Connect("malformed response".into()))?;
    let status = head
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| FakeError::Connect("malformed status line".into()))?;
    let body = if head
        .to_ascii_lowercase()
        .contains("transfer-encoding: chunked")
    {
        dechunk(rest)
    } else {
        rest.to_owned()
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
        s = rest[n..].trim_start_matches("\r\n");
    }
    out
}

/// Exchanges a pairing code for a device token over plain HTTP.
///
/// Deliberately minimal (no TLS): the fake device is for local tests and demos.
pub async fn pair(base_url: &str, code: &str) -> Result<(String, String), FakeError> {
    let body = serde_json::json!({ "code": code, "platform": "fake", "model": "Latch fake phone" })
        .to_string();
    let (status, body) = http(base_url, "POST", "/v1/pair", None, Some(&body))
        .await
        .map_err(|e| FakeError::Pairing(e.to_string()))?;
    if status != 200 {
        return Err(FakeError::Pairing(format!("HTTP {status}")));
    }
    let json: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| FakeError::Pairing(e.to_string()))?;
    let field = |k: &str| {
        json.get(k)
            .and_then(|v| v.as_str())
            .map(str::to_owned)
            .ok_or_else(|| FakeError::Pairing(format!("response lacks {k}")))
    };
    Ok((field("device_id")?, field("token")?))
}

/// Owner actions a test can trigger while the device is connected.
#[derive(Debug)]
pub enum Control {
    /// Send the current capabilities and session (after changing them in [`FakeState`]).
    PushState,
    /// Press Stop: say bye and disconnect.
    Stop,
}

/// Connects to `ws_url` (e.g. `ws://127.0.0.1:8787/v1/device`) and serves
/// commands until the gateway closes the connection, revokes the device, or a
/// [`Control::Stop`] arrives.
pub async fn run(
    ws_url: &str,
    token: &str,
    state: Shared,
    mut control: tokio::sync::mpsc::Receiver<Control>,
) -> Result<(), FakeError> {
    let mut request = ws_url
        .into_client_request()
        .map_err(|e| FakeError::Connect(e.to_string()))?;
    let auth = HeaderValue::from_str(&format!("Bearer {token}"))
        .map_err(|e| FakeError::Connect(e.to_string()))?;
    request.headers_mut().insert("Authorization", auth);
    let (socket, _) = tokio_tungstenite::connect_async(request)
        .await
        .map_err(|e| FakeError::Connect(e.to_string()))?;
    let (mut sink, mut stream) = socket.split();

    let hello = {
        let s = lock(&state);
        DeviceToGateway::Hello(Hello {
            protocol: s
                .hello_protocol
                .clone()
                .unwrap_or_else(|| PROTOCOL_VERSION.into()),
            device: descriptor(),
            capabilities: s.capabilities.clone(),
            session: s.session,
            device_time_ms: Some(now_ms()),
            device_key: s.device_key.as_ref().map(signing::DeviceKey::public_b64),
            battery_restricted: s.battery_restricted,
        })
    };
    let key = lock(&state).device_key.clone();
    // Signed messages are bound to the connection the welcome names ("" for hello).
    let mut connection = String::new();
    send(&mut sink, &encode(key.as_ref(), &connection, &hello)?).await?;

    loop {
        let frame = tokio::select! {
            frame = stream.next() => frame,
            action = control.recv() => {
                match action {
                    Some(Control::PushState) => {
                        let message = {
                            let s = lock(&state);
                            DeviceToGateway::State {
                                capabilities: s.capabilities.clone(),
                                session: s.session,
                                device_time_ms: Some(now_ms()),
                            }
                        };
                        send(&mut sink, &encode(key.as_ref(), &connection, &message)?).await?;
                    }
                    Some(Control::Stop) | None => {
                        let bye = DeviceToGateway::Bye { reason: "user_stopped".into() };
                        send(&mut sink, &encode(key.as_ref(), &connection, &bye)?).await?;
                        let _ = sink.close().await;
                        return Ok(());
                    }
                }
                continue;
            }
        };
        let Some(frame) = frame else { break };
        let frame = frame.map_err(|e| FakeError::Connect(e.to_string()))?;
        let text = match frame {
            Message::Text(text) => text,
            Message::Close(_) => break,
            _ => continue,
        };
        let message: GatewayToDevice = serde_json::from_str(&text)
            .map_err(|e| FakeError::Protocol(format!("bad gateway frame: {e}")))?;
        match message {
            GatewayToDevice::Welcome {
                device_id,
                connection: named,
                ..
            } => {
                connection = named.unwrap_or_default();
                lock(&state).device_id = Some(device_id);
            }
            GatewayToDevice::Command(envelope) => {
                let outcome = answer(&state, &envelope).await;
                if lose_answer(&state, &envelope) {
                    // The answer is lost with the connection; the phone remembers it.
                    return Ok(());
                }
                let result = DeviceToGateway::Result {
                    id: envelope.id,
                    outcome,
                };
                send(&mut sink, &encode(key.as_ref(), &connection, &result)?).await?;
            }
            GatewayToDevice::Cancel { .. } | GatewayToDevice::ApprovalAnswer { .. } => {}
            GatewayToDevice::Revoked { .. } => {
                lock(&state).revoked = true;
                break;
            }
        }
    }
    Ok(())
}

/// Same as [`run`], over the HTTP long-poll binding (`/v1/device/hello`,
/// `/poll`, `/messages`) that serverless gateways use.
pub async fn run_poll(
    base_url: &str,
    token: &str,
    state: Shared,
    mut control: tokio::sync::mpsc::Receiver<Control>,
) -> Result<(), FakeError> {
    let protocol = |e: serde_json::Error| FakeError::Protocol(e.to_string());
    let hello = {
        let s = lock(&state);
        DeviceToGateway::Hello(Hello {
            protocol: s
                .hello_protocol
                .clone()
                .unwrap_or_else(|| PROTOCOL_VERSION.into()),
            device: descriptor(),
            capabilities: s.capabilities.clone(),
            session: s.session,
            device_time_ms: Some(now_ms()),
            device_key: s.device_key.as_ref().map(signing::DeviceKey::public_b64),
            battery_restricted: s.battery_restricted,
        })
    };
    let key = lock(&state).device_key.clone();
    let hello = encode(key.as_ref(), "", &hello)?;
    let (status, body) = http(
        base_url,
        "POST",
        "/v1/device/hello",
        Some(token),
        Some(&hello),
    )
    .await?;
    if status != 200 {
        return Err(FakeError::Connect(format!(
            "hello failed with HTTP {status}"
        )));
    }
    let connection = match serde_json::from_str::<GatewayToDevice>(&body).map_err(protocol)? {
        GatewayToDevice::Welcome {
            device_id,
            connection: Some(connection),
            ..
        } => {
            lock(&state).device_id = Some(device_id);
            connection
        }
        _ => {
            return Err(FakeError::Protocol(
                "expected welcome with a connection".into(),
            ));
        }
    };
    let poll_path = format!("/v1/device/poll?connection={connection}&wait=5");
    let post_path = format!("/v1/device/messages?connection={connection}");
    let post = |message: DeviceToGateway| {
        let post_path = post_path.clone();
        let text = encode(key.as_ref(), &connection, &message);
        async move {
            let text = text?;
            let (status, _) = http(base_url, "POST", &post_path, Some(token), Some(&text)).await?;
            if status == 204 {
                Ok(())
            } else {
                Err(FakeError::Connect(format!(
                    "message rejected with HTTP {status}"
                )))
            }
        }
    };

    loop {
        let polled = tokio::select! {
            polled = http(base_url, "GET", &poll_path, Some(token), None) => polled?,
            action = control.recv() => {
                match action {
                    Some(Control::PushState) => {
                        let message = {
                            let s = lock(&state);
                            DeviceToGateway::State {
                                capabilities: s.capabilities.clone(),
                                session: s.session,
                                device_time_ms: Some(now_ms()),
                            }
                        };
                        post(message).await?;
                    }
                    Some(Control::Stop) | None => {
                        post(DeviceToGateway::Bye { reason: "user_stopped".into() }).await?;
                        return Ok(());
                    }
                }
                continue;
            }
        };
        match polled {
            (204, _) => continue,
            (200, body) => {
                match serde_json::from_str::<GatewayToDevice>(&body).map_err(protocol)? {
                    GatewayToDevice::Command(envelope) => {
                        let outcome = answer(&state, &envelope).await;
                        if lose_answer(&state, &envelope) {
                            // The answer is lost with the connection; the phone remembers it.
                            return Ok(());
                        }
                        post(DeviceToGateway::Result {
                            id: envelope.id,
                            outcome,
                        })
                        .await?;
                    }
                    GatewayToDevice::Revoked { .. } => {
                        lock(&state).revoked = true;
                        return Ok(());
                    }
                    GatewayToDevice::Cancel { .. }
                    | GatewayToDevice::Welcome { .. }
                    | GatewayToDevice::ApprovalAnswer { .. } => {}
                }
            }
            (401, _) => {
                lock(&state).revoked = true;
                return Ok(());
            }
            (status, _) => {
                return Err(FakeError::Connect(format!(
                    "poll failed with HTTP {status}"
                )));
            }
        }
    }
}

/// A message as the phone sends it: signed when it has a device key (1.12).
fn encode(
    key: Option<&signing::DeviceKey>,
    connection: &str,
    message: &DeviceToGateway,
) -> Result<String, FakeError> {
    match key {
        Some(key) => key.seal(connection, message),
        None => serde_json::to_string(message),
    }
    .map_err(|e| FakeError::Protocol(e.to_string()))
}

async fn send<S>(sink: &mut S, text: &str) -> Result<(), FakeError>
where
    S: SinkExt<Message> + Unpin,
{
    sink.send(Message::Text(text.to_owned().into()))
        .await
        .map_err(|_| FakeError::Connect("send failed".into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use latch_protocol::ObserveAfter;

    fn envelope(command: Command, observe_after: bool) -> CommandEnvelope {
        CommandEnvelope {
            id: "c_1".into(),
            deadline_ms: 20_000,
            command,
            confirm: None,
            observe_after: observe_after.then_some(ObserveAfter {
                settle_ms: 0,
                include_screenshot: false,
                max_nodes: 400,
                quiet_ms: None,
            }),
        }
    }

    #[test]
    fn an_id_seen_again_is_answered_from_memory_and_never_run_twice() {
        let ok = |n: u32| Outcome::Ok {
            data: serde_json::json!({ "n": n }),
        };
        let mut answers = Answers::default();
        assert_eq!(answers.replay("c_0"), None, "a new id runs");
        for n in 0..=Answers::KEPT as u32 {
            answers.remember(format!("c_{n}"), ok(n));
        }
        // The newest answers come back exactly as first sent.
        assert_eq!(answers.replay("c_64"), Some(ok(64)));
        // The oldest no longer has its answer, but is still not run again.
        match answers.replay("c_0") {
            Some(Outcome::Error { error }) => assert_eq!(error.code, ErrorCode::Cancelled),
            other => panic!("expected cancelled, got {other:?}"),
        }
    }

    #[test]
    fn observe_after_returns_the_new_screen_or_says_why_not() {
        let home = || Command::Global {
            action: GlobalAction::Home,
        };
        let mut state = FakeState::new(&Capability::ALL);
        let data = handle(&mut state, &envelope(home(), true)).expect("action runs");
        let result: ActionResult = serde_json::from_value(data).expect("action result");
        let observation = result
            .observation
            .expect("observation in the same round trip");
        // The returned observation is the latest one, so the next action may cite it.
        let tap = Command::Tap {
            observation_id: observation.observation_id,
            target: Target::Element {
                element: "n1".into(),
            },
            long_press: false,
            double: false,
        };
        handle(&mut state, &envelope(tap, false)).expect("fresh observation accepted");

        // Without ui.observe the action still runs and the phone says why it did not observe.
        let mut limited = FakeState::new(&[Capability::NavGlobal]);
        let data = handle(&mut limited, &envelope(home(), true)).expect("action runs");
        let result: ActionResult = serde_json::from_value(data).expect("action result");
        assert!(result.observation.is_none());
        assert_eq!(
            result.observation_error.expect("reason").code,
            ErrorCode::PermissionMissing
        );

        // observe_after on a read is refused before anything runs.
        let observe = Command::Observe {
            include_screenshot: false,
            max_nodes: 10,
            marks: false,
        };
        let refused = handle(&mut state, &envelope(observe, true)).expect_err("refused");
        assert_eq!(refused.code, ErrorCode::InvalidRequest);
    }
}
