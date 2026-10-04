// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// File tools (protocol 1.6, ADR-026; encrypted links since 1.7, ADR-027).
// Same tools and texts as servers/mcp/src/mcp/files.rs. Whole files travel by
// link, so their bytes never pass through the AI's context. With protocol 1.7
// the phone and the computer move the encrypted bytes themselves (Vercel Blob,
// or this gateway's Redis for files up to 4 MB); phones on 1.6 still get the
// old Redis links of up to 4 MB, which Vercel functions limit to 4.5 MB per
// request.

import type { CommandTiming, Devices, Live } from "./devices.js";
import type { LinkRecord, Links } from "./links.js";
import {
  type Command, type FileChunk, type FileItem, type FileList, type FileLocation, type FilePreview, type FileTransfer, LIMITS, ProtocolError, isHex,
} from "./protocol.js";
import { quote, textResult } from "./render.js";
import { newToken } from "./secret.js";
import type { Store } from "./store.js";

export const FILE_TOOLS = [
  "list_files", "read_file", "get_file_link", "upload_link", "write_file", "create_folder", "rename_file", "delete_file", "share_to_app",
  "transfer_status",
] as const;

/** How long one tool call follows a transfer before answering with its progress. */
export const TRANSFER_TOOL_BUDGET_MS = 45_000;
/** Each `file.transfer` waits at most this long on the phone (under the 20 s command deadline). */
const TRANSFER_WAIT_MS = 15_000;

/** Largest file one link carries on Vercel. */
export const MAX_TRANSFER_BYTES = 4 * 1024 * 1024;
/** Most bytes write_file takes inline (text or data_base64). */
export const MAX_INLINE_BYTES = 128 * 1024;
export const LINK_TTL_MS = 15 * 60 * 1000;
/** Redis values stay well under Upstash's request limit. */
const PART_BYTES = 512 * 1024;

type ToolResult = { content: ({ type: string; text?: string; data?: string; mimeType?: string })[]; _meta?: unknown };

interface Meta { kind: "download" | "upload"; name?: string; mime?: string; size?: number; parts?: number }

const metaKey = (t: string) => `latch:blob:${t}`;
const partKey = (t: string, i: number) => `latch:blob:${t}:${i}`;

/** Download and upload links in Redis, each a 256-bit token valid 15 minutes. */
export class Transfers {
  constructor(private readonly store: Store) {}

  private async putBytes(token: string, meta: Meta, bytes: Buffer) {
    const parts = Math.ceil(bytes.length / PART_BYTES);
    for (let i = 0; i < parts; i++) {
      await this.store.set(partKey(token, i), bytes.subarray(i * PART_BYTES, (i + 1) * PART_BYTES).toString("base64"), { px: LINK_TTL_MS });
    }
    await this.store.set(metaKey(token), JSON.stringify({ ...meta, size: bytes.length, parts }), { px: LINK_TTL_MS });
  }

  private async getBytes(token: string, meta: Meta): Promise<Buffer | undefined> {
    const parts = meta.parts ?? 0;
    if (parts === 0) return Buffer.alloc(0);
    const values = await this.store.mget(Array.from({ length: parts }, (_, i) => partKey(token, i)));
    if (values.some((v) => v === null)) return undefined;
    return Buffer.concat(values.map((v) => Buffer.from(v as string, "base64")));
  }

  private async meta(token: string): Promise<Meta | undefined> {
    if (!/^l(dl|ul)_[0-9a-f]{64}$/.test(token)) return undefined;
    const raw = await this.store.get(metaKey(token));
    return raw ? (JSON.parse(raw) as Meta) : undefined;
  }

  async putDownload(name: string, mime: string, bytes: Buffer): Promise<string> {
    if (bytes.length > MAX_TRANSFER_BYTES) throw tooLarge();
    const token = newToken("ldl");
    await this.putBytes(token, { kind: "download", name, mime }, bytes);
    return token;
  }

  async download(token: string): Promise<{ name: string; mime: string; bytes: Buffer } | undefined> {
    const meta = await this.meta(token);
    if (meta?.kind !== "download") return undefined;
    const bytes = await this.getBytes(token, meta);
    return bytes ? { name: meta.name ?? "file", mime: meta.mime ?? "application/octet-stream", bytes } : undefined;
  }

