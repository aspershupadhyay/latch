// Port of crates/policy. Must reach the same decision as the Rust engine for
// every case in packages/schemas/v1/policy/cases.json (see test/policy.test.ts).
// Screen content is untrusted: it may only raise scrutiny, never lower it.

import { WORDS } from "./generated/policy-words";
import {
  CAPABILITY_DESCRIPTIONS, type CapabilityState, type Command, type ConfirmRequest, type Observation,
  ProtocolError, type RiskLevel, type SessionInfo, type UiNode, isAction, isEmptyRect, nodeAt,
  observationIdOf, requiredCapabilities, validateCommand,
} from "./protocol";

export const MAX_OBSERVATION_AGE_MS = 60_000;

export interface DeviceContext {
  capabilities: CapabilityState[];
  session: SessionInfo;
  /** Latest observation and when it arrived (gateway clock). */
  latestObservation?: { observation: Observation; receivedAtMs: number };
  nowMs: number;
}

export type Decision =
  | { kind: "allow"; risk: RiskLevel }
  | { kind: "confirm"; request: ConfirmRequest }
  | { kind: "deny"; error: ProtocolError };

const deny = (code: ProtocolError["code"], message: string): Decision => ({ kind: "deny", error: new ProtocolError(code, message) });

const AGENT_DETAIL = "Requested by an AI agent connected through Latch.";

export function evaluate(command: Command, ctx: DeviceContext): Decision {
  try {
    validateCommand(command);
  } catch (e) {
    return { kind: "deny", error: e as ProtocolError };
  }
  if (ctx.session.paused) return deny("device_unavailable", "the owner paused this session");
  if (ctx.session.expires_at_ms <= ctx.nowMs) return deny("device_unavailable", "the session on the phone has ended");

  for (const capability of requiredCapabilities(command)) {
    const status = ctx.capabilities.find((c) => c.capability === capability)?.status;
    if (status === "enabled") continue;
    if (status === "disabled") {
      return deny("permission_missing", `the owner has not allowed '${capability}' (${CAPABILITY_DESCRIPTIONS[capability]})`);
    }
    if (status === "needs_permission") {
      return deny("permission_missing", `'${capability}' needs an Android or iOS permission the owner has not granted`);
    }
    return deny("unsupported_capability", `this device does not support '${capability}'`);
  }

  let observation: Observation | undefined;
  const cited = observationIdOf(command);
  if (cited !== undefined) {
    const latest = ctx.latestObservation;
    if (!latest) return deny("stale_observation", "observe the screen before acting on it");
    if (latest.observation.observation_id !== cited) return deny("stale_observation", "that is not the latest observation of this device");
    if (ctx.nowMs - latest.receivedAtMs > MAX_OBSERVATION_AGE_MS) return deny("stale_observation", "that observation is more than 60 seconds old");
    observation = latest.observation;
  }

  let assessment: Assessment;
  try {
    assessment = assess(command, observation);
  } catch (e) {
    return { kind: "deny", error: e as ProtocolError };
  }
  if (assessment.risk === "high" || (ctx.session.approve_every_action && isAction(command))) {
    return { kind: "confirm", request: assessment };
  }
  return { kind: "allow", risk: assessment.risk };
}

interface Assessment { risk: RiskLevel; title: string; detail: string }

const GLOBAL_NAMES = { back: "Back", home: "Home", recents: "Recents" } as const;

