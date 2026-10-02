// Devices, live connections, and the command path, on top of the Store.
// Mirrors servers/mcp/src/devices.rs: one command at a time per phone, policy
// before anything leaves the gateway, typed results, redacted audit.

import { evaluate } from "./policy.js";
import {
  type CapabilityState, type Command, type ConfirmRequest, type ErrorCode, type Hello, type Observation, type ObserveAfter,
  type Outcome, ProtocolError, RECOVERY_HINTS, type SessionInfo, isAction, normalizeObservation, supportsObserveAfter,
} from "./protocol.js";
import { newId } from "./secret.js";
import type { BatchOp, Store } from "./store.js";

export const COMMAND_DEADLINE_MS = 20_000;
export const CONFIRM_DEADLINE_MS = 120_000;
export const POLL_STALE_MS = 45_000;
const QUEUE_WAIT_MS = 30_000;
const OBSERVATION_TTL_MS = 120_000;

export interface Timing {
  /** How often a waiting poll checks the queue (each check is one Redis command). */
  pollIntervalMs: number;
  /** Poll interval while the phone is in an active agent loop (it says so with `hot=1`). */
  hotPollIntervalMs: number;
  /** How often a waiting tool call checks for the phone's result. */
  resultIntervalMs: number;
}

/** Where the time of one command went, for the owner and the agent (`_meta` of tool results). */
export interface CommandTiming {
  /** Waiting for an earlier command on the same phone to finish. */
  lock_wait_ms: number;
  /** From queueing the command until the phone's answer arrived: transport + phone work (+ approval). */
  phone_ms: number;
  /** Everything the gateway spent on this command, including its own storage round trips. */
  total_ms: number;
}

export interface Executed {
  data: unknown;
  /** The screen after an action, when the phone observed it in the same round trip (protocol 1.2). */
  observation?: Observation;
  /** Why the phone could not observe after the action, if it tried. */
  observationError?: ProtocolError;
  timing: CommandTiming;
}

export interface ExecuteOptions {
  /** Ask the phone to observe after a successful action; ignored for phones older than 1.2. */
  observeAfter?: ObserveAfter;
}

export interface DeviceRecord {
  id: string; name: string; model: string; platform: string;
  token_sha256: string; paired_at_ms: number; last_seen_ms?: number;
}

export interface Live {
  conn: string;
  /** Protocol version from the phone's hello (absent for records written before 1.2). */
  protocol?: string;
  device: Hello["device"];
  capabilities: CapabilityState[];
  /** Session with expires_at_ms translated to the gateway clock. */
  session: SessionInfo;
  connected_at_ms: number;
}

export interface LiveSummary {
  device_id: string; platform: string; model: string; os_version: string; app_version: string;
  capabilities: CapabilityState[]; session: SessionInfo; connected_at_ms: number;
}

export interface AuditEvent {
  at_ms: number; device_id: string; command: string; decision: "allow" | "confirm" | "deny"; outcome: string; latency_ms: number;
}