  async newUpload(): Promise<string> {
    const token = newToken("lul");
    await this.store.set(metaKey(token), JSON.stringify({ kind: "upload" }), { px: LINK_TTL_MS });
    return token;
  }

  /** "ok", or why not. Each upload link is filled once. */
  async putUpload(token: string, bytes: Buffer): Promise<"ok" | "too_large" | "used" | "unknown"> {
    if (bytes.length > MAX_TRANSFER_BYTES) return "too_large";
    const meta = await this.meta(token);
    if (meta?.kind !== "upload") return "unknown";
    if (meta.parts !== undefined) return "used";
    await this.putBytes(token, { kind: "upload" }, bytes);
    return "ok";
  }

  /** Takes an uploaded file out (each upload is saved once). */
  async takeUpload(token: string): Promise<Buffer> {
    const meta = await this.meta(token);
    const bytes = meta?.kind === "upload" && meta.parts !== undefined ? await this.getBytes(token, meta) : undefined;
    if (!bytes) throw bad("that upload_id is unknown, expired, already saved, or nothing was uploaded to it yet");
    for (let i = 0; i < (meta?.parts ?? 0); i++) await this.store.del(partKey(token, i));
    await this.store.del(metaKey(token));
    return bytes;
  }
}

const bad = (message: string) => new ProtocolError("invalid_request", message);
const tooLarge = () => bad("files above 4 MB cannot be copied through this gateway");

function str(args: Record<string, unknown>, key: string): string | undefined {
  const v = args[key];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "string") throw bad(`${key} must be a string`);
  return v;
}
function req(args: Record<string, unknown>, key: string): string {
  const v = str(args, key);
  if (v === undefined) throw bad(`${key} is required`);
  return v;
}
function flag(args: Record<string, unknown>, key: string, d: boolean): boolean {
  const v = args[key];
  if (v === undefined || v === null) return d;
  if (typeof v !== "boolean") throw bad(`${key} must be true or false`);
  return v;
}
function count(args: Record<string, unknown>, key: string, d: number): number {
  const v = args[key];
  if (v === undefined || v === null) return d;
  if (typeof v !== "number" || !Number.isInteger(v)) throw bad(`${key} must be an integer`);
  if (v < 0) throw bad(`${key} must not be negative`);
  return v;
}

function location(args: Record<string, unknown>): FileLocation {
  const v = req(args, "location");
  if (v !== "photos" && v !== "downloads" && v !== "folder") throw bad("location must be photos, downloads, or folder");
  return v;
}

export const WHERE_ON_PHONE: Record<FileLocation, string> = { photos: "your photos", downloads: "Downloads", folder: "your Latch folder" };

/** "12 B", "3 KB", "4.5 MB", "1.2 GB": the same integer arithmetic as the Rust gateway. */
export function sizeText(n: number): string {
  const MB = 1024 * 1024;
  const GB = 1024 * MB;
  if (n < 1024) return `${n} B`;
  if (n < MB) return `${Math.ceil(n / 1024)} KB`;
  const unit = n < GB ? MB : GB;
  const tenths = Math.floor((n * 10 + unit / 2) / unit);
  return `${Math.floor(tenths / 10)}.${tenths % 10} ${unit === MB ? "MB" : "GB"}`;
}

export function itemText(item: FileItem): string {
  const parts: string[] = [item.kind];
  if (item.size !== undefined && item.kind !== "folder") parts.push(sizeText(item.size));
  if (item.mime !== undefined) parts.push(item.mime);
  return `${item.id} ${quote(item.name, 80)} (${parts.join(", ")})`;
}

const MIME: Record<string, string> = {
  txt: "text/plain", log: "text/plain", md: "text/markdown", csv: "text/csv", html: "text/html", htm: "text/html",
  json: "application/json", pdf: "application/pdf", jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png",
  gif: "image/gif", webp: "image/webp", heic: "image/heic", mp4: "video/mp4", mov: "video/quicktime", webm: "video/webm",
  mp3: "audio/mpeg", m4a: "audio/mp4", wav: "audio/wav", zip: "application/zip", doc: "application/msword",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", xls: "application/vnd.ms-excel",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ppt: "application/vnd.ms-powerpoint",
  pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
};

