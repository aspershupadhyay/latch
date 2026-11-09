# Where to host your Latch gateway

Latch runs no server of its own. Your phone dials **out** to a gateway you run, and your AI app reaches the same gateway over MCP. Any place both can reach works. The phone can keep several at once (one Vercel relay, one Cloudflare relay, one computer on Wi-Fi; ADR-075) and uses all of them in every session, so one running out or going down does not stop your AI.

Plans and free tiers change often: the notes below were written in November 2026 and are not checked by CI. Check each host's current pricing before you rely on it.

## One tap, nothing to type (from the Latch app)

| Host | What runs | Free plan fits personal use | Needs | Guide |
|---|---|---|---|---|
| **Cloudflare Workers** | TypeScript relay in one Durable Object (SQLite inside) | Yes: 100,000 requests a day | Cloudflare and GitHub accounts | [cloudflare.md](cloudflare.md) |
| **Vercel** | TypeScript relay in a function, Upstash Redis | Yes: 1M function calls and 500,000 Redis commands a month | Vercel account (Upstash is added for you) | [vercel.md](vercel.md) |
| **Your computer on Wi-Fi** | Rust gateway (`latch start`) | Free | The computer on, on the same Wi-Fi | [gateway.md](gateway.md), [connect-your-phone.md](../connect-your-phone.md) |

## The Rust gateway as a container (a few commands)

The `latch-gateway` image (about 11 MB) is the same gateway as on your computer, with WebSockets, live mirror, and files up to 2 GB. It needs a disk that survives restarts (`LATCH_DATA_DIR`, default `/data`), and `LATCH_ADMIN_TOKEN` and `LATCH_MCP_TOKEN` set as secrets ([settings](gateway.md#configuration)). With `PORT` set (most hosts below) it listens on `0.0.0.0:$PORT`.

| Host | Free or cheapest | Lasting disk | Notes |
|---|---|---|---|
| **Fly.io** | Pay as you go, a few dollars a month | Volumes | `fly launch` with the Dockerfile, one volume at `/data` |
| **Render** | Starter plan (free web services sleep and lose their disk) | Persistent disk (paid) | Use a paid instance with a disk at `/data` |
| **Railway** | Usage-based | Volumes | Add a volume at `/data` |
| **Koyeb** | Small free instance | Volumes on paid plans | Free instances sleep when idle, which drops the phone |
| **Google Cloud Run** | Free tier, then per use | Not on the instance; mount a Cloud Storage bucket | Set minimum instances to 1 so the phone's connection is not cut |
| **Azure Container Apps** | Free monthly grant | Azure Files | Minimum replicas 1 |
| **Northflank** | Free developer project | Volumes | |
| **A VPS** (Hetzner, DigitalOcean, Linode, OVH; Oracle Cloud's Always Free ARM) | From free (Oracle) or a few euros a month | The VPS disk | `deploy/docker-compose.yml` with Caddy for HTTPS |
| **A home server or NAS** | Free | Yours | Reach it from outside with a tunnel (Cloudflare Tunnel, Tailscale Funnel) or a port forward with HTTPS |

## Not supported yet

- **Netlify Functions, Deno Deploy, AWS Lambda behind API Gateway:** their requests end long before a phone's long poll does, or their free tiers have no store the relay can rely on. The TypeScript relay could be adapted (it needs a `fetch(Request) → Response` entry and a store with the operations in `servers/vercel/src/store.ts`); contributions welcome.
- **Backblaze B2:** a file store, not a host (it runs no code). It is planned as the store for big files on a Cloudflare relay, through its S3-compatible API (ADR-075).

## Which to choose

- **Your AI app runs on your computer** (Claude Code, Claude Desktop, Cursor): the gateway on that computer is fastest and keeps everything at home. Add a hosted relay as a backup for when you are away.
- **Your AI app runs in the cloud** (Claude on the web, ChatGPT): a hosted relay. Cloudflare answers quickest (no database between the phone and the AI); Vercel is the longest-tested. Both together give the AI a second way in.
- **You need live mirror on the internet or files over 4 MB:** the Rust container on a VPS or Fly.io.
