// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export const sha256 = (s: string) => createHash("sha256").update(s, "utf8").digest("hex");

/** 256-bit bearer token with a recognisable prefix for secret scanners. */
export const newToken = (prefix: string) => `${prefix}_${randomBytes(32).toString("hex")}`;

/** Short random identifier. Not a secret. */
export const newId = (prefix: string) => `${prefix}_${randomBytes(8).toString("hex")}`;

/** Constant-time comparison of two secrets of any length. */
export function secretsEqual(a: string, b: string): boolean {
  return timingSafeEqual(createHash("sha256").update(a).digest(), createHash("sha256").update(b).digest());
}

export function bearer(header: string | null): string | undefined {
  if (!header) return undefined;
  const space = header.indexOf(" ");
  if (space < 0 || header.slice(0, space).toLowerCase() !== "bearer") return undefined;
  const token = header.slice(space + 1).trim();
  return token === "" ? undefined : token;
}

/** Pairing codes avoid 0/O and 1/I/L: people type them on phones. */
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
/** Rejection sampling: `byte % 31` would make some letters likelier than others. */
export function pairingCode(length = 8): string {
  const limit = 256 - (256 % ALPHABET.length);
  let code = "";
  while (code.length < length) {
    for (const b of randomBytes(length * 2)) {
      if (b < limit && code.length < length) code += ALPHABET[b % ALPHABET.length];
    }
  }
  return code;
}
export const normalizeCode = (code: string) => code.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
