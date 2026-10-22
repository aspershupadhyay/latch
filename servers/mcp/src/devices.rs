// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Live device connections and the command path to them.
//!
//! Every command an adapter wants to run goes through [`execute`], which
//! serialises commands per device, asks the policy engine, sends the command,
//! and waits for the typed result or the deadline.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use latch_policy::{Decision, DeviceContext};
use latch_protocol::{
    ActionResult, ActivityList, AppList, Capability, CapabilityState, CapabilityStatus, Command,
    CommandEnvelope, DeviceInfo, ErrorCode, FileChunk, FileItem, FileList, FilePreview,
    FileTransfer, GatewayToDevice, Hello, Observation, ObserveAfter, Outcome, ProtocolError,
    SessionInfo, WaitResult, validate,
};
use serde::Serialize;
use tokio::sync::{mpsc, oneshot};

use crate::{AppState, audit::AuditEvent, now_ms, secret};

/// Time the phone gets for an ordinary command.
pub const COMMAND_DEADLINE_MS: u32 = 20_000;
/// Time the phone gets when the owner must approve first.
pub const CONFIRM_DEADLINE_MS: u32 = 120_000;
/// The deadline of a full-access `adb.shell` command whose caller set none
/// (ADR-046): in effect unlimited. The command still ends when the phone
/// disconnects, the owner stops it, or the caller goes away.
pub const NO_DEADLINE_MS: u32 = u32::MAX;
/// How long a command may wait behind other commands for the same device.
const QUEUE_WAIT: Duration = Duration::from_secs(30);
/// A polling phone that has not polled for this long is considered gone.
/// Polls last at most 25 s, so this tolerates one slow round trip.
pub const POLL_STALE_MS: u64 = 45_000;
/// How long a command in flight waits for its phone to come back after the
/// connection dropped (ADR-048). The app retries for 20 s before it gives an
/// answer up; this leaves room for its reconnect on top.
pub const RECONNECT_GRACE_MS: u64 = 30_000;
/// The first protocol minor whose phones run each command id at most once,
/// so a command in flight can be sent again after a reconnect (ADR-048).
const RESEND_MINOR: u32 = 19;
/// How often a waiting command checks whether its phone came back in time.
const ORPHAN_CHECK: Duration = Duration::from_secs(1);

/// Receiving half of a polled device's queue, plus when it last showed up.
struct PollChannel {
    rx: Arc<tokio::sync::Mutex<mpsc::Receiver<GatewayToDevice>>>,
    last_seen_ms: u64,
}

/// A command sent to a phone and not answered yet.
struct Inflight {
    tx: oneshot::Sender<Outcome>,
    /// Kept to send again, with the same id, to a phone that reconnects (1.19).
    envelope: CommandEnvelope,
}

/// Commands whose phone dropped off while they ran (ADR-048). They wait for
/// the phone's next hello, up to [`Registry::reconnect_grace_ms`].
struct Orphaned {
    since_ms: u64,
    pending: HashMap<String, Inflight>,
    awaiting_owner: HashSet<String>,
}

struct Live {
    conn_id: u64,
    tx: mpsc::Sender<GatewayToDevice>,
    hello: Hello,
    capabilities: Vec<CapabilityState>,
    /// Session with `expires_at_ms` translated to the gateway clock.
    session: SessionInfo,
    pending: HashMap<String, Inflight>,
    /// Commands whose phone is waiting for the owner's approval (protocol 1.4).
    awaiting_owner: HashSet<String>,
    /// Latest observation without its screenshot, and when it arrived.
    latest_observation: Option<(Arc<Observation>, u64)>,
    /// The last screen the AI was shown in full (roadmap R5), without its screenshot.
    /// Per AI app (caller key): two apps on one phone each get changes against
    /// the screen they themselves were shown.
    shown_base: HashMap<String, Arc<Observation>>,
    command_lock: Arc<tokio::sync::Mutex<()>>,
    connected_at_ms: u64,
    /// Present for HTTP long-poll connections, absent for WebSockets.
    poll: Option<PollChannel>,
}

/// AI apps whose last full screen is kept per phone connection; more start over.
const MAX_SHOWN_BASES: usize = 8;

pub struct Registry {
    live: Mutex<HashMap<String, Live>>,
    /// Commands in flight on a connection that dropped, by device (ADR-048).
    /// Locked after `live` when both are needed.
    orphans: Mutex<HashMap<String, Orphaned>>,
    /// How long orphaned commands wait for their phone; [`RECONNECT_GRACE_MS`] unless a test sets it.
    pub reconnect_grace_ms: AtomicU64,
    /// R24: the Wi-Fi address each phone last connected through, for its file links.
    link_bases: Mutex<HashMap<String, String>>,
}

