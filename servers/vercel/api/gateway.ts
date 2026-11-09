// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Vercel Function entry point. vercel.json rewrites "/", /mcp, /healthz, and /v1/*
// here; only the stylesheet and robots.txt are static (public/).
import { Relay } from "../src/relay.js";
import { storeFromEnv } from "../src/store.js";

const store = storeFromEnv(process.env);
// ADR-075: the owner key comes from LATCH_ADMIN_TOKEN or from the phone's claim.
const gateway = store ? new Relay(store, process.env, "vercel") : undefined;

async function handle(request: Request): Promise<Response> {
  if (!gateway) {
    return new Response(
      JSON.stringify({ error: "No Redis store is connected. In Vercel: Storage → Create → Upstash Redis → connect it to this project, then redeploy." }),
      { status: 503, headers: { "content-type": "application/json" } },
    );
  }
  return gateway.fetch(request);
}

export const GET = handle;
export const POST = handle;
// `curl -T` uploads to /v1/uploads/<id> with PUT.
export const PUT = handle;
export const DELETE = handle;
export const OPTIONS = handle;
