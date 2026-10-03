// Latch device protocol v1.6 for the Vercel gateway. The normative definition
// is the Rust crate `crates/protocol`; this port must accept and reject the
// shared fixtures in packages/schemas/v1/fixtures exactly like it does.

export const PROTOCOL_VERSION = "1.6";

export function isCompatible(version: string): boolean {
  const parts = version.split(".");
  return parts.length === 2 && parts[0] === PROTOCOL_VERSION.split(".")[0] && /^\d+$/.test(parts[1] ?? "");
}

export type ErrorCode =
  | "invalid_request" | "unsupported_capability" | "permission_missing" | "policy_refused"
  | "sensitive_target" | "stale_observation" | "target_not_found" | "screen_protected"
  | "user_denied" | "confirmation_expired" | "cancelled" | "deadline_exceeded"
  | "device_unavailable" | "ambiguous_device" | "transport_unavailable" | "internal";

export const RECOVERY_HINTS: Record<ErrorCode, string> = {
  invalid_request: "Fix the arguments and try again.",
  unsupported_capability: "This device cannot do that. Choose a different approach.",
  permission_missing: "Ask the phone owner to enable this capability in the Latch app.",
  policy_refused: "This operation is not allowed. Do not retry it.",
  sensitive_target: "Latch never touches password, OTP, or payment fields. Ask the user to do this step.",
  stale_observation: "Call observe again and plan from the new screen.",
  target_not_found: "Call observe again and pick an element from the new screen.",
  screen_protected: "The app blocks screenshots. Use the element tree instead, or ask the user.",
  user_denied: "The owner said no. Do not retry without asking them.",
  confirmation_expired: "Nobody approved in time. Ask the user to watch their phone, then retry once.",
  cancelled: "The operation was cancelled. Observe before continuing.",
  deadline_exceeded: "The device did not answer in time. Observe, then retry.",
  device_unavailable: "Ask the owner to open Latch on the phone and start a session.",
  ambiguous_device: "Pass device_id. Call list_devices to see the options.",
  transport_unavailable: "Wait a few seconds and retry.",
  internal: "This is a Latch bug. Stop and report it.",
};

export const RETRYABLE: ReadonlySet<ErrorCode> = new Set(["deadline_exceeded", "device_unavailable", "transport_unavailable"]);

export class ProtocolError extends Error {
  constructor(readonly code: ErrorCode, message: string) {
    super(message);
  }
}

export const CAPABILITIES = [
  "device.info", "ui.observe", "screen.capture", "input.gesture", "input.text", "nav.global", "app.launch",
  "file.read", "file.write", "app.share",
] as const;
export type Capability = (typeof CAPABILITIES)[number];
export type CapabilityStatus = "enabled" | "disabled" | "needs_permission" | "unsupported";
export interface CapabilityState { capability: Capability; status: CapabilityStatus }

export const CAPABILITY_DESCRIPTIONS: Record<Capability, string> = {
  "device.info": "read basic device and session information",
  "ui.observe": "read on-screen UI elements (sensitive fields are redacted)",
  "screen.capture": "take screenshots",
  "input.gesture": "tap, long-press, and swipe",
  "input.text": "type into non-sensitive text fields",
  "nav.global": "press back, home, and recents",
  "app.launch": "list and open apps",
  "file.read": "list and read allowed photos and files",
  "file.write": "save, rename, and delete files",
  "app.share": "share files to an app",
};

export interface SessionInfo {
  expires_at_ms: number; approve_every_action: boolean; paused: boolean;
  /** Since 1.4: the owner lets approvals be answered in the AI app too. */
  remote_approvals?: boolean;
}

/** Since 1.4: answers to an approval. */
export type ApprovalChoice = "once" | "session" | "always" | "deny";
export const APPROVAL_CHOICES: readonly ApprovalChoice[] = ["once", "session", "always", "deny"];

