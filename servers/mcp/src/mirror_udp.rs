// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! R56 (ADR-061): the mirror's video and sound over UDP on the Wi-Fi.
//!
//! The phone's WebSocket runs on TCP, which delivers in order: one lost Wi-Fi
//! packet holds every later frame until it is sent again, and a lost packet at
//! the end of a burst waits for TCP's retransmission timer (200 ms or more).
//! Over UDP each frame is cut into small packets; the gateway puts frames
//! together, asks for a missing piece within milliseconds, and passes complete
//! frames on in order, skipping a frame that does not come within
//! [`HOLD_MS`] instead of holding the rest behind it.
//!
//! Every packet is sealed with AES-256-GCM under a key the phone makes for each
//! mirror connection and hands over on its authenticated, pinned WebSocket
//! (`{"t":"udp","id":…,"key":…}`). The nonce is the direction and a counter
//! that never repeats under one key; a replay window drops repeated and stale
//! packets. Only sealed packets that open are acted on, and the gateway answers
//! only the address its latest valid packet came from. Nothing here is logged
//! with frame contents or keys.
//!
//! Wire format (docs/protocol/mirror.md, fixture `packages/schemas/v1/mirror/udp.json`):
//! `version (1) ‖ session id (8) ‖ counter (8, big-endian) ‖ AES-256-GCM(plaintext) ‖ tag (16)`,
//! with the first 17 bytes as associated data and the nonce
//! `direction (1) ‖ 0 0 0 ‖ counter (8, big-endian)`.

use std::collections::{BTreeMap, HashMap};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::Duration;

use axum::body::Bytes;
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use tokio::net::UdpSocket;

use crate::mirror::{FrameSink, KIND_H264, KIND_H265, MAX_FRAME_BYTES};

pub const VERSION: u8 = 1;
/// Version, session id, and counter: also the associated data.
pub const HEADER_BYTES: usize = 17;
pub const TAG_BYTES: usize = 16;
/// The most frame bytes in one packet: with headers, well under a Wi-Fi MTU.
pub const MAX_FRAGMENT_BYTES: usize = 1200;
/// Fragment header in the plaintext: type, frame sequence, index, count.
pub const FRAGMENT_HEADER_BYTES: usize = 9;
const MAX_PACKET_BYTES: usize =
    HEADER_BYTES + FRAGMENT_HEADER_BYTES + MAX_FRAGMENT_BYTES + TAG_BYTES;
/// Fragments in the largest frame the WebSocket would take.
pub const MAX_FRAGMENTS: usize = MAX_FRAME_BYTES.div_ceil(MAX_FRAGMENT_BYTES);

pub const DIR_PHONE: u8 = 1;
pub const DIR_GATEWAY: u8 = 2;

pub const T_HELLO: u8 = 1;
pub const T_HELLO_ACK: u8 = 2;
pub const T_FRAGMENT: u8 = 3;
pub const T_NACK: u8 = 4;
pub const T_REPORT: u8 = 5;

/// How long a later complete frame waits for a missing one before it is skipped.
pub const HOLD_MS: u64 = 50;
/// Between two requests for the same missing pieces.
pub const NACK_GAP_MS: u64 = 8;
/// Requests for one frame before it is left to [`HOLD_MS`].
pub const NACK_TRIES: u8 = 4;
/// Missing pieces named in one request; the phone sends the whole frame past this.
pub const NACK_MAX_INDICES: usize = 64;
/// How often the phone hears how many packets arrived.
pub const REPORT_EVERY_MS: u64 = 250;
/// Frames being put together at once, and their bytes: a phone cannot make the gateway hold more.
const MAX_PARTIAL_FRAMES: usize = 256;
const MAX_PARTIAL_BYTES: usize = 24 * 1024 * 1024;
/// A frame this far ahead of the next one due means the phone started over.
const MAX_AHEAD: u32 = 512;
/// Sessions at once (one per mirroring phone).
const MAX_SESSIONS: usize = 64;
const TICK: Duration = Duration::from_millis(4);