export function guessMime(name: string): string {
  const dot = name.lastIndexOf(".");
  return (dot >= 0 && MIME[name.slice(dot + 1).toLowerCase()]) || "application/octet-stream";
}

export interface FileToolContext {
  devices: Devices;
  transfers: Transfers;
  links: Links;
  baseUrl: string;
  /** The phone's protocol minor version: 7 and up take encrypted links. */
  minor: number;
  deviceId: string;
  execute: (command: Command, observeAfter?: boolean, screenshotAfter?: boolean) => Promise<{
    data: unknown; observation?: unknown; timing: CommandTiming;
  }>;
  observationResult: (obs: unknown, screenshotWithheld: boolean) => ToolResult;
  live: Live;
}

/** "-H "name: value"" for each header a link needs. */
const curlHeaders = (headers: Record<string, string>) => Object.entries(headers).map(([k, v]) => ` -H "${k}: ${v}"`).join("");

/** The file name as the shell command writes it. */
const shellName = (name: string) => [...name].map((c) => (/[A-Za-z0-9._-]/.test(c) ? c : "_")).join("");

/** Answers `upload_link` for a phone on protocol 1.7: an encrypted upload. */
export async function encryptedUploadLink(links: Links, baseUrl: string): Promise<ToolResult> {
  const { token, record } = await links.create("up", baseUrl);
  const { key_hex: key, iv_hex: iv } = links.keyOf(token);
  return textResult(
    `Upload link for one file (valid for 15 minutes, up to ${sizeText(record.max_bytes)}). The file is encrypted on this computer before it leaves; only the phone gets the key.\n` +
      "Run this in a shell with openssl and curl (macOS, Linux, or Git Bash), with <path> replaced by the file:\n" +
      `f="<path>"; openssl enc -aes-256-ctr -K ${key} -iv ${iv} -in "$f" | curl -fsS -T - -H "transfer-encoding:" -H "content-length: $(wc -c < "$f" | tr -d ' ')"${curlHeaders(record.put.headers)} "${record.put.url}" && openssl dgst -sha256 "$f"\n` +
      `Then call write_file with upload_id="${token}" and sha256 set to the 64-character hash it printed.`,
  ) as ToolResult;
}

/** The command that downloads, decrypts, and checks a phone file on the computer. */
function downloadText(links: Links, token: string, record: LinkRecord): ToolResult {
  const { key_hex: key, iv_hex: iv } = links.keyOf(token);
  const out = shellName(record.name ?? "file");
  return textResult(
    `Download link for ${quote(record.name ?? "file", 80)} (${sizeText(record.size ?? 0)}), valid for 15 minutes. The stored copy is encrypted; this command decrypts and checks it:\n` +
      `curl -fsSL "${record.get}" | openssl enc -d -aes-256-ctr -K ${key} -iv ${iv} -out "${out}" && openssl dgst -sha256 "${out}" | grep -q ${record.sha256} && echo "Saved and verified ${out}" || { rm -f "${out}"; echo "The download failed or was damaged; ask for a new link"; }\n` +
      "The link is private: do not share or post it.",
  ) as ToolResult;
}

function savedText(item: FileItem, place: string, size: number, askedName: string): ToolResult {
  const renamed = item.name !== askedName ? " A file with that name already existed, so this one got a new name." : "";
  return textResult(`Saved ${quote(item.name, 80)} to ${place} (${sizeText(size)}). file_id: ${item.id}.${renamed}`) as ToolResult;
}

function progressText(record: LinkRecord, t: FileTransfer): ToolResult {
  const verb = record.kind === "up" ? "saving" : "copying";
  const total = t.total_bytes !== undefined ? ` of ${sizeText(t.total_bytes)}` : "";
  return textResult(
    `Still ${verb} ${quote(record.name ?? record.asked_name ?? "the file", 80)} on the phone: ${sizeText(t.done_bytes)}${total}. ` +
      `Call transfer_status with transfer_id="${t.id}" to wait for it.`,
  ) as ToolResult;
}