export const K = {
  devices: "latch:devices",
  deviceToken: (sha: string) => `latch:devtoken:${sha}`,
  live: (id: string) => `latch:live:${id}`,
  queue: (id: string, conn: string) => `latch:q:${id}:${conn}`,
  pending: (cmd: string) => `latch:pending:${cmd}`,
  result: (cmd: string) => `latch:result:${cmd}`,
  lock: (id: string) => `latch:lock:${id}`,
  observation: (id: string) => `latch:obs:${id}`,
  audit: "latch:audit",
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function toGatewayClock(session: SessionInfo, deviceTimeMs: number | undefined, now: number): SessionInfo {
  if (deviceTimeMs === undefined) return session;
  return { ...session, expires_at_ms: Math.max(0, session.expires_at_ms - (deviceTimeMs - now)) };
}

export class Devices {
  constructor(private readonly store: Store, private readonly timing: Timing) {}

  // ---- Records ----

  async records(): Promise<DeviceRecord[]> {
    const all = await this.store.hgetall(K.devices);
    return Object.values(all).map((v) => JSON.parse(v) as DeviceRecord).sort((a, b) => a.paired_at_ms - b.paired_at_ms);
  }

  async record(id: string): Promise<DeviceRecord | undefined> {
    return (await this.records()).find((d) => d.id === id);
  }

  async addRecord(record: DeviceRecord): Promise<void> {
    await this.store.hset(K.devices, record.id, JSON.stringify(record));
    await this.store.set(K.deviceToken(record.token_sha256), record.id);
  }

  /** Revokes a device: credential gone, live connection told and dropped. */
  async revoke(id: string): Promise<boolean> {
    const record = await this.record(id);
    if (!record) return false;
    await this.store.hdel(K.devices, id);
    await this.store.del(K.deviceToken(record.token_sha256));
    const live = await this.live(id);
    if (live) {
      await this.store.lpush(K.queue(id, live.conn), JSON.stringify({ type: "revoked", reason: "revoked by the gateway owner" }), 60_000);
      await this.store.del(K.live(id));
    }
    await this.store.del(K.observation(id));
    return true;
  }

  async deviceForToken(sha: string): Promise<string | undefined> {
    return (await this.store.get(K.deviceToken(sha))) ?? undefined;
  }

  async touchRecord(id: string, now: number): Promise<void> {
    const record = await this.record(id);
    if (record) await this.store.hset(K.devices, id, JSON.stringify({ ...record, last_seen_ms: now }));
  }

  // ---- Live connections ----

  async live(id: string): Promise<Live | undefined> {
    const v = await this.store.get(K.live(id));
    return v ? (JSON.parse(v) as Live) : undefined;
  }

  async connect(id: string, hello: Hello, now: number): Promise<string> {
    const conn = newId("k");
    const live: Live = {
      conn,
      protocol: hello.protocol,
      device: hello.device,
      capabilities: hello.capabilities,
      session: toGatewayClock(hello.session, hello.device_time_ms, now),
      connected_at_ms: now,
    };
    await this.store.set(K.live(id), JSON.stringify(live), { px: POLL_STALE_MS });
    await this.store.del(K.observation(id));
    await this.touchRecord(id, now);
    return conn;
  }

  /** The current connection if `conn` is it; refreshes presence. One round trip. */
  async current(id: string, conn: string): Promise<Live | undefined> {
    // Refreshing a replaced connection's key only keeps the newer connection alive; harmless.
    const [raw] = await this.store.batch([{ op: "get", key: K.live(id) }, { op: "pexpire", key: K.live(id), px: POLL_STALE_MS }]);
    const live = raw ? (JSON.parse(raw) as Live) : undefined;
    return live?.conn === conn ? live : undefined;
  }

  async updateState(id: string, live: Live, capabilities: CapabilityState[], session: SessionInfo, deviceTimeMs: number | undefined, now: number) {
    const next: Live = { ...live, capabilities, session: toGatewayClock(session, deviceTimeMs, now) };
    await this.store.set(K.live(id), JSON.stringify(next), { px: POLL_STALE_MS });
    if (next.session.paused) await this.store.del(K.observation(id));
  }

  async disconnect(id: string, conn: string, now: number) {
    const live = await this.live(id);
    if (live?.conn === conn) {
      await this.store.del(K.live(id));
      await this.store.del(K.observation(id));
    }
    await this.touchRecord(id, now);
  }

  /**
   * Waits up to `waitMs` for the next message for this connection. A phone in
   * an active agent loop (`hot`) is checked more often so commands reach it sooner.
   */
  async nextMessage(id: string, conn: string, waitMs: number, hot = false): Promise<string | undefined> {
    const until = Date.now() + waitMs;
    const interval = hot ? this.timing.hotPollIntervalMs : this.timing.pollIntervalMs;
    for (;;) {
      const message = await this.store.rpop(K.queue(id, conn));
      if (message) return message;
      if (Date.now() + interval > until) return undefined;
      await sleep(interval);
    }
  }

  /**
   * Records a phone's answer, if it belongs to a command sent on this
   * connection and the connection is still current. Two round trips.
   * Returns false when the connection was replaced or expired.
   */
  async deliverResult(id: string, conn: string, commandId: string, outcome: Outcome): Promise<boolean> {
    const [rawLive, pending] = await this.store.batch([
      { op: "get", key: K.live(id) },
      { op: "pexpire", key: K.live(id), px: POLL_STALE_MS },
      { op: "getdel", key: K.pending(commandId) },
    ]).then((r) => [r[0], r[2]]);
    const live = rawLive ? (JSON.parse(rawLive) as Live) : undefined;
    if (live?.conn !== conn) return false;
    if (!pending) return true;
    const p = JSON.parse(pending) as { device: string; conn: string };
    if (p.device !== id || p.conn !== conn) return true;
    await this.store.set(K.result(commandId), JSON.stringify(outcome), { px: 60_000 });
    return true;
  }

  async online(): Promise<LiveSummary[]> {
    const records = await this.records();
    const lives = await this.store.mget(records.map((r) => K.live(r.id)));
    const out: LiveSummary[] = [];
    for (const [i, record] of records.entries()) {
      const raw = lives[i];
      if (!raw) continue;
      const l = JSON.parse(raw) as Live;
      out.push({
        device_id: record.id, platform: l.device.platform, model: l.device.model, os_version: l.device.os_version,
        app_version: l.device.app_version, capabilities: l.capabilities, session: l.session, connected_at_ms: l.connected_at_ms,
      });
    }
    return out.sort((a, b) => a.device_id.localeCompare(b.device_id));
  }

  async latestObservation(id: string): Promise<Observation | undefined> {
    const v = await this.store.get(K.observation(id));
    return v ? (JSON.parse(v) as { observation: Observation }).observation : undefined;
  }

  async audit(limit: number): Promise<AuditEvent[]> {
    return (await this.store.lrange(K.audit, 0, limit - 1)).map((v) => JSON.parse(v) as AuditEvent);
  }

  // ---- Command path ----

  /** Picks the phone a tool call is for, with its live state. */
  async resolveDevice(requested: string | undefined): Promise<{ id: string; live: Live }> {
    if (requested !== undefined) {
      const live = await this.live(requested);
      if (live) return { id: requested, live };
      if (await this.record(requested)) {
        throw new ProtocolError("device_unavailable", "that device is paired but not connected; the owner needs to start a session in the Latch app");
      }
      throw new ProtocolError("invalid_request", "no paired device has that id");
    }
    const records = await this.records();
    const lives = (await this.store.mget(records.map((r) => K.live(r.id))))
      .map((raw, i) => (raw ? { id: records[i]!.id, live: JSON.parse(raw) as Live } : undefined))
      .filter((x) => x !== undefined);
    if (lives.length === 1) return lives[0]!;
    if (lives.length === 0) throw new ProtocolError("device_unavailable", "no phone is connected; the owner needs to start a session in the Latch app");
    throw new ProtocolError("ambiguous_device", "more than one phone is connected; pass device_id");
  }

  /**
   * Runs one command on one device, end to end. Storage round trips are
   * batched because each one can cost 100+ ms when Redis is in another region:
   * lock, read state, queue, wait, then one batch that stores the observation,
   * releases the lock, and writes the audit event.
   */
  async execute(id: string, command: Command, options: ExecuteOptions = {}): Promise<Executed> {
    const started = Date.now();
    let decision: AuditEvent["decision"] = "deny";
    let outcome = "ok";
    const lockToken = newId("l");
    let locked = false;
    const finish: BatchOp[] = [];
    try {
      // One command at a time per phone: actions on a screen are inherently sequential.
      const lockUntil = started + QUEUE_WAIT_MS;
      while (!(await this.store.set(K.lock(id), lockToken, { px: CONFIRM_DEADLINE_MS + 15_000, nx: true }))) {
        if (Date.now() > lockUntil) throw new ProtocolError("transport_unavailable", "the phone is busy with other commands");
        await sleep(100);
      }
      locked = true;
      const lockWaitMs = Date.now() - started;
      const result = await this.run(id, command, options, (d) => { decision = d; }, finish);
      return { ...result, timing: { lock_wait_ms: lockWaitMs, phone_ms: result.phoneMs, total_ms: Date.now() - started } };
    } catch (e) {
      const error = e instanceof ProtocolError ? e : new ProtocolError("internal", "unexpected gateway error");
      outcome = error.code;
      throw error;
    } finally {
      if (locked) finish.push({ op: "delIfEquals", key: K.lock(id), value: lockToken });
      const event: AuditEvent = { at_ms: started, device_id: id, command: command.name, decision, outcome, latency_ms: Date.now() - started };
      finish.push({ op: "lpush", key: K.audit, value: JSON.stringify(event), px: 30 * 24 * 3600_000 }, { op: "ltrim", key: K.audit, start: 0, stop: 999 });
      await this.store.batch(finish);
    }
  }

  /** The part of [execute] that holds the lock. Writes to store later go into `finish`. */
  private async run(
    id: string, command: Command, options: ExecuteOptions, setDecision: (d: AuditEvent["decision"]) => void, finish: BatchOp[],
  ): Promise<Omit<Executed, "timing"> & { phoneMs: number }> {
    const unavailable = () => new ProtocolError("device_unavailable", "the phone disconnected");
    const [liveRaw, obsRecord] = await this.store.mget([K.live(id), K.observation(id)]);
    if (!liveRaw) throw unavailable();
    const live = JSON.parse(liveRaw) as Live;
    const cached = obsRecord ? (JSON.parse(obsRecord) as { conn: string; observation: Observation; received_at_ms: number }) : undefined;
    const now = Date.now();
    const decision = evaluate(command, {
      capabilities: live.capabilities,
      session: live.session,
      latestObservation: cached && cached.conn === live.conn ? { observation: cached.observation, receivedAtMs: cached.received_at_ms } : undefined,
      nowMs: now,
    });
    if (decision.kind === "deny") throw decision.error;
    setDecision(decision.kind);
    const confirm: ConfirmRequest | undefined = decision.kind === "confirm" ? decision.request : undefined;
    const deadlineMs = confirm ? CONFIRM_DEADLINE_MS : COMMAND_DEADLINE_MS;
    const enabled = (c: string) => live.capabilities.some((s) => s.capability === c && s.status === "enabled");
    const observeAfter = options.observeAfter && isAction(command) && supportsObserveAfter(live.protocol) && enabled("ui.observe")
      ? { ...options.observeAfter, include_screenshot: options.observeAfter.include_screenshot && enabled("screen.capture") }
      : undefined;

    const commandId = newId("c");
    const envelope = {
      type: "command", id: commandId, deadline_ms: deadlineMs, command,
      ...(confirm ? { confirm } : {}),
      ...(observeAfter ? { observe_after: observeAfter } : {}),
    };
    const send: BatchOp[] = [];
    // Whatever happens next, the old screen can no longer be trusted.
    if (isAction(command)) send.push({ op: "del", key: K.observation(id) });
    send.push(
      { op: "set", key: K.pending(commandId), value: JSON.stringify({ device: id, conn: live.conn }), px: deadlineMs + 10_000 },
      { op: "lpush", key: K.queue(id, live.conn), value: JSON.stringify(envelope), px: deadlineMs + 10_000 },
    );
    await this.store.batch(send);
    const sentAt = Date.now();

    // A little grace on top of the device's own deadline for the network.
    const waitUntil = sentAt + deadlineMs + 3_000;
    let lastPresenceCheck = sentAt;
    let result: string | null = null;
    for (;;) {
      result = await this.store.getdel(K.result(commandId));
      if (result) break;
      if (Date.now() > waitUntil) {
        finish.push(
          { op: "del", key: K.pending(commandId) },
          { op: "lpush", key: K.queue(id, live.conn), value: JSON.stringify({ type: "cancel", id: commandId }), px: 60_000 },
        );
        throw new ProtocolError("deadline_exceeded", "the phone did not answer in time");
      }
      if (Date.now() - lastPresenceCheck > 3_000) {
        lastPresenceCheck = Date.now();
        if ((await this.live(id))?.conn !== live.conn) throw unavailable();
      }
      await sleep(this.timing.resultIntervalMs);
    }
    const phoneMs = Date.now() - sentAt;

    const outcome = JSON.parse(result) as Outcome;
    if (outcome.status === "error") throw new ProtocolError(outcome.error.code, outcome.error.message);
    const remember = (observation: Observation) => {
      const { screenshot: _drop, ...withoutScreenshot } = observation;
      finish.push({
        op: "set", key: K.observation(id),
        value: JSON.stringify({ conn: live.conn, observation: withoutScreenshot, received_at_ms: Date.now() }), px: OBSERVATION_TTL_MS,
      });
    };
    const malformed = () => new ProtocolError("internal", "the phone returned a malformed result");
    if (command.name === "ui.observe") {
      let observation: Observation;
      try {
        observation = normalizeObservation(outcome.data, command.params.max_nodes);
      } catch (e) {
        throw e instanceof ProtocolError ? e : malformed();
      }
      remember(observation);
      return { data: observation, phoneMs };
    }
    if (typeof outcome.data !== "object" || outcome.data === null) throw malformed();
    if (!observeAfter) return { data: outcome.data, phoneMs };
    // The action ran; a bad or missing observation only means the agent must observe itself.
    const { observation: rawObservation, observation_error: rawError, ...data } = outcome.data as Record<string, unknown>;
    if (rawObservation !== undefined) {
      try {
        const observation = normalizeObservation(rawObservation, observeAfter.max_nodes);
        remember(observation);
        return { data, observation, phoneMs };
      } catch {
        return { data, observationError: malformed(), phoneMs };
      }
    }
    const e = rawError as { code?: unknown; message?: unknown } | undefined;
    const observationError = typeof e?.code === "string" && e.code in RECOVERY_HINTS && typeof e.message === "string"
      ? new ProtocolError(e.code as ErrorCode, e.message.slice(0, 500))
      : new ProtocolError("internal", "the phone did not observe after the action");
    return { data, observationError, phoneMs };
  }
}
