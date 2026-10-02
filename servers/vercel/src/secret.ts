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
export function pairingCode(): string {
  const bytes = randomBytes(8);
  return [...bytes].map((b) => ALPHABET[b % ALPHABET.length]).join("");
}
export const normalizeCode = (code: string) => code.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