/** Since 1.4: the phone is waiting for the owner to answer an approval for a running command. */
export interface ApprovalRequest {
  command_id: string;
  nonce: string;
  title: string;
  detail: string;
  kind: "action" | "app";
  choices: ApprovalChoice[];
  remote: boolean;
  expires_at_ms: number;
}
export interface DeviceDescriptor { platform: string; os_version: string; model: string; app_version: string }
export interface Hello {
  type: "hello"; protocol: string; device: DeviceDescriptor; capabilities: CapabilityState[];
  session: SessionInfo; device_time_ms?: number;
}
export interface Rect { left: number; top: number; right: number; bottom: number }
export interface UiNode {
  id: string; parent?: string; role: string; text?: string; description?: string; resource_id?: string;
  bounds: Rect; clickable: boolean; long_clickable: boolean; editable: boolean; scrollable: boolean;
  checked?: boolean; enabled: boolean; focused: boolean; sensitive: boolean;
}
export interface Screenshot { mime: string; width: number; height: number; data_base64: string }
export interface Observation {
  observation_id: string; captured_at_ms: number; package?: string;
  screen: { width: number; height: number; rotation: number };
  nodes: UiNode[]; screenshot?: Screenshot; redacted_count: number; truncated: boolean;
}

export type Target = { element: string } | { x: number; y: number };
export type Direction = "up" | "down" | "left" | "right";
const DIRECTIONS: readonly string[] = ["up", "down", "left", "right"];
export type Command =
  | { name: "device.info"; params: Record<string, never> }
  | { name: "ui.observe"; params: { include_screenshot: boolean; max_nodes: number } }
  | { name: "input.tap"; params: { observation_id: string; target: Target; long_press: boolean; double?: boolean } }
  | { name: "input.swipe"; params: { observation_id: string; from: { x: number; y: number }; to: { x: number; y: number }; duration_ms: number; hold_ms?: number } }
  | { name: "input.pinch"; params: { observation_id: string; center: { x: number; y: number }; start_span: number; end_span: number; duration_ms: number } }
  | { name: "input.type"; params: { observation_id: string; element: string; text: string; submit?: boolean } }
  | { name: "ui.wait"; params: { text: string; gone: boolean; timeout_ms: number; max_nodes: number } }
  | { name: "ui.scroll_to"; params: { observation_id: string; text: string; direction: Direction; container?: string; max_swipes: number } }
  | { name: "nav.global"; params: { action: "back" | "home" | "recents" } }
  | { name: "app.list"; params: Record<string, never> }
  | { name: "app.launch"; params: { package: string } }
  /** Since 1.5: ask the owner to do something only a person should do; needs no capability. */
  | { name: "owner.ask"; params: { message: string } }
  // Since 1.6 (ADR-026): files addressed by opaque ids the phone issued.
  | { name: "file.list"; params: { location: FileLocation; folder?: string; query?: string; limit: number; offset?: number } }
  | { name: "file.preview"; params: { id: string } }
  | { name: "file.read"; params: { id: string; offset: number; length: number } }
  | {
    name: "file.write";
    params: {
      location: FileLocation; folder?: string; subfolder?: string; name: string; mime?: string;
      data_base64: string; append?: boolean; overwrite?: boolean;
    };
  }
  | { name: "file.mkdir"; params: { folder?: string; name: string } }
  | { name: "file.rename"; params: { id: string; name: string } }
  | { name: "file.delete"; params: { id: string } }
  | { name: "app.share"; params: { package: string; ids: string[]; text?: string } };

export type FileLocation = "photos" | "downloads" | "folder";
export const FILE_LOCATIONS: readonly FileLocation[] = ["photos", "downloads", "folder"];
export type FileKind = "folder" | "image" | "video" | "file";
export interface FileItem { id: string; name: string; kind: FileKind; location: FileLocation; mime?: string; size?: number; modified_ms?: number }
export interface FileList { location: FileLocation; folder_name?: string; items: FileItem[]; total: number; next_offset?: number }
export interface FilePreview { item: FileItem; text?: string; text_truncated?: boolean; image?: Screenshot }
export interface FileChunk { item: FileItem; offset: number; data_base64: string; eof: boolean }

/** The owner's answer to `owner.ask` (since 1.5), in `ActionResult.owner`. */
export type OwnerReply = "done" | "cant" | "no_answer";

