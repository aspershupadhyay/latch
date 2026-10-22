// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Programs (Latch Turbo phase 2, R70, ADR-072): a whole task in one call.
// Mirrors servers/mcp/src/mcp/program.rs; both pass the shared cases in
// packages/schemas/v1/program. Every phone action runs through the ordinary
// tools, so policy, approvals, freshness, and redaction apply per step.

import { ApprovalDeferred } from "./devices.js";
import { type Observation, ProtocolError, SETTINGS_PANELS, type UiNode } from "./protocol.js";
import { quote, textResult } from "./render.js";
import { iconClass, labelParts, matchLevel, norm, usable } from "./skills.js";

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
type Obj = { [k: string]: Json };
type ToolResult = { content: { type: string; text?: string }[] } & Record<string, unknown>;

export const PROGRAM_TOOL = "run_program";
const MAX_TOP_STEPS = 50;
const MAX_TOTAL_STEPS = 120;
const MAX_DEPTH = 3;
const MAX_ITEMS = 30;
const MAX_REPEAT = 50;
const MAX_STR = 200;
const MAX_CANDIDATES = 5;
const MAX_ACTIONS = 80;
const RUN_BUDGET_MS = 240_000;

const KINDS = ["open", "go", "tap", "type", "find", "toggle", "scroll", "back", "home", "wait", "expect", "read", "if", "for_each", "call", "ask_owner"];
const ROLES = ["button", "input", "row", "card", "switch", "tab", "video", "image", "plus", "minus", "send", "back", "search", "close", "cart", "heart", "share", "menu"];
export const ROUTES = ["media", "volume", "flashlight", "brightness", "ringer", "do_not_disturb", "panel", "link", "clipboard"];
const POPUP_WORDS = ["not now", "no thanks", "no, thanks", "maybe later", "later", "skip", "dismiss", "close", "got it", "remind me later"];

const KIND_FIELDS: Record<string, string[]> = {
  open: ["app", "fresh"], go: ["link", "app"], tap: ["sel", "repeat", "long"], type: ["sel", "text", "submit"], find: ["sel"],
  toggle: ["sel", "on"], scroll: ["direction", "sel", "until"], wait: ["sel", "gone", "timeout_ms"], expect: ["cond", "timeout_ms"],
  read: ["sel", "as"], if: ["cond", "then", "else"], for_each: ["items", "as", "steps"], call: ["route", "args"], ask_owner: ["message"],
};
const REQUIRED: Record<string, string[]> = {
  open: ["app"], go: ["link"], tap: ["sel"], find: ["sel"], read: ["sel"], type: ["text"], toggle: ["sel", "on"], scroll: ["direction"],
  expect: ["cond"], if: ["cond", "then"], for_each: ["items", "steps"], call: ["route"], ask_owner: ["message"],
};

// ---- Validation ----

const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v);
/** Keys in Rust's order (serde_json maps are sorted), so the same error comes first. */
const keys = (o: Obj) => Object.keys(o).sort();
const truncate = (s: string, max: number) => [...s].slice(0, max).join("");

function bounded(v: unknown, what: string): string {
  if (typeof v !== "string") throw new Error(`${what} must be a string`);
  if (v.trim() === "" || [...v].length > MAX_STR || /\p{Cc}/u.test(v)) {
    throw new Error(`${what} must be 1 to ${MAX_STR} characters without control characters`);
  }
  return v;
}
const isIdent = (s: string) => /^[a-z][a-z0-9_]*$/.test(s) && s.length <= 24;
function flag(v: unknown, what: string): boolean {
  if (typeof v !== "boolean") throw new Error(`${what} must be true or false`);
  return v;
}

/** A selector in canonical form: a string means its words. */
export function selector(v: unknown, depth: number): Obj {
  if (typeof v === "string") return { text: bounded(v, "selector") };
  if (!isObj(v)) throw new Error("a selector is words or an object with text, desc, id, role, near, inside, index, state");
  if (Object.keys(v).length === 0) throw new Error("a selector needs at least one field");
  const out: Obj = {};
  for (const key of keys(v)) {
    const value = v[key];
    switch (key) {
      case "text": case "desc": case "id":
        out[key] = bounded(value, `selector ${key}`);
        break;
      case "role":
        if (typeof value !== "string" || !ROLES.includes(value)) throw new Error(`selector role must be one of ${ROLES.join(", ")}`);
        out[key] = value;
        break;
      case "near": case "inside":
        if (depth >= 1) throw new Error(`selector ${key} cannot nest another near or inside`);
        out[key] = selector(value, depth + 1);
        break;
      case "index":
        if (!isInt(value) || value < 1 || value > 50) throw new Error("selector index must be 1 to 50");
        out[key] = value;
        break;
      case "state": {
        if (!isObj(value)) throw new Error("selector state is an object, e.g. {\"checked\": true}");
        const clean: Obj = {};
        for (const k of keys(value)) {
          if (!["checked", "selected", "enabled", "focused"].includes(k)) {
            throw new Error(`selector state '${truncate(k, 24)}' must be checked, selected, enabled, or focused`);
          }
          clean[k] = flag(value[k], `selector state ${k}`);
        }
        if (Object.keys(clean).length === 0) throw new Error("selector state needs a field");
        out[key] = clean;
        break;
      }
      default:
        throw new Error(`unknown selector field '${truncate(key, 24)}'`);
    }
  }
  if (Object.keys(out).every((k) => ["index", "near", "inside", "state"].includes(k))) throw new Error("a selector needs text, desc, id, or role");
  return out;
}

