// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Live device connections and the command path to them.
//!
//! Every command an adapter wants to run goes through [`execute`], which
//! serialises commands per device, asks the policy engine, sends the command,
//! and waits for the typed result or the deadline.

use std::collections::HashMap;
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
/// How long a command may wait behind other commands for the same device.
const QUEUE_WAIT: Duration = Duration::from_secs(30);
/// A polling phone that has not polled for this long is considered gone.
/// Polls last at most 25 s, so this tolerates one slow round trip.
pub const POLL_STALE_MS: u64 = 45_000;

/// Receiving half of a polled device's queue, plus when it last showed up.
struct PollChannel {
    rx: Arc<tokio::sync::Mutex<mpsc::Receiver<GatewayToDevice>>>,
    last_seen_ms: u64,
}

struct Live {
    conn_id: u64,
    tx: mpsc::Sender<GatewayToDevice>,
    hello: Hello,
    capabilities: Vec<CapabilityState>,
    /// Session with `expires_at_ms` translated to the gateway clock.
    session: SessionInfo,
    pending: HashMap<String, oneshot::Sender<Outcome>>,
    /// Commands whose phone is waiting for the owner's approval (protocol 1.4).
    awaiting_owner: std::collections::HashSet<String>,
    /// Latest observation without its screenshot, and when it arrived.
    latest_observation: Option<(Arc<Observation>, u64)>,
    command_lock: Arc<tokio::sync::Mutex<()>>,
    connected_at_ms: u64,
    /// Present for HTTP long-poll connections, absent for WebSockets.
    poll: Option<PollChannel>,
}

#[derive(Default)]
pub struct Registry {
    live: Mutex<HashMap<String, Live>>,
    next_conn: std::sync::atomic::AtomicU64,
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
    fn lock(&self) -> MutexGuard<'_, HashMap<String, Live>> {
        // A poisoned lock means a panic mid-update; the map itself is still usable.
        let mut live = self.live.lock().unwrap_or_else(|e| e.into_inner());
        // Polled phones have no socket to close; they disappear by going quiet.
        // Dropping the entry fails its pending commands with device_unavailable.
        let now = now_ms();
        live.retain(|_, l| {
            l.poll
                .as_ref()
                .is_none_or(|p| now.saturating_sub(p.last_seen_ms) <= POLL_STALE_MS)
        });
        live
    }

    /// Adds a connection, replacing (and thereby closing) any previous one.
    pub fn register(
        &self,
        device_id: &str,
        hello: Hello,
        tx: mpsc::Sender<GatewayToDevice>,
    ) -> u64 {
        let now = now_ms();
        let conn_id = self
            .next_conn
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let live = Live {
            conn_id,
            tx,
            poll: None,
            capabilities: hello.capabilities.clone(),
            session: to_gateway_clock(hello.session, hello.device_time_ms, now),
            hello,
            pending: HashMap::new(),
            awaiting_owner: std::collections::HashSet::new(),
            latest_observation: None,
            command_lock: Arc::new(tokio::sync::Mutex::new(())),
            connected_at_ms: now,
        };
        self.lock().insert(device_id.to_owned(), live);
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

    /// Removes a connection if it is still the current one. Pending commands fail.
    pub fn unregister(&self, device_id: &str, conn_id: u64) {
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

    pub fn resolve(&self, device_id: &str, conn_id: u64, command_id: &str, outcome: Outcome) {
        let waiter = self
            .lock()
            .get_mut(device_id)
            .filter(|l| l.conn_id == conn_id)
            .and_then(|l| {
                l.awaiting_owner.remove(command_id);
                l.pending.remove(command_id)
            });
        if let Some(waiter) = waiter {
            let _ = waiter.send(outcome);
        }
    }

    /// The phone is waiting for the owner on a running command (protocol 1.4), so
    /// the command may outlive its normal deadline. This gateway does not relay
    /// answers from the AI app; the Vercel gateway does (ADR-023).
    pub fn approval_requested(&self, device_id: &str, conn_id: u64, command_id: &str) {
        if let Some(l) = self
            .lock()
            .get_mut(device_id)
            .filter(|l| l.conn_id == conn_id && l.pending.contains_key(command_id))
        {
            l.awaiting_owner.insert(command_id.to_owned());
        }
    }

    fn awaiting_owner(&self, device_id: &str, conn_id: u64, command_id: &str) -> bool {
        self.lock()
            .get(device_id)
            .is_some_and(|l| l.conn_id == conn_id && l.awaiting_owner.contains(command_id))
    }

    /// Tells a device it was revoked and drops its connection.
    pub fn kick(&self, device_id: &str, reason: &str) {
        if let Some(live) = self.lock().remove(device_id) {
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
            })
            .collect();
        out.sort_by(|a, b| a.device_id.cmp(&b.device_id));
        out
    }
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
}

