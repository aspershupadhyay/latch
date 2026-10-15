// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// The `phone_control` tool (protocol 1.11): volume, media keys, brightness,
// the Wi-Fi and Bluetooth panels, and the flashlight, each behind its own
// capability. Mirrors servers/mcp/src/mcp/control.rs and
// crates/protocol/src/control.rs word for word; packages/schemas/v1/mcp/control-*.json pins both.

export type VolumeStream = "media" | "ring" | "notification" | "alarm";
export type VolumeStep = "up" | "down" | "mute" | "unmute";
export type MediaAction = "play_pause" | "play" | "pause" | "next" | "previous" | "stop";
export type SettingsPanel = "wifi" | "bluetooth";

export type PhoneControl =
  | { kind: "volume"; stream: VolumeStream; percent?: number; step?: VolumeStep }
  | { kind: "media"; action: MediaAction }
  | { kind: "brightness"; percent?: number; auto?: boolean }
  | { kind: "panel"; panel: SettingsPanel }
  | { kind: "flashlight"; on: boolean };

/** What a control reads after the change, in `ActionResult.control`. */
export interface ControlReading { percent?: number; on?: boolean }

export const CONTROL_CAPABILITY = {
  volume: "control.volume",
  media: "control.media",
  brightness: "control.brightness",
  panel: "control.panels",
  flashlight: "control.flashlight",
} as const;

const STREAMS: readonly VolumeStream[] = ["media", "ring", "notification", "alarm"];
const STEPS: readonly VolumeStep[] = ["up", "down", "mute", "unmute"];
const MEDIA: readonly MediaAction[] = ["play_pause", "play", "pause", "next", "previous", "stop"];
const PANELS: readonly SettingsPanel[] = ["wifi", "bluetooth"];

/** Only a panel changes what is on screen. */
export const controlChangesScreen = (c: PhoneControl) => c.kind === "panel";

/** Plain words for approvals and answers, e.g. "Set the media volume to 40%". */
export function describeControl(c: PhoneControl): string {
  switch (c.kind) {
    case "volume":
      if (c.percent !== undefined) return `Set the ${c.stream} volume to ${c.percent}%`;
      switch (c.step) {
        case "up": return `Turn the ${c.stream} volume up`;
        case "down": return `Turn the ${c.stream} volume down`;
        case "mute": return `Mute the ${c.stream} volume`;
        case "unmute": return `Unmute the ${c.stream} volume`;
        default: return `Change the ${c.stream} volume`;
      }
    case "media":
      return {
        play_pause: "Play or pause media",
        play: "Play media",
        pause: "Pause media",
        next: "Skip to the next track",
        previous: "Go to the previous track",
        stop: "Stop media",
      }[c.action];
    case "brightness":
      if (c.percent !== undefined) return `Set the screen brightness to ${c.percent}%`;
      if (c.auto === true) return "Turn automatic brightness on";
      if (c.auto === false) return "Turn automatic brightness off";
      return "Change the screen brightness";
    case "panel":
      return c.panel === "wifi" ? "Open the Wi-Fi panel" : "Open the Bluetooth panel";
    case "flashlight":
      return c.on ? "Turn the flashlight on" : "Turn the flashlight off";
  }
}

/** Shape check for a control inside a `control.set` command (mirrors latch_protocol::validate). */
export function validateControl(raw: unknown): PhoneControl {
  const fail = (m: string) => { throw new ControlError(m); };
  if (typeof raw !== "object" || raw === null) return fail("control must be an object");
  const c = raw as Record<string, unknown>;
  const percentOk = (p: unknown) => p === undefined || (Number.isInteger(p) && (p as number) >= 0 && (p as number) <= 100);
  switch (c.kind) {
    case "volume":
      if (!STREAMS.includes(c.stream as VolumeStream)) return fail("unknown volume stream");
      if (c.step !== undefined && !STEPS.includes(c.step as VolumeStep)) return fail("unknown volume step");
      if ((c.percent === undefined) === (c.step === undefined)) return fail("volume takes exactly one of percent and step");
      if (!percentOk(c.percent)) return fail("percent must be between 0 and 100");
      return { kind: "volume", stream: c.stream as VolumeStream, ...(c.percent !== undefined ? { percent: c.percent as number } : {}), ...(c.step !== undefined ? { step: c.step as VolumeStep } : {}) };
    case "media":
      if (!MEDIA.includes(c.action as MediaAction)) return fail("unknown media action");
      return { kind: "media", action: c.action as MediaAction };
    case "brightness":
      if (c.auto !== undefined && typeof c.auto !== "boolean") return fail("auto must be a boolean");
      if ((c.percent === undefined) === (c.auto === undefined)) return fail("brightness takes exactly one of percent and auto");
      if (!percentOk(c.percent)) return fail("percent must be between 0 and 100");
      return { kind: "brightness", ...(c.percent !== undefined ? { percent: c.percent as number } : {}), ...(c.auto !== undefined ? { auto: c.auto as boolean } : {}) };
    case "panel":
      if (!PANELS.includes(c.panel as SettingsPanel)) return fail("unknown panel");
      return { kind: "panel", panel: c.panel as SettingsPanel };
    case "flashlight":
      if (typeof c.on !== "boolean") return fail("on must be a boolean");
      return { kind: "flashlight", on: c.on };
    default:
      return fail("unknown control kind");
  }
}