impl Default for Registry {
    fn default() -> Self {
        Self {
            live: Mutex::default(),
            orphans: Mutex::default(),
            reconnect_grace_ms: AtomicU64::new(RECONNECT_GRACE_MS),
            link_bases: Mutex::default(),
        }
    }
}

/// Public, redaction-safe view of a connected device.
#[derive(Debug, Clone, Serialize)]
pub struct LiveSummary {
    pub device_id: String,
    pub platform: String,
    pub model: String,
    pub os_version: String,
    pub app_version: String,
    /// Protocol version from the phone's `hello`, e.g. "1.3".
    pub protocol: String,
    pub capabilities: Vec<CapabilityState>,
    pub session: SessionInfo,
    pub connected_at_ms: u64,
    /// Since 1.13: Android may stop the app in the background (battery optimisation).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub battery_restricted: Option<bool>,
}

/// Translates a device-clock timestamp to the gateway clock.
fn to_gateway_clock(session: SessionInfo, device_time_ms: Option<u64>, now: u64) -> SessionInfo {
    match device_time_ms {
        Some(device_now) => {
            let skew = device_now as i128 - now as i128;
            let adjusted = (session.expires_at_ms as i128 - skew).clamp(0, u64::MAX as i128);
            SessionInfo {
                expires_at_ms: adjusted as u64,
                ..session
            }
        }
        None => session,
    }
}

impl Registry {
    /// Remembers how a phone reached this gateway: its Wi-Fi address (R24),
    /// or `None` for the usual address. File links for it are built on that.
    pub fn set_link_base(&self, device_id: &str, base: Option<String>) {
        let mut bases = self.link_bases.lock().unwrap_or_else(|e| e.into_inner());
        match base {
            Some(base) => bases.insert(device_id.to_owned(), base),
            None => bases.remove(device_id),
        };
    }

    /// The Wi-Fi address a phone connected through, if it did (R24).
    pub fn link_base(&self, device_id: &str) -> Option<String> {
        self.link_bases
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(device_id)
            .cloned()
    }