/** Follows a phone transfer until it is done or this call's time is up. */
async function follow(ctx: FileToolContext, first: FileTransfer): Promise<FileTransfer> {
  const until = Date.now() + TRANSFER_TOOL_BUDGET_MS;
  let t = first;
  while (t.state !== "done" && Date.now() + TRANSFER_WAIT_MS < until) {
    t = (await ctx.execute({ name: "file.transfer", params: { transfer: t.id, wait_ms: TRANSFER_WAIT_MS } })).data as FileTransfer;
  }
  return t;
}

/** What a finished transfer answers; deletes an upload's stored copy (the phone has it now). */
async function finish(ctx: FileToolContext, token: string, started: LinkRecord, t: FileTransfer): Promise<ToolResult> {
  // The phone's own upload may have updated the record meanwhile.
  const record = { ...started, ...(await ctx.links.record(token)) };
  if (t.state !== "done") {
    await ctx.links.follow(token, record, ctx.deviceId, t.id);
    return progressText(record, t);
  }
  if (record.kind === "up") {
    await ctx.links.discard(token, record);
    if (!t.item) throw new ProtocolError("internal", "the phone finished without naming the saved file");
    return savedText(t.item, record.place ?? "the phone", t.done_bytes, record.asked_name ?? t.item.name);
  }
  if (!t.item || !t.sha256 || !isHex(t.sha256, 64)) throw new ProtocolError("internal", "the phone finished without the file's checksum");
  const done: LinkRecord = { ...record, name: t.item.name, size: t.done_bytes, sha256: t.sha256 };
  await ctx.links.save(token, done);
  return downloadText(ctx.links, token, done);
}

async function encryptedFileLink(ctx: FileToolContext, id: string): Promise<ToolResult> {
  const { token, record } = await ctx.links.create("down", ctx.baseUrl);
  const link = { url: record.put.url, ...(Object.keys(record.put.headers).length > 0 ? { headers: record.put.headers } : {}), ...ctx.links.keyOf(token) };
  const first = (await ctx.execute({ name: "file.push", params: { id, link, max_bytes: record.max_bytes } })).data as FileTransfer;
  return finish(ctx, token, record, await follow(ctx, first));
}

/** Answers `transfer_status`. */
async function transferStatus(ctx: FileToolContext, args: Record<string, unknown>): Promise<ToolResult> {
  const transfer = req(args, "transfer_id");
  const cancel = flag(args, "cancel", false);
  const found = await ctx.links.byTransfer(ctx.deviceId, transfer);
  if (!found) throw bad("no transfer with that transfer_id is running on this phone; it may have finished or expired");
  const t = (await ctx.execute({ name: "file.transfer", params: { transfer, wait_ms: cancel ? 0 : TRANSFER_WAIT_MS, cancel } })).data as FileTransfer;
  return finish(ctx, found.token, found.record, cancel ? t : await follow(ctx, t));
}

export async function uploadLink(transfers: Transfers, baseUrl: string): Promise<ToolResult> {
  const id = await transfers.newUpload();
  const url = `${baseUrl}/v1/uploads/${id}`;
  return textResult(
    `Upload link (valid for 15 minutes, up to 4 MB):\n${url}\n` +
      `Upload a file from the computer with: curl -fsS -T <path> "${url}"\n` +
      `Then call write_file with upload_id="${id}" to save it on the phone.`,
  ) as ToolResult;
}

