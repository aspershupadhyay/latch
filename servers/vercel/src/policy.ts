// Port of crates/policy. Must reach the same decision as the Rust engine for
// every case in packages/schemas/v1/policy/cases.json (see test/policy.test.ts).
// Screen content is untrusted: it may only raise scrutiny, never lower it.

import { WORDS } from "./generated/policy-words.js";
import {
  CAPABILITY_DESCRIPTIONS, MAX_REMEMBER_CHARS, type CapabilityState, type Command, type ConfirmRequest, type Observation,
  ProtocolError, type RiskLevel, type SessionInfo, type UiNode, isAction, isEmptyRect, nodeAt,
  observationIdOf, requiredCapabilities, validateCommand,
} from "./protocol.js";

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

interface Assessment { risk: RiskLevel; title: string; detail: string; remember?: string }

const CONSEQUENTIAL_DETAIL = "Requested by an AI agent connected through Latch. This control may send, call, post, delete, or change something that is hard to undo.";
const CRITICAL_DETAIL = "Requested by an AI agent connected through Latch. This involves money, app installs, permissions, or account deletion, so Latch asks every time.";

/** How much human attention an action needs (mirrors latch_policy::Consequence). */
export type Consequence = "none" | "consequential" | "critical";

const medium = (title: string): Assessment => ({ risk: "medium", title, detail: AGENT_DETAIL });

function judged(consequence: Consequence, title: string, key: string): Assessment {
  if (consequence === "consequential") {
    return { risk: "high", title, detail: CONSEQUENTIAL_DETAIL, remember: [...key].slice(0, MAX_REMEMBER_CHARS).join("") };
  }
  if (consequence === "critical") return { risk: "high", title, detail: CRITICAL_DETAIL };
  return medium(title);
}

const GLOBAL_NAMES = { back: "Back", home: "Home", recents: "Recents" } as const;

const place = (pkg: string | undefined) => (pkg !== undefined ? ` in ${pkg}` : "");

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
    case "app.launch": return medium(`Open ${command.params.package}`);
    case "input.pinch": {
      const obs = need();
      const { center, start_span: startSpan, end_span: endSpan } = command.params;
      checkOnScreen(obs, center.x, center.y);
      if (nodeAt(obs, center.x, center.y)?.sensitive) throw sensitive();
      return medium(`Pinch to zoom ${endSpan > startSpan ? "in" : "out"}${place(obs.package)}`);
    }
    case "input.swipe": {
      const obs = need();
      const { from, to } = command.params;
      checkOnScreen(obs, from.x, from.y);
      checkOnScreen(obs, to.x, to.y);
      if (nodeAt(obs, from.x, from.y)?.sensitive) throw sensitive();
      // In a phone app a swipe can answer, decline, or place a call.
      const consequence: Consequence = obs.package !== undefined && isCallPackage(obs.package) ? "consequential" : "none";
      const verb = (command.params.hold_ms ?? 0) > 0 ? "Drag" : "Swipe";
      return judged(consequence, `${verb} on the screen${place(obs.package)}`, `${verb.toLowerCase()}|${obs.package ?? "?"}|`);
    }
    case "input.tap": {
      const obs = need();
      const verb = command.params.long_press ? "Long-press" : command.params.double === true ? "Double-tap" : "Tap";
      const t = command.params.target;
      let node: UiNode | undefined;
      if ("element" in t) node = resolve(obs, t.element);
      else { checkOnScreen(obs, t.x, t.y); node = nodeAt(obs, t.x, t.y); }
      if (!node) {
        return judged(classify([], obs.package), `${verb} on the screen${place(obs.package)}`, `${verb.toLowerCase()}|${obs.package ?? "?"}|`);
      }
      if (node.sensitive) throw sensitive();
      const scope = scopeOf(obs, node);
      const found = scope.map(ownLabel).find((l) => l !== undefined);
      const label = found !== undefined ? shorten(found) : labelOf(node);
      return judged(
        classify(scope, obs.package),
        `${verb} “${label}”${place(obs.package)}`,
        `${verb.toLowerCase()}|${obs.package ?? "?"}|${label.toLowerCase()}`,
      );
    }
    case "input.type": {
      const obs = need();
      const node = resolve(obs, command.params.element);
      if (node.sensitive || looksLikeSecretField(node)) throw sensitive();
      if (!node.editable) throw new ProtocolError("invalid_request", "that element is not an editable text field");
      const count = [...command.params.text].length;
      if (command.params.submit !== true) return medium(`Type ${count} characters into “${labelOf(node)}”`);
      // Enter in a chat box sends; in a search box it searches. The field's own
      // text is what is being typed, so it is never used as its name.
      const name = fieldLabel(node);
      let consequence = classify([node], obs.package);
      if (consequence === "none" && !isSearchField(node)) consequence = "consequential";
      return judged(
        consequence,
        `Type ${count} characters into “${name}” and press Enter${place(obs.package)}`,
        `enter|${obs.package ?? "?"}|${name.toLowerCase()}`,
      );
    }
    case "ui.wait":
      return low(`Wait for “${shorten(command.params.text)}” to ${command.params.gone ? "disappear" : "appear"}`);
    case "ui.scroll_to": {
      const obs = need();
      if (command.params.container !== undefined && resolve(obs, command.params.container).sensitive) throw sensitive();
      return medium(`Scroll to “${shorten(command.params.text)}”${place(obs.package)}`);
    }
  }
}