/** A condition in canonical form (for expect and if). */
export function condition(v: unknown, depth: number): Obj {
  if (!isObj(v) || Object.keys(v).length !== 1) {
    throw new Error("a condition is one of {text}, {gone}, {state}, {app}, {count}, {playing}, {var}, {not}");
  }
  const key = Object.keys(v)[0]!;
  const value = v[key];
  let clean: Json;
  switch (key) {
    case "text": case "gone":
      clean = selector(value, 0);
      break;
    case "state": {
      if (!isObj(value)) throw new Error("state takes {sel, checked|selected|enabled|focused}");
      if (value.sel === undefined) throw new Error("state needs sel");
      const c: Obj = { sel: selector(value.sel, 0) };
      for (const k of keys(value)) {
        if (k === "sel") continue;
        if (!["checked", "selected", "enabled", "focused"].includes(k)) throw new Error(`unknown state field '${truncate(k, 24)}'`);
        c[k] = flag(value[k], `state ${k}`);
      }
      if (Object.keys(c).length < 2) throw new Error("state needs checked, selected, enabled, or focused");
      clean = c;
      break;
    }
    case "app":
      clean = bounded(value, "app");
      break;
    case "count": {
      if (!isObj(value)) throw new Error("count takes {sel, min?, max?}");
      if (value.sel === undefined) throw new Error("count needs sel");
      const c: Obj = { sel: selector(value.sel, 0) };
      for (const k of keys(value)) {
        if (k === "sel") continue;
        if (k !== "min" && k !== "max") throw new Error(`unknown count field '${truncate(k, 24)}'`);
        const n = value[k];
        if (!isInt(n) || n < 0 || n > 500) throw new Error(`count ${k} must be 0 to 500`);
        c[k] = n;
      }
      clean = c;
      break;
    }
    case "playing":
      clean = flag(value, "playing");
      break;
    case "var": {
      if (!isObj(value)) throw new Error("var takes {name, equals | contains}");
      const name = typeof value.name === "string" ? value.name : "";
      if (!isIdent(name)) throw new Error("var name is a lowercase identifier");
      const c: Obj = { name };
      for (const k of keys(value)) {
        if (k === "name") continue;
        if (k !== "equals" && k !== "contains") throw new Error(`unknown var field '${truncate(k, 24)}'`);
        c[k] = bounded(value[k], `var ${k}`);
      }
      if (Object.keys(c).length !== 2) throw new Error("var takes exactly one of equals or contains");
      clean = c;
      break;
    }
    case "not":
      if (depth >= 2) throw new Error("not cannot nest that deep");
      clean = condition(value, depth + 1);
      break;
    default:
      throw new Error(`unknown condition '${truncate(key, 24)}'`);
  }
  return { [key]: clean };
}

/** Checks a program and returns it in canonical form. */
export function validate(program: unknown): Obj[] {
  if (!Array.isArray(program)) throw new Error("steps must be a list");
  if (program.length === 0 || program.length > MAX_TOP_STEPS) throw new Error(`1 to ${MAX_TOP_STEPS} steps`);
  const total = { n: 0 };
  return validateBlock(program, 1, total);
}

function validateBlock(steps: unknown[], depth: number, total: { n: number }): Obj[] {
  return steps.map((step) => {
    total.n++;
    if (total.n > MAX_TOTAL_STEPS) throw new Error(`at most ${MAX_TOTAL_STEPS} steps in all`);
    return validateStep(step, depth, total);
  });
}

function validateStep(step: unknown, depth: number, total: { n: number }): Obj {
  if (!isObj(step)) throw new Error("each step must be an object");
  const kind = typeof step.do === "string" ? step.do : "";
  if (!KINDS.includes(kind)) throw new Error(`each step needs do: one of ${KINDS.join(", ")}`);
  const allowed = KIND_FIELDS[kind] ?? [];
  for (const key of keys(step)) {
    if (!["do", "optional", "note"].includes(key) && !allowed.includes(key)) throw new Error(`step ${kind}: unknown field '${truncate(key, 32)}'`);
  }
  for (const req of REQUIRED[kind] ?? []) {
    if (!(req in step)) throw new Error(`step ${kind}: '${req}' is required`);
  }
  const clean: Obj = { do: kind };
  for (const key of keys(step)) {
    const value = step[key];
    const what = `step ${kind}: ${key}`;
    switch (key) {
      case "do": continue;
      case "optional": case "fresh": case "long": case "submit": case "gone": case "on":
        clean[key] = flag(value, what);
        break;
      case "sel": case "until":
        clean[key] = prefixed(kind, () => selector(value, 0));
        break;
      case "cond":
        clean[key] = prefixed(kind, () => condition(value, 0));
        break;
      case "timeout_ms":
        if (!isInt(value) || value < 100 || value > 30_000) throw new Error(`${what} must be 100 to 30000`);
        clean[key] = value;
        break;
      case "repeat":
        if (typeof value === "number") {
          if (!isInt(value) || value < 0 || value > MAX_REPEAT) throw new Error(`${what} must be 0 to ${MAX_REPEAT}`);
          clean[key] = value;
        } else clean[key] = bounded(value, what);
        break;
      case "direction":
        if (typeof value !== "string" || !["up", "down", "left", "right"].includes(value)) throw new Error(`${what} must be up, down, left, or right`);
        clean[key] = value;
        break;
      case "as":
        if (typeof value !== "string" || !isIdent(value)) throw new Error(`${what} is a lowercase name, e.g. price`);
        clean[key] = value;
        break;
      case "route":
        if (typeof value !== "string" || !ROUTES.includes(value)) throw new Error(`${what} must be one of ${ROUTES.join(", ")}`);
        clean[key] = value;
        break;
      case "args": {
        if (!isObj(value)) throw new Error(`${what} must be an object`);
        if (Object.keys(value).length > 4) throw new Error(`${what} takes at most 4 fields`);
        for (const k of keys(value)) {
          const a = value[k];
          if (typeof a !== "boolean" && typeof a !== "number") bounded(a, `${what}.${k}`);
        }
        clean[key] = value;
        break;
      }
      case "items": {
        if (!Array.isArray(value)) throw new Error(`${what} must be a list`);
        if (value.length === 0 || value.length > MAX_ITEMS) throw new Error(`${what} takes 1 to ${MAX_ITEMS} items`);
        for (const item of value) {
          if (typeof item === "string") bounded(item, what);
          else if (typeof item === "number") { /* any number */ }
          else if (isObj(item) && Object.keys(item).length <= 6) {
            for (const k of keys(item)) {
              if (!isIdent(k)) throw new Error(`${what}: field names are lowercase identifiers`);
              if (typeof item[k] !== "number") bounded(item[k], `${what}.${k}`);
            }
          } else throw new Error(`${what}: each item is text, a number, or an object of up to 6 fields`);
        }
        clean[key] = value as Json;
        break;
      }
      case "then": case "else": case "steps": {
        if (depth >= MAX_DEPTH) throw new Error(`step ${kind}: blocks nest at most ${MAX_DEPTH} deep`);
        if (!Array.isArray(value)) throw new Error(`${what} must be a list of steps`);
        if (value.length === 0 || value.length > 20) throw new Error(`${what} takes 1 to 20 steps`);
        clean[key] = validateBlock(value, depth + 1, total);
        break;
      }
      case "app": case "link": case "text": case "message": case "note":
        clean[key] = bounded(value, what);
        break;
      default:
        throw new Error(`step ${kind}: unknown field '${truncate(key, 32)}'`);
    }
  }
  if (kind === "for_each" && clean.as === undefined) clean.as = "item";
  if (kind === "go" && !String(clean.link).includes(" > ") && clean.app === undefined) {
    throw new Error("step go: a link needs app, the package that opens it");
  }
  return clean;
}

