// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Device-key signatures (ADR-037, roadmap R19): the same rules as
// servers/mcp/src/signing.rs. A phone on protocol 1.12 signs every message
// with a P-256 key from its keystore; once the gateway has seen the key, it
// refuses anything from that phone that is not signed by it. ECDSA P-256 /
// SHA-256, keys as SPKI DER, signatures as DER, both base64 (ADR-014).
import { createPublicKey, verify as cryptoVerify } from "node:crypto";

const SIGNING_CONTEXT = "latch-signed-v1";
const MAX_DEVICE_KEY_CHARS = 256;
const MAX_SIGNATURE_CHARS = 128;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

export type Refused = "malformed" | "bad_signature" | "unsigned" | "key_changed" | "unsigned_key";

/** The same texts as the Rust gateway; never echoes the message. */
export const REFUSED: Record<Refused, string> = {
  malformed: "unparseable device message",
  bad_signature: "the message signature does not match this phone's device key",
  unsigned: "this phone signs its messages; an unsigned message was refused",
  key_changed: "this phone's device key changed (reinstalled?); pair it again from the Latch app",
  unsigned_key: "a hello that carries a device_key must be signed by it",
};

const isBase64 = (v: unknown, max: number): v is string =>
  typeof v === "string" && v.length > 0 && v.length <= max && v.length % 4 === 0 && BASE64.test(v);

/** The bytes a phone signs for one message (see `signing_input` in crates/protocol). */
export const signingInput = (connection: string, payload: string) => Buffer.from(`${SIGNING_CONTEXT}\n${connection}\n${payload}`, "utf8");

/** Whether `sig` signs the message `payload` on `connection` with the phone's `key`. */
export function verifySignature(key: string, connection: string, payload: string, sig: string): boolean {
  if (!isBase64(key, MAX_DEVICE_KEY_CHARS) || !isBase64(sig, MAX_SIGNATURE_CHARS) || payload.length === 0) return false;
  try {
    const publicKey = createPublicKey({ key: Buffer.from(key, "base64"), format: "der", type: "spki" });
    if (publicKey.asymmetricKeyType !== "ec" || publicKey.asymmetricKeyDetails?.namedCurve !== "prime256v1") return false;
    return cryptoVerify("sha256", signingInput(connection, payload), { key: publicKey, dsaEncoding: "der" }, Buffer.from(sig, "base64"));
  } catch {
    return false;
  }
}

type Message = Record<string, unknown>;
const parse = (text: string): Message | undefined => {
  try {
    const v = JSON.parse(text) as unknown;
    return typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Message) : undefined;
  } catch {
    return undefined;
  }
};

/** A phone's hello checked against its registered key; `newKey` is set the first time it shows one. */
export function openHello(body: Message, registered: string | undefined):
  { ok: true; hello: Message; newKey?: string } | { ok: false; refused: Refused } {
  if (body.type === "signed") {
    const hello = typeof body.payload === "string" ? parse(body.payload) : undefined;
    if (hello?.type !== "hello") return { ok: false, refused: "malformed" };
    const key = hello.device_key;
    if (typeof key !== "string") return { ok: false, refused: "unsigned_key" };
    if (registered !== undefined && registered !== key) return { ok: false, refused: "key_changed" };
    if (!verifySignature(key, "", body.payload as string, String(body.sig))) return { ok: false, refused: "bad_signature" };
    return registered === undefined ? { ok: true, hello, newKey: key } : { ok: true, hello };
  }
  if (body.type !== "hello") return { ok: false, refused: "malformed" };
  if (registered !== undefined) return { ok: false, refused: "unsigned" };
  if (body.device_key !== undefined) return { ok: false, refused: "unsigned_key" };
  return { ok: true, hello: body };
}

/** A phone's message after its hello, signed when the phone registered a key. */
export function openMessage(body: Message, registered: string | undefined, connection: string):
  { ok: true; message: Message } | { ok: false; refused: Refused } {
  if (body.type === "signed") {
    if (registered === undefined || typeof body.payload !== "string") return { ok: false, refused: "bad_signature" };
    if (!verifySignature(registered, connection, body.payload, String(body.sig))) return { ok: false, refused: "bad_signature" };
    const message = parse(body.payload);
    if (!message || message.type === "signed") return { ok: false, refused: "malformed" };
    return { ok: true, message };
  }
  if (registered !== undefined) return { ok: false, refused: "unsigned" };
  return { ok: true, message: body };
}