/// One direction's key: seals with its own counter, opens the other side's.
pub struct Keys {
    key: LessSafeKey,
    id: [u8; 8],
}

fn nonce(direction: u8, counter: u64) -> Nonce {
    let mut n = [0u8; 12];
    n[0] = direction;
    n[4..].copy_from_slice(&counter.to_be_bytes());
    Nonce::assume_unique_for_key(n)
}

impl Keys {
    pub fn new(id: [u8; 8], key: &[u8; 32]) -> Self {
        let key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).expect("32-byte key"));
        Self { key, id }
    }

    /// `plaintext` sealed as `direction`'s packet number `counter`.
    pub fn seal(&self, direction: u8, counter: u64, plaintext: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_BYTES + plaintext.len() + TAG_BYTES);
        out.push(VERSION);
        out.extend_from_slice(&self.id);
        out.extend_from_slice(&counter.to_be_bytes());
        let mut body = plaintext.to_vec();
        self.key
            .seal_in_place_append_tag(
                nonce(direction, counter),
                Aad::from(&out[..HEADER_BYTES]),
                &mut body,
            )
            .expect("sealing a packet");
        out.extend_from_slice(&body);
        out
    }

    /// The counter and plaintext of `direction`'s `packet`, or None for anything
    /// that is not a packet of this session sealed with this key.
    pub fn open(&self, direction: u8, packet: &[u8]) -> Option<(u64, Vec<u8>)> {
        if packet.len() < HEADER_BYTES + TAG_BYTES
            || packet[0] != VERSION
            || packet[1..9] != self.id
        {
            return None;
        }
        let counter = u64::from_be_bytes(packet[9..17].try_into().ok()?);
        let mut body = packet[HEADER_BYTES..].to_vec();
        let plain = self
            .key
            .open_in_place(
                nonce(direction, counter),
                Aad::from(&packet[..HEADER_BYTES]),
                &mut body,
            )
            .ok()?;
        let len = plain.len();
        body.truncate(len);
        Some((counter, body))
    }
}

/// The session id of a packet, before anything else is read.
pub fn session_id(packet: &[u8]) -> Option<[u8; 8]> {
    (packet.len() >= HEADER_BYTES && packet[0] == VERSION).then(|| packet[1..9].try_into().ok())?
}

/// Drops repeated and very old counters (64 behind the newest).
#[derive(Default)]
pub struct ReplayWindow {
    highest: Option<u64>,
    seen: u64,
}

impl ReplayWindow {
    /// True the first time `counter` is seen, if it is not too old.
    pub fn accept(&mut self, counter: u64) -> bool {
        let Some(highest) = self.highest else {
            self.highest = Some(counter);
            self.seen = 1;
            return true;
        };
        if counter > highest {
            let shift = counter - highest;
            self.seen = if shift >= 64 { 0 } else { self.seen << shift };
            self.seen |= 1;
            self.highest = Some(counter);
            return true;
        }
        let back = highest - counter;
        if back >= 64 || self.seen & (1 << back) != 0 {
            return false;
        }
        self.seen |= 1 << back;
        true
    }
}

/// A fragment as the plaintext carries it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fragment {
    pub seq: u32,
    pub index: u16,
    pub count: u16,
    pub data: Bytes,
}

impl Fragment {
    pub fn parse(plain: &[u8]) -> Option<Self> {
        if plain.len() <= FRAGMENT_HEADER_BYTES || plain[0] != T_FRAGMENT {
            return None;
        }
        let seq = u32::from_be_bytes(plain[1..5].try_into().ok()?);
        let index = u16::from_be_bytes(plain[5..7].try_into().ok()?);
        let count = u16::from_be_bytes(plain[7..9].try_into().ok()?);
        let data = &plain[FRAGMENT_HEADER_BYTES..];
        (count > 0
            && usize::from(count) <= MAX_FRAGMENTS
            && index < count
            && data.len() <= MAX_FRAGMENT_BYTES)
            .then(|| Self {
                seq,
                index,
                count,
                data: Bytes::copy_from_slice(data),
            })
    }
}