function prefixed<T>(kind: string, f: () => T): T {
  try {
    return f();
  } catch (e) {
    throw new Error(`step ${kind}: ${(e as Error).message}`);
  }
}

// ---- Selectors on the live screen ----

export type Want = "tap" | "type" | "any";
export type Resolve = { found: string } | { ambiguous: string[] } | "not_found";

const fold = (s: string) =>
  [...s].filter((c) => /[\p{Alphabetic}\p{N}\s]/u.test(c)).join("").trim().split(/\s+/u).filter(Boolean).join(" ").toLowerCase();

function wordsLevel(c: string, t: string): number {
  const exact = matchLevel(norm(c), norm(t));
  if (exact > 0) return exact;
  const fc = fold(c);
  const ft = fold(t);
  if (ft === "") return 0;
  if (fc === ft) return 3;
  return fc.includes(ft) ? 1 : 0;
}

function textScore(node: UiNode, target: string): number {
  let best = 0;
  for (const t of target.split("||")) {
    if (t.trim() !== "") best = Math.max(best, textScoreOne(node, t.trim()));
  }
  return best;
}

function textScoreOne(node: UiNode, t: string): number {
  let own = 0;
  for (const c of [node.text, node.description]) if (c !== undefined) own = Math.max(own, wordsLevel(c, t));
  if (own > 0) return own;
  if (node.label === undefined) return 0;
  const head = labelParts(node.label)[0];
  const level = wordsLevel(head, t);
  if (level > 0) return level;
  const cls = iconClass(norm(t));
  const nh = norm(head);
  return cls !== undefined && (iconClass(nh) === cls || nh.split(" ").some((w) => iconClass(w) === cls)) ? 2 : 0;
}

function hasWord(node: UiNode, cls: string): boolean {
  const fields = [node.text, node.description, node.label === undefined ? undefined : labelParts(node.label)[0],
    node.resource_id === undefined ? undefined : node.resource_id.split("/").pop()];
  return fields.some((f) => {
    if (f === undefined) return false;
    const n = norm(f.replace(/[_-]/g, " "));
    return iconClass(n) === cls || n.split(" ").some((w) => iconClass(w) === cls);
  });
}

function roleMatches(node: UiNode, role: string): boolean {
  const r = node.role;
  switch (role) {
    case "button": return r.includes("Button") || (node.clickable && !node.editable && node.checked === undefined);
    case "input": return node.editable;
    case "switch": return node.checked !== undefined;
    case "tab": return r.includes("Tab");
    case "row": return node.position !== undefined;
    case "card": return node.clickable && (r.endsWith("Layout") || r.includes("ViewGroup") || r.includes("Card") || r === "View");
    case "video": return ["SurfaceView", "TextureView", "VideoView"].includes(r) || r.includes("Video") || r.includes("Player");
    case "image": return r.includes("Image");
    default: return hasWord(node, role);
  }
}

function stateMatches(node: UiNode, state: Obj): boolean {
  return Object.entries(state).every(([k, v]) => {
    const want = v === true;
    switch (k) {
      case "checked": return node.checked === want;
      case "selected": return (node.selected ?? false) === want;
      case "enabled": return node.enabled === want;
      case "focused": return node.focused === want;
      default: return false;
    }
  });
}

function score(node: UiNode, sel: Obj): number {
  let total = 0;
  const s = (k: string) => (typeof sel[k] === "string" ? (sel[k] as string) : undefined);
  const t = s("text");
  if (t !== undefined) {
    const level = textScore(node, t);
    if (level === 0) return 0;
    total += level * 10;
  }
  const d = s("desc");
  if (d !== undefined) {
    const level = node.description === undefined ? 0 : wordsLevel(node.description, d);
    if (level === 0) return 0;
    total += level * 10;
  }
  const id = s("id");
  if (id !== undefined) {
    const rid = (node.resource_id ?? "").toLowerCase();
    const want = id.toLowerCase();
    const entry = rid.split("/").pop() ?? "";
    const level = entry === want || rid === want ? 3 : rid.includes(want) ? 2 : 0;
    if (level === 0) return 0;
    total += level * 10;
  }
  const role = s("role");
  if (role !== undefined) {
    if (!roleMatches(node, role)) return 0;
    total += 5;
  }
  if (isObj(sel.state) && !stateMatches(node, sel.state)) return 0;
  return total;
}

const area = (n: UiNode) => {
  const b = n.bounds;
  return b.right <= b.left || b.bottom <= b.top ? 0 : (b.right - b.left) * (b.bottom - b.top);
};
const center = (n: UiNode): [number, number] => [
  n.bounds.left + Math.trunc((n.bounds.right - n.bounds.left) / 2),
  n.bounds.top + Math.trunc((n.bounds.bottom - n.bounds.top) / 2),
];

function ancestors(node: UiNode, byId: Map<string, UiNode>): string[] {
  const out: string[] = [];
  let parent = node.parent;
  while (parent !== undefined) {
    if (out.length >= 64 || out.includes(parent)) break;
    out.push(parent);
    parent = byId.get(parent)?.parent;
  }
  return out;
}

/** What the AI sees for a candidate: its id and its words. */
export function describe(node: UiNode): string {
  return `${node.id}: ${truncate(node.text ?? node.description ?? node.label ?? node.role, 80)}`;
}

function matches(nodes: UiNode[], sel: Obj, want: Want): [UiNode, number][] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const page = Math.max(0, ...nodes.map(area));
  let inside: string[] | undefined;
  if (isObj(sel.inside)) {
    const inner = sel.inside;
    inside = [];
    for (const n of nodes.filter((x) => score(x, inner) > 0)) {
      inside.push(n.id);
      for (const a of ancestors(n, byId)) {
        const p = byId.get(a);
        if (p === undefined || area(p) * 10 >= page * 6) break;
        inside.push(a);
      }
    }
  }
  let found: [UiNode, number][] = nodes
    .filter((n) => usable(n) && (want !== "type" || n.editable))
    .map((n): [UiNode, number] => [n, score(n, sel)])
    .filter(([, s]) => s > 0)
    .filter(([n]) => inside === undefined || ancestors(n, byId).some((a) => inside!.includes(a)));
  const ids = found.map(([n]) => n.id);
  found = found.filter(([n]) => !ancestors(n, byId).some((a) => ids.includes(a)));
  if (want === "tap" && found.some(([n]) => n.clickable || n.long_clickable)) {
    const clickableAncestor = (n: UiNode) => ancestors(n, byId).some((a) => byId.get(a)?.clickable === true);
    found = found.filter(([n]) => n.clickable || n.long_clickable || clickableAncestor(n));
  }
  return found;
}

