// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/** Reads a file from packages/schemas/v1 (tests run inside the repository). */
export function shared(path: string): string {
  return readFileSync(fileURLToPath(new URL(`../../../packages/schemas/v1/${path}`, import.meta.url)), "utf8");
}
