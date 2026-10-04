// Vercel Function entry point. vercel.json rewrites /mcp, /healthz, and /v1/*
// here; the static page at "/" is served from public/.
import { Gateway, configFromEnv } from "../src/gateway.js";
import { storeFromEnv } from "../src/store.js";

const store = storeFromEnv(process.env);
const gateway = store ? new Gateway(store, configFromEnv(process.env)) : undefined;

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