/** Finds the one element a selector means; two equally good fits are ambiguous. */
export function resolve(nodes: UiNode[], sel: Obj, want: Want): Resolve {
  let found = matches(nodes, sel, want);
  if (found.length === 0) return "not_found";
  if (isInt(sel.index)) {
    found.sort(([a], [b]) => a.bounds.top - b.bounds.top || a.bounds.left - b.bounds.left);
    const pick = found[sel.index - 1];
    return pick === undefined ? "not_found" : { found: pick[0].id };
  }
  let anchor: { at: [number, number]; words: string | undefined } | undefined;
  if (isObj(sel.near)) {
    const nearSel = sel.near;
    const pool = matches(nodes, nearSel, "any").filter(([n]) => !n.editable);
    let best: [UiNode, number] | undefined;
    for (const c of pool) {
      if (best === undefined || c[1] > best[1] ||
        (c[1] === best[1] && (c[0].bounds.top < best[0].bounds.top || (c[0].bounds.top === best[0].bounds.top && c[0].bounds.left < best[0].bounds.left)))) best = c;
    }
    if (best === undefined) return "not_found";
    anchor = { at: center(best[0]), words: typeof nearSel.text === "string" ? norm(nearSel.text) : undefined };
  }
  const key = (n: UiNode, s: number): [boolean, number, number] => {
    if (anchor === undefined) return [false, s, 0];
    const [cx, cy] = center(n);
    const distance = Math.abs(cy - anchor.at[1]) * 2 + Math.abs(cx - anchor.at[0]);
    const words = anchor.words;
    const inCard = words !== undefined && n.label !== undefined && norm(labelParts(n.label)[1]).includes(words);
    return [inCard, s, distance];
  };
  found = [...found].sort(([a, sa], [b, sb]) => {
    const ka = key(a, sa);
    const kb = key(b, sb);
    return Number(kb[0]) - Number(ka[0]) || kb[1] - ka[1] || ka[2] - kb[2] || a.bounds.top - b.bounds.top || a.bounds.left - b.bounds.left;
  });
  const [best, bestScore] = found[0]!;
  const second = found[1];
  if (second !== undefined) {
    const kb = key(best, bestScore);
    const ks = key(second[0], second[1]);
    const close = kb[0] === ks[0] && kb[1] === ks[1] && (anchor === undefined || (ks[2] - kb[2]) * 10 <= Math.max(ks[2], 1));
    if (close) {
      return {
        ambiguous: found.slice(0, MAX_CANDIDATES).filter(([n, s]) => {
          const k = key(n, s);
          return k[0] === kb[0] && k[1] === kb[1];
        }).map(([n]) => describe(n)),
      };
    }
  }
  return { found: best.id };
}

const scalarText = (v: Json | undefined): string =>
  v === undefined || v === null ? "" : typeof v === "string" ? v : JSON.stringify(v);

/** Whether a condition holds on the screen with these values. */
export function holds(obs: Observation, vars: Obj, cond: Obj): boolean {
  const key = Object.keys(cond)[0];
  if (key === undefined) return false;
  const value = cond[key];
  const nodes = obs.nodes;
  switch (key) {
    case "text": return isObj(value) && resolve(nodes, value, "any") !== "not_found";
    case "gone": return isObj(value) && resolve(nodes, value, "any") === "not_found";
    case "state": {
      if (!isObj(value) || !isObj(value.sel)) return false;
      const { sel, ...state } = value;
      const first = matches(nodes, sel, "any")[0];
      return first !== undefined && stateMatches(first[0], state);
    }
    case "app": return obs.package === value;
    case "count": {
      if (!isObj(value) || !isObj(value.sel)) return false;
      const n = matches(nodes, value.sel, "any").length;
      return (!isInt(value.min) || n >= value.min) && (!isInt(value.max) || n <= value.max);
    }
    case "playing": {
      const pause = nodes.some((n) => usable(n) &&
        [n.text, n.description, n.label === undefined ? undefined : labelParts(n.label)[0]].some((w) => w !== undefined && norm(w) === "pause"));
      return pause === (value !== false);
    }
    case "var": {
      if (!isObj(value)) return false;
      const have = scalarText(vars[String(value.name)]);
      if (typeof value.equals === "string") return norm(have) === norm(value.equals);
      if (typeof value.contains === "string") return norm(have).includes(norm(value.contains));
      return false;
    }
    case "not": return isObj(value) && !holds(obs, vars, value);
    default: return false;
  }
}

/** Fills {name} and {name.field} from vars; other braces stay as written. */
export function fill(template: string, vars: Obj): string {
  let out = "";
  let rest = template;
  for (;;) {
    const start = rest.indexOf("{");
    if (start < 0) break;
    out += rest.slice(0, start);
    const after = rest.slice(start + 1);
    const end = after.indexOf("}");
    if (end < 0) return out + rest.slice(start);
    const inner = after.slice(0, end);
    const dot = inner.indexOf(".");
    const name = dot < 0 ? inner : inner.slice(0, dot);
    const field = dot < 0 ? undefined : inner.slice(dot + 1);
    if (isIdent(name) && (field === undefined || isIdent(field))) {
      if (!(name in vars)) throw new Error(`{${inner}}: no value named ${name} yet`);
      let value: Json | undefined = vars[name];
      if (field !== undefined) {
        if (!isObj(value) || !(field in value)) throw new Error(`{${inner}}: ${name} has no field ${field}`);
        value = value[field];
      }
      out += scalarText(value);
    } else {
      out += rest.slice(start, start + end + 2);
    }
    rest = after.slice(end + 1);
  }
  return out + rest;
}

function fillValue(v: Json, vars: Obj): Json {
  if (typeof v === "string") return fill(v, vars);
  if (Array.isArray(v)) return v.map((x) => fillValue(x, vars));
  if (isObj(v)) return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, fillValue(x, vars)]));
  return v;
}

function fillStep(step: Obj, vars: Obj): Obj {
  return Object.fromEntries(Object.entries(step).map(([k, v]) =>
    [k, ["then", "else", "steps", "note", "do"].includes(k) ? v : fillValue(v, vars)]));
}

/** The dismiss button of a pop-up on screen, if one is shown. */
export function popupDismiss(nodes: UiNode[]): string | undefined {
  return nodes.find((n) => usable(n) && n.clickable &&
    [n.text, n.description].some((w) => w !== undefined && POPUP_WORDS.includes(norm(w))))?.id;
}

