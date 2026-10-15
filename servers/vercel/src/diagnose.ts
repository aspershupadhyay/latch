// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// `diagnose` (roadmap R13): whether each phone is ready, with the owner's fix
// for every problem. Byte for byte the text of servers/mcp/src/mcp/diagnose.rs
// (packages/schemas/v1/mcp/diagnose-*.{json,txt}). No screen content.

import { type CapabilityState, type SessionInfo, minorVersion } from "./protocol.js";

export interface DiagnosisInput {
  gateway_protocol: string;
  now_ms: number;
  /** Paired phones in the order to report them. */
  phones: PhoneState[];
}

export interface PhoneState {
  id: string;
  name: string;
  model: string;
  /** Present while the phone is connected; session expiry on the gateway clock. */
  live: { protocol: string; capabilities: CapabilityState[]; session: SessionInfo } | null;
}

/** What an agent needs for ordinary screen work, and what each one lets it do. */
const CORE: readonly (readonly [string, string])[] = [
  ["ui.observe", "read the screen"],
  ["input.gesture", "tap, scroll, or swipe"],
  ["input.text", "type"],
  ["nav.global", "press back or home"],
  ["app.launch", "open apps"],
];

export function renderDiagnosis(input: DiagnosisInput): string {
  let out = `Latch diagnosis (gateway protocol ${cut(input.gateway_protocol, 16)}). Owner-facing fixes are marked "Fix:"; pass them on to the user.\n`;
  if (input.phones.length === 0) {
    return out + "- No phone is paired with this gateway. Fix: the owner opens the Latch app's Connect tab and connects the phone to this gateway.\n"
      + "Verdict: not ready.\n";
  }
  const gatewayMinor = minorVersion(input.gateway_protocol) ?? 0;
  let ready = 0;
  for (const phone of input.phones) {
    const label = `${phone.id} "${cut(phone.name, 40)}" (${cut(phone.model, 40)})`;
    const live = phone.live;
    if (!live) {
      out += `- ${label}: not connected. Fix: open Latch on the phone and check that it says it is connected; keep the phone online, and set Latch's battery use to Unrestricted so Android does not stop it.\n`;
      continue;
    }
    out += `- ${label}: connected, app protocol ${cut(live.protocol, 16)}.\n`;
    let blocked = false;
    const status = (id: string) => live.capabilities.find((c) => c.capability === id)?.status ?? "unsupported";
    if (live.session.paused) {
      blocked = true;
      out += "  - The owner paused the session; every command is refused. Fix: tap Resume in the Latch app.\n";
    }
    if (live.session.expires_at_ms <= input.now_ms) {
      blocked = true;
      out += "  - The session has ended. Fix: the owner starts a new session in the Latch app.\n";
    } else {
      out += `  - Session: ${Math.ceil((live.session.expires_at_ms - input.now_ms) / 60_000)} min left.\n`;
    }
    const missing = CORE.filter(([id]) => status(id) === "needs_permission").map(([id]) => id);
    if (missing.length > 0) {
      blocked = true;
      out += `  - Switched on but Android has not allowed it: ${missing.join(", ")}. Fix: Android Settings → Accessibility → Latch → On (on Android 13+, first allow restricted settings in Latch's App info).\n`;
    }
    const off = CORE.filter(([id]) => status(id) === "disabled" || status(id) === "unsupported");
    if (off.length > 0) {
      if (off.some(([id]) => id === "ui.observe")) blocked = true;
      const whats = off.map(([, what]) => what);
      const cannot = whats.length === 1 ? whats[0] : `${whats.slice(0, -1).join(", ")} or ${whats[whats.length - 1]}`;
      out += `  - Off: ${off.map(([id]) => id).join(", ")}, so the AI cannot ${cannot}. Fix: the owner switches ${off.length === 1 ? "it" : "them"} on in the Latch app's Access tab.\n`;
    }
    if ((minorVersion(live.protocol) ?? 0) < gatewayMinor) {
      out += "  - The app is older than the gateway, so newer tools fail on this phone. Fix: update Latch on the phone (Settings tab → Check for updates).\n";
    }
    if (live.session.approve_every_action) {
      out += "  - Note: the owner approves every action, so each step waits for a tap on the phone.\n";
    }
    if (blocked) {
      out += "  Not ready until the fixes above are done.\n";
    } else {
      ready += 1;
      out += "  Ready.\n";
    }
  }
  return out + (ready === 0 ? "Verdict: not ready.\n" : `Verdict: ready (${ready} phone${ready === 1 ? "" : "s"} usable).\n`);
}

function cut(s: string, max: number): string {
  const chars = [...s];
  return chars.length > max ? chars.slice(0, max).join("") + "…" : s;
}
