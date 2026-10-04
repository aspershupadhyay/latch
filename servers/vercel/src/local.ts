// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Runs the Vercel gateway as a plain Node server, for development and tests:
//   LATCH_ADMIN_TOKEN=... npm run dev     (in-memory store unless Upstash env is set)
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { Gateway, PRIVATE_HEADERS, configFromEnv } from "./gateway.js";
import { MemoryStore, storeFromEnv } from "./store.js";

const STATIC: Record<string, [string, string]> = {
  "/": ["index.html", "text/html; charset=utf-8"],
  "/gateway.css": ["gateway.css", "text/css; charset=utf-8"],
  "/robots.txt": ["robots.txt", "text/plain; charset=utf-8"],
};

export const PAGE_CSP = "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

async function toRequest(req: IncomingMessage, origin: string): Promise<Request> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string") headers.set(k, v);
  const body = chunks.length > 0 && req.method !== "GET" && req.method !== "HEAD" ? Buffer.concat(chunks) : undefined;
  return new Request(new URL(req.url ?? "/", origin), { method: req.method, headers, body });
}

async function send(res: ServerResponse, response: Response) {
  res.statusCode = response.status;
  response.headers.forEach((v, k) => res.setHeader(k, v));
  if (!response.body) {
    res.end();
    return;
  }
  // Streamed, so event-stream answers (elicitation) reach the client as they happen.
  res.flushHeaders();
  const reader = response.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    res.write(value);
  }
  res.end();
}

export function startLocal(gateway: Gateway, port: number) {
  const server = createServer(async (req, res) => {
    const path = new URL(req.url ?? "/", "http://x").pathname;
    const asset = STATIC[path];
    if (asset && req.method === "GET") {
      const file = fileURLToPath(new URL(`../public/${asset[0]}`, import.meta.url));
      // What vercel.json sets for public/ in production.
      for (const [k, v] of Object.entries(PRIVATE_HEADERS)) res.setHeader(k, v);
      res.setHeader("content-security-policy", PAGE_CSP);
      res.setHeader("content-type", asset[1]);
      res.end(await readFile(file));
      return;
    }
    await send(res, await gateway.fetch(await toRequest(req, `http://${req.headers.host ?? "localhost"}`)));
  });
  return new Promise<typeof server>((resolve) => server.listen(port, "127.0.0.1", () => resolve(server)));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const store = storeFromEnv(process.env) ?? new MemoryStore();
  const port = Number(process.env.PORT ?? 8788);
  startLocal(new Gateway(store, configFromEnv(process.env)), port).then(() => {
    console.log(`Latch (Vercel build) on http://127.0.0.1:${port} with ${store instanceof MemoryStore ? "an in-memory store" : "Upstash Redis"}`);
  });
}