/** Most descendants of a tapped element that are read for its meaning. */
const MAX_SCOPE_NODES = 64;

/**
 * The elements that say what a tap on `node` does: the node, what is drawn
 * inside it, and when none of those has a label, the nearest labeled ancestor.
 */
export function scopeOf(obs: Observation, node: UiNode): UiNode[] {
  const scope = [node];
  const frontier = [node.id];
  while (frontier.length > 0) {
    const parent = frontier.pop()!;
    for (const child of obs.nodes) {
      if (child.parent !== parent) continue;
      if (scope.length >= MAX_SCOPE_NODES) break;
      // Guard against malformed trees that loop back to an included node.
      if (scope.some((s) => s.id === child.id)) continue;
      scope.push(child);
      frontier.push(child.id);
    }
  }
  if (scope.every((n) => ownLabel(n) === undefined)) {
    let cursor = node.parent;
    for (let i = 0; i < 3 && cursor !== undefined; i++) {
      const parent = obs.nodes.find((n) => n.id === cursor);
      if (!parent) break;
      if (ownLabel(parent) !== undefined) { scope.push(parent); break; }
      cursor = parent.parent;
    }
  }
  return scope;
}

/** Judges what a tap acts on. Screen text only ever raises the outcome. */
export function classify(scope: UiNode[], pkg: string | undefined): Consequence {
  if ((pkg !== undefined && (WORDS.critical_packages as readonly string[]).includes(pkg))
    || scope.some((n) => matches(n, WORDS.critical_words, WORDS.critical_phrases))) return "critical";
  if ((pkg !== undefined && isCallPackage(pkg)) || scope.some((n) => isConsequential(n) || showsPhoneNumber(n))) return "consequential";
  return "none";
}

const isCallPackage = (pkg: string) => (WORDS.call_packages as readonly string[]).includes(pkg);

/** Tapping a phone number usually starts a call. */
export function showsPhoneNumber(node: UiNode): boolean {
  return [node.text, node.description].some((raw) => {
    if (raw === undefined) return false;
    const t = raw.trim();
    const digits = [...t].filter((c) => c >= "0" && c <= "9").length;
    return digits >= 7 && digits <= 15 && /^[0-9 +\-().\u00a0]*$/.test(t);
  });
}

/** A text field's name for prompts: its description or resource id, never its content. */
function fieldLabel(node: UiNode): string {
  const rid = node.resource_id !== undefined ? node.resource_id.split("/").pop() ?? node.resource_id : undefined;
  return shorten(nonBlank(node.description) ?? rid ?? node.role);
}

/** Search, address, and URL boxes, where Enter only looks something up. */
export function isSearchField(node: UiNode): boolean {
  const list = WORDS.search_field_words as readonly string[];
  return [node.description, node.resource_id].some((field) => {
    if (field === undefined) return false;
    const ws = words(field);
    const compact = ws.join("");
    return ws.some((w) => list.includes(w)) || list.some((w) => w.length >= 5 && compact.includes(w));
  });
}

const nonBlank = (t?: string) => (t !== undefined && t.trim() !== "" ? t : undefined);
const ownLabel = (n: UiNode) => nonBlank(n.text) ?? nonBlank(n.description);

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
  return shorten(nonBlank(node.text) ?? nonBlank(node.description) ?? node.resource_id ?? node.role);
}

/** One line of at most 48 characters, for approval prompts. */
function shorten(raw: string): string {
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