    fn lock(&self) -> MutexGuard<'_, HashMap<String, Live>> {
        // A poisoned lock means a panic mid-update; the map itself is still usable.
        let mut live = self.live.lock().unwrap_or_else(|e| e.into_inner());
        // Polled phones have no socket to close; they disappear by going quiet.
        let now = now_ms();
        let gone: Vec<(String, Live)> = live
            .extract_if(|_, l| {
                l.poll
                    .as_ref()
                    .is_some_and(|p| now.saturating_sub(p.last_seen_ms) > POLL_STALE_MS)
            })
            .collect();
        for (device_id, l) in gone {
            self.orphan(&device_id, l);
        }
        live
    }

    fn orphans(&self) -> MutexGuard<'_, HashMap<String, Orphaned>> {
        self.orphans.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A connection is gone. Its commands in flight wait for the phone to come
    /// back if it runs each id at most once (1.19); otherwise they fail now with
    /// device_unavailable, as they always did.
    fn orphan(&self, device_id: &str, live: Live) {
        let minor = latch_protocol::minor_version(&live.hello.protocol).unwrap_or(0);
        if live.pending.is_empty() || minor < RESEND_MINOR {
            return;
        }
        let mut orphans = self.orphans();
        let entry = orphans
            .entry(device_id.to_owned())
            .or_insert_with(|| Orphaned {
                since_ms: now_ms(),
                pending: HashMap::new(),
                awaiting_owner: HashSet::new(),
            });
        entry.pending.extend(live.pending);
        entry.awaiting_owner.extend(live.awaiting_owner);
    }

    /// Fails the orphaned commands whose phone did not come back in time.
    fn prune_orphans(&self) {
        let grace = self.reconnect_grace_ms.load(Ordering::Relaxed);
        let now = now_ms();
        // Dropping an entry drops its waiters' senders: they report the phone gone.
        self.orphans()
            .retain(|_, o| now.saturating_sub(o.since_ms) <= grace);
    }

    /// Adds a connection, replacing (and thereby closing) any previous one.
    pub fn register(
        &self,
        device_id: &str,
        hello: Hello,
        tx: mpsc::Sender<GatewayToDevice>,
    ) -> u64 {
        let now = now_ms();
        // Random, not counted: a signed message is bound to its connection, and a
        // counter that restarts with the gateway would make old messages valid again.
        let conn_id = u64::from_le_bytes(crate::secret::random_bytes::<8>());
        let mut new = Live {
            conn_id,
            tx,
            poll: None,
            capabilities: hello.capabilities.clone(),
            session: to_gateway_clock(hello.session, hello.device_time_ms, now),
            hello,
            pending: HashMap::new(),
            awaiting_owner: HashSet::new(),
            latest_observation: None,
            shown_base: HashMap::new(),
            command_lock: Arc::new(tokio::sync::Mutex::new(())),
            connected_at_ms: now,
        };
        let mut live = self.lock();
        // ADR-048: commands still in flight on the previous connection, or on one
        // that dropped a moment ago, carry over to this one instead of failing:
        // the phone may have run them, and its answer can still arrive here.
        if let Some(old) = live.remove(device_id) {
            self.orphan(device_id, old);
        }
        let minor = latch_protocol::minor_version(&new.hello.protocol).unwrap_or(0);
        // A phone that went back to an app before 1.19 might run them twice: they
        // are dropped here and fail.
        if let Some(carried) = self.orphans().remove(device_id)
            && minor >= RESEND_MINOR
        {
            for inflight in carried.pending.values() {
                // Same id: the phone answers from memory if it already ran it,
                // and runs it only if the first copy never reached it.
                let _ = new
                    .tx
                    .try_send(GatewayToDevice::Command(inflight.envelope.clone()));
            }
            new.pending = carried.pending;
            new.awaiting_owner = carried.awaiting_owner;
        }
        live.insert(device_id.to_owned(), new);
        conn_id
    }

    /// Adds an HTTP long-poll connection, replacing any previous connection.
    pub fn register_poll(&self, device_id: &str, hello: Hello) -> u64 {
        let (tx, rx) = mpsc::channel(32);
        let conn_id = self.register(device_id, hello, tx);
        if let Some(l) = self
            .lock()
            .get_mut(device_id)
            .filter(|l| l.conn_id == conn_id)
        {
            l.poll = Some(PollChannel {
                rx: Arc::new(tokio::sync::Mutex::new(rx)),
                last_seen_ms: now_ms(),
            });
        }
        conn_id
    }

    /// Marks a polled connection as alive and returns its queue, if it is current.
    pub fn poll_queue(
        &self,
        device_id: &str,
        conn_id: u64,
    ) -> Option<Arc<tokio::sync::Mutex<mpsc::Receiver<GatewayToDevice>>>> {
        let mut live = self.lock();
        let l = live.get_mut(device_id).filter(|l| l.conn_id == conn_id)?;
        let poll = l.poll.as_mut()?;
        poll.last_seen_ms = now_ms();
        Some(poll.rx.clone())
    }

    /// True when `conn_id` is the device's current connection (and refreshes presence).
    pub fn touch(&self, device_id: &str, conn_id: u64) -> bool {
        let mut live = self.lock();
        match live.get_mut(device_id).filter(|l| l.conn_id == conn_id) {
            Some(l) => {
                if let Some(p) = l.poll.as_mut() {
                    p.last_seen_ms = now_ms();
                }
                true
            }
            None => false,
        }
    }

    /// Removes a connection if it is still the current one. Its commands in
    /// flight wait a while for the phone to come back (ADR-048).
    pub fn unregister(&self, device_id: &str, conn_id: u64) {
        let mut live = self.lock();
        if live.get(device_id).is_some_and(|l| l.conn_id == conn_id)
            && let Some(old) = live.remove(device_id)
        {
            self.orphan(device_id, old);
        }
    }

    /// The phone said `bye`: the owner pressed Stop, unpaired, or the session
    /// ended. Its commands in flight fail now and are never sent again, not
    /// even if the owner starts a new session a moment later.
    pub fn said_bye(&self, device_id: &str, conn_id: u64) {
        let mut live = self.lock();
        if live.get(device_id).is_some_and(|l| l.conn_id == conn_id) {
            live.remove(device_id);
        }
    }

    pub fn update_state(
        &self,
        device_id: &str,
        conn_id: u64,
        capabilities: Vec<CapabilityState>,
        session: SessionInfo,
        device_time_ms: Option<u64>,
    ) {
        if let Some(l) = self
            .lock()
            .get_mut(device_id)
            .filter(|l| l.conn_id == conn_id)
        {
            l.capabilities = capabilities;
            l.session = to_gateway_clock(session, device_time_ms, now_ms());
            if l.session.paused {
                // Anything planned before a pause must be re-observed afterwards.
                l.latest_observation = None;
            }
        }
    }

    /// The phone's answer to a command it was sent. It counts on whichever of
    /// the phone's connections it arrives (ADR-048): an answer sent just before
    /// a reconnect, or again after one, still reaches the waiting tool call.
    /// Every connection is authenticated as this phone, and ids are random.
    pub fn resolve(&self, device_id: &str, command_id: &str, outcome: Outcome) {
        let waiter = {
            let mut live = self.lock();
            match live
                .get_mut(device_id)
                .and_then(|l| l.pending.remove(command_id).map(|w| (l, w)))
            {
                Some((l, waiter)) => {
                    l.awaiting_owner.remove(command_id);
                    Some(waiter)
                }
                None => self.orphans().get_mut(device_id).and_then(|o| {
                    o.awaiting_owner.remove(command_id);
                    o.pending.remove(command_id)
                }),
            }
        };
        if let Some(waiter) = waiter {
            let _ = waiter.tx.send(outcome);
        }
    }

    /// The phone is waiting for the owner on a running command (protocol 1.4), so
    /// the command may outlive its normal deadline. This gateway does not relay
    /// answers from the AI app; the Vercel gateway does (ADR-023).
    pub fn approval_requested(&self, device_id: &str, command_id: &str) {
        if let Some(l) = self
            .lock()
            .get_mut(device_id)
            .filter(|l| l.pending.contains_key(command_id))
        {
            l.awaiting_owner.insert(command_id.to_owned());
        }
    }

    fn awaiting_owner(&self, device_id: &str, command_id: &str) -> bool {
        let live = self.lock();
        live.get(device_id)
            .is_some_and(|l| l.awaiting_owner.contains(command_id))
            || self
                .orphans()
                .get(device_id)
                .is_some_and(|o| o.awaiting_owner.contains(command_id))
    }

    /// Stops waiting for a command's answer, and returns the phone's current
    /// connection to tell it to cancel, if the phone is connected.
    fn forget(&self, device_id: &str, command_id: &str) -> Option<mpsc::Sender<GatewayToDevice>> {
        let mut live = self.lock();
        if let Some(l) = live.get_mut(device_id) {
            l.pending.remove(command_id);
            l.awaiting_owner.remove(command_id);
            return Some(l.tx.clone());
        }
        if let Some(o) = self.orphans().get_mut(device_id) {
            o.pending.remove(command_id);
            o.awaiting_owner.remove(command_id);
        }
        None
    }

    /// Tells a phone that the gateway refused `tool` before it reached the phone,
    /// for the owner's activity log (protocol 1.20, R67). Best effort.
    pub fn note_refusal(&self, device_id: &str, tool: &str, code: ErrorCode) {
        if let Some(live) = self.lock().get(device_id) {
            let _ = live.tx.try_send(GatewayToDevice::Refused {
                tool: tool.to_owned(),
                code,
            });
        }
    }

    /// Tells a device it was revoked and drops its connection. Its commands in
    /// flight fail at once: a revoked phone does not come back.
    pub fn kick(&self, device_id: &str, reason: &str) {
        let removed = self.lock().remove(device_id);
        self.orphans().remove(device_id);
        if let Some(live) = removed {
            let _ = live.tx.try_send(GatewayToDevice::Revoked {
                reason: reason.to_owned(),
            });
        }
    }

    /// The observation actions may currently cite, if any.
    pub fn latest_observation(&self, device_id: &str) -> Option<Arc<Observation>> {
        self.lock()
            .get(device_id)
            .and_then(|l| l.latest_observation.as_ref().map(|(o, _)| o.clone()))
    }

    /// The last screen the AI was shown in full on this connection (R5).
    pub fn shown_base(&self, device_id: &str) -> Option<Arc<Observation>> {
        let caller = crate::caller_key();
        self.lock()
            .get(device_id)
            .and_then(|l| l.shown_base.get(&caller).cloned())
    }

    /// Remembers `obs` (without its screenshot) as the screen the AI was shown in full.
    /// R50: the copy is made before taking the lock, and the screenshot is never copied.
    pub fn set_shown_base(&self, device_id: &str, obs: &Observation) {
        let base = Arc::new(Observation {
            observation_id: obs.observation_id.clone(),
            captured_at_ms: obs.captured_at_ms,
            package: obs.package.clone(),
            screen: obs.screen,
            nodes: obs.nodes.clone(),
            screenshot: None,
            redacted_count: obs.redacted_count,
            truncated: obs.truncated,
        });
        let caller = crate::caller_key();
        if let Some(l) = self.lock().get_mut(device_id) {
            if l.shown_base.len() >= MAX_SHOWN_BASES {
                l.shown_base.clear();
            }
            l.shown_base.insert(caller, base);
        }
    }

    /// R50: one capability of one connected phone, without copying every phone's summary.
    pub fn capability_status(
        &self,
        device_id: &str,
        capability: latch_protocol::Capability,
    ) -> Option<latch_protocol::CapabilityStatus> {
        self.lock().get(device_id).and_then(|l| {
            l.capabilities
                .iter()
                .find(|c| c.capability == capability)
                .map(|c| c.status)
        })
    }

    /// R50: the ids of the connected phones, sorted, without their summaries.
    pub fn online_ids(&self) -> Vec<String> {
        let mut ids: Vec<String> = self.lock().keys().cloned().collect();
        ids.sort();
        ids
    }

    /// Minor protocol version the phone spoke in its hello, while connected.
    pub fn protocol_minor(&self, device_id: &str) -> Option<u32> {
        self.lock()
            .get(device_id)
            .and_then(|l| latch_protocol::minor_version(&l.hello.protocol))
    }

    pub fn is_online(&self, device_id: &str) -> bool {
        self.lock().contains_key(device_id)
    }

    pub fn online(&self) -> Vec<LiveSummary> {
        let mut out: Vec<_> = self
            .lock()
            .iter()
            .map(|(id, l)| LiveSummary {
                device_id: id.clone(),
                platform: l.hello.device.platform.clone(),
                model: l.hello.device.model.clone(),
                os_version: l.hello.device.os_version.clone(),
                app_version: l.hello.device.app_version.clone(),
                protocol: l.hello.protocol.chars().take(16).collect(),
                capabilities: l.capabilities.clone(),
                session: l.session,
                connected_at_ms: l.connected_at_ms,
                battery_restricted: l.hello.battery_restricted,
            })
            .collect();
        out.sort_by(|a, b| a.device_id.cmp(&b.device_id));
        out
    }
}