export type RiskLevel = "low" | "medium" | "high";
/**
 * `remember` (since 1.3): present when the owner may save the answer for the
 * session or for this app; absent for critical actions, asked every time.
 */
export interface ConfirmRequest { title: string; detail: string; risk: RiskLevel; remember?: string }
export const MAX_REMEMBER_CHARS = 160;
/**
 * Since protocol 1.2: after a successful action the phone waits `settle_ms`,
 * observes the screen, and returns the observation inside the action result,
 * which saves the agent loop a whole second round trip.
 */
export interface ObserveAfter { settle_ms: number; include_screenshot: boolean; max_nodes: number; quiet_ms?: number }
export const MAX_SETTLE_MS = 3_000;
/** Since 1.3: observe once the screen has been still this long (at most), within settle_ms. */
export const MAX_QUIET_MS = 1_000;

/** Result of `ui.wait` (since 1.3). */
export interface WaitResult { matched: boolean; observation: Observation }

/** Minor protocol version of a compatible `major.minor` string, or undefined. */
export function minorVersion(version: string | undefined): number | undefined {
  const v = String(version ?? "");
  return isCompatible(v) ? Number(v.split(".")[1]) : undefined;
}

/** Lowest minor version a phone must speak to run this command exactly. */
export function minMinorVersion(c: Command): number {
  if (c.name === "ui.wait" || c.name === "ui.scroll_to" || c.name === "input.pinch") return 3;
  if (c.name === "input.type" && c.params.submit === true) return 3;
  if (c.name === "input.tap" && c.params.double === true) return 3;
  if (c.name === "input.swipe" && (c.params.hold_ms ?? 0) > 0) return 3;
  if (c.name === "owner.ask") return 5;
  if (c.name.startsWith("file.") || c.name === "app.share") return 6;
  return 0;
}


export type Outcome = { status: "ok"; data: unknown } | { status: "error"; error: { code: ErrorCode; message: string } };

export function requiredCapabilities(c: Command): Capability[] {
  switch (c.name) {
    case "device.info": return ["device.info"];
    case "ui.observe": return c.params.include_screenshot ? ["ui.observe", "screen.capture"] : ["ui.observe"];
    case "input.tap": case "input.swipe": case "input.pinch": return ["input.gesture"];
    case "input.type": return ["input.text"];
    case "nav.global": return ["nav.global"];
    case "app.list": case "app.launch": return ["app.launch"];
    case "ui.wait": return ["ui.observe"];
    case "ui.scroll_to": return ["ui.observe", "input.gesture"];
    // Only shows the owner a question; the owner does the rest.
    case "owner.ask": return [];
    case "file.list": case "file.preview": case "file.read": return ["file.read"];
    case "file.write": case "file.mkdir": case "file.rename": case "file.delete": return ["file.write"];
    case "app.share": return ["app.share"];
  }
}

/** Asking the owner counts as an action: the owner changes the screen while answering. */
export const isAction = (c: Command) =>
  !["device.info", "ui.observe", "app.list", "ui.wait", "file.list", "file.preview", "file.read"].includes(c.name);

/** `file.write`, `file.mkdir`, `file.rename`, `file.delete`: changes made off screen. */
export const isFileChange = (c: Command) => ["file.write", "file.mkdir", "file.rename", "file.delete"].includes(c.name);

/** Actions after which the old observation is stale and a new one is worth returning. */
export const changesScreen = (c: Command) => isAction(c) && !isFileChange(c);

/** Actions the owner approves under "Ask me before every action"; asking the owner is already a question. */
export const needsOwnerApprovalWhenStrict = (c: Command) =>
  isAction(c) && c.name !== "owner.ask" && !(c.name === "file.write" && c.params.append === true);

export function observationIdOf(c: Command): string | undefined {
  return c.name === "input.tap" || c.name === "input.swipe" || c.name === "input.type" || c.name === "ui.scroll_to" || c.name === "input.pinch"
    ? c.params.observation_id
    : undefined;
}

// ---- Limits and validation (mirrors latch_protocol::validate) ----

