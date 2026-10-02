// Devices, live connections, and the command path, on top of the Store.
// Mirrors servers/mcp/src/devices.rs: one command at a time per phone, policy
// before anything leaves the gateway, typed results, redacted audit.

import { evaluate } from "./policy.js";
import {
  type CapabilityState, type Command, type ConfirmRequest, type Hello, type Observation, type Outcome,
  ProtocolError, type SessionInfo, isAction, normalizeObservation,
} from "./protocol.js";
import { newId } from "./secret.js";
import type { Store } from "./store.js";

export const COMMAND_DEADLINE_MS = 20_000;
export const CONFIRM_DEADLINE_MS = 120_000;
export const POLL_STALE_MS = 45_000;
const QUEUE_WAIT_MS = 30_000;
const OBSERVATION_TTL_MS = 120_000;

export interface Timing {
  /** How often a waiting poll checks the queue (each check is one Redis command). */
  pollIntervalMs: number;
  /** How often a waiting tool call checks for the phone's result. */
  resultIntervalMs: number;
}

export interface DeviceRecord {
  id: string; name: string; model: string; platform: string;
  token_sha256: string; paired_at_ms: number; last_seen_ms?: number;
}

export interface Live {
  conn: string;
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

  /** The current connection if `conn` is it; refreshes presence. */
  async current(id: string, conn: string): Promise<Live | undefined> {
    const live = await this.live(id);
    if (!live || live.conn !== conn) return undefined;
    await this.store.pexpire(K.live(id), POLL_STALE_MS);
    return live;
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

  /** Waits up to `waitMs` for the next message for this connection. */
  async nextMessage(id: string, conn: string, waitMs: number): Promise<string | undefined> {
    const until = Date.now() + waitMs;
    for (;;) {
      const message = await this.store.rpop(K.queue(id, conn));
      if (message) return message;
      if (Date.now() + this.timing.pollIntervalMs > until) return undefined;
      await sleep(this.timing.pollIntervalMs);
    }
  }

  /** Records a phone's answer, if it belongs to a command sent on this connection. */
  async deliverResult(id: string, conn: string, commandId: string, outcome: Outcome) {
    const pending = await this.store.getdel(K.pending(commandId));
    if (!pending) return;
    const p = JSON.parse(pending) as { device: string; conn: string };
    if (p.device !== id || p.conn !== conn) return;
    await this.store.set(K.result(commandId), JSON.stringify(outcome), { px: 60_000 });
  }

  async online(): Promise<LiveSummary[]> {
    const out: LiveSummary[] = [];
    for (const record of await this.records()) {
      const l = await this.live(record.id);
      if (!l) continue;
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

  async resolveDevice(requested: string | undefined): Promise<string> {
    if (requested !== undefined) {
      if (await this.live(requested)) return requested;
      if (await this.record(requested)) {
        throw new ProtocolError("device_unavailable", "that device is paired but not connected; the owner needs to start a session in the Latch app");
      }
      throw new ProtocolError("invalid_request", "no paired device has that id");
    }
    const online = await this.online();
    if (online.length === 1) return online[0]!.device_id;
    if (online.length === 0) throw new ProtocolError("device_unavailable", "no phone is connected; the owner needs to start a session in the Latch app");
    throw new ProtocolError("ambiguous_device", "more than one phone is connected; pass device_id");
  }

  /** Runs one command on one device, end to end. Returns the result data. */
  async execute(id: string, command: Command): Promise<unknown> {
    const started = Date.now();
    let decision: AuditEvent["decision"] = "deny";
    let outcome = "ok";
    try {
      const run = await this.executeInner(id, command, (d) => { decision = d; });
      return run;
    } catch (e) {
      const error = e instanceof ProtocolError ? e : new ProtocolError("internal", "unexpected gateway error");
      outcome = error.code;
      throw error;
    } finally {
      const event: AuditEvent = { at_ms: started, device_id: id, command: command.name, decision, outcome, latency_ms: Date.now() - started };
      await this.store.lpush(K.audit, JSON.stringify(event), 30 * 24 * 3600_000);
      await this.store.ltrim(K.audit, 0, 999);
    }
  }

  private async executeInner(id: string, command: Command, setDecision: (d: AuditEvent["decision"]) => void): Promise<unknown> {
    const unavailable = () => new ProtocolError("device_unavailable", "the phone disconnected");
    if (!(await this.live(id))) throw unavailable();

    // One command at a time per phone: actions on a screen are inherently sequential.
    const lockToken = newId("l");
    const lockUntil = Date.now() + QUEUE_WAIT_MS;
    while (!(await this.store.set(K.lock(id), lockToken, { px: CONFIRM_DEADLINE_MS + 15_000, nx: true }))) {
      if (Date.now() > lockUntil) throw new ProtocolError("transport_unavailable", "the phone is busy with other commands");
      await sleep(200);
    }
    try {
      const live = await this.live(id);
      if (!live) throw unavailable();
      const obsRecord = await this.store.get(K.observation(id));
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
      // Whatever happens next, the old screen can no longer be trusted.
      if (isAction(command)) await this.store.del(K.observation(id));

      const commandId = newId("c");
      await this.store.set(K.pending(commandId), JSON.stringify({ device: id, conn: live.conn }), { px: deadlineMs + 10_000 });
      const envelope = { type: "command", id: commandId, deadline_ms: deadlineMs, command, ...(confirm ? { confirm } : {}) };
      await this.store.lpush(K.queue(id, live.conn), JSON.stringify(envelope), deadlineMs + 10_000);

      // A little grace on top of the device's own deadline for the network.
      const waitUntil = Date.now() + deadlineMs + 3_000;
      let lastPresenceCheck = Date.now();
      let result: string | null = null;
      for (;;) {
        result = await this.store.getdel(K.result(commandId));
        if (result) break;
        if (Date.now() > waitUntil) {
          await this.store.del(K.pending(commandId));
          await this.store.lpush(K.queue(id, live.conn), JSON.stringify({ type: "cancel", id: commandId }), 60_000);
          throw new ProtocolError("deadline_exceeded", "the phone did not answer in time");
        }
        if (Date.now() - lastPresenceCheck > 3_000) {
          lastPresenceCheck = Date.now();
          if ((await this.live(id))?.conn !== live.conn) throw unavailable();
        }
        await sleep(this.timing.resultIntervalMs);
      }

      const outcome = JSON.parse(result) as Outcome;
      if (outcome.status === "error") throw new ProtocolError(outcome.error.code, outcome.error.message);
      if (command.name === "ui.observe") {
        let observation: Observation;
        try {
          observation = normalizeObservation(outcome.data, command.params.max_nodes);
        } catch (e) {
          throw e instanceof ProtocolError ? e : new ProtocolError("internal", "the phone returned a malformed result");
        }
        const { screenshot: _drop, ...withoutScreenshot } = observation;
        await this.store.set(
          K.observation(id),
          JSON.stringify({ conn: live.conn, observation: withoutScreenshot, received_at_ms: Date.now() }),
          { px: OBSERVATION_TTL_MS },
        );
        return observation;
      }
      if (typeof outcome.data !== "object" || outcome.data === null) {
        throw new ProtocolError("internal", "the phone returned a malformed result");
      }
      return outcome.data;
    } finally {
      if ((await this.store.get(K.lock(id))) === lockToken) await this.store.del(K.lock(id));
    }
  }
}