function switchFor(nodes: UiNode[], id: string): UiNode | undefined {
  const node = nodes.find((n) => n.id === id);
  if (node === undefined) return undefined;
  if (node.checked !== undefined) return node;
  const row = node.bounds;
  const [rx, ry] = center(node);
  let best: UiNode | undefined;
  let bestD = Infinity;
  for (const n of nodes) {
    if (n.checked === undefined || !usable(n)) continue;
    const [cx, cy] = center(n);
    if (!(n.parent === id || (cy >= row.top && cy <= row.bottom))) continue;
    const d = Math.abs(cy - ry) + Math.abs(cx - rx);
    if (d < bestD) { best = n; bestD = d; }
  }
  return best;
}

function pickApp(listing: string, name: string): string | string[] {
  const apps: [string, string][] = [];
  for (const line of listing.split("\n")) {
    if (!line.startsWith("- ")) continue;
    const l = line.slice(2);
    const sp = l.indexOf(" ");
    if (sp < 0) continue;
    apps.push([l.slice(0, sp), l.slice(sp + 1).trim().replace(/^"+|"+$/g, "")]);
  }
  const wanted = norm(name);
  const exact = apps.filter(([, label]) => norm(label) === wanted);
  if (exact.length === 1) return exact[0]![0];
  if (exact.length === 0 && apps.length === 1) return apps[0]![0];
  return apps.slice(0, MAX_CANDIDATES).map(([p, l]) => `${p} ${quote(l, 40)}`);
}

function selectorText(sel: Json | undefined): string {
  if (isObj(sel) && Object.keys(sel).length === 1 && typeof sel.text === "string") return quote(sel.text, 60);
  return JSON.stringify(sel ?? null);
}

function label(step: Obj): string {
  const s = (k: string) => (typeof step[k] === "string" ? (step[k] as string) : "");
  switch (s("do")) {
    case "tap": {
      let t = `${step.long === true ? "long-press" : "tap"} ${selectorText(step.sel)}`;
      if (step.repeat !== undefined && step.repeat !== 1) t += ` ×${scalarText(step.repeat)}`;
      return t;
    }
    case "type": {
      let t = `type ${quote(s("text"), 60)}`;
      if (step.sel !== undefined) t += ` into ${selectorText(step.sel)}`;
      if (step.submit === true) t += " and submit";
      return t;
    }
    case "find": return `find ${selectorText(step.sel)}`;
    case "toggle": return `turn ${selectorText(step.sel)} ${step.on === true ? "on" : "off"}`;
    case "scroll": return step.until !== undefined ? `scroll ${s("direction")} to ${selectorText(step.until)}` : `scroll ${s("direction")}`;
    case "wait":
      if (step.sel === undefined) return "wait for the screen to settle";
      return step.gone === true ? `wait until ${selectorText(step.sel)} is gone` : `wait for ${selectorText(step.sel)}`;
    case "expect": return `expect ${JSON.stringify(step.cond)}`;
    case "if": return `if ${JSON.stringify(step.cond)}`;
    case "ask_owner": return `ask the owner: ${quote(s("message"), 80)}`;
    default: return s("do");
  }
}

// ---- Running ----

export interface ProgramContext {
  deviceId: string;
  act: (tool: string, args: Record<string, unknown>) => Promise<ToolResult>;
  latest: () => Promise<Observation | undefined>;
  deferred: (e: ApprovalDeferred, tool: string, args: Record<string, unknown>) => Promise<ToolResult>;
}

type Stop =
  | { kind: "failed"; path: string; command: string; selector?: Json; reason: string; message: string; candidates: string[]; tried: string[] }
  | { kind: "error"; path: string; command: string; error: ProtocolError }
  | { kind: "owner"; path: string; said: string }
  | { kind: "paused"; path: string }
  | { kind: "waiting"; path: string; result: ToolResult };

class StopRun extends Error {
  constructor(readonly stop: Stop) {
    super("stop");
  }
}

const pathText = (p: number[]) => p.join(".");
function parsePath(s: string): number[] | undefined {
  const parts = s.split(".").map((x) => (/^\d+$/.test(x) ? Number(x) : NaN));
  return parts.length > 0 && parts.length <= 7 && parts.every((n) => Number.isInteger(n) && n >= 1) ? parts : undefined;
}
const startsWith = (a: number[], prefix: number[]) => prefix.length <= a.length && prefix.every((x, i) => a[i] === x);
function less(a: number[], b: number[]): boolean {
  for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i]! < b[i]!;
  return a.length < b.length;
}
const firstText = (v: ToolResult) => v.content?.[0]?.text ?? "";
const isHeadline = (part: { text?: string }) => typeof part.text === "string" && part.text.endsWith("The screen after the action:");

class Runner {
  vars: Obj;
  actions = 0;
  readonly started = Date.now();
  lines: string[] = [];
  last: ToolResult | undefined;
  resume: number[] | undefined;
  routes: string[] = [];

  constructor(readonly ctx: ProgramContext, vars: Obj, resume: number[] | undefined) {
    this.vars = vars;
    this.resume = resume;
  }

  async act(path: number[], command: string, tool: string, args: Record<string, unknown>): Promise<ToolResult> {
    this.actions++;
    try {
      const v = await this.ctx.act(tool, { ...args, device_id: this.ctx.deviceId });
      this.last = v;
      return v;
    } catch (e) {
      if (e instanceof ApprovalDeferred) {
        const result = await this.ctx.deferred(e, tool, { ...args, device_id: this.ctx.deviceId });
        throw new StopRun({ kind: "waiting", path: pathText(path), result });
      }
      if (e instanceof ProtocolError) throw new StopRun({ kind: "error", path: pathText(path), command, error: e });
      throw e;
    }
  }

  /** One tool call whose protocol error is handled by the caller. */
  async tryAct(path: number[], command: string, tool: string, args: Record<string, unknown>): Promise<ToolResult | ProtocolError> {
    try {
      return await this.act(path, command, tool, args);
    } catch (e) {
      if (e instanceof StopRun && e.stop.kind === "error") return e.stop.error;
      throw e;
    }
  }

  async screen(path: number[], command: string, fresh: boolean): Promise<Observation> {
    if (!fresh) {
      const obs = await this.ctx.latest();
      if (obs) return obs;
    }
    await this.act(path, command, "observe", { screenshot: false });
    const obs = await this.ctx.latest();
    if (!obs) throw new StopRun({ kind: "error", path: pathText(path), command, error: new ProtocolError("device_unavailable", "could not read the screen") });
    return obs;
  }