export const LIMITS = {
  maxTextChars: 2_000, maxNodes: 2_000, maxSwipeMs: 5_000, maxCoordinate: 20_000, maxIdChars: 64,
  maxPackageChars: 255, maxNodeTextChars: 4_000, maxScreenshotBase64: 6 * 1024 * 1024,
  maxMessageBytes: 8 * 1024 * 1024, minWaitMs: 100, maxWaitMs: 15_000, maxFindTextChars: 200, maxScrollSwipes: 20, maxHoldMs: 3_000, minPinchSpan: 20,
  maxAskOwnerChars: 300,
  maxFileChunkBytes: 512 * 1024, maxFileNameChars: 120, maxFileList: 200, maxShareFiles: 10, maxMimeChars: 100,
};

const invalid = (message: string) => new ProtocolError("invalid_request", message);

/** A file or folder name: no path separators, control characters, or leading dot. */
export function isValidFileName(name: string): boolean {
  const n = [...name].length;
  // eslint-disable-next-line no-control-regex
  return n > 0 && n <= LIMITS.maxFileNameChars && name.trim() === name && !name.startsWith(".") && !/[\u0000-\u001f\u007f-\u009f/\\:*?"<>|]/u.test(name);
}

export const isValidMime = (mime: string) => mime.length <= LIMITS.maxMimeChars && /^[A-Za-z0-9.+_-]+\/[A-Za-z0-9.+_-]+$/.test(mime);

function fileName(name: string, what: string) {
  if (!isValidFileName(name)) throw invalid(`${what} must be 1-${LIMITS.maxFileNameChars} characters, without / \\ : * ? " < > | or a leading dot`);
}

export const isValidId = (id: string) => id.length > 0 && id.length <= LIMITS.maxIdChars && /^[A-Za-z0-9_-]+$/.test(id);

export function isValidPackage(name: string): boolean {
  if (name.length === 0 || name.length > LIMITS.maxPackageChars) return false;
  const segments = name.split(".");
  return segments.length >= 2 && segments.every((s) => /^[A-Za-z][A-Za-z0-9_]*$/.test(s));
}

const isInt = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v);

function coordinate(name: string, v: number) {
  if (v < 0 || v > LIMITS.maxCoordinate) throw invalid(`${name} must be between 0 and ${LIMITS.maxCoordinate}`);
}

function id(name: string, v: string) {
  if (!isValidId(v)) throw invalid(`${name} must be 1-${LIMITS.maxIdChars} characters of letters, digits, '_' or '-'`);
}

function findText(text: string) {
  if (text.trim() === "" || [...text].length > LIMITS.maxFindTextChars) throw invalid(`text must be 1-${LIMITS.maxFindTextChars} characters`);
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f]/.test(text)) throw invalid("text must not contain control characters");
}