export class ControlError extends Error {}

const ALLOWED: Record<string, readonly string[]> = {
  volume: ["stream", "percent", "step"],
  media: ["action"],
  brightness: ["percent", "auto"],
  flashlight: ["on"],
  panel: ["panel"],
};
const PARAMS = ["stream", "percent", "step", "action", "auto", "on", "panel"];

/** The control the tool arguments ask for; throws ControlError with the reason they are wrong. */
export function parseControlArgs(args: Record<string, unknown>): PhoneControl {
  const fail = (m: string): never => { throw new ControlError(m); };
  const present = (k: string) => args[k] !== undefined && args[k] !== null;
  const string = (k: string): string | undefined => {
    if (!present(k)) return undefined;
    return typeof args[k] === "string" ? (args[k] as string) : fail(`${k} must be a string`);
  };
  const pick = <T extends string>(k: string, options: readonly T[]): T | undefined => {
    const v = string(k);
    if (v === undefined) return undefined;
    return options.includes(v as T) ? (v as T) : fail(`${k} must be one of ${options.join(", ")}`);
  };
  const boolean = (k: string): boolean | undefined => {
    if (!present(k)) return undefined;
    return typeof args[k] === "boolean" ? (args[k] as boolean) : fail(`${k} must be true or false`);
  };
  const percent = (): number | undefined => {
    if (!present("percent")) return undefined;
    const p = args.percent;
    return Number.isInteger(p) && (p as number) >= 0 && (p as number) <= 100
      ? (p as number)
      : fail("percent must be an integer from 0 to 100");
  };

  const control = string("control") ?? fail("control is required");
  const accepted = ALLOWED[control] ?? fail("control must be one of volume, media, brightness, flashlight, panel");
  const extra = PARAMS.find((k) => !accepted.includes(k) && present(k));
  if (extra !== undefined) fail(`${control} does not take ${extra}`);
  switch (control) {
    case "volume": {
      const stream = pick("stream", STREAMS) ?? "media";
      const p = percent();
      const step = pick("step", STEPS);
      if ((p === undefined) === (step === undefined)) fail("volume takes either percent or step");
      return { kind: "volume", stream, ...(p !== undefined ? { percent: p } : {}), ...(step !== undefined ? { step } : {}) };
    }
    case "media":
      return { kind: "media", action: pick("action", MEDIA) ?? fail("media needs action") };
    case "brightness": {
      const p = percent();
      const auto = boolean("auto");
      if ((p === undefined) === (auto === undefined)) fail("brightness takes either percent or auto");
      return { kind: "brightness", ...(p !== undefined ? { percent: p } : {}), ...(auto !== undefined ? { auto } : {}) };
    }
    case "flashlight":
      return { kind: "flashlight", on: boolean("on") ?? fail("flashlight needs on") };
    default:
      return { kind: "panel", panel: pick("panel", PANELS) ?? fail("panel needs panel") };
  }
}

/** The tool's answer after a control that stays off screen ran. */
export function controlAnswer(c: PhoneControl, reading: ControlReading | null | undefined): string {
  const words = describeControl(c);
  const done = `Done: ${words.charAt(0).toLowerCase()}${words.slice(1)}.`;
  let now: string | undefined;
  if (reading) {
    if (c.kind === "volume") {
      if (reading.on === true) now = "It is muted now.";
      else if (reading.percent !== undefined) now = `It is at ${reading.percent}% now.`;
    } else if (c.kind === "brightness") {
      if (reading.on === true) now = "Automatic brightness is on.";
      else if (reading.percent !== undefined) now = `Brightness is at ${reading.percent}% now.`;
    } else if (c.kind === "flashlight" && reading.on !== undefined) {
      now = `The flashlight is ${reading.on ? "on" : "off"} now.`;
    }
  }
  if (now !== undefined) return `${done} ${now}`;
  if (c.kind === "media") return `${done} The key went to whichever app is playing; observe to check it.`;
  return done;
}
