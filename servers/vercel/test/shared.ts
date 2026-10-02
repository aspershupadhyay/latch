import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/** Reads a file from packages/schemas/v1 (tests run inside the repository). */
export function shared(path: string): string {
  return readFileSync(fileURLToPath(new URL(`../../../packages/schemas/v1/${path}`, import.meta.url)), "utf8");
}