export function validateCommand(c: Command): void {
  switch (c.name) {
    case "ui.wait":
      findText(c.params.text);
      if (c.params.timeout_ms < LIMITS.minWaitMs || c.params.timeout_ms > LIMITS.maxWaitMs) {
        throw invalid(`timeout_ms must be between ${LIMITS.minWaitMs} and ${LIMITS.maxWaitMs}`);
      }
      if (c.params.max_nodes < 1 || c.params.max_nodes > LIMITS.maxNodes) throw invalid(`max_nodes must be between 1 and ${LIMITS.maxNodes}`);
      return;
    case "ui.scroll_to":
      id("observation_id", c.params.observation_id);
      findText(c.params.text);
      if (c.params.container !== undefined) id("container", c.params.container);
      if (c.params.max_swipes < 1 || c.params.max_swipes > LIMITS.maxScrollSwipes) {
        throw invalid(`max_swipes must be between 1 and ${LIMITS.maxScrollSwipes}`);
      }
      return;
    case "device.info": case "app.list": case "nav.global": return;
    case "ui.observe":
      if (c.params.max_nodes < 1 || c.params.max_nodes > LIMITS.maxNodes) throw invalid(`max_nodes must be between 1 and ${LIMITS.maxNodes}`);
      return;
    case "input.pinch": {
      const p = c.params;
      id("observation_id", p.observation_id);
      coordinate("center.x", p.center.x); coordinate("center.y", p.center.y);
      for (const [name, span] of [["start_span", p.start_span], ["end_span", p.end_span]] as const) {
        if (span < LIMITS.minPinchSpan || span > LIMITS.maxCoordinate) throw invalid(`${name} must be between ${LIMITS.minPinchSpan} and ${LIMITS.maxCoordinate}`);
      }
      if (p.start_span === p.end_span) throw invalid("start_span and end_span must differ");
      if (p.duration_ms < 50 || p.duration_ms > LIMITS.maxSwipeMs) throw invalid(`duration_ms must be between 50 and ${LIMITS.maxSwipeMs}`);
      return;
    }
    case "input.tap": {
      id("observation_id", c.params.observation_id);
      if (c.params.long_press && c.params.double === true) throw invalid("a tap is either long_press or double, not both");
      const t = c.params.target;
      if ("element" in t) id("element", t.element);
      else { coordinate("x", t.x); coordinate("y", t.y); }
      return;
    }
    case "input.swipe": {
      const p = c.params;
      id("observation_id", p.observation_id);
      if ((p.hold_ms ?? 0) > LIMITS.maxHoldMs) throw invalid(`hold_ms must be at most ${LIMITS.maxHoldMs}`);
      coordinate("from.x", p.from.x); coordinate("from.y", p.from.y); coordinate("to.x", p.to.x); coordinate("to.y", p.to.y);
      if (p.duration_ms < 50 || p.duration_ms > LIMITS.maxSwipeMs) throw invalid(`duration_ms must be between 50 and ${LIMITS.maxSwipeMs}`);
      if (p.from.x === p.to.x && p.from.y === p.to.y) throw invalid("swipe start and end must differ");
      return;
    }
    case "input.type": {
      id("observation_id", c.params.observation_id);
      id("element", c.params.element);
      if ([...c.params.text].length > LIMITS.maxTextChars) throw invalid(`text is limited to ${LIMITS.maxTextChars} characters`);
      // eslint-disable-next-line no-control-regex
      if (/[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(c.params.text)) throw invalid("text must not contain control characters");
      return;
    }
    case "app.launch":
      if (!isValidPackage(c.params.package)) throw invalid("package must be an application id such as com.example.app");
      return;
    case "file.list":
      if (!FILE_LOCATIONS.includes(c.params.location)) throw invalid("location must be photos, downloads, or folder");
      if (c.params.folder !== undefined) id("folder", c.params.folder);
      if (c.params.query !== undefined) findText(c.params.query);
      if (c.params.limit < 1 || c.params.limit > LIMITS.maxFileList) throw invalid(`limit must be between 1 and ${LIMITS.maxFileList}`);
      if ((c.params.offset ?? 0) < 0) throw invalid("offset must not be negative");
      return;
    case "file.preview": case "file.delete":
      id("id", c.params.id);
      return;
    case "file.read":
      id("id", c.params.id);
      if (c.params.offset < 0) throw invalid("offset must not be negative");
      if (c.params.length < 1 || c.params.length > LIMITS.maxFileChunkBytes) throw invalid(`length must be between 1 and ${LIMITS.maxFileChunkBytes}`);
      return;
    case "file.write": {
      const p = c.params;
      if (!FILE_LOCATIONS.includes(p.location)) throw invalid("location must be photos, downloads, or folder");
      fileName(p.name, "name");
      if (p.folder !== undefined) {
        if (p.location !== "folder") throw invalid("folder is only for location \"folder\"");
        id("folder", p.folder);
      }
      if (p.subfolder !== undefined) {
        if (p.location === "folder") throw invalid("subfolder is for photos and downloads; use folder");
        fileName(p.subfolder, "subfolder");
      }
      if (p.mime !== undefined && !isValidMime(p.mime)) throw invalid("mime must look like type/subtype");
      if (p.append && p.overwrite) throw invalid("a write either appends or overwrites, not both");
      if (p.data_base64.length > Math.ceil(LIMITS.maxFileChunkBytes / 3) * 4 || !/^[A-Za-z0-9+/=]*$/.test(p.data_base64)) {
        throw invalid(`data_base64 must be base64 of at most ${LIMITS.maxFileChunkBytes} bytes`);
      }
      return;
    }
    case "file.mkdir":
      if (c.params.folder !== undefined) id("folder", c.params.folder);
      fileName(c.params.name, "name");
      return;
    case "file.rename":
      id("id", c.params.id);
      fileName(c.params.name, "name");
      return;
    case "app.share": {
      if (!isValidPackage(c.params.package)) throw invalid("package must be an application id such as com.example.app");
      if (c.params.ids.length < 1 || c.params.ids.length > LIMITS.maxShareFiles) throw invalid(`ids must hold 1-${LIMITS.maxShareFiles} file ids`);
      for (const v of c.params.ids) id("ids", v);
      const t = c.params.text;
      // eslint-disable-next-line no-control-regex
      if (t !== undefined && ([...t].length > LIMITS.maxTextChars || /[\u0000-\u0009\u000b-\u001f\u007f-\u009f]/.test(t))) {
        throw invalid(`text must be at most ${LIMITS.maxTextChars} characters without control characters`);
      }
      return;
    }
    case "owner.ask": {
      const n = [...c.params.message].length;
      if (n === 0 || n > LIMITS.maxAskOwnerChars || c.params.message.trim() === "") {
        throw invalid(`message must be 1-${LIMITS.maxAskOwnerChars} characters`);
      }
      // eslint-disable-next-line no-control-regex
      if (/[\u0000-\u0009\u000b-\u001f\u007f-\u009f]/.test(c.params.message)) throw invalid("message must not contain control characters");
      return;
    }
  }
}

/** Parses an untrusted `{name, params}` object into a typed command. */
export function parseCommand(raw: unknown): Command {
  if (typeof raw !== "object" || raw === null) throw invalid("command must be an object");
  const { name, params = {} } = raw as { name?: unknown; params?: Record<string, unknown> };
  const p = params as Record<string, unknown>;
  const str = (k: string) => { if (typeof p[k] !== "string") throw invalid(`${k} must be a string`); return p[k] as string; };
  const int = (v: unknown, k: string) => { if (!isInt(v)) throw invalid(`${k} must be an integer`); return v; };
  const bool = (k: string, d: boolean) => { if (p[k] === undefined) return d; if (typeof p[k] !== "boolean") throw invalid(`${k} must be a boolean`); return p[k] as boolean; };
  const point = (k: string) => { const o = p[k] as Record<string, unknown> | undefined; if (!o) throw invalid(`${k} is required`); return { x: int(o.x, `${k}.x`), y: int(o.y, `${k}.y`) }; };
  switch (name) {
    case "device.info": return { name, params: {} };
    case "app.list": return { name, params: {} };
    case "ui.observe": return { name, params: { include_screenshot: bool("include_screenshot", false), max_nodes: p.max_nodes === undefined ? 400 : int(p.max_nodes, "max_nodes") } };
    case "input.tap": {
      const t = p.target as Record<string, unknown> | undefined;
      if (!t) throw invalid("target is required");
      const target: Target = typeof t.element === "string" ? { element: t.element } : { x: int(t.x, "x"), y: int(t.y, "y") };
      return { name, params: { observation_id: str("observation_id"), target, long_press: bool("long_press", false), double: bool("double", false) } };
    }
    case "input.swipe": return {
      name,
      params: {
        observation_id: str("observation_id"), from: point("from"), to: point("to"),
        duration_ms: p.duration_ms === undefined ? 300 : int(p.duration_ms, "duration_ms"),
        hold_ms: p.hold_ms === undefined ? 0 : int(p.hold_ms, "hold_ms"),
      },
    };
    case "input.pinch": return {
      name,
      params: {
        observation_id: str("observation_id"), center: point("center"),
        start_span: int(p.start_span, "start_span"), end_span: int(p.end_span, "end_span"),
        duration_ms: p.duration_ms === undefined ? 300 : int(p.duration_ms, "duration_ms"),
      },
    };
    case "input.type": return { name, params: { observation_id: str("observation_id"), element: str("element"), text: str("text"), submit: bool("submit", false) } };
    case "ui.wait": return {
      name,
      params: {
        text: str("text"), gone: bool("gone", false),
        timeout_ms: p.timeout_ms === undefined ? 5_000 : int(p.timeout_ms, "timeout_ms"),
        max_nodes: p.max_nodes === undefined ? 400 : int(p.max_nodes, "max_nodes"),
      },
    };
    case "ui.scroll_to": {
      const direction = str("direction");
      if (!DIRECTIONS.includes(direction)) throw invalid("direction must be up, down, left, or right");
      if (p.container !== undefined && typeof p.container !== "string") throw invalid("container must be a string");
      return {
        name,
        params: {
          observation_id: str("observation_id"), text: str("text"), direction: direction as Direction,
          ...(p.container !== undefined ? { container: p.container as string } : {}),
          max_swipes: p.max_swipes === undefined ? 10 : int(p.max_swipes, "max_swipes"),
        },
      };
    }
    case "nav.global": {
      const action = str("action");
      if (action !== "back" && action !== "home" && action !== "recents") throw invalid("unknown global action");
      return { name, params: { action } };
    }
    case "app.launch": return { name, params: { package: str("package") } };
    case "owner.ask": return { name, params: { message: str("message") } };
    case "file.list": {
      const location = str("location") as FileLocation;
      return {
        name,
        params: {
          location,
          ...(p.folder !== undefined ? { folder: str("folder") } : {}),
          ...(p.query !== undefined ? { query: str("query") } : {}),
          limit: p.limit === undefined ? 50 : int(p.limit, "limit"),
          ...(p.offset !== undefined ? { offset: int(p.offset, "offset") } : {}),
        },
      };
    }
    case "file.preview": return { name, params: { id: str("id") } };
    case "file.delete": return { name, params: { id: str("id") } };
    case "file.read": return { name, params: { id: str("id"), offset: p.offset === undefined ? 0 : int(p.offset, "offset"), length: int(p.length, "length") } };
    case "file.write": return {
      name,
      params: {
        location: str("location") as FileLocation,
        ...(p.folder !== undefined ? { folder: str("folder") } : {}),
        ...(p.subfolder !== undefined ? { subfolder: str("subfolder") } : {}),
        name: str("name"),
        ...(p.mime !== undefined ? { mime: str("mime") } : {}),
        data_base64: str("data_base64"),
        append: bool("append", false),
        overwrite: bool("overwrite", false),
      },
    };
    case "file.mkdir": return { name, params: { ...(p.folder !== undefined ? { folder: str("folder") } : {}), name: str("name") } };
    case "file.rename": return { name, params: { id: str("id"), name: str("name") } };
    case "app.share": {
      if (!Array.isArray(p.ids) || p.ids.some((v) => typeof v !== "string")) throw invalid("ids must be a list of strings");
      return { name, params: { package: str("package"), ids: p.ids as string[], ...(p.text !== undefined ? { text: str("text") } : {}) } };
    }
    default: throw new ProtocolError("unsupported_capability", "unknown command");
  }
}

const NONCE = /^[0-9a-f]{32}$/;

function text(name: string, v: unknown, max: number): string {
  // eslint-disable-next-line no-control-regex
  if (typeof v !== "string" || v.trim() === "" || [...v].length > max || /[\u0000-\u001f\u007f-\u009f]/u.test(v)) {
    throw invalid(`${name} must be 1-${max} characters without control characters`);
  }
  return v;
}

/** Mirrors latch_protocol::validate::approval_request. */
export function parseApprovalRequest(raw: Record<string, unknown>): ApprovalRequest {
  if (typeof raw.command_id !== "string") throw invalid("command_id is required");
  id("command_id", raw.command_id);
  if (typeof raw.nonce !== "string" || !NONCE.test(raw.nonce)) throw invalid("nonce must be 32 lowercase hex characters");
  if (raw.kind !== "action" && raw.kind !== "app") throw invalid("kind must be action or app");
  const choices = raw.choices;
  if (!Array.isArray(choices) || choices.length === 0 || choices.length > 4) throw invalid("choices must list 1-4 answers");
  if (choices.some((c, i) => !APPROVAL_CHOICES.includes(c as ApprovalChoice) || choices.indexOf(c) !== i)) throw invalid("choices must be distinct answers");
  if (typeof raw.remote !== "boolean" || !isInt(raw.expires_at_ms)) throw invalid("remote and expires_at_ms are required");
  return {
    command_id: raw.command_id, nonce: raw.nonce, title: text("title", raw.title, 200), detail: text("detail", raw.detail, 600),
    kind: raw.kind, choices: choices as ApprovalChoice[], remote: raw.remote, expires_at_ms: raw.expires_at_ms as number,
  };
}

export function validateHello(h: Hello): void {
  if (!isCompatible(h.protocol)) {
    throw new ProtocolError("unsupported_capability", `device speaks protocol ${String(h.protocol).slice(0, 16)}, gateway speaks ${PROTOCOL_VERSION}`);
  }
  for (const k of ["platform", "os_version", "model", "app_version"] as const) {
    const v = h.device?.[k];
    if (typeof v !== "string" || v.length === 0 || [...v].length > 64) throw invalid(`device.${k} must be 1-64 characters`);
  }
  if (!Array.isArray(h.capabilities) || h.capabilities.length > 64) throw invalid("too many capabilities");
  const s = h.session;
  if (!s || !isInt(s.expires_at_ms)) throw invalid("session.expires_at_ms is required");
}

/** Fills protocol defaults and checks bounds on an observation from a phone. */
export function normalizeObservation(raw: unknown, maxNodes: number): Observation {
  const o = raw as Observation;
  if (typeof o !== "object" || o === null || !Array.isArray(o.nodes) || typeof o.screen !== "object") throw invalid("malformed observation");
  id("observation_id", o.observation_id);
  if (o.nodes.length > Math.min(maxNodes, LIMITS.maxNodes)) throw invalid("observation has more nodes than requested");
  if (o.package !== undefined && o.package.length > LIMITS.maxPackageChars) throw invalid("package is too long");
  const nodes = o.nodes.map((n) => {
    id("node id", n.id);
    if ((n.text?.length ?? 0) + (n.description?.length ?? 0) > LIMITS.maxNodeTextChars) throw invalid("node text exceeds the protocol limit");
    if (n.sensitive && (n.text !== undefined || n.description !== undefined)) {
      throw new ProtocolError("internal", "device sent content for a sensitive element");
    }
    return {
      ...n, clickable: n.clickable ?? false, long_clickable: n.long_clickable ?? false, editable: n.editable ?? false,
      scrollable: n.scrollable ?? false, enabled: n.enabled ?? true, focused: n.focused ?? false, sensitive: n.sensitive ?? false,
    };
  });
  if (o.screenshot) {
    if (o.screenshot.mime !== "image/jpeg" && o.screenshot.mime !== "image/png") throw invalid("screenshot must be image/jpeg or image/png");
    if (o.screenshot.data_base64.length > LIMITS.maxScreenshotBase64) throw invalid("screenshot exceeds the protocol size limit");
  }
  return { ...o, screen: { ...o.screen, rotation: o.screen.rotation ?? 0 }, nodes, redacted_count: o.redacted_count ?? 0, truncated: o.truncated ?? false };
}

// ---- Geometry helpers ----

export const contains = (r: Rect, x: number, y: number) => x >= r.left && x < r.right && y >= r.top && y < r.bottom;
export const isEmptyRect = (r: Rect) => r.right <= r.left || r.bottom <= r.top;
export const area = (r: Rect) => (isEmptyRect(r) ? 0 : (r.right - r.left) * (r.bottom - r.top));

/** Smallest node containing the point, preferring actionable ones. */
export function nodeAt(o: Observation, x: number, y: number): UiNode | undefined {
  let best: UiNode | undefined;
  let bestKey: [number, number] | undefined;
  for (const n of o.nodes) {
    if (!contains(n.bounds, x, y)) continue;
    const key: [number, number] = [n.clickable || n.editable ? 0 : 1, area(n.bounds)];
    if (!bestKey || key[0] < bestKey[0] || (key[0] === bestKey[0] && key[1] < bestKey[1])) { best = n; bestKey = key; }
  }
  return best;
}