/// A request to send pieces of frame `seq` again: `indices`, or the whole frame when empty.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Nack {
    pub seq: u32,
    pub indices: Vec<u16>,
}

impl Nack {
    pub fn plaintext(&self) -> Vec<u8> {
        let mut out = vec![T_NACK];
        out.extend_from_slice(&self.seq.to_be_bytes());
        out.extend_from_slice(&(self.indices.len() as u16).to_be_bytes());
        for i in &self.indices {
            out.extend_from_slice(&i.to_be_bytes());
        }
        out
    }
}

/// What the reassembler hands on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// A whole frame, in order.
    Frame(Bytes),
    /// A frame was given up on; true when it was (or may have been) video.
    Skipped { video: bool },
}

struct Partial {
    count: u16,
    pieces: Vec<Option<Bytes>>,
    have: u16,
    bytes: usize,
    first_ms: u64,
    last_ms: u64,
    nacks: u8,
    last_nack_ms: u64,
}

impl Partial {
    fn complete(&self) -> bool {
        self.have == self.count
    }

    /// Video unless the first piece says otherwise (only video waits for a key frame).
    fn video(&self) -> bool {
        self.pieces[0]
            .as_ref()
            .and_then(|p| p.first())
            .is_none_or(|k| matches!(*k, KIND_H264 | KIND_H265))
    }
}

/// Puts frames together in order from fragments that come in any order.
#[derive(Default)]
pub struct Reassembler {
    next: Option<u32>,
    partial: BTreeMap<u32, Partial>,
    bytes: usize,
    /// Frames that never showed a piece: when first missed, requests sent, last request.
    missing: HashMap<u32, (u64, u8, u64)>,
}

impl Reassembler {
    pub fn push(&mut self, f: Fragment, now: u64) -> Vec<Event> {
        let mut events = Vec::new();
        let next = *self.next.get_or_insert(f.seq);
        if f.seq < next {
            // Already handed on or skipped: a late copy.
            return events;
        }
        if f.seq - next > MAX_AHEAD {
            // The phone started over (a new encoder, a long pause): start from here.
            for (_, p) in std::mem::take(&mut self.partial) {
                if !p.complete() {
                    events.push(Event::Skipped { video: p.video() });
                }
            }
            self.bytes = 0;
            self.missing.clear();
            self.next = Some(f.seq);
        }
        if !self.partial.contains_key(&f.seq) {
            if self.partial.len() >= MAX_PARTIAL_FRAMES
                || self.bytes + f.data.len() > MAX_PARTIAL_BYTES
            {
                return events;
            }
            self.missing.remove(&f.seq);
            self.partial.insert(
                f.seq,
                Partial {
                    count: f.count,
                    pieces: vec![None; usize::from(f.count)],
                    have: 0,
                    bytes: 0,
                    first_ms: now,
                    last_ms: now,
                    nacks: 0,
                    last_nack_ms: 0,
                },
            );
        }
        let Some(p) = self.partial.get_mut(&f.seq) else {
            return events;
        };
        if p.count != f.count || p.pieces[usize::from(f.index)].is_some() {
            return events;
        }
        if self.bytes + f.data.len() > MAX_PARTIAL_BYTES {
            return events;
        }
        p.bytes += f.data.len();
        self.bytes += f.data.len();
        p.have += 1;
        p.last_ms = now;
        p.pieces[usize::from(f.index)] = Some(f.data);
        events.extend(self.deliver(now));
        events
    }