tokio::task_local! {
    /// Set while a tool call runs; true once the phone itself answered a command
    /// with an error (and so logged it). Read by the MCP layer (R67).
    pub static PHONE_ANSWERED: std::sync::Arc<std::sync::atomic::AtomicBool>;
}

/// Typed result of a successful command.
#[derive(Debug, Clone)]
pub enum Output {
    Info(Box<DeviceInfo>),
    Observation(Box<Observation>),
    Action(ActionResult),
    Apps(AppList),
    Wait(Box<WaitResult>),
    /// Since 1.6.
    Files(FileList),
    Preview(Box<FilePreview>),
    Chunk(FileChunk),
    Item(FileItem),
    /// Since 1.7.
    Transfer(FileTransfer),
    /// Since 1.8.
    Activity(ActivityList),
    /// Since 1.14: a same-Wi-Fi address, already checked.
    Local(latch_protocol::LocalSlot),
    /// Since 1.17 (ADR-046): one frame of an `adb.shell` result.
    Adb(latch_protocol::AdbResult),
}

/// Chooses the device a tool call addresses.
pub fn resolve_device(state: &AppState, requested: Option<&str>) -> Result<String, ProtocolError> {
    match requested {
        Some(id) => {
            if state.devices.is_online(id) {
                Ok(id.to_owned())
            } else if state.store().get(id).is_some() {
                let connected = state.devices.online_ids();
                Err(ProtocolError::new(
                    ErrorCode::DeviceUnavailable,
                    if connected.is_empty() {
                        "that device is paired but not connected; the owner needs to start a session in the Latch app".to_owned()
                    } else {
                        format!(
                            "that device is paired but not connected; connected now: {}",
                            connected.join(", ")
                        )
                    },
                ))
            } else {
                Err(ProtocolError::new(
                    ErrorCode::InvalidRequest,
                    "no paired device has that id",
                ))
            }
        }
        None => {
            let online = state.devices.online_ids();
            match online.len() {
                1 => Ok(online[0].clone()),
                0 => Err(ProtocolError::new(
                    ErrorCode::DeviceUnavailable,
                    "no phone is connected; the owner needs to start a session in the Latch app",
                )),
                _ => Err(ProtocolError::new(
                    ErrorCode::AmbiguousDevice,
                    "more than one phone is connected; pass device_id",
                )),
            }
        }
    }
}