function assess(command: Command, observation: Observation | undefined): Assessment {
  const low = (title: string): Assessment => ({ risk: "low", title, detail: AGENT_DETAIL });
  const need = () => {
    if (!observation) throw new ProtocolError("internal", "action evaluated without an observation");
    return observation;
  };
  switch (command.name) {
    case "device.info": return low("Read device information");
    case "ui.observe": return low(command.params.include_screenshot ? "Read the screen and take a screenshot" : "Read the screen");
    case "app.list": return low("List installed apps");
    case "nav.global": return low(`Press ${GLOBAL_NAMES[command.params.action]}`);
    case "app.launch": return { risk: "medium", title: `Open ${command.params.package}`, detail: AGENT_DETAIL };
    case "input.swipe": {
      const obs = need();
      const { from, to } = command.params;
      checkOnScreen(obs, from.x, from.y);
      checkOnScreen(obs, to.x, to.y);
      if (nodeAt(obs, from.x, from.y)?.sensitive) throw sensitive();
      return { risk: "medium", title: "Swipe on the screen", detail: AGENT_DETAIL };
    }
    case "input.tap": {
      const obs = need();
      const verb = command.params.long_press ? "Long-press" : "Tap";
      const t = command.params.target;
      let node: UiNode | undefined;
      if ("element" in t) node = resolve(obs, t.element);
      else { checkOnScreen(obs, t.x, t.y); node = nodeAt(obs, t.x, t.y); }
      if (!node) return { risk: "medium", title: `${verb} on the screen`, detail: AGENT_DETAIL };
      if (node.sensitive) throw sensitive();
      const place = obs.package !== undefined ? ` in ${obs.package}` : "";
      const title = `${verb} “${labelOf(node)}”${place}`;
      if (isConsequential(node)) {
        return { risk: "high", title, detail: `${AGENT_DETAIL} This control may send, buy, delete, publish, or change something that is hard to undo.` };
      }
      return { risk: "medium", title, detail: AGENT_DETAIL };
    }
    case "input.type": {
      const obs = need();
      const node = resolve(obs, command.params.element);
      if (node.sensitive || looksLikeSecretField(node)) throw sensitive();
      if (!node.editable) throw new ProtocolError("invalid_request", "that element is not an editable text field");
      return { risk: "medium", title: `Type ${[...command.params.text].length} characters into “${labelOf(node)}”`, detail: AGENT_DETAIL };
    }
  }
}

const sensitive = () => new ProtocolError("sensitive_target", "that element is a password, PIN, one-time code, or payment field");

function resolve(obs: Observation, element: string): UiNode {
  const node = obs.nodes.find((n) => n.id === element);
  if (!node) throw new ProtocolError("target_not_found", "no element with that id in the observation");
  if (!node.enabled) throw new ProtocolError("target_not_found", "that element is disabled");
  if (isEmptyRect(node.bounds)) throw new ProtocolError("target_not_found", "that element is not visible");
  return node;
}

function checkOnScreen(obs: Observation, x: number, y: number) {
  if (!(x >= 0 && y >= 0 && x < obs.screen.width && y < obs.screen.height)) {
    throw new ProtocolError("invalid_request", `point (${x}, ${y}) is outside the ${obs.screen.width}x${obs.screen.height} screen`);
  }
}

/** Short human label for approval prompts. Untrusted text. */
export function labelOf(node: UiNode): string {
  const nonBlank = (t?: string) => (t !== undefined && t.trim() !== "" ? t : undefined);
  const raw = nonBlank(node.text) ?? nonBlank(node.description) ?? node.resource_id ?? node.role;
  const single = raw.split(/\s+/u).filter(Boolean).join(" ");
  const chars = [...single];
  return chars.length > 48 ? chars.slice(0, 48).join("") + "…" : single;
}

const words = (text: string) => text.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);

function matches(node: UiNode, single: readonly string[], phrases: readonly string[]): boolean {
  return [node.text, node.description, node.resource_id].some((field) => {
    if (field === undefined) return false;
    const ws = words(field);
    const joined = ` ${ws.join(" ")} `;
    const compact = ws.join("");
    return ws.some((w) => single.includes(w))
      || phrases.some((p) => joined.includes(` ${words(p).join(" ")} `))
      // resource ids like `btnSignOut` or `sign_out` collapse to one token
      || ["signout", "logout"].some((t) => single.includes(t) && compact.includes(t));
  });
}

export const isConsequential = (node: UiNode) => matches(node, WORDS.consequential_words, WORDS.consequential_phrases);
export const looksLikeSecretField = (node: UiNode) => matches(node, WORDS.secret_field_words, WORDS.secret_field_phrases);