    /// Hands on every frame that can go now, skipping a missing one that has waited [`HOLD_MS`]
    /// while a later frame is complete.
    pub fn deliver(&mut self, now: u64) -> Vec<Event> {
        let mut events = Vec::new();
        while let Some(next) = self.next {
            if self.partial.get(&next).is_some_and(Partial::complete) {
                let p = self.partial.remove(&next).expect("present");
                self.bytes -= p.bytes;
                let mut frame = Vec::with_capacity(p.bytes);
                for piece in p.pieces.into_iter().flatten() {
                    frame.extend_from_slice(&piece);
                }
                events.push(Event::Frame(Bytes::from(frame)));
                self.next = Some(next.wrapping_add(1));
                continue;
            }
            let Some((_, later)) = self
                .partial
                .range(next.saturating_add(1)..)
                .find(|(_, p)| p.complete())
            else {
                break;
            };
            let since = match self.partial.get(&next) {
                Some(p) => p.first_ms,
                None => later
                    .first_ms
                    .min(self.missing.get(&next).map_or(u64::MAX, |m| m.0)),
            };
            if now.saturating_sub(since) < HOLD_MS {
                break;
            }
            let video = self.partial.remove(&next).map(|p| {
                self.bytes -= p.bytes;
                p.video()
            });
            self.missing.remove(&next);
            events.push(Event::Skipped {
                video: video.unwrap_or(true),
            });
            self.next = Some(next.wrapping_add(1));
        }
        events
    }

    /// Requests for pieces that should have come: of frames with a later frame
    /// already seen, or that stopped arriving.
    pub fn nacks(&mut self, now: u64) -> Vec<Nack> {
        let Some(next) = self.next else {
            return Vec::new();
        };
        let Some(&newest) = self.partial.keys().next_back() else {
            return Vec::new();
        };
        let mut out = Vec::new();
        for seq in next..=newest {
            match self.partial.get_mut(&seq) {
                Some(p) if !p.complete() => {
                    let stalled = seq < newest || now.saturating_sub(p.last_ms) >= NACK_GAP_MS;
                    if !stalled
                        || p.nacks >= NACK_TRIES
                        || (p.nacks > 0 && now.saturating_sub(p.last_nack_ms) < NACK_GAP_MS)
                    {
                        continue;
                    }
                    p.nacks += 1;
                    p.last_nack_ms = now;
                    let missing: Vec<u16> = (0..p.count)
                        .filter(|i| p.pieces[usize::from(*i)].is_none())
                        .collect();
                    out.push(Nack {
                        seq,
                        indices: if missing.len() > NACK_MAX_INDICES {
                            Vec::new()
                        } else {
                            missing
                        },
                    });
                }
                Some(_) => {}
                None => {
                    let entry = self.missing.entry(seq).or_insert((now, 0, 0));
                    if entry.1 >= NACK_TRIES
                        || (entry.1 > 0 && now.saturating_sub(entry.2) < NACK_GAP_MS)
                    {
                        continue;
                    }
                    entry.1 += 1;
                    entry.2 = now;
                    out.push(Nack {
                        seq,
                        indices: Vec::new(),
                    });
                }
            }
        }
        out
    }
}

/// Where a session's frames go, and what the phone hears back.
struct Session {
    keys: Keys,
    sink: FrameSink,
    state: Mutex<SessionState>,
}

#[derive(Default)]
struct SessionState {
    window: ReplayWindow,
    reassembler: Reassembler,
    /// The address of the latest packet that opened; replies go only there.
    addr: Option<SocketAddr>,
    counter: u64,
    received: u32,
    highest_at_report: Option<u64>,
    highest: Option<u64>,
    skipped: u32,
    last_report_ms: u64,
}

impl Session {
    fn state(&self) -> MutexGuard<'_, SessionState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl SessionState {
    fn seal(&mut self, keys: &Keys, plaintext: &[u8]) -> Vec<u8> {
        let packet = keys.seal(DIR_GATEWAY, self.counter, plaintext);
        self.counter += 1;
        packet
    }
}

/// The mirror's UDP side: one socket beside the Wi-Fi listener, and the phones using it.
#[derive(Default)]
pub struct UdpMedia {
    socket: OnceLock<Arc<UdpSocket>>,
    sessions: Mutex<HashMap<[u8; 8], Arc<Session>>>,
}