/// Runs one command on one device, end to end.
pub async fn execute(
    state: &AppState,
    device_id: &str,
    command: Command,
) -> Result<Output, ProtocolError> {
    execute_with(state, device_id, command, None).await
}

/// Like [`execute`], and for actions asks the phone to observe the screen in
/// the same round trip (protocol 1.2+). The phone may not: check the result.
pub async fn execute_with(
    state: &AppState,
    device_id: &str,
    command: Command,
    observe_after: Option<ObserveAfter>,
) -> Result<Output, ProtocolError> {
    let started = now_ms();
    let name = command.name();
    let result = execute_inner(state, device_id, command, observe_after).await;
    let (decision, outcome) = match &result {
        Ok((decision, _)) => (*decision, "ok"),
        Err((decision, e)) => (*decision, e.code.as_str()),
    };
    state.audit.record(AuditEvent {
        at_ms: started,
        device_id: device_id.to_owned(),
        command: name,
        decision,
        outcome,
        latency_ms: now_ms().saturating_sub(started),
    });
    tracing::info!(
        device_id,
        command = name,
        decision,
        outcome,
        "command finished"
    );
    result.map(|(_, out)| out).map_err(|(_, e)| e)
}

type Staged<T> = Result<(&'static str, T), (&'static str, ProtocolError)>;

