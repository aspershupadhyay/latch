// Encrypted file links (protocol 1.7, ADR-027). A whole file moves between the
// AI's computer and the phone without passing through this function or the
// AI's context: the computer and the phone upload and download it themselves.
//
// What is stored is always AES-256-CTR ciphertext under a key made for that
// one transfer. The key is derived here from the gateway's own secret and the
// transfer's token, and is never stored: not in Redis, not with the file. The
// storage (Vercel Blob, or this gateway's Redis for small files) only ever
// holds scrambled bytes, and the plain file's SHA-256 travels separately, so
// any change on the way is caught.

import { createHash, createHmac } from "node:crypto";
import { issueSignedToken, presignUrl, del as blobDelete, head as blobHead } from "@vercel/blob";
import { newToken } from "./secret.js";
import type { Store } from "./store.js";

/** How long a link works. */
export const LINK_TTL_MS = 15 * 60 * 1000;
/** Largest file this gateway's own (Redis) storage carries: Vercel functions take 4.5 MB per request. */
export const GATEWAY_LINK_BYTES = 4 * 1024 * 1024;
/** Default largest file through Vercel Blob; LATCH_MAX_TRANSFER_MB changes it. */
export const DEFAULT_BLOB_LINK_BYTES = 2 * 1024 * 1024 * 1024;
/** Redis values stay well under Upstash's request limit. */
const PART_BYTES = 512 * 1024;
/** Blob's API version the signed upload must name (what @vercel/blob 2.8 sends). */
const BLOB_API_VERSION = "12";

export interface Target { url: string; headers: Record<string, string> }

/** One transfer, kept in Redis for the link's life. Never holds the key. */
export interface LinkRecord {
  kind: "up" | "down";
  storage: "blob" | "gateway";
  /** Where the bytes are: a Blob pathname, or this record's own token. */
  path: string;
  put: Target;
  get: string;
  max_bytes: number;
  /** Gateway storage: bytes arrived. */
  filled?: boolean;
  /** An upload was handed to a phone (each upload is saved once). */
  used?: boolean;
  /** Phone side of a running transfer, for transfer_status. */
  device?: string;
  transfer?: string;
  /** What the tool answers once the phone is done. */
  name?: string;
  size?: number;
  sha256?: string;
  place?: string;
  asked_name?: string;
}

const recordKey = (token: string) => `latch:link:${token}`;
const partKey = (token: string, i: number) => `latch:link:${token}:${i}`;
const byTransferKey = (device: string, transfer: string) => `latch:linkxfer:${device}:${transfer}`;
/** Blob pathnames to delete once their link expires (field = path, value = expiry ms). */
const GC_KEY = "latch:linkgc";

const TOKEN = /^ltr_[0-9a-f]{64}$/;

/** Signs Vercel Blob URLs for a private store. */
export class BlobLinks {
  constructor(readonly maxBytes: number) {}

  /** A PUT target and a GET URL for `path`, both valid for the link's life. */
  async prepare(path: string, maxBytes: number): Promise<{ put: Target; get: string }> {
    const validUntil = Date.now() + LINK_TTL_MS;
    const token = await issueSignedToken({ pathname: path, operations: ["put", "get"], validUntil, maximumSizeInBytes: maxBytes });
    const put = await presignUrl(token, {
      operation: "put", pathname: path, access: "private", maximumSizeInBytes: maxBytes, addRandomSuffix: false, allowOverwrite: false, validUntil,
    });
    const get = await presignUrl(token, { operation: "get", pathname: path, access: "private", validUntil, useCache: false });
    return {
      put: {
        url: put.presignedUrl,
        headers: { "x-api-version": BLOB_API_VERSION, "x-vercel-blob-access": "private", "x-vercel-blob-store-id": storeIdOf(token.delegationToken) },
      },
      get: get.presignedUrl,
    };
  }

  async size(path: string): Promise<number | undefined> {
    try {
      return (await blobHead(path)).size;
    } catch {
      return undefined;
    }
  }

  async remove(paths: string[]): Promise<void> {
    if (paths.length > 0) await blobDelete(paths).catch(() => undefined);
  }
}

function storeIdOf(delegationToken: string): string {
  try {
    const payload = JSON.parse(Buffer.from(delegationToken.split(".")[0] ?? "", "base64url").toString("utf8")) as { storeId?: string };
    return String(payload.storeId ?? "").replace(/^store_/, "");
  } catch {
    return "";
  }
}

/** Vercel Blob is used when the project has a Blob store connected. */
export function blobLinksFromEnv(env: NodeJS.ProcessEnv): BlobLinks | undefined {
  if (!env.BLOB_READ_WRITE_TOKEN && !env.BLOB_STORE_ID) return undefined;
  const mb = Number(env.LATCH_MAX_TRANSFER_MB ?? "");
  const max = Number.isFinite(mb) && mb > 0 ? Math.min(mb * 1024 * 1024, 4 * 1024 * 1024 * 1024) : DEFAULT_BLOB_LINK_BYTES;
  return new BlobLinks(Math.floor(max));
}

export class Links {
  constructor(
    private readonly store: Store,
    /** The gateway's own secret (its admin token); keys are derived from it. */
    private readonly secret: string,
    readonly blob?: BlobLinks,
  ) {}