impl UdpMedia {
    fn sessions(&self) -> MutexGuard<'_, HashMap<[u8; 8], Arc<Session>>> {
        self.sessions.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The port phones send to, once a socket is bound.
    pub fn port(&self) -> Option<u16> {
        self.socket
            .get()
            .and_then(|s| s.local_addr().ok())
            .map(|a| a.port())
    }

    /// A phone's offer (on its authenticated WebSocket): the port to send to, or
    /// None when this gateway has no UDP socket or too many phones use it.
    pub fn register(&self, id: [u8; 8], key: &[u8; 32], sink: FrameSink) -> Option<u16> {
        let port = self.port()?;
        let mut sessions = self.sessions();
        if sessions.len() >= MAX_SESSIONS && !sessions.contains_key(&id) {
            return None;
        }
        sessions.insert(
            id,
            Arc::new(Session {
                keys: Keys::new(id, key),
                sink,
                state: Mutex::new(SessionState::default()),
            }),
        );
        Some(port)
    }

    pub fn unregister(&self, id: &[u8; 8]) {
        self.sessions().remove(id);
    }

    /// Starts serving on `socket` (bound beside the Wi-Fi listener).
    pub fn start(self: &Arc<Self>, socket: UdpSocket) {
        let _ = socket2::SockRef::from(&socket).set_tos_v4(crate::wifi::INTERACTIVE_TOS);
        let socket = Arc::new(socket);
        if self.socket.set(socket.clone()).is_err() {
            return;
        }
        let me = self.clone();
        tokio::spawn(async move { me.serve(socket).await });
    }