/// Chooses the device a tool call addresses.
pub fn resolve_device(state: &AppState, requested: Option<&str>) -> Result<String, ProtocolError> {
    match requested {
        Some(id) => {
            if state.devices.is_online(id) {
                Ok(id.to_owned())
            } else if state.store().get(id).is_some() {
                let connected: Vec<String> = state
                    .devices
                    .online()
                    .into_iter()
                    .map(|d| d.device_id)
                    .collect();
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
            let online = state.devices.online();
            match online.len() {
                1 => Ok(online[0].device_id.clone()),
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

    let lock = state
        .devices
        .lock()
        .get(device_id)
        .map(|l| l.command_lock.clone())
        .ok_or_else(unavailable)?;
    // One command at a time per phone: actions on a screen are inherently sequential.
    let _turn = tokio::time::timeout(QUEUE_WAIT, lock.lock_owned())
        .await
        .map_err(|_| {
            (
                "deny",
                ProtocolError::new(
                    ErrorCode::TransportUnavailable,
                    "the phone is busy with other commands",
                ),
            )
        })?;

    let (decision, deadline_ms, id, rx, conn_id, observe_after) = {
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
        };
        let (decision, confirm) = match latch_policy::evaluate(&command, &ctx) {
            Decision::Allow { .. } => ("allow", None),
            Decision::Confirm(request) => ("confirm", Some(request)),
            Decision::Deny(error) => return Err(("deny", error)),
        };
        // The owner needs time for an approval, and for whatever they were asked to do.
        let deadline_ms = if confirm.is_some() || matches!(command, Command::AskOwner { .. }) {
            CONFIRM_DEADLINE_MS
        } else {
            COMMAND_DEADLINE_MS
        };
        if command.changes_screen() {
            // Whatever happens next, the old screen can no longer be trusted.
            l.latest_observation = None;
        }
        let id = secret::new_id("c");
        let (tx, rx) = oneshot::channel();
        l.pending.insert(id.clone(), tx);
        let envelope = GatewayToDevice::Command(CommandEnvelope {
            id: id.clone(),
            deadline_ms,
            command: command.clone(),
            confirm,
            observe_after,
        });
        if l.tx.try_send(envelope).is_err() {
            l.pending.remove(&id);
            return Err((
                decision,
                ProtocolError::new(
                    ErrorCode::TransportUnavailable,
                    "the connection to the phone is congested",
                ),
            ));
        }
        (decision, deadline_ms, id, rx, l.conn_id, observe_after)
    };

    // A little grace on top of the device's own deadline for the network. A phone
    // that is waiting for the owner (protocol 1.4) gets one approval's worth more.
    let mut wait = Duration::from_millis(u64::from(deadline_ms) + 3_000);
    let mut extended = false;
    let mut rx = rx;
    let received = loop {
        match tokio::time::timeout(wait, &mut rx).await {
            Ok(Ok(outcome)) => break Some(outcome),
            Ok(Err(_)) => return Err((decision, unavailable().1)),
            Err(_) if !extended && state.devices.awaiting_owner(device_id, conn_id, &id) => {
                extended = true;
                wait = Duration::from_millis(u64::from(CONFIRM_DEADLINE_MS) + 3_000);
            }
            Err(_) => break None,
        }
    };
    let outcome = match received {
        Some(outcome) => outcome,
        None => {
            let tx = {
                let mut live = state.devices.lock();
                live.get_mut(device_id)
                    .filter(|l| l.conn_id == conn_id)
                    .map(|l| {
                        l.pending.remove(&id);
                        l.awaiting_owner.remove(&id);
                        l.tx.clone()
                    })
            };
            if let Some(tx) = tx {
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
        Outcome::Error { error } => return Err((decision, error)),
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
        if let Some(l) = state
            .devices
            .lock()
            .get_mut(device_id)
            .filter(|l| l.conn_id == conn_id)
        {
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
    Ok((decision, output))
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
