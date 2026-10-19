// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Live mirror (roadmap R30, ADR-051): the owner watches the phone's screen in
//! a browser and uses it from there.
//!
//! Two WebSockets meet here. The phone dials `GET /v1/mirror/phone` with its
//! device token and proves its device key; the browser dials
//! `GET /v1/mirror/view` and joins with the 6-digit code the phone shows or the
//! viewer key from the phone's link. The gateway relays bytes only: it never
//! decodes, stores, or logs a frame, and it passes on from the browser only the
//! input messages it validated, rebuilt from their fields.
//!
//! Video is never queued: when the browser falls behind, frames are dropped
//! until the next key frame, which the phone is asked for. Mirroring is for
//! the owner, a person; MCP clients have no route to it.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::State;
use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::sync::mpsc;

use crate::{AppState, now_ms, secret, signing};

/// The mirror channel's own version; the device protocol is unchanged.
pub const MIRROR_VERSION: u32 = 1;
/// Largest video frame: a full-resolution key frame with room to spare.
pub const MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;
/// Largest text message either side may send.
pub const MAX_TEXT_BYTES: usize = 16 * 1024;
/// Largest text message from the phone: room for clipboard text (ADR-057).
pub const MAX_PHONE_TEXT_BYTES: usize = 64 * 1024;
/// Frame header: kind (1 byte), flags (1 byte), timestamp in µs (8 bytes, big-endian).
pub const FRAME_HEADER_BYTES: usize = 10;
/// H.264 Annex B access unit; key frames carry their SPS and PPS.
pub const KIND_H264: u8 = 1;
/// A JPEG still, for browsers without WebCodecs.
pub const KIND_JPEG: u8 = 2;
/// The phone's sound: 48 kHz stereo 16-bit PCM, 20 ms a frame (ADR-055).
pub const KIND_PCM: u8 = 3;
/// A full-resolution PNG screenshot the owner asked for (ADR-057); never dropped.
pub const KIND_SHOT: u8 = 4;
pub const FLAG_KEY: u8 = 1;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const PING_EVERY: Duration = Duration::from_secs(15);
const IDLE_TIMEOUT: Duration = Duration::from_secs(45);
/// A phone that drops off without saying `end` may come back with the same key
/// within this time; the viewer stays and the picture resumes.
pub const PHONE_GRACE: Duration = Duration::from_secs(15);
/// The code the phone shows works once, for this long.
pub const CODE_LIFETIME_MS: u64 = 10 * 60 * 1000;
/// Wrong codes for one mirror before its code stops working.
const WRONG_CODES_PER_MIRROR: u32 = 5;
/// Wrong codes across the gateway per minute before code joins pause for the minute.
const WRONG_CODES_PER_MINUTE: usize = 20;
/// Video frames waiting for one viewer before newer ones are dropped. A few
/// frames of slack absorb jitter; more would only add delay (at 60 frames a
/// second, three are 50 ms).
const VIEWER_VIDEO_QUEUE: usize = 3;
const VIEWER_TEXT_QUEUE: usize = 64;
const PHONE_QUEUE: usize = 256;
/// Least time between key-frame requests to the phone.
const SYNC_REQUEST_GAP_MS: u64 = 250;
/// Input messages a viewer may send per second; more are dropped.
const VIEWER_MESSAGES_PER_SECOND: u32 = 1_000;
/// Largest coordinate or scroll distance in phone pixels.
const MAX_COORDINATE: i32 = 16_384;
const MAX_TEXT_CHARS: usize = 256;
/// Most characters one paste from the computer's clipboard carries (ADR-057).
const MAX_PASTE_CHARS: usize = 4096;

// Close codes the page understands (docs/protocol/mirror.md).
pub const CLOSE_REFUSED: u16 = 4401;
pub const CLOSE_REPLACED: u16 = 4409;
pub const CLOSE_ENDED: u16 = 4410;

#[derive(Default)]
pub struct Mirrors {
    inner: Mutex<Inner>,
}

#[derive(Default)]
struct Inner {
    sessions: HashMap<String, Session>,
    /// When recent wrong codes were entered, gateway-wide.
    wrong_codes: VecDeque<u64>,
    next_id: u64,
}

struct Session {
    /// Changes whenever the phone (re)connects, so a stale connection's frames are ignored.
    generation: u64,
    key_hash: [u8; 32],
    /// Kept in memory only, to hand to a viewer who joined by code so it can reconnect.
    key: String,
    code: Option<Code>,
    phone: Option<mpsc::Sender<ToPhone>>,
    viewer: Option<Viewer>,
    /// The phone's latest `screen` message, for a viewer who joins later.
    screen: Option<String>,
    /// The phone's latest `ai` message.
    ai: Option<String>,
    /// The phone's latest `status` and `input` messages (ADR-057).
    status: Option<String>,
    input: Option<String>,
}

struct Code {
    digits: String,
    expires_ms: u64,
    wrong: u32,
}