    async fn serve(self: Arc<Self>, socket: Arc<UdpSocket>) {
        let mut buf = vec![0u8; MAX_PACKET_BYTES + 64];
        let mut tick = tokio::time::interval(TICK);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                received = socket.recv_from(&mut buf) => {
                    let Ok((n, from)) = received else { continue };
                    for (to, packet) in self.packet(&buf[..n], from, crate::now_ms()) {
                        let _ = socket.try_send_to(&packet, to);
                    }
                }
                _ = tick.tick() => {
                    for (to, packet) in self.tick(crate::now_ms()) {
                        let _ = socket.try_send_to(&packet, to);
                    }
                }
            }
        }
    }

    /// One received packet; returns what to send back.
    pub fn packet(&self, packet: &[u8], from: SocketAddr, now: u64) -> Vec<(SocketAddr, Vec<u8>)> {
        let Some(id) = session_id(packet) else {
            return Vec::new();
        };
        let Some(session) = self.sessions().get(&id).cloned() else {
            return Vec::new();
        };
        let Some((counter, plain)) = session.keys.open(DIR_PHONE, packet) else {
            return Vec::new();
        };
        let mut replies = Vec::new();
        let mut events = Vec::new();
        {
            let mut st = session.state();
            if !st.window.accept(counter) {
                return Vec::new();
            }
            st.addr = Some(from);
            st.received = st.received.saturating_add(1);
            st.highest = Some(st.highest.map_or(counter, |h| h.max(counter)));
            match plain.first() {
                Some(&T_HELLO) => {
                    let ack = st.seal(&session.keys, &[T_HELLO_ACK]);
                    replies.push((from, ack));
                }
                Some(&T_FRAGMENT) => {
                    if let Some(f) = Fragment::parse(&plain) {
                        events = st.reassembler.push(f, now);
                    }
                }
                _ => {}
            }
        }
        Self::hand_on(&session, events);
        replies
    }

    /// Time passing: frames that waited long enough, requests for missing pieces, reports.
    pub fn tick(&self, now: u64) -> Vec<(SocketAddr, Vec<u8>)> {
        let sessions: Vec<Arc<Session>> = self.sessions().values().cloned().collect();
        let mut out = Vec::new();
        for session in sessions {
            let events;
            {
                let mut st = session.state();
                let Some(addr) = st.addr else { continue };
                events = st.reassembler.deliver(now);
                for nack in st.reassembler.nacks(now) {
                    let packet = st.seal(&session.keys, &nack.plaintext());
                    out.push((addr, packet));
                }
                if now.saturating_sub(st.last_report_ms) >= REPORT_EVERY_MS {
                    st.last_report_ms = now;
                    let sent = match (st.highest_at_report, st.highest) {
                        (Some(a), Some(b)) => b.saturating_sub(a),
                        (None, Some(_)) => u64::from(st.received),
                        _ => 0,
                    };
                    let received = u64::from(st.received);
                    let lost = sent.saturating_sub(received).min(u64::from(u32::MAX)) as u32;
                    let mut report = vec![T_REPORT];
                    report.extend_from_slice(&st.received.to_be_bytes());
                    report.extend_from_slice(&lost.to_be_bytes());
                    report.extend_from_slice(&st.skipped.to_be_bytes());
                    st.highest_at_report = st.highest;
                    st.received = 0;
                    st.skipped = 0;
                    let packet = st.seal(&session.keys, &report);
                    out.push((addr, packet));
                }
            }
            Self::hand_on(&session, events);
        }
        out
    }

    fn hand_on(session: &Session, events: Vec<Event>) {
        for event in events {
            match event {
                Event::Frame(frame) => {
                    if crate::mirror::valid_frame(&frame) {
                        session.sink.frame(frame);
                    }
                }
                Event::Skipped { video } => {
                    session.state().skipped += 1;
                    if video {
                        // Frames after a lost one would show damage: ask for a fresh picture.
                        session.sink.lost();
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frag(seq: u32, index: u16, count: u16, data: &[u8]) -> Fragment {
        Fragment {
            seq,
            index,
            count,
            data: Bytes::copy_from_slice(data),
        }
    }

    fn frames(events: &[Event]) -> Vec<Vec<u8>> {
        events
            .iter()
            .filter_map(|e| match e {
                Event::Frame(f) => Some(f.to_vec()),
                Event::Skipped { .. } => None,
            })
            .collect()
    }

    #[test]
    fn packets_match_the_shared_fixture() {
        let fixture: serde_json::Value =
            serde_json::from_str(include_str!("../../../packages/schemas/v1/mirror/udp.json"))
                .expect("fixture");
        let unhex = |k: &str| {
            let s = fixture[k].as_str().expect(k);
            (0..s.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex"))
                .collect::<Vec<u8>>()
        };
        let key: [u8; 32] = unhex("key").try_into().expect("key");
        let id: [u8; 8] = unhex("id").try_into().expect("id");
        let keys = Keys::new(id, &key);
        for case in fixture["packets"].as_array().expect("packets") {
            let dir = case["direction"].as_u64().expect("dir") as u8;
            let counter = case["counter"].as_u64().expect("counter");
            let plain = case["plaintext"].as_str().expect("plain");
            let plain: Vec<u8> = (0..plain.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&plain[i..i + 2], 16).expect("hex"))
                .collect();
            let sealed = keys.seal(dir, counter, &plain);
            assert_eq!(
                crate::secret::hex(&sealed),
                case["packet"].as_str().expect("packet")
            );
            assert_eq!(keys.open(dir, &sealed), Some((counter, plain)));
        }
    }

    #[test]
    fn a_packet_opens_only_with_its_key_direction_and_unchanged_bytes() {
        let keys = Keys::new([7; 8], &[1; 32]);
        let packet = keys.seal(DIR_PHONE, 5, b"hello");
        assert_eq!(keys.open(DIR_PHONE, &packet), Some((5, b"hello".to_vec())));
        assert_eq!(
            keys.open(DIR_GATEWAY, &packet),
            None,
            "the other direction's nonce"
        );
        assert_eq!(
            Keys::new([7; 8], &[2; 32]).open(DIR_PHONE, &packet),
            None,
            "another key"
        );
        assert_eq!(
            Keys::new([8; 8], &[1; 32]).open(DIR_PHONE, &packet),
            None,
            "another session"
        );
        for i in 0..packet.len() {
            let mut bad = packet.clone();
            bad[i] ^= 1;
            assert_eq!(keys.open(DIR_PHONE, &bad), None, "byte {i} changed");
        }
        assert_eq!(
            keys.open(DIR_PHONE, &packet[..HEADER_BYTES + TAG_BYTES - 1]),
            None
        );
    }

    #[test]
    fn the_replay_window_takes_each_counter_once() {
        let mut w = ReplayWindow::default();
        assert!(w.accept(10));
        assert!(!w.accept(10));
        assert!(w.accept(12));
        assert!(w.accept(11), "late but new");
        assert!(!w.accept(11));
        assert!(w.accept(100));
        assert!(!w.accept(36), "64 or more behind the newest");
        assert!(w.accept(37));
    }

    #[test]
    fn fragments_are_checked() {
        let ok = [T_FRAGMENT, 0, 0, 0, 7, 0, 1, 0, 2, 0xaa];
        assert_eq!(Fragment::parse(&ok), Some(frag(7, 1, 2, &[0xaa])));
        let mut index_past_count = ok;
        index_past_count[6] = 2;
        assert_eq!(Fragment::parse(&index_past_count), None);
        let mut no_count = ok;
        no_count[8] = 0;
        assert_eq!(Fragment::parse(&no_count), None);
        assert_eq!(
            Fragment::parse(&ok[..FRAGMENT_HEADER_BYTES]),
            None,
            "no data"
        );
        let mut too_long = ok.to_vec();
        too_long.extend(std::iter::repeat_n(0, MAX_FRAGMENT_BYTES));
        assert_eq!(Fragment::parse(&too_long), None);
    }

    #[test]
    fn frames_are_put_together_in_order_from_any_order() {
        let mut r = Reassembler::default();
        assert!(frames(&r.push(frag(1, 1, 2, b"cd"), 0)).is_empty());
        // Frame 2 complete first: it waits for frame 1.
        assert!(frames(&r.push(frag(2, 0, 1, b"ef"), 1)).is_empty());
        assert_eq!(
            frames(&r.push(frag(1, 0, 2, b"ab"), 2)),
            vec![b"abcd".to_vec(), b"ef".to_vec()]
        );
        // Late copies of handed-on frames are ignored.
        assert!(r.push(frag(1, 0, 2, b"ab"), 3).is_empty());
    }

    #[test]
    fn a_missing_piece_is_asked_for_and_a_frame_that_never_comes_is_skipped() {
        let mut r = Reassembler::default();
        r.push(frag(10, 0, 3, &[KIND_H264, 1]), 0);
        r.push(frag(10, 2, 3, b"z"), 0);
        r.push(frag(11, 0, 1, &[KIND_H264, 0, 9]), 1);
        assert_eq!(
            r.nacks(2),
            vec![Nack {
                seq: 10,
                indices: vec![1]
            }]
        );
        assert!(r.nacks(3).is_empty(), "not again at once");
        assert_eq!(r.nacks(2 + NACK_GAP_MS).len(), 1);
        // Frame 11 waits up to HOLD_MS, then 10 is skipped.
        assert!(r.deliver(HOLD_MS - 1).is_empty());
        let events = r.deliver(HOLD_MS);
        assert_eq!(events[0], Event::Skipped { video: true });
        assert_eq!(frames(&events), vec![vec![KIND_H264, 0, 9]]);
    }

    #[test]
    fn a_frame_with_no_piece_at_all_is_asked_for_whole() {
        let mut r = Reassembler::default();
        r.push(frag(1, 0, 1, b"a"), 0);
        r.push(frag(3, 0, 1, b"c"), 1);
        assert_eq!(
            r.nacks(2),
            vec![Nack {
                seq: 2,
                indices: vec![]
            }]
        );
        assert_eq!(
            frames(&r.push(frag(2, 0, 1, b"b"), 3)),
            vec![b"b".to_vec(), b"c".to_vec()]
        );
    }

    #[test]
    fn a_phone_cannot_make_the_gateway_hold_unbounded_frames() {
        let mut r = Reassembler::default();
        for seq in 0..(MAX_PARTIAL_FRAMES as u32 + 50) {
            r.push(frag(seq, 0, 2, &[0; 100]), 0);
        }
        assert!(r.partial.len() <= MAX_PARTIAL_FRAMES);
        // A frame far ahead starts over instead of waiting for everything before it.
        let events = r.push(frag(100_000, 0, 1, b"x"), 1);
        assert_eq!(frames(&events), vec![b"x".to_vec()]);
        assert!(r.partial.is_empty());
    }

    #[test]
    fn the_server_answers_only_packets_that_open_and_only_to_their_address() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let media = UdpMedia::default();
        let delivered = Arc::new(AtomicUsize::new(0));
        let lost = Arc::new(AtomicUsize::new(0));
        let id = [3; 8];
        let key = [9; 32];
        // No socket: no session.
        assert!(
            media
                .register(
                    id,
                    &key,
                    FrameSink::for_test(delivered.clone(), lost.clone())
                )
                .is_none()
        );
        let socket = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind");
        socket.set_nonblocking(true).expect("nonblocking");
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("rt");
        let _guard = rt.enter();
        media
            .socket
            .set(Arc::new(UdpSocket::from_std(socket).expect("tokio")))
            .ok();
        assert!(
            media
                .register(
                    id,
                    &key,
                    FrameSink::for_test(delivered.clone(), lost.clone())
                )
                .is_some()
        );

        let phone = Keys::new(id, &key);
        let from: SocketAddr = "192.168.1.9:50000".parse().expect("addr");
        // Hello: answered with an ack sealed for the phone, to where it came from.
        let replies = media.packet(&phone.seal(DIR_PHONE, 0, &[T_HELLO]), from, 0);
        assert_eq!(replies.len(), 1);
        assert_eq!(replies[0].0, from);
        assert_eq!(
            phone.open(DIR_GATEWAY, &replies[0].1).map(|p| p.1),
            Some(vec![T_HELLO_ACK])
        );
        // A replay, a forgery, and another session's packet: nothing.
        assert!(
            media
                .packet(&phone.seal(DIR_PHONE, 0, &[T_HELLO]), from, 1)
                .is_empty()
        );
        assert!(
            media
                .packet(
                    &Keys::new(id, &[1; 32]).seal(DIR_PHONE, 1, &[T_HELLO]),
                    from,
                    1
                )
                .is_empty()
        );
        assert!(
            media
                .packet(
                    &Keys::new([4; 8], &key).seal(DIR_PHONE, 1, &[T_HELLO]),
                    from,
                    1
                )
                .is_empty()
        );

        // A video frame in two pieces reaches the mirror once it is whole.
        let frame = [&[KIND_H264, 1][..], &[0; 8], &[0, 0, 0, 1, 0x65, 0x88]].concat();
        let piece = |i: u16, data: &[u8]| {
            let mut p = vec![T_FRAGMENT, 0, 0, 0, 1];
            p.extend_from_slice(&i.to_be_bytes());
            p.extend_from_slice(&2u16.to_be_bytes());
            p.extend_from_slice(data);
            p
        };
        media.packet(&phone.seal(DIR_PHONE, 1, &piece(0, &frame[..7])), from, 2);
        assert_eq!(delivered.load(Ordering::SeqCst), 0);
        media.packet(&phone.seal(DIR_PHONE, 2, &piece(1, &frame[7..])), from, 3);
        assert_eq!(delivered.load(Ordering::SeqCst), 1);

        // Reports go to the phone's address, sealed.
        let out = media.tick(REPORT_EVERY_MS + 10);
        assert!(out.iter().all(|(to, _)| *to == from));
        let report = out
            .iter()
            .find_map(|(_, p)| phone.open(DIR_GATEWAY, p))
            .expect("report")
            .1;
        assert_eq!(report[0], T_REPORT);
        media.unregister(&id);
        assert!(
            media
                .packet(&phone.seal(DIR_PHONE, 9, &[T_HELLO]), from, 4)
                .is_empty()
        );
    }
}