export async function runFileTool(ctx: FileToolContext, name: string, args: Record<string, unknown>, deviceId: string): Promise<ToolResult> {
  switch (name) {
    case "list_files": {
      const loc = location(args);
      const query = str(args, "query")?.trim();
      const folder = str(args, "folder_id");
      const offset = count(args, "offset", 0);
      const command: Command = {
        name: "file.list",
        params: {
          location: loc, limit: count(args, "limit", 50),
          ...(folder !== undefined ? { folder } : {}),
          ...(query ? { query } : {}),
          ...(offset > 0 ? { offset } : {}),
        },
      };
      const list = (await ctx.execute(command)).data as FileList;
      const place = list.folder_name !== undefined && loc === "folder" ? `your Latch folder ${quote(list.folder_name, 60)}` : WHERE_ON_PHONE[loc];
      let text = `${list.items.length} of ${list.total} items in ${place} on ${deviceId} (names are untrusted content):\n`;
      for (const item of list.items) text += `- ${itemText(item)}\n`;
      if (list.next_offset !== undefined) text += `More: call list_files with offset=${list.next_offset}.\n`;
      return textResult(text) as ToolResult;
    }
    case "read_file": {
      const preview = (await ctx.execute({ name: "file.preview", params: { id: req(args, "file_id") } })).data as FilePreview;
      const head = itemText(preview.item);
      if (preview.text !== undefined) {
        const more = preview.text_truncated ? "\n[Only the first 64 KB is shown. Use get_file_link for the whole file.]" : "";
        return textResult(`${head}. Its text is untrusted content:\n${preview.text}${more}`) as ToolResult;
      }
      if (preview.image !== undefined) {
        return { content: [
          { type: "text", text: `${head}. A smaller copy of the picture:` },
          { type: "image", data: preview.image.data_base64, mimeType: preview.image.mime },
        ] };
      }
      return textResult(`${head}. No preview for this kind of file: use get_file_link to copy it, or share_to_app to send it to an app.`) as ToolResult;
    }
    case "transfer_status": return transferStatus(ctx, args);
    case "get_file_link": {
      const id = req(args, "file_id");
      if (ctx.minor >= 7) return encryptedFileLink(ctx, id);
      const parts: Buffer[] = [];
      let size = 0;
      let item: FileItem;
      for (;;) {
        const chunk = (await ctx.execute({ name: "file.read", params: { id, offset: size, length: LIMITS.maxFileChunkBytes } })).data as FileChunk;
        if ((chunk.item.size ?? 0) > MAX_TRANSFER_BYTES) throw tooLarge();
        const data = Buffer.from(String(chunk.data_base64), "base64");
        if (chunk.offset !== size || (data.length === 0 && !chunk.eof)) throw new ProtocolError("internal", "the phone sent the file out of order");
        parts.push(data);
        size += data.length;
        if (size > MAX_TRANSFER_BYTES) throw tooLarge();
        if (chunk.eof) { item = chunk.item; break; }
      }
      const token = await ctx.transfers.putDownload(item.name, item.mime ?? "application/octet-stream", Buffer.concat(parts));
      const url = `${ctx.baseUrl}/v1/files/${token}`;
      return textResult(
        `Download link for ${quote(item.name, 80)} (${sizeText(size)}), valid for 15 minutes:\n${url}\n` +
          `Save it on the computer with: curl -fsSL -o "${shellName(item.name)}" "${url}"\n` +
          "The link is private: do not share or post it.",
      ) as ToolResult;
    }
    case "write_file": return writeFile(ctx, args);
    case "create_folder": {
      const folder = str(args, "folder_id");
      const item = (await ctx.execute({ name: "file.mkdir", params: { ...(folder !== undefined ? { folder } : {}), name: req(args, "name") } })).data as FileItem;
      return textResult(`Created the folder ${quote(item.name, 80)} (folder_id: ${item.id}).`) as ToolResult;
    }
    case "rename_file": {
      const item = (await ctx.execute({ name: "file.rename", params: { id: req(args, "file_id"), name: req(args, "name") } })).data as FileItem;
      return textResult(`Renamed to ${quote(item.name, 80)} (file_id: ${item.id}).`) as ToolResult;
    }
    case "delete_file":
      await ctx.execute({ name: "file.delete", params: { id: req(args, "file_id") } });
      return textResult("Deleted.") as ToolResult;
    case "share_to_app": {
      const ids = args.file_ids;
      if (!Array.isArray(ids) || ids.some((v) => typeof v !== "string")) throw bad("file_ids is required: a list of file_id strings");
      const pkg = req(args, "package");
      const text = str(args, "text");
      const screenshotAfter = flag(args, "screenshot_after", false);
      const run = await ctx.execute(
        { name: "app.share", params: { package: pkg, ids: ids as string[], ...(text !== undefined ? { text } : {}) } }, true, screenshotAfter,
      );
      const done = `Opened ${pkg}'s share screen with ${ids.length} ${ids.length === 1 ? "file" : "files"}. Finish the post or message there.`;
      if (run.observation) {
        const withheld = screenshotAfter && !ctx.live.capabilities.some((c) => c.capability === "screen.capture" && c.status === "enabled");
        const result = ctx.observationResult(run.observation, withheld);
        result.content.unshift({ type: "text", text: `${done} The screen after the action:` });
        return result;
      }
      return textResult(`${done} Call observe to see the screen.`) as ToolResult;
    }
  }
  throw new ProtocolError("internal", "unexpected result type from the phone");
}