/// Cancels a sent command on the phone when dropped while still armed, which
/// happens only when the future waiting for its answer is dropped (the MCP
/// caller disconnected or cancelled). Every other path disarms it first.
struct CancelOnDrop<'a> {
    state: &'a AppState,
    device_id: &'a str,
    id: Option<String>,
}

impl Drop for CancelOnDrop<'_> {
    fn drop(&mut self) {
        let Some(id) = self.id.take() else { return };
        if let Some(tx) = self.state.devices.forget(self.device_id, &id) {
            let _ = tx.try_send(GatewayToDevice::Cancel { id });
        }
    }
}

async fn execute_inner(
    state: &AppState,
    device_id: &str,
    command: Command,
    observe_after: Option<ObserveAfter>,
) -> Staged<Output> {
    let unavailable = || {
        (
            "deny",
            ProtocolError::new(ErrorCode::DeviceUnavailable, "the phone disconnected"),
        )
    };
    let apps = crate::caller_apps();

    let lock = state
        .devices
        .lock()
        .get(device_id)
        .map(|l| l.command_lock.clone())
        .ok_or_else(unavailable)?;
    // One screen command at a time per phone: actions on a screen are
    // inherently sequential. Full-access ADB never touches the screen, so it
    // runs beside them instead of waiting (ADR-046); the phone does the same.
    let _turn = if matches!(command, Command::AdbShell { .. }) {
        None
    } else {
        Some(
            tokio::time::timeout(QUEUE_WAIT, lock.lock_owned())
                .await
                .map_err(|_| {
                    (
                        "deny",
                        ProtocolError::new(
                            ErrorCode::TransportUnavailable,
                            "the phone is busy with other commands",
                        ),
                    )
                })?,
        )
    };

    let (decision, deadline_ms, id, rx, observe_after) = {
        let mut live = state.devices.lock();
        let l = live.get_mut(device_id).ok_or_else(unavailable)?;
        let minor = latch_protocol::minor_version(&l.hello.protocol).unwrap_or(0);
        if command.min_minor_version() > minor {
            return Err((
                "deny",
                ProtocolError::new(
                    ErrorCode::UnsupportedCapability,
                    format!(
                        "the Latch app on this phone (protocol {}) is too old for {}; the owner needs to update it",
                        truncate(&l.hello.protocol, 16),
                        command.name()
                    ),
                ),
            ));
        }
        let enabled = |c: Capability| {
            l.capabilities
                .iter()
                .any(|s| s.capability == c && s.status == CapabilityStatus::Enabled)
        };
        // Only phones that understand it, only for actions, only when the owner lets it read the screen.
        let observe_after = observe_after
            .filter(|_| command.changes_screen() && minor >= 2 && enabled(Capability::UiObserve))
            .map(|after| ObserveAfter {
                include_screenshot: after.include_screenshot && enabled(Capability::ScreenCapture),
                // 1.2 phones ignore quiet_ms and would wait the whole settle_ms.
                settle_ms: if minor >= 3 {
                    after.settle_ms
                } else {
                    state.settle_ms.min(u64::from(validate::MAX_SETTLE_MS)) as u32
                },
                quiet_ms: after.quiet_ms.filter(|_| minor >= 3),
                ..after
            });
        let ctx = DeviceContext {
            capabilities: &l.capabilities,
            session: l.session,
            latest_observation: l
                .latest_observation
                .as_ref()
                .map(|(o, at)| (o.as_ref(), *at)),
            now_ms: now_ms(),
            allowed_apps: apps.as_deref(),
        };
        let (decision, confirm) = match latch_policy::evaluate(&command, &ctx) {
            Decision::Allow { .. } => ("allow", None),
            Decision::Confirm(request) => ("confirm", Some(request)),
            Decision::Deny(error) => return Err(("deny", error)),
        };
        let deadline_ms = match &command {
            // ADR-046: full-access ADB has no Latch-imposed deadline, only one
            // the caller asked for. A continuation is answered from memory.
            Command::AdbShell {
                continue_from: Some(_),
                ..
            } => COMMAND_DEADLINE_MS,
            // Since 1.20: a job starts and answers at once; collecting waits up to wait_ms.
            Command::AdbShell {
                background: true, ..
            } => COMMAND_DEADLINE_MS,
            Command::AdbShell {
                job: Some(_),
                wait_ms,
                ..
            } => wait_ms.saturating_add(COMMAND_DEADLINE_MS),
            Command::AdbShell { timeout_ms, .. } if *timeout_ms > 0 => *timeout_ms,
            Command::AdbShell { .. } => NO_DEADLINE_MS,
            // The owner needs time for an approval, and for whatever they were asked to do.
            _ if confirm.is_some() || matches!(command, Command::AskOwner { .. }) => {
                CONFIRM_DEADLINE_MS
            }
            _ => COMMAND_DEADLINE_MS,
        };
        if command.changes_screen() {
            // Whatever happens next, the old screen can no longer be trusted.
            l.latest_observation = None;
        }
        let id = secret::new_id("c");
        let (tx, rx) = oneshot::channel();
        let envelope = CommandEnvelope {
            id: id.clone(),
            deadline_ms,
            command: command.clone(),
            confirm,
            observe_after,
        };
        l.pending.insert(
            id.clone(),
            Inflight {
                tx,
                envelope: envelope.clone(),
            },
        );
        if l.tx.try_send(GatewayToDevice::Command(envelope)).is_err() {
            l.pending.remove(&id);
            return Err((
                decision,
                ProtocolError::new(
                    ErrorCode::TransportUnavailable,
                    "the connection to the phone is congested",
                ),
            ));
        }
        (decision, deadline_ms, id, rx, observe_after)
    };

    // If the caller goes away (its request is dropped) before the phone answers,
    // tell the phone to stop, so a long command does not hold its queue.
    let mut abandon = CancelOnDrop {
        state,
        device_id,
        id: Some(id.clone()),
    };

    // A little grace on top of the device's own deadline for the network. A phone
    // that is waiting for the owner (protocol 1.4) gets one approval's worth more.
    let mut until =
        tokio::time::Instant::now() + Duration::from_millis(u64::from(deadline_ms) + 3_000);
    let mut extended = false;
    let mut rx = rx;
    let received = loop {
        // Wakes every second to fail commands whose phone dropped off and did not
        // come back in time (ADR-048); the answer itself arrives at once.
        let tick = until
            .saturating_duration_since(tokio::time::Instant::now())
            .min(ORPHAN_CHECK);
        match tokio::time::timeout(tick, &mut rx).await {
            Ok(Ok(outcome)) => break Some(outcome),
            Ok(Err(_)) => return Err((decision, gone_before_answer(&command))),
            Err(_) if tokio::time::Instant::now() < until => state.devices.prune_orphans(),
            Err(_) if !extended && state.devices.awaiting_owner(device_id, &id) => {
                extended = true;
                until = tokio::time::Instant::now()
                    + Duration::from_millis(u64::from(CONFIRM_DEADLINE_MS) + 3_000);
            }
            Err(_) => break None,
        }
    };
    abandon.id = None;
    let outcome = match received {
        Some(outcome) => outcome,
        None => {
            if let Some(tx) = state.devices.forget(device_id, &id) {
                let _ = tx.try_send(GatewayToDevice::Cancel { id });
            }
            return Err((
                decision,
                ProtocolError::new(
                    ErrorCode::DeadlineExceeded,
                    "the phone did not answer in time",
                ),
            ));
        }
    };

    let data = match outcome {
        Outcome::Ok { data } => data,
        Outcome::Error { error } => {
            // The phone logged this itself; the gateway must not log it again.
            let _ =
                PHONE_ANSWERED.try_with(|f| f.store(true, std::sync::atomic::Ordering::Relaxed));
            return Err((decision, error));
        }
    };
    let malformed = |e: serde_json::Error| {
        tracing::warn!(device_id, command = command.name(), error = %e, "malformed device result");
        (
            decision,
            ProtocolError::new(ErrorCode::Internal, "the phone returned a malformed result"),
        )
    };
    // The newest screen the phone reported becomes the one actions must cite.
    let remember = |obs: &Observation| {
        let cached = Observation {
            screenshot: None,
            ..obs.clone()
        };
        // On whichever connection the phone is now: the answer may have come after a reconnect.
        if let Some(l) = state.devices.lock().get_mut(device_id) {
            l.latest_observation = Some((Arc::new(cached), now_ms()));
        }
    };
    let output = match &command {
        Command::DeviceInfo {} => {
            Output::Info(Box::new(serde_json::from_value(data).map_err(malformed)?))
        }
        Command::Observe { max_nodes, .. } => {
            let obs: Observation = serde_json::from_value(data).map_err(malformed)?;
            validate::observation(&obs, *max_nodes).map_err(|e| (decision, e))?;
            remember(&obs);
            Output::Observation(Box::new(obs))
        }
        Command::WaitFor { max_nodes, .. } => {
            let wait: WaitResult = serde_json::from_value(data).map_err(malformed)?;
            validate::observation(&wait.observation, *max_nodes).map_err(|e| (decision, e))?;
            remember(&wait.observation);
            Output::Wait(Box::new(wait))
        }
        Command::ListApps {} => Output::Apps(serde_json::from_value(data).map_err(malformed)?),
        Command::ListFiles { .. } => {
            Output::Files(serde_json::from_value(data).map_err(malformed)?)
        }
        Command::PreviewFile { .. } => {
            Output::Preview(Box::new(serde_json::from_value(data).map_err(malformed)?))
        }
        Command::ReadFile { .. } => Output::Chunk(serde_json::from_value(data).map_err(malformed)?),
        Command::WriteFile { .. } | Command::MakeFolder { .. } | Command::RenameFile { .. } => {
            Output::Item(serde_json::from_value(data).map_err(malformed)?)
        }
        Command::FetchFile { .. } | Command::PushFile { .. } | Command::TransferStatus { .. } => {
            let transfer: FileTransfer = serde_json::from_value(data).map_err(malformed)?;
            if !validate::is_valid_id(&transfer.id)
                || transfer
                    .sha256
                    .as_deref()
                    .is_some_and(|h| !validate::is_hex(h, 64))
            {
                return Err((
                    decision,
                    ProtocolError::new(
                        ErrorCode::Internal,
                        "the phone returned a malformed transfer",
                    ),
                ));
            }
            Output::Transfer(transfer)
        }
        Command::OpenLocal { .. } => {
            let slot: latch_protocol::LocalSlot =
                serde_json::from_value(data).map_err(malformed)?;
            validate::local_slot(&slot).map_err(|e| (decision, e))?;
            Output::Local(slot)
        }
        Command::AdbShell { .. } => Output::Adb(serde_json::from_value(data).map_err(malformed)?),
        Command::ListActivity { limit, .. } => {
            let mut list: ActivityList = serde_json::from_value(data).map_err(malformed)?;
            // Phone text: bounded before it reaches the AI.
            list.entries.truncate(*limit as usize);
            for e in &mut list.entries {
                e.summary = truncate(&e.summary, 300);
                e.app = e.app.as_deref().map(|a| truncate(a, 80));
            }
            Output::Activity(list)
        }
        _ => {
            let mut result: ActionResult = serde_json::from_value(data).map_err(malformed)?;
            // The action ran; a bad or unasked-for observation only means the agent must observe.
            match (&result.observation, observe_after) {
                (Some(obs), Some(after)) if validate::observation(obs, after.max_nodes).is_ok() => {
                    remember(obs);
                }
                (Some(_), _) => {
                    result.observation = None;
                    result.observation_error = Some(ProtocolError::new(
                        ErrorCode::Internal,
                        "the phone returned a malformed observation",
                    ));
                }
                (None, _) => {}
            }
            Output::Action(result)
        }
    };
    // R18: a screen from an app outside this AI app's limit is never shown to it.
    let seen = match &output {
        Output::Observation(o) => Some(o.package.as_deref()),
        Output::Wait(w) => Some(w.observation.package.as_deref()),
        Output::Action(a) => a.observation.as_ref().map(|o| o.package.as_deref()),
        _ => None,
    };
    if let Some(package) = seen
        && !latch_policy::app_allowed(apps.as_deref(), package)
    {
        return Err((decision, latch_policy::screen_withheld(package)));
    }
    Ok((decision, output))
}

/// The phone went away with a command in flight and did not come back in
/// time. An action may have happened anyway, so the AI must look first.
fn gone_before_answer(command: &Command) -> ProtocolError {
    ProtocolError::new(
        ErrorCode::DeviceUnavailable,
        if command.changes_screen() {
            "the phone disconnected before answering; the action may already have happened, so observe before repeating it"
        } else {
            "the phone disconnected before answering"
        },
    )
}

fn truncate(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_expiry_is_translated_to_gateway_clock() {
        let s = SessionInfo {
            expires_at_ms: 10_000,
            approve_every_action: false,
            paused: false,
            remote_approvals: false,
            files: None,
        };
        // Phone clock is 4s ahead of the gateway.
        assert_eq!(to_gateway_clock(s, Some(5_000), 1_000).expires_at_ms, 6_000);
        // Phone clock is behind.
        assert_eq!(
            to_gateway_clock(s, Some(1_000), 5_000).expires_at_ms,
            14_000
        );
        assert_eq!(to_gateway_clock(s, None, 5_000).expires_at_ms, 10_000);
    }
}