struct Viewer {
    id: u64,
    codec: Codec,
    video: mpsc::Sender<Bytes>,
    text: mpsc::Sender<ToViewer>,
    waiting_key: bool,
    last_sync_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Codec {
    H264,
    Jpeg,
}

enum ToPhone {
    Text(String),
    Close(u16, &'static str),
}

enum ToViewer {
    Text(String),
    /// A frame that must arrive (a screenshot), queued with the text messages.
    Binary(Bytes),
    Close(u16, &'static str),
}

fn lock(m: &Mutex<Inner>) -> MutexGuard<'_, Inner> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn hash(key: &str) -> [u8; 32] {
    Sha256::digest(key.as_bytes()).into()
}

/// A viewer key as the phone makes it: 32 random bytes, base64url without padding.
pub fn valid_key(key: &str) -> bool {
    key.len() == 43
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn new_code(taken: impl Fn(&str) -> bool) -> String {
    loop {
        let n = u32::from_le_bytes(secret::random_bytes::<4>());
        // Rejection sampling keeps every code equally likely.
        if n >= 4_294_000_000 {
            continue;
        }
        let code = format!("{:06}", n % 1_000_000);
        if !taken(&code) {
            return code;
        }
    }
}

fn text(message: &Value) -> String {
    message.to_string()
}

impl Mirrors {
    /// Registers a phone's mirror, or brings back the one it had: the same key
    /// within the grace keeps the viewer and the code.
    fn attach_phone(
        &self,
        device_id: &str,
        key: &str,
        to_phone: mpsc::Sender<ToPhone>,
    ) -> (u64, Option<String>, Option<Codec>) {
        let mut inner = lock(&self.inner);
        inner.next_id += 1;
        let generation = inner.next_id;
        let key_hash = hash(key);
        let now = now_ms();
        let resumed = inner
            .sessions
            .get(device_id)
            .is_some_and(|s| bool::from(s.key_hash.ct_eq(&key_hash)));
        if !resumed {
            if let Some(old) = inner.sessions.remove(device_id) {
                end_session(old, "the phone started a new mirror");
            }
            let taken: Vec<String> = inner
                .sessions
                .values()
                .filter_map(|s| s.code.as_ref().map(|c| c.digits.clone()))
                .collect();
            let digits = new_code(|c| taken.iter().any(|t| t == c));
            inner.sessions.insert(
                device_id.to_owned(),
                Session {
                    generation,
                    key_hash,
                    key: key.to_owned(),
                    code: Some(Code {
                        digits,
                        expires_ms: now + CODE_LIFETIME_MS,
                        wrong: 0,
                    }),
                    phone: None,
                    viewer: None,
                    screen: None,
                    ai: None,
                    status: None,
                    input: None,
                },
            );
        }
        let Some(session) = inner.sessions.get_mut(device_id) else {
            return (generation, None, None);
        };
        if let Some(old) = session.phone.replace(to_phone) {
            let _ = old.try_send(ToPhone::Close(
                CLOSE_REPLACED,
                "replaced by a newer connection",
            ));
        }
        session.generation = generation;
        let codec = session.viewer.as_mut().map(|v| {
            v.waiting_key = true;
            let _ = v
                .text
                .try_send(ToViewer::Text(text(&json!({"t": "phone", "online": true}))));
            v.codec
        });
        let code = session
            .code
            .as_ref()
            .filter(|c| c.expires_ms > now)
            .map(|c| c.digits.clone());
        (generation, code, codec)
    }

    /// The phone's connection ended. With `ended` (it said so, or was revoked)
    /// the mirror ends now; otherwise the viewer waits [`PHONE_GRACE`] for it.
    fn detach_phone(self: &Arc<Self>, device_id: &str, generation: u64, ended: bool) {
        let mut inner = lock(&self.inner);
        let Some(session) = inner.sessions.get_mut(device_id) else {
            return;
        };
        if session.generation != generation {
            return;
        }
        if ended {
            if let Some(session) = inner.sessions.remove(device_id) {
                end_session(session, "mirroring ended on the phone");
            }
            return;
        }
        session.phone = None;
        if let Some(v) = &session.viewer {
            let _ = v.text.try_send(ToViewer::Text(text(
                &json!({"t": "phone", "online": false}),
            )));
        }
        let mirrors = self.clone();
        let device_id = device_id.to_owned();
        tokio::spawn(async move {
            tokio::time::sleep(PHONE_GRACE).await;
            let mut inner = lock(&mirrors.inner);
            let gone = inner
                .sessions
                .get(&device_id)
                .is_some_and(|s| s.generation == generation && s.phone.is_none());
            if gone && let Some(session) = inner.sessions.remove(&device_id) {
                end_session(session, "the phone did not come back");
            }
        });
    }

    /// Ends a phone's mirror at once (revocation, the phone's own `end`).
    pub fn end(&self, device_id: &str) {
        if let Some(session) = lock(&self.inner).sessions.remove(device_id) {
            end_session(session, "mirroring ended");
        }
    }

    /// A text message for this phone's viewer (ADR-055/056); false when no one watches.
    pub fn to_viewer(&self, device_id: &str, message: &str) -> bool {
        let inner = lock(&self.inner);
        inner
            .sessions
            .get(device_id)
            .and_then(|s| s.viewer.as_ref())
            .is_some_and(|v| v.text.try_send(ToViewer::Text(message.to_owned())).is_ok())
    }

    /// The phone whose mirror this viewer key opens, while someone watches it
    /// (ADR-057: a file dropped on the page goes to that phone).
    pub fn watched_by_key(&self, key: &str) -> Option<String> {
        if !valid_key(key) {
            return None;
        }
        let key_hash = hash(key);
        lock(&self.inner)
            .sessions
            .iter()
            .find(|(_, s)| bool::from(s.key_hash.ct_eq(&key_hash)) && s.viewer.is_some())
            .map(|(id, _)| id.clone())
    }

    /// Whether this phone has a mirror open (connected or within its grace).
    pub fn is_open(&self, device_id: &str) -> bool {
        lock(&self.inner).sessions.contains_key(device_id)
    }

    fn remember(&self, device_id: &str, generation: u64, kind: &str, message: &str) {
        let mut inner = lock(&self.inner);
        let Some(session) = inner.sessions.get_mut(device_id) else {
            return;
        };
        if session.generation != generation {
            return;
        }
        match kind {
            "screen" => session.screen = Some(message.to_owned()),
            "ai" => session.ai = Some(message.to_owned()),
            "status" => session.status = Some(message.to_owned()),
            "input" => session.input = Some(message.to_owned()),
            _ => {}
        }
        if let Some(v) = &session.viewer {
            // Small and rare; a viewer this far behind gets the next one.
            let _ = v.text.try_send(ToViewer::Text(message.to_owned()));
        }
    }

    /// One encoded frame from the phone, for the viewer if there is one.
    fn frame(&self, device_id: &str, generation: u64, frame: Bytes) {
        let key = frame.get(1).is_some_and(|f| f & FLAG_KEY != 0);
        let mut inner = lock(&self.inner);
        let Some(session) = inner.sessions.get_mut(device_id) else {
            return;
        };
        if session.generation != generation {
            return;
        }
        let Some(viewer) = session.viewer.as_mut() else {
            return;
        };
        if frame.first() == Some(&KIND_SHOT) {
            // The owner asked for it: it waits with the text messages instead of being dropped.
            let _ = viewer.text.try_send(ToViewer::Binary(frame));
            return;
        }
        if frame.first() == Some(&KIND_PCM) {
            // Sound never waits for a key frame; when the viewer is behind, a dropped
            // 20 ms is better than late sound.
            let _ = viewer.video.try_send(frame);
            return;
        }
        let now = now_ms();
        let mut want_sync = false;
        if viewer.waiting_key && !key {
            // A delta frame without the frames before it would only show garbage.
            want_sync = now.saturating_sub(viewer.last_sync_ms) >= 4 * SYNC_REQUEST_GAP_MS;
        } else {
            match viewer.video.try_send(frame) {
                Ok(()) => viewer.waiting_key = false,
                Err(mpsc::error::TrySendError::Full(_)) => {
                    viewer.waiting_key = true;
                    want_sync = now.saturating_sub(viewer.last_sync_ms) >= SYNC_REQUEST_GAP_MS;
                }
                Err(mpsc::error::TrySendError::Closed(_)) => {}
            }
        }
        if want_sync {
            viewer.last_sync_ms = now;
            if let Some(phone) = &session.phone {
                let _ = phone.try_send(ToPhone::Text(text(&json!({"t": "sync"}))));
            }
        }
    }

    /// A browser asks to watch. Returns what it needs, or why not (without
    /// saying whether a code exists).
    fn join(&self, join: &Join) -> Result<Joined, &'static str> {
        let now = now_ms();
        let mut inner = lock(&self.inner);
        let device_id = if let Some(key) = join.key.as_deref() {
            if !valid_key(key) {
                return Err("that link is not valid");
            }
            let key_hash = hash(key);
            inner
                .sessions
                .iter()
                .find(|(_, s)| bool::from(s.key_hash.ct_eq(&key_hash)))
                .map(|(id, _)| id.clone())
                .ok_or("this mirror has ended; start it again on the phone")?
        } else if let Some(code) = join.code.as_deref() {
            while inner
                .wrong_codes
                .front()
                .is_some_and(|t| now.saturating_sub(*t) > 60_000)
            {
                inner.wrong_codes.pop_front();
            }
            if inner.wrong_codes.len() >= WRONG_CODES_PER_MINUTE {
                return Err("too many wrong codes; wait a minute, or use the link from the phone");
            }
            let found = inner
                .sessions
                .iter()
                .find(|(_, s)| {
                    s.code.as_ref().is_some_and(|c| {
                        c.expires_ms > now && bool::from(c.digits.as_bytes().ct_eq(code.as_bytes()))
                    })
                })
                .map(|(id, _)| id.clone());
            match found {
                Some(id) => id,
                None => {
                    inner.wrong_codes.push_back(now);
                    // Every live code gets a strike: a guesser cannot aim at one.
                    for s in inner.sessions.values_mut() {
                        if let Some(c) = s.code.as_mut() {
                            c.wrong += 1;
                        }
                        if s.code
                            .as_ref()
                            .is_some_and(|c| c.wrong >= WRONG_CODES_PER_MIRROR)
                        {
                            s.code = None;
                            if let Some(p) = &s.phone {
                                let _ = p.try_send(ToPhone::Text(text(
                                    &json!({"t": "code", "code": null}),
                                )));
                            }
                        }
                    }
                    return Err("wrong or expired code; check the code on the phone");
                }
            }
        } else {
            return Err("a code or link is needed");
        };
        inner.next_id += 1;
        let id = inner.next_id;
        let Some(session) = inner.sessions.get_mut(&device_id) else {
            return Err("this mirror has ended; start it again on the phone");
        };
        let by_code = join.key.is_none();
        if by_code {
            if session.viewer.is_some() {
                return Err(
                    "someone is already watching; use the link from the phone to take over",
                );
            }
            // The code works once; reconnects use the key it is exchanged for.
            session.code = None;
            if let Some(p) = &session.phone {
                let _ = p.try_send(ToPhone::Text(text(&json!({"t": "code", "code": null}))));
            }
        }
        if let Some(old) = session.viewer.take() {
            let _ = old.text.try_send(ToViewer::Close(
                CLOSE_REPLACED,
                "watching in another window now",
            ));
        }
        let codec = if join.codecs.contains(&Codec::H264) {
            Codec::H264
        } else {
            Codec::Jpeg
        };
        let (video_tx, video_rx) = mpsc::channel(VIEWER_VIDEO_QUEUE);
        let (text_tx, text_rx) = mpsc::channel(VIEWER_TEXT_QUEUE);
        for earlier in [&session.screen, &session.ai, &session.status, &session.input]
            .into_iter()
            .flatten()
        {
            let _ = text_tx.try_send(ToViewer::Text(earlier.clone()));
        }
        if session.phone.is_none() {
            let _ = text_tx.try_send(ToViewer::Text(text(
                &json!({"t": "phone", "online": false}),
            )));
        }
        session.viewer = Some(Viewer {
            id,
            codec,
            video: video_tx,
            text: text_tx,
            waiting_key: true,
            last_sync_ms: now,
        });
        if let Some(p) = &session.phone {
            let _ = p.try_send(ToPhone::Text(text(
                &json!({"t": "viewer", "on": true, "codec": codec}),
            )));
        }
        Ok(Joined {
            device_id,
            viewer_id: id,
            key: session.key.clone(),
            codec,
            video: video_rx,
            text: text_rx,
        })
    }

    fn leave(&self, device_id: &str, viewer_id: u64) {
        let mut inner = lock(&self.inner);
        let Some(session) = inner.sessions.get_mut(device_id) else {
            return;
        };
        if session.viewer.as_ref().is_some_and(|v| v.id == viewer_id) {
            session.viewer = None;
            if let Some(p) = &session.phone {
                let _ = p.try_send(ToPhone::Text(text(&json!({"t": "viewer", "on": false}))));
            }
        }
    }

    /// A validated message from the viewer, for the phone.
    fn to_phone(&self, device_id: &str, viewer_id: u64, message: &ViewerMessage) {
        let mut inner = lock(&self.inner);
        let Some(session) = inner.sessions.get_mut(device_id) else {
            return;
        };
        let Some(viewer) = session.viewer.as_mut().filter(|v| v.id == viewer_id) else {
            return;
        };
        match message {
            ViewerMessage::Sync => {
                let now = now_ms();
                if now.saturating_sub(viewer.last_sync_ms) < SYNC_REQUEST_GAP_MS {
                    return;
                }
                viewer.last_sync_ms = now;
                viewer.waiting_key = true;
            }
            ViewerMessage::Codec { codec } => {
                viewer.codec = *codec;
                viewer.waiting_key = true;
            }
            _ => {}
        }
        if let Some(p) = &session.phone
            && let Ok(json) = serde_json::to_string(message)
        {
            // A phone this far behind loses input rather than replaying it late.
            let _ = p.try_send(ToPhone::Text(json));
        }
    }
}

fn end_session(session: Session, why: &'static str) {
    if let Some(v) = session.viewer {
        let _ = v.text.try_send(ToViewer::Close(CLOSE_ENDED, why));
    }
    if let Some(p) = session.phone {
        let _ = p.try_send(ToPhone::Close(1000, why));
    }
}

struct Joined {
    device_id: String,
    viewer_id: u64,
    key: String,
    codec: Codec,
    video: mpsc::Receiver<Bytes>,
    text: mpsc::Receiver<ToViewer>,
}

// ---- Messages from the browser ----

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Join {
    #[serde(rename = "t")]
    _t: JoinTag,
    #[serde(default)]
    pub key: Option<String>,
    #[serde(default)]
    pub code: Option<String>,
    #[serde(default)]
    pub codecs: Vec<Codec>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum JoinTag {
    Join,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Down,
    Move,
    Up,
    Cancel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Key {
    Backspace,
    Delete,
    Enter,
    Tab,
    Left,
    Right,
    Up,
    Down,
    // ADR-057: editing shortcuts from the computer's keyboard.
    Home,
    End,
    SelectAll,
    WordBackspace,
    WordLeft,
    WordRight,
    Newline,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Button {
    Back,
    Home,
    Recents,
    /// The notification shade (ADR-057).
    Notifications,
    QuickSettings,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    /// Hold the AI before its next command.
    Pause,
    /// Let AI continue.
    Resume,
    /// End the AI session, as the phone's Stop does.
    Stop,
    /// End mirroring.
    End,
}

/// What a viewer may send after joining. Anything else is dropped; what passes
/// is re-serialized from these fields, so the phone never sees extra data.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case", deny_unknown_fields)]
pub enum ViewerMessage {
    Touch {
        phase: Phase,
        x: i32,
        y: i32,
    },
    Wheel {
        x: i32,
        y: i32,
        dx: i32,
        dy: i32,
    },
    Text {
        text: String,
    },
    Key {
        key: Key,
    },
    Button {
        name: Button,
    },
    Control {
        action: Action,
    },
    Ping {
        at: f64,
    },
    Sync,
    Codec {
        codec: Codec,
    },
    /// The viewer plays the phone's sound: the phone's own speaker goes quiet (ADR-055).
    Sound {
        on: bool,
    },
    /// The screen's light off or on, run by the gateway through Full ADB (ADR-056);
    /// never forwarded to the phone's mirror link.
    ScreenLight {
        on: bool,
    },
    /// Text from the computer's clipboard: onto the phone's clipboard and into
    /// the field in focus (ADR-057).
    Paste {
        text: String,
    },
    /// Copy or cut what is selected on the phone, for the computer's clipboard.
    /// (Braces, so extra fields are refused as on every other message.)
    Copy {},
    Cut {},
    /// A full-resolution screenshot for the computer.
    Shot {},
    /// Android 15's screen-share protection off (`show`) or back on, run by
    /// the gateway through Full ADB (ADR-057); never forwarded to the phone.
    Notifications {
        show: bool,
    },
}

impl ViewerMessage {
    /// Parses and checks bounds; `None` for anything the phone should not get.
    pub fn parse(raw: &str) -> Option<Self> {
        if raw.len() > MAX_TEXT_BYTES {
            return None;
        }
        let message: Self = serde_json::from_str(raw).ok()?;
        let point =
            |x: i32, y: i32| (0..=MAX_COORDINATE).contains(&x) && (0..=MAX_COORDINATE).contains(&y);
        let ok = match &message {
            ViewerMessage::Touch { x, y, .. } => point(*x, *y),
            ViewerMessage::Wheel { x, y, dx, dy } => {
                point(*x, *y)
                    && (-MAX_COORDINATE..=MAX_COORDINATE).contains(dx)
                    && (-MAX_COORDINATE..=MAX_COORDINATE).contains(dy)
            }
            ViewerMessage::Text { text } => {
                !text.is_empty()
                    && text.chars().count() <= MAX_TEXT_CHARS
                    && !text.chars().any(char::is_control)
            }
            ViewerMessage::Ping { at } => at.is_finite(),
            ViewerMessage::Paste { text } => {
                !text.is_empty()
                    && text.chars().count() <= MAX_PASTE_CHARS
                    && !text
                        .chars()
                        .any(|c| c.is_control() && c != '\n' && c != '\t')
            }
            _ => true,
        };
        ok.then_some(message)
    }
}

/// Messages the phone may send as text; anything else ends its connection.
const PHONE_TEXT_KINDS: &[&str] = &[
    "screen",
    "ai",
    "pong",
    "notice",
    "end",
    "clipboard",
    "status",
    "input",
];

fn phone_kind(raw: &str) -> Option<String> {
    if raw.len() > MAX_PHONE_TEXT_BYTES {
        return None;
    }
    let value: Value = serde_json::from_str(raw).ok()?;
    let kind = value.get("t")?.as_str()?;
    PHONE_TEXT_KINDS.contains(&kind).then(|| kind.to_owned())
}

/// A frame as the phone sends it: header plus a non-empty payload of a known kind.
pub fn valid_frame(frame: &[u8]) -> bool {
    frame.len() > FRAME_HEADER_BYTES
        && frame.len() <= MAX_FRAME_BYTES
        && matches!(frame[0], KIND_H264 | KIND_JPEG | KIND_PCM | KIND_SHOT)
}

// ---- The phone's socket ----

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Start {
    #[serde(rename = "t")]
    _t: StartTag,
    version: u32,
    key: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum StartTag {
    Start,
}

#[derive(Deserialize)]
struct SignedEnvelope {
    #[serde(rename = "type")]
    kind: String,
    payload: String,
    sig: String,
}

/// Reads the phone's `start`: signed with its device key when it has one
/// registered (as every message on its device link is), plain otherwise.
fn open_start(raw: &str, key: Option<&str>, connection: &str) -> Result<Start, &'static str> {
    let payload = match serde_json::from_str::<SignedEnvelope>(raw) {
        Ok(env) if env.kind == "signed" => {
            // A phone whose key the gateway has not learned yet (it never
            // connected its device link) is trusted as an unsigned one is.
            if let Some(key) = key
                && !signing::verify(key, connection, &env.payload, &env.sig)
            {
                return Err(signing::Refused::BadSignature.message());
            }
            env.payload
        }
        _ if key.is_some() => return Err(signing::Refused::Unsigned.message()),
        _ => raw.to_owned(),
    };
    let start: Start = serde_json::from_str(&payload).map_err(|_| "expected start")?;
    if start.version != MIRROR_VERSION {
        return Err("unsupported mirror version; update the gateway or the app");
    }
    if !valid_key(&start.key) {
        return Err("the viewer key is not valid");
    }
    Ok(start)
}

/// Where the owner can open the page: on the gateway's own computer first when
/// the phone came over the gateway's Wi-Fi listener (localhost is a secure
/// context, so the browser decodes video there without a certificate warning).
fn viewer_urls(state: &AppState, wifi: Option<&str>, headers: &HeaderMap) -> Vec<String> {
    match wifi {
        Some(origin) => vec![
            format!("http://127.0.0.1:{}/mirror", state.config.bind.port()),
            format!("{origin}/mirror"),
        ],
        None => vec![format!(
            "{}/mirror",
            crate::http::gateway_url(state, headers)
        )],
    }
}

pub async fn phone_upgrade(
    State(state): State<Arc<AppState>>,
    wifi: Option<axum::Extension<crate::wifi::WifiOrigin>>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    let device = secret::bearer(headers.get(header::AUTHORIZATION)).and_then(|token| {
        state
            .store()
            .authenticate(token)
            .map(|d| (d.id.clone(), d.device_key.clone()))
    });
    let Some((device_id, device_key)) = device else {
        return (
            StatusCode::UNAUTHORIZED,
            "unknown or revoked device credential",
        )
            .into_response();
    };
    let urls = viewer_urls(
        &state,
        wifi.as_ref().map(|axum::Extension(o)| o.0.as_str()),
        &headers,
    );
    ws.max_message_size(MAX_FRAME_BYTES)
        .max_frame_size(MAX_FRAME_BYTES)
        .on_upgrade(move |socket| serve_phone(state, device_id, device_key, urls, socket))
}

fn close(code: u16, reason: &'static str) -> Message {
    Message::Close(Some(CloseFrame {
        code,
        reason: reason.into(),
    }))
}

async fn serve_phone(
    state: Arc<AppState>,
    device_id: String,
    device_key: Option<String>,
    urls: Vec<String>,
    socket: WebSocket,
) {
    let (mut sink, mut stream) = socket.split();
    let connection = format!("m_{}", secret::hex(&secret::random_bytes::<16>()));
    let challenge = json!({"t": "challenge", "version": MIRROR_VERSION, "connection": connection});
    if sink
        .send(Message::Text(text(&challenge).into()))
        .await
        .is_err()
    {
        return;
    }
    let start = match tokio::time::timeout(HANDSHAKE_TIMEOUT, stream.next()).await {
        Ok(Some(Ok(Message::Text(raw)))) => open_start(&raw, device_key.as_deref(), &connection),
        _ => Err("expected start"),
    };
    let start = match start {
        Ok(start) => start,
        Err(why) => {
            tracing::warn!(device_id, why, "refused mirror start");
            let _ = sink.send(close(CLOSE_REFUSED, why)).await;
            return;
        }
    };
    let (tx, mut rx) = mpsc::channel::<ToPhone>(PHONE_QUEUE);
    let (generation, code, codec) = state.mirrors.attach_phone(&device_id, &start.key, tx);
    let ready = json!({
        "t": "ready",
        "code": code,
        "code_expires_ms": now_ms() + CODE_LIFETIME_MS,
        "urls": urls,
    });
    if sink.send(Message::Text(text(&ready).into())).await.is_err() {
        state.mirrors.detach_phone(&device_id, generation, false);
        return;
    }
    if let Some(codec) = codec {
        let resume = json!({"t": "viewer", "on": true, "codec": codec});
        let _ = sink.send(Message::Text(text(&resume).into())).await;
    }
    tracing::info!(device_id, "mirror started");

    let mut writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(PING_EVERY);
        ping.tick().await;
        loop {
            tokio::select! {
                message = rx.recv() => match message {
                    Some(ToPhone::Text(t)) => {
                        if sink.send(Message::Text(t.into())).await.is_err() {
                            break;
                        }
                    }
                    Some(ToPhone::Close(code, why)) => {
                        let _ = sink.send(close(code, why)).await;
                        break;
                    }
                    None => break,
                },
                _ = ping.tick() => {
                    if sink.send(Message::Ping(Vec::new().into())).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    let mut ended = false;
    loop {
        let frame = tokio::select! {
            frame = tokio::time::timeout(IDLE_TIMEOUT, stream.next()) => match frame {
                Ok(Some(Ok(frame))) => frame,
                _ => break,
            },
            // Replaced by a newer connection, or the mirror was ended here.
            _ = &mut writer => break,
        };
        match frame {
            Message::Binary(bytes) => {
                if !valid_frame(&bytes) {
                    tracing::warn!(device_id, "malformed mirror frame; closing");
                    break;
                }
                state.mirrors.frame(&device_id, generation, bytes);
            }
            Message::Text(raw) => {
                let Some(kind) = phone_kind(&raw) else {
                    // Do not echo it: it may describe the screen.
                    tracing::warn!(device_id, "unexpected mirror message from phone; closing");
                    break;
                };
                if kind == "end" {
                    ended = true;
                    break;
                }
                state.mirrors.remember(&device_id, generation, &kind, &raw);
            }
            Message::Close(_) => break,
            Message::Ping(_) | Message::Pong(_) => {}
        }
    }
    writer.abort();
    // A revoked phone's mirror ends with it.
    let revoked = state.store().get(&device_id).is_none();
    state
        .mirrors
        .detach_phone(&device_id, generation, ended || revoked);
    tracing::info!(device_id, ended, "mirror phone connection closed");
}

// ---- The viewer's socket ----

/// The browser's WebSocket carries no credential header; it joins with its
/// first message. A page from another site cannot use a socket here.
pub async fn view_upgrade(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    if !same_origin(&headers) {
        return (
            StatusCode::FORBIDDEN,
            "cross-site mirror connections are refused",
        )
            .into_response();
    }
    // Twice the message limit, so a message that is too long is dropped, not the viewer.
    ws.max_message_size(2 * MAX_TEXT_BYTES)
        .max_frame_size(2 * MAX_TEXT_BYTES)
        .on_upgrade(move |socket| serve_viewer(state, socket))
}

/// No `Origin` (a non-browser client) or one whose host is the host asked for.
pub(crate) fn same_origin(headers: &HeaderMap) -> bool {
    let Some(origin) = headers.get(header::ORIGIN).and_then(|o| o.to_str().ok()) else {
        return true;
    };
    let host = headers
        .get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .unwrap_or_default();
    let origin_host = origin.split_once("://").map_or("", |(_, rest)| rest);
    !host.is_empty() && origin_host.eq_ignore_ascii_case(host)
}

async fn serve_viewer(state: Arc<AppState>, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let join = match tokio::time::timeout(HANDSHAKE_TIMEOUT, stream.next()).await {
        Ok(Some(Ok(Message::Text(raw)))) => serde_json::from_str::<Join>(&raw).ok(),
        _ => None,
    };
    let Some(join) = join else {
        let _ = sink.send(close(CLOSE_REFUSED, "expected join")).await;
        return;
    };
    let joined = match state.mirrors.join(&join) {
        Ok(joined) => joined,
        Err(why) => {
            let _ = sink.send(close(CLOSE_REFUSED, why)).await;
            return;
        }
    };
    let Joined {
        device_id,
        viewer_id,
        key,
        codec,
        mut video,
        text: mut texts,
    } = joined;
    let name = state
        .store()
        .get(&device_id)
        .map(|d| d.name.clone())
        .unwrap_or_default();
    let joined = json!({
        "t": "joined",
        "version": MIRROR_VERSION,
        "device": name,
        "codec": codec,
        // A viewer who joined by code keeps this to reconnect without it.
        "key": key,
    });
    if sink
        .send(Message::Text(text(&joined).into()))
        .await
        .is_err()
    {
        state.mirrors.leave(&device_id, viewer_id);
        return;
    }
    tracing::info!(device_id, "mirror viewer joined");

    let mut writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(PING_EVERY);
        ping.tick().await;
        loop {
            tokio::select! {
                biased;
                message = texts.recv() => match message {
                    Some(ToViewer::Text(t)) => {
                        if sink.send(Message::Text(t.into())).await.is_err() {
                            break;
                        }
                    }
                    Some(ToViewer::Binary(bytes)) => {
                        if sink.send(Message::Binary(bytes)).await.is_err() {
                            break;
                        }
                    }
                    Some(ToViewer::Close(code, why)) => {
                        let _ = sink.send(close(code, why)).await;
                        break;
                    }
                    None => break,
                },
                frame = video.recv() => match frame {
                    Some(bytes) => {
                        if sink.send(Message::Binary(bytes)).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                },
                _ = ping.tick() => {
                    if sink.send(Message::Ping(Vec::new().into())).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    let mut second = 0u64;
    let mut in_second = 0u32;
    loop {
        let frame = tokio::select! {
            frame = tokio::time::timeout(IDLE_TIMEOUT, stream.next()) => match frame {
                Ok(Some(Ok(frame))) => frame,
                _ => break,
            },
            // The writer ended (replaced, mirror ended, or the socket broke).
            _ = &mut writer => break,
        };
        let raw = match frame {
            Message::Text(raw) => raw,
            Message::Close(_) => break,
            _ => continue,
        };
        let now = now_ms() / 1000;
        if now != second {
            second = now;
            in_second = 0;
        }
        in_second += 1;
        if in_second > VIEWER_MESSAGES_PER_SECOND {
            continue;
        }
        match ViewerMessage::parse(&raw) {
            Some(ViewerMessage::ScreenLight { on }) => {
                let state = state.clone();
                let device_id = device_id.clone();
                tokio::spawn(async move {
                    let text = match crate::mcp::screen_light(&state, &device_id, on).await {
                        Ok(done) => done.trim_start_matches("Done: ").to_owned(),
                        Err(e) => format!("Screen light: {}", e.message),
                    };
                    let notice = json!({ "t": "notice", "text": text }).to_string();
                    state.mirrors.to_viewer(&device_id, &notice);
                });
            }
            Some(ViewerMessage::Notifications { show }) => {
                let state = state.clone();
                let device_id = device_id.clone();
                tokio::spawn(async move {
                    let text =
                        match crate::mcp::screen_share_protection(&state, &device_id, show).await {
                            Ok(done) => done.trim_start_matches("Done: ").to_owned(),
                            Err(e) => format!("Notifications: {}", e.message),
                        };
                    let notice = json!({ "t": "notice", "text": text }).to_string();
                    state.mirrors.to_viewer(&device_id, &notice);
                });
            }
            Some(message) => state.mirrors.to_phone(&device_id, viewer_id, &message),
            None => {}
        }
    }
    writer.abort();
    state.mirrors.leave(&device_id, viewer_id);
    tracing::info!(device_id, "mirror viewer left");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn viewer_messages_are_checked_and_rebuilt() {
        let touch = ViewerMessage::parse(r#"{"t":"touch","phase":"down","x":10,"y":20}"#);
        assert_eq!(
            touch,
            Some(ViewerMessage::Touch {
                phase: Phase::Down,
                x: 10,
                y: 20
            })
        );
        assert_eq!(
            ViewerMessage::parse(r#"{"t":"sound","on":true}"#),
            Some(ViewerMessage::Sound { on: true })
        );
        assert_eq!(
            ViewerMessage::parse(r#"{"t":"screen_light","on":false}"#),
            Some(ViewerMessage::ScreenLight { on: false })
        );
        assert_eq!(
            ViewerMessage::parse(r#"{"t":"paste","text":"two\nlines"}"#),
            Some(ViewerMessage::Paste {
                text: "two\nlines".into()
            })
        );
        assert_eq!(
            ViewerMessage::parse(r#"{"t":"notifications","show":true}"#),
            Some(ViewerMessage::Notifications { show: true })
        );
        assert_eq!(
            ViewerMessage::parse(r#"{"t":"key","key":"word_backspace"}"#),
            Some(ViewerMessage::Key {
                key: Key::WordBackspace
            })
        );
        for bad in [
            r#"{"t":"sound","on":"yes"}"#,
            r#"{"t":"touch","phase":"down","x":-1,"y":20}"#,
            r#"{"t":"touch","phase":"down","x":10,"y":20,"extra":1}"#,
            r#"{"t":"touch","phase":"hover","x":10,"y":20}"#,
            r#"{"t":"text","text":""}"#,
            r#"{"t":"text","text":"a\nb"}"#,
            r#"{"t":"key","key":"f12"}"#,
            r#"{"t":"button","name":"power"}"#,
            r#"{"t":"control","action":"approve"}"#,
            r#"{"t":"shell","command":"id"}"#,
            r#"{"t":"paste","text":""}"#,
            r#"{"t":"paste","text":"bell\u0007"}"#,
            r#"{"t":"copy","text":"x"}"#,
            r#"not json"#,
        ] {
            assert_eq!(ViewerMessage::parse(bad), None, "{bad}");
        }
        let long: String = "a".repeat(MAX_TEXT_CHARS + 1);
        assert_eq!(
            ViewerMessage::parse(&json!({"t": "text", "text": long}).to_string()),
            None
        );
    }

    /// The shared fixtures the phone's parser is tested against too: what passes
    /// is sent on exactly as written, and nothing invalid passes.
    #[test]
    fn shared_viewer_fixtures() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../packages/schemas/v1/mirror");
        let read = |name: &str| -> Vec<Value> {
            let text = std::fs::read_to_string(dir.join(name)).expect("fixture");
            serde_json::from_str(&text).expect("json array")
        };
        for v in read("viewer-valid.json") {
            let parsed =
                ViewerMessage::parse(&v.to_string()).unwrap_or_else(|| panic!("valid: {v}"));
            let rebuilt: Value =
                serde_json::from_str(&serde_json::to_string(&parsed).expect("json")).expect("json");
            assert_eq!(rebuilt, v, "rebuilt as written");
        }
        for v in read("viewer-invalid.json") {
            assert_eq!(ViewerMessage::parse(&v.to_string()), None, "invalid: {v}");
        }
    }

    #[test]
    fn keys_and_frames_are_checked() {
        assert!(valid_key("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO-_"));
        assert!(!valid_key("short"));
        assert!(!valid_key("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO+/"));
        let mut frame = vec![KIND_H264, FLAG_KEY, 0, 0, 0, 0, 0, 0, 0, 1];
        assert!(!valid_frame(&frame), "no payload");
        frame.push(0);
        assert!(valid_frame(&frame));
        frame[0] = KIND_PCM;
        assert!(valid_frame(&frame));
        frame[0] = KIND_SHOT;
        assert!(valid_frame(&frame));
        frame[0] = 9;
        assert!(!valid_frame(&frame));
    }

    #[test]
    fn same_origin_needs_the_same_host() {
        let mut h = HeaderMap::new();
        h.insert(header::HOST, "127.0.0.1:8787".parse().expect("host"));
        assert!(same_origin(&h));
        h.insert(
            header::ORIGIN,
            "http://127.0.0.1:8787".parse().expect("origin"),
        );
        assert!(same_origin(&h));
        h.insert(
            header::ORIGIN,
            "https://evil.example".parse().expect("origin"),
        );
        assert!(!same_origin(&h));
    }
}