  get maxBytes(): number {
    return this.blob?.maxBytes ?? GATEWAY_LINK_BYTES;
  }

  /** The transfer's AES-256-CTR key and counter, derived, never stored. */
  keyOf(token: string): { key_hex: string; iv_hex: string } {
    const mac = (label: string) => createHmac("sha256", this.secret).update(`latch link ${label}\0${token}`).digest("hex");
    return { key_hex: mac("key"), iv_hex: mac("iv").slice(0, 32) };
  }

  /** A new link. `baseUrl` is where phones and computers reach this gateway. */
  async create(kind: "up" | "down", baseUrl: string): Promise<{ token: string; record: LinkRecord }> {
    await this.collect();
    const token = newToken("ltr");
    let record: LinkRecord;
    if (this.blob) {
      // The pathname does not reveal the token.
      const path = `latch/${createHash("sha256").update(token).digest("hex").slice(0, 40)}.bin`;
      const { put, get } = await this.blob.prepare(path, this.blob.maxBytes);
      record = { kind, storage: "blob", path, put, get, max_bytes: this.blob.maxBytes };
      await this.store.hset(GC_KEY, path, String(Date.now() + LINK_TTL_MS));
    } else {
      const url = `${baseUrl}/v1/blobs/${token}`;
      record = { kind, storage: "gateway", path: token, put: { url, headers: {} }, get: url, max_bytes: GATEWAY_LINK_BYTES };
    }
    await this.save(token, record);
    return { token, record };
  }

  async record(token: string): Promise<LinkRecord | undefined> {
    if (!TOKEN.test(token)) return undefined;
    const raw = await this.store.get(recordKey(token));
    return raw ? (JSON.parse(raw) as LinkRecord) : undefined;
  }

  async save(token: string, record: LinkRecord): Promise<void> {
    await this.store.set(recordKey(token), JSON.stringify(record), { px: LINK_TTL_MS });
  }

  /** Remembers which link a phone transfer belongs to, for transfer_status. */
  async follow(token: string, record: LinkRecord, device: string, transfer: string): Promise<void> {
    await this.save(token, { ...record, device, transfer });
    await this.store.set(byTransferKey(device, transfer), token, { px: LINK_TTL_MS });
  }

  async byTransfer(device: string, transfer: string): Promise<{ token: string; record: LinkRecord } | undefined> {
    const token = await this.store.get(byTransferKey(device, transfer));
    const record = token ? await this.record(token) : undefined;
    return token && record ? { token, record } : undefined;
  }

  /** Size of what was uploaded to a link, or undefined if nothing was. */
  async uploadedSize(record: LinkRecord): Promise<number | undefined> {
    if (record.storage === "blob") return this.blob?.size(record.path);
    if (!record.filled) return undefined;
    return record.size;
  }

  /** Deletes a link's stored bytes now (the phone has them). */
  async discard(token: string, record: LinkRecord): Promise<void> {
    if (record.storage === "blob") {
      await this.blob?.remove([record.path]);
      await this.store.hdel(GC_KEY, record.path);
    } else {
      const parts = Math.ceil((record.size ?? 0) / PART_BYTES);
      for (let i = 0; i < parts; i++) await this.store.del(partKey(token, i));
    }
  }

  /** Deletes stored copies whose links expired (Blob keeps files until deleted). */
  async collect(): Promise<void> {
    if (!this.blob) return;
    const now = Date.now();
    const expired = Object.entries(await this.store.hgetall(GC_KEY)).filter(([, at]) => Number(at) < now).map(([path]) => path).slice(0, 50);
    if (expired.length === 0) return;
    await this.blob.remove(expired);
    for (const path of expired) await this.store.hdel(GC_KEY, path);
  }

  // ---- This gateway's own storage (no Blob store connected) ----

  /** "ok", or why not. Each link is filled once. */
  async putBytes(token: string, bytes: Buffer): Promise<"ok" | "too_large" | "used" | "unknown"> {
    const record = await this.record(token);
    if (!record || record.storage !== "gateway") return "unknown";
    if (bytes.length > GATEWAY_LINK_BYTES) return "too_large";
    if (record.filled) return "used";
    const parts = Math.ceil(bytes.length / PART_BYTES);
    for (let i = 0; i < parts; i++) {
      await this.store.set(partKey(token, i), bytes.subarray(i * PART_BYTES, (i + 1) * PART_BYTES).toString("base64"), { px: LINK_TTL_MS });
    }
    await this.save(token, { ...record, filled: true, size: bytes.length });
    return "ok";
  }

  async getBytes(token: string): Promise<Buffer | undefined> {
    const record = await this.record(token);
    if (!record || record.storage !== "gateway" || !record.filled) return undefined;
    const parts = Math.ceil((record.size ?? 0) / PART_BYTES);
    if (parts === 0) return Buffer.alloc(0);
    const values = await this.store.mget(Array.from({ length: parts }, (_, i) => partKey(token, i)));
    if (values.some((v) => v === null)) return undefined;
    return Buffer.concat(values.map((v) => Buffer.from(v as string, "base64")));
  }
}