  overBudget(): boolean {
    return this.actions >= MAX_ACTIONS || Date.now() - this.started > RUN_BUDGET_MS;
  }

  skip(path: number[]): boolean {
    const r = this.resume;
    if (r === undefined) return false;
    if (startsWith(r, path)) {
      if (r.length === path.length) this.resume = undefined;
      return false;
    }
    if (less(path, r)) return true;
    this.resume = undefined;
    return false;
  }

  failed(path: number[], command: string, selector: Json | undefined, reason: string, message: string, candidates: string[] = [], tried: string[] = []): StopRun {
    return new StopRun({ kind: "failed", path: pathText(path), command, ...(selector === undefined ? {} : { selector }), reason, message, candidates, tried });
  }

  async locate(path: number[], command: string, sel: Obj, want: Want): Promise<[string, string]> {
    const tried: string[] = [];
    for (const attempt of ["first", "settle", "popup", "scroll", "retry"]) {
      const fresh = attempt === "settle" || attempt === "retry";
      if (attempt === "popup") {
        const obs = await this.ctx.latest();
        const dismiss = obs === undefined ? undefined : popupDismiss(obs.nodes);
        if (obs === undefined || dismiss === undefined) continue;
        tried.push("popup");
        await this.act(path, command, "tap", { observation_id: obs.observation_id, element_id: dismiss });
      } else if (attempt === "scroll") {
        const near = isObj(sel.near) ? sel.near.text : undefined;
        const goal = typeof sel.text === "string" ? sel.text : typeof near === "string" ? near : undefined;
        const obs = await this.ctx.latest();
        if (goal === undefined || obs === undefined) continue;
        tried.push("scroll");
        // Not found or nothing to scroll: the look below decides.
        await this.tryAct(path, command, "scroll_to", { observation_id: obs.observation_id, text: goal, max_swipes: 4 });
      } else if (attempt !== "first") {
        tried.push(attempt);
      }
      const obs = await this.screen(path, command, fresh);
      const r = resolve(obs.nodes, sel, want);
      if (r !== "not_found" && "found" in r) return [obs.observation_id, r.found];
      if (r !== "not_found") {
        throw this.failed(path, command, sel, "ambiguous", "two or more elements fit equally well; add near, inside, role, or index", r.ambiguous, tried);
      }
      if (this.overBudget()) break;
    }
    throw this.failed(path, command, sel, "not_found", "nothing on the screen fits", [], tried);
  }

  done(path: number[], what: string) {
    this.lines.push(`${pathText(path)}. ✓ ${what}`);
  }

  async block(steps: Obj[], prefix: number[]): Promise<void> {
    for (let i = 0; i < steps.length; i++) {
      const path = [...prefix, i + 1];
      if (this.skip(path)) continue;
      if (this.overBudget()) throw new StopRun({ kind: "paused", path: pathText(path) });
      const raw = steps[i]!;
      let step: Obj;
      try {
        step = fillStep(raw, this.vars);
      } catch (e) {
        throw this.failed(path, String(raw.do), undefined, "bad_value", (e as Error).message);
      }
      try {
        await this.step(step, path);
      } catch (e) {
        if (e instanceof StopRun && e.stop.kind === "failed" && e.stop.reason === "not_found" && step.optional === true) {
          this.lines.push(`${pathText(path)}. – skipped, not on screen: ${label(step)}`);
          continue;
        }
        throw e;
      }
    }
  }