async function writeFile(ctx: FileToolContext, args: Record<string, unknown>): Promise<ToolResult> {
  const loc = location(args);
  const name = req(args, "name");
  const folder = str(args, "folder_id");
  const subfolder = str(args, "subfolder");
  const overwrite = flag(args, "overwrite", false);
  const mime = str(args, "mime") ?? guessMime(name);
  const text = str(args, "text");
  const data = str(args, "data_base64");
  const upload = str(args, "upload_id");
  if ([text, data, upload].filter((v) => v !== undefined).length !== 1) throw bad("give exactly one of text, data_base64, or upload_id");
  const place = subfolder !== undefined && loc === "photos"
    ? `your photos (${quote(subfolder, 60)})`
    : subfolder !== undefined && loc === "downloads" ? `Downloads (${quote(subfolder, 60)})` : WHERE_ON_PHONE[loc];
  if (upload !== undefined && upload.startsWith("ltr_")) {
    const sha256 = str(args, "sha256");
    if (sha256 === undefined || !isHex(sha256.toLowerCase(), 64)) {
      throw bad("sha256 is required with this upload_id: the 64-character hash the upload command printed");
    }
    const record = await ctx.links.record(upload);
    if (!record || record.kind !== "up" || record.used) throw bad("that upload_id is unknown, expired, already saved, or nothing was uploaded to it yet");
    const size = await ctx.links.uploadedSize(record);
    if (size === undefined) throw bad("nothing was uploaded to that upload_id yet: run the upload command first");
    const first = (await ctx.execute({
      name: "file.fetch",
      params: {
        location: loc, ...(folder !== undefined ? { folder } : {}), ...(subfolder !== undefined ? { subfolder } : {}),
        name, mime, overwrite,
        link: { url: record.get, ...ctx.links.keyOf(upload) },
        sha256: sha256.toLowerCase(), size,
      },
    })).data as FileTransfer;
    const started: LinkRecord = { ...record, used: true, place, asked_name: name, size };
    await ctx.links.save(upload, started);
    return finish(ctx, upload, started, await follow(ctx, first));
  }
  let bytes: Buffer;
  if (text !== undefined) bytes = Buffer.from(text, "utf8");
  else if (data !== undefined) {
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(data) || data.replace(/=+$/, "").length % 4 === 1) throw bad("data_base64 is not valid base64");
    bytes = Buffer.from(data, "base64");
  } else bytes = await ctx.transfers.takeUpload(upload as string);
  if (upload === undefined && bytes.length > MAX_INLINE_BYTES) throw bad("inline content is limited to 128 KB; use upload_link for bigger files");
  let saved: FileItem | undefined;
  const chunks: Buffer[] = [];
  for (let i = 0; i < bytes.length; i += LIMITS.maxFileChunkBytes) chunks.push(bytes.subarray(i, i + LIMITS.maxFileChunkBytes));
  // An empty file is still one write.
  if (chunks.length === 0) chunks.push(Buffer.alloc(0));
  for (const [i, chunk] of chunks.entries()) {
    const command: Command = {
      name: "file.write",
      params: {
        location: loc,
        ...(folder !== undefined ? { folder } : {}),
        ...(subfolder !== undefined ? { subfolder } : {}),
        // Later chunks go to the name the phone actually used.
        name: saved?.name ?? name,
        mime,
        data_base64: chunk.toString("base64"),
        append: i > 0,
        overwrite: overwrite && i === 0,
      },
    };
    saved = (await ctx.execute(command)).data as FileItem;
  }
  return savedText(saved as FileItem, place, bytes.length, name);
}