  async step(step: Obj, path: number[]): Promise<void> {
    const kind = String(step.do);
    const s = (k: string) => (typeof step[k] === "string" ? (step[k] as string) : "");
    const fail = (reason: string, message: string) => this.failed(path, kind, step.sel, reason, message);
    switch (kind) {
      case "open": {
        const app = s("app");
        let pkg: string;
        if (app.includes(".") && !app.includes(" ")) pkg = app;
        else {
          const list = await this.act(path, kind, "list_apps", { query: app });
          const picked = pickApp(firstText(list), app);
          if (typeof picked === "string") pkg = picked;
          else if (picked.length === 0) throw fail("not_found", `no app named ${quote(app, 60)}`);
          else throw this.failed(path, kind, undefined, "ambiguous", `several apps match ${quote(app, 60)}; name the package`, picked);
        }
        await this.act(path, kind, "launch_app", { package: pkg, fresh: step.fresh === true });
        this.done(path, `open ${pkg}`);
        return;
      }
      case "go": {
        const link = s("link");
        const at = link.indexOf(" > ");
        if (at >= 0) {
          const app = link.slice(0, at);
          let panel = norm(link.slice(at + 3)).replace(/[ -]/g, "_");
          if (["hotspot", "tethering", "mobile_data"].includes(panel)) panel = "internet";
          else if (panel === "wi_fi") panel = "wifi";
          if (norm(app) !== "settings" || !(SETTINGS_PANELS as readonly string[]).includes(panel)) {
            throw fail("route_unavailable", "app maps are not built yet (R73); only \"settings > <page>\" for the phone's settings pages works");
          }
          this.routes.push("panel");
          await this.act(path, kind, "phone_control", { control: "panel", panel });
          this.done(path, `go settings > ${panel} (route: panel)`);
        } else {
          this.routes.push("link");
          await this.act(path, kind, "open_link", { url: link, package: s("app") });
          this.done(path, `go ${quote(link, 60)} (route: link)`);
        }
        return;
      }
      case "tap": {
        const sel = step.sel as Obj;
        let times: number;
        if (step.repeat === undefined) times = 1;
        else if (typeof step.repeat === "number") times = step.repeat;
        else {
          const t = String(step.repeat).trim();
          if (!/^-?\d+$/.test(t)) throw fail("bad_value", "repeat is not a whole number");
          times = Number(t);
        }
        times = Math.min(Math.max(times, 0), MAX_REPEAT);
        let quiet = false;
        for (let rep = 0; rep < times; rep++) {
          if (this.overBudget()) throw new StopRun({ kind: "paused", path: pathText(path) });
          let el: [string, string];
          try {
            el = await this.locate(path, kind, sel, "tap");
          } catch (e) {
            if (e instanceof StopRun && e.stop.kind === "failed" && rep > 0) e.stop.message += ` (after ${rep} of ${times} taps)`;
            throw e;
          }
          const args = { observation_id: el[0], element_id: el[1], long_press: step.long === true };
          let v = await this.tryAct(path, kind, "tap", args);
          if (v instanceof ProtocolError && v.code === "stale_observation") {
            const again = await this.locate(path, kind, sel, "tap");
            v = await this.act(path, kind, "tap", { ...args, observation_id: again[0], element_id: again[1] });
          } else if (v instanceof ProtocolError) {
            throw new StopRun({ kind: "error", path: pathText(path), command: kind, error: v });
          }
          if (firstText(v).startsWith("Done, but the screen did not visibly change")) quiet = true;
        }
        this.done(path, label(step) + (quiet ? " (once with no visible change)" : ""));
        return;
      }
      case "type": {
        let el: [string, string];
        if (isObj(step.sel)) el = await this.locate(path, kind, step.sel, "type");
        else {
          const obs = await this.screen(path, kind, false);
          let best: UiNode | undefined;
          for (const n of obs.nodes.filter((x) => usable(x) && x.editable)) {
            if (best === undefined || (n.focused && !best.focused) ||
              (n.focused === best.focused && (n.bounds.top < best.bounds.top || (n.bounds.top === best.bounds.top && n.bounds.left < best.bounds.left)))) best = n;
          }
          if (best === undefined) throw fail("not_found", "no text field on the screen");
          el = [obs.observation_id, best.id];
        }
        const v = await this.act(path, kind, "type_text", { observation_id: el[0], element_id: el[1], text: s("text"), submit: step.submit === true });
        if (firstText(v).startsWith("Typed, but Enter did nothing")) throw fail("not_submitted", "typed, but Enter did nothing visible; tap the app's own Send or Search");
        this.done(path, label(step));
        return;
      }
      case "find":
        await this.locate(path, kind, step.sel as Obj, "any");
        this.done(path, label(step));
        return;
      case "toggle": {
        const want = step.on === true;
        const [obsId, el] = await this.locate(path, kind, step.sel as Obj, "any");
        const nodes = (await this.ctx.latest())?.nodes ?? [];
        const sw = switchFor(nodes, el);
        if (sw === undefined) throw fail("no_switch", "found it, but it has no on/off state");
        if (sw.checked === want) {
          this.done(path, `${label(step)} (already ${want ? "on" : "off"})`);
          return;
        }
        await this.act(path, kind, "tap", { observation_id: obsId, element_id: sw.id });
        const now = (await this.ctx.latest())?.nodes ?? [];
        const r = resolve(now, step.sel as Obj, "any");
        const after = r !== "not_found" && "found" in r ? switchFor(now, r.found)?.checked : undefined;
        if (after !== want) throw fail("state_unchanged", "tapped, but the switch did not change");
        this.done(path, label(step));
        return;
      }
      case "scroll": {
        const obs = await this.screen(path, kind, false);
        const container = isObj(step.sel) ? (await this.locate(path, kind, step.sel, "any"))[1] : undefined;
        if (isObj(step.until)) {
          if (typeof step.until.text !== "string") throw fail("bad_value", "until needs text");
          const args: Record<string, unknown> = { observation_id: obs.observation_id, text: step.until.text, direction: s("direction") };
          if (container !== undefined) args.element_id = container;
          const v = await this.act(path, kind, "scroll_to", args);
          if (firstText(v).startsWith("Did not find")) {
            throw this.failed(path, kind, step.until, "not_found", "scrolling both ways did not reveal it", [], ["scroll"]);
          }
        } else {
          const now = await this.screen(path, kind, false);
          const args: Record<string, unknown> = { observation_id: now.observation_id, direction: s("direction") };
          if (container !== undefined) args.element_id = container;
          await this.act(path, kind, "scroll", args);
        }
        this.done(path, label(step));
        return;
      }
      case "back": case "home":
        await this.act(path, kind, "press", { button: kind });
        this.done(path, kind);
        return;
      case "wait": {
        const text = isObj(step.sel) && typeof step.sel.text === "string" ? step.sel.text : undefined;
        if (text !== undefined) {
          const timeout = isInt(step.timeout_ms) ? step.timeout_ms : 5_000;
          const v = await this.act(path, kind, "wait_for", { text, gone: step.gone === true, timeout_ms: timeout });
          const t = firstText(v);
          if (t.includes(" did not appear within ") || t.includes(" was still on screen after ")) throw fail("not_found", `still not as expected after ${timeout} ms`);
        } else {
          await this.screen(path, kind, true);
        }
        this.done(path, label(step));
        return;
      }
      case "expect": {
        const cond = step.cond as Obj;
        const key = Object.keys(cond)[0];
        const inner = key === undefined ? undefined : cond[key];
        if ((key === "text" || key === "gone") && isObj(inner) && typeof inner.text === "string") {
          // Words to appear or leave: wait on the phone instead of looking again and again.
          await this.tryAct(path, kind, "wait_for", { text: inner.text, gone: key === "gone", timeout_ms: isInt(step.timeout_ms) ? step.timeout_ms : 1_500 });
        }
        const obs = await this.screen(path, kind, true);
        if (!holds(obs, this.vars, cond)) {
          throw this.failed(path, kind, cond, "expect_failed", "the screen is not as the program expected", [], ["settle"]);
        }
        this.done(path, label(step));
        return;
      }
      case "read": {
        const [, el] = await this.locate(path, kind, step.sel as Obj, "any");
        const node = (await this.ctx.latest())?.nodes.find((n) => n.id === el);
        if (node === undefined) throw fail("not_found", "it left the screen");
        const words = node.text ?? node.description ?? node.state ?? node.label ?? "";
        const name = s("as");
        this.lines.push(`${pathText(path)}. ✓ read ${name} = ${quote(words, 60)}`);
        this.vars[name] = words;
        return;
      }
      case "if": {
        const entering = this.resume !== undefined && startsWith(this.resume, path) && this.resume.length > path.length;
        const obs = await this.screen(path, kind, !entering);
        const yes = holds(obs, this.vars, step.cond as Obj);
        const branch = yes ? step.then : step.else;
        if (!entering) this.lines.push(`${pathText(path)}. ${label(step)} → ${yes ? "then" : "else"}`);
        if (Array.isArray(branch)) await this.block(branch as Obj[], path);
        return;
      }
      case "for_each": {
        const items = Array.isArray(step.items) ? step.items : [];
        const name = s("as");
        const body = (Array.isArray(step.steps) ? step.steps : []) as Obj[];
        const had = name in this.vars;
        const saved = this.vars[name];
        for (let i = 0; i < items.length; i++) {
          const at = [...path, i + 1];
          if (this.resume !== undefined && !startsWith(this.resume, at) && less(at, this.resume)) continue;
          this.vars[name] = items[i]!;
          await this.block(body, at);
        }
        if (had) this.vars[name] = saved!;
        else delete this.vars[name];
        this.done(path, `for each of ${items.length} items`);
        return;
      }
      case "call": {
        const route = s("route");
        const args = isObj(step.args) ? step.args : {};
        const a = (k: string) => args[k] ?? null;
        const routes: Record<string, [string, Record<string, unknown>]> = {
          media: ["phone_control", { control: "media", action: a("action") }],
          volume: ["phone_control", { control: "volume", stream: args.stream ?? "media", percent: a("percent") }],
          flashlight: ["phone_control", { control: "flashlight", on: a("on") }],
          brightness: ["phone_control", { control: "brightness", percent: a("percent") }],
          ringer: ["phone_control", { control: "ringer", mode: a("mode") }],
          do_not_disturb: ["phone_control", { control: "do_not_disturb", on: a("on") }],
          panel: ["phone_control", { control: "panel", panel: a("panel") }],
          link: ["open_link", { url: a("url"), package: a("app") }],
          clipboard: ["set_clipboard", { text: a("text") }],
        };
        const target = routes[route];
        if (target === undefined) throw fail("route_unavailable", `no route ${quote(route, 30)}`);
        this.routes.push(route);
        await this.act(path, kind, target[0], target[1]);
        this.done(path, `call ${route} (route: ${route})`);
        return;
      }
      case "ask_owner": {
        const v = await this.act(path, kind, "ask_owner", { message: s("message") });
        const said = firstText(v);
        if (!said.startsWith("The owner says it's done.")) {
          const end = said.indexOf(" The screen after");
          throw new StopRun({ kind: "owner", path: pathText(path), said: (end < 0 ? said : said.slice(0, end)).trim() });
        }
        this.done(path, `${label(step)} → the owner tapped Done`);
        return;
      }
      default:
        throw fail("bad_value", "unknown step");
    }
  }
}

const bad = (message: string) => new ProtocolError("invalid_request", message);

/** run_program: validates, runs, and answers with what happened and the screen now. */
export async function runProgram(ctx: ProgramContext, args: Record<string, unknown>): Promise<ToolResult> {
  let steps: Obj[];
  try {
    steps = validate(args.steps ?? null);
  } catch (e) {
    throw bad((e as Error).message);
  }
  let resume: number[] | undefined;
  const from = args.from_step;
  if (typeof from === "string") {
    resume = parsePath(from);
    if (resume === undefined) throw bad("from_step is a step number such as \"4\" or \"3.2.1\"");
  } else if (typeof from === "number") {
    if (!Number.isInteger(from) || from < 1) throw bad("from_step must be 1 or more");
    resume = [from];
  } else if (from !== undefined && from !== null) {
    throw bad("from_step is a step number such as \"4\" or \"3.2.1\"");
  }
  let vars: Obj = {};
  if (args.vars !== undefined && args.vars !== null) {
    if (!isObj(args.vars) || Object.keys(args.vars).length > 40) throw bad("vars must be an object of at most 40 values");
    vars = { ...(args.vars as Obj) };
  }
  const runner = new Runner(ctx, vars, resume);
  let stop: Stop | undefined;
  try {
    await runner.block(steps, []);
  } catch (e) {
    if (!(e instanceof StopRun)) throw e;
    stop = e.stop;
  }
  const report: Record<string, unknown> = { status: "done", actions: runner.actions, ms: Date.now() - runner.started };
  if (Object.keys(runner.vars).length > 0) report.vars = runner.vars;
  if (runner.routes.length > 0) report.routes = runner.routes;
  let head: string;
  let waiting: ToolResult | undefined;
  if (stop === undefined) {
    head = "Ran the program: every step done. Check the screen below before telling the user it worked.";
  } else if (stop.kind === "failed") {
    Object.assign(report, { status: "failed", step: stop.path, command: stop.command });
    if (stop.selector !== undefined) report.selector = stop.selector;
    report.reason = stop.reason;
    if (stop.candidates.length > 0) report.candidates = stop.candidates;
    report.recovery_tried = stop.tried;
    report.resume = { from_step: stop.path };
    head = `The program stopped at step ${stop.path} (${stop.command}): ${stop.reason}, ${stop.message}. Fix only that step (or do it with the ` +
      `normal tools), then call run_program again with the same steps, from_step "${stop.path}", and the vars below; finished steps do not run again.`;
  } else if (stop.kind === "error") {
    Object.assign(report, { status: "failed", step: stop.path, command: stop.command, reason: stop.error.code, resume: { from_step: stop.path } });
    head = `The program stopped at step ${stop.path} (${stop.command}): ${stop.error.code}: ${stop.error.message}. Tell the user; if they want to continue, ` +
      `fix the cause and call run_program with from_step "${stop.path}".`;
  } else if (stop.kind === "owner") {
    const p = parsePath(stop.path) ?? [];
    const next = pathText(p.map((x, i) => (i === p.length - 1 ? x + 1 : x)));
    Object.assign(report, { status: "owner", step: stop.path, resume: { from_step: next } });
    head = `The program stopped at step ${stop.path}: ${stop.said} If the user wants to continue later, call run_program with from_step "${next}".`;
  } else if (stop.kind === "paused") {
    Object.assign(report, { status: "paused", resume: { from_step: stop.path } });
    head = `The program paused before step ${stop.path} to keep this call short. Call run_program with from_step "${stop.path}" and the vars below to continue.`;
  } else {
    // A step waits for the owner's approval: once answered, that step is done.
    const p = parsePath(stop.path) ?? [];
    const next = pathText(p.map((x, i) => (i === p.length - 1 ? x + 1 : x)));
    Object.assign(report, { status: "waiting_for_owner", step: stop.path, resume: { from_step: next } });
    head = `The program is waiting for the owner at step ${stop.path}. After answer_approval finishes that step, call run_program with the same steps, ` +
      `from_step "${next}", and the vars below.`;
    waiting = stop.result;
  }
  if (stop !== undefined && Object.keys(runner.vars).length > 0) (report.resume as Record<string, unknown>).vars = runner.vars;
  let text = `${head}\n${JSON.stringify(report)}`;
  if (runner.lines.length > 0) text += `\nDone in this call:\n${runner.lines.join("\n")}`;
  const result = textResult(text) as ToolResult;
  if (waiting !== undefined) {
    result.content.push(...waiting.content);
    return result;
  }
  let screen: ToolResult | undefined = stop?.kind === "failed" ? undefined : runner.last;
  if (screen === undefined) {
    try {
      screen = await ctx.act("observe", { device_id: ctx.deviceId, screenshot: false });
    } catch {
      screen = undefined;
    }
  }
  if (screen) result.content.push(...screen.content.filter((p) => !isHeadline(p)));
  return result;
}
