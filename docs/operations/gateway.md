# Running the Latch gateway

The gateway is one small binary (or an ~11 MB container image) that AI clients and phones both connect to. Phones dial **out** to it, so it can run anywhere both can reach: a cloud container, a VPS, a home server, or a laptop on the same Wi-Fi for development. Latch runs no relay of its own; you operate the gateway.

> **Privacy:** screen content passes through the gateway's memory while a session is active. Run it on infrastructure you trust. It never writes screen content to disk or logs.

## Configuration

| Variable | Required | Meaning |
|---|---|---|
| `LATCH_ADMIN_TOKEN` | yes | The owner key: the pairing, revocation, and AI-key API, used by the Latch app. ≥ 32 characters. Never give it to an AI client, and never type it into a web page. |
| `LATCH_MCP_TOKEN` | yes (`serve`) | Bearer token MCP clients send. ≥ 32 characters, different from the admin token. |
| `LATCH_PUBLIC_URL` | recommended | Public `https://` base URL, shown to phones during pairing. |
| `LATCH_BIND` | no | Listen address. Default `127.0.0.1:8787`; with `PORT` set (Cloud Run, Render, Railway, Fly) it is `0.0.0.0:$PORT`; the container image uses `0.0.0.0:8787`. |
| `LATCH_DATA_DIR` | no | Holds `devices.json` (device ids, names, token hashes). Default `./latch-data`; `/data` in the image. Back it up; losing it means re-pairing phones. |
| `LATCH_MAX_TRANSFER_MB` | no | Largest file one link carries, in MB (default 2048, at most 4096). Links are streamed to a private temporary folder (mode 700) as AES-256 ciphertext; keys live only in memory; files are deleted when the phone has them or the link expires (15 minutes). All links together hold at most four times this size. |
| `LATCH_ALLOWED_ORIGINS` | no | Comma-separated browser origins allowed to call `/mcp`. Requests carrying any other `Origin` header are rejected (DNS-rebinding defence). Server-side clients send no `Origin` and are unaffected. |
| `RUST_LOG` | no | Log filter, default `info`. Logs never contain screen text, typed text, or tokens. |

Generate tokens with `latch-gateway gen-token` (or `openssl rand -hex 32`).

## Endpoints

| Path | Who | Auth |
|---|---|---|
| `POST /mcp` | MCP clients (Streamable HTTP, JSON responses) | `Authorization: Bearer $LATCH_MCP_TOKEN` |
| `GET /v1/device` | Phones (WebSocket) | per-phone token issued at pairing |
| `POST /v1/pair` | Phones, once | single-use pairing code |
| `/v1/admin/*` | You, from the Latch app (or curl) | `LATCH_ADMIN_TOKEN` |
| `GET /` | Anyone | none; a static "private gateway" page with no scripts, forms, or data |
| `GET /healthz` | Load balancers | none; returns status, version, and protocol only |

**Private by default.** Every answer carries `X-Robots-Tag: noindex, nofollow, noarchive, nosnippet, noimageindex`, `X-Frame-Options: DENY`, HSTS, and a locked-down `Permissions-Policy`; `/robots.txt` disallows everything. Search engines and browsers get nothing to index, frame, or sign in to. Access still rests on the keys, not on the address staying secret: hosting addresses appear in public certificate logs. See ADR-029.

## Deploy

**Any container platform.** Deploy the image (`docker build -t latch-gateway .`, or `ghcr.io/<owner>/latch-gateway:<version>` once released), set the two tokens and `LATCH_PUBLIC_URL`, mount a persistent volume at `/data`, and expose port 8787 over HTTPS. The platform must support WebSockets and keep idle connections open for at least 60 s (the gateway pings every 20 s). Run **one** instance: device connections live in process memory.

**VPS or home server with automatic HTTPS.** `deploy/docker-compose.yml` runs the gateway behind Caddy, which obtains certificates automatically:

```sh
cd deploy && cp .env.example .env   # set LATCH_DOMAIN and both tokens
docker compose up -d
```

**Local development, or a phone on the same Wi-Fi.** `scripts/dev.sh` builds and starts a gateway with fresh tokens plus a simulated phone. Debug builds of the Android app accept `http://` addresses; release builds require `https://`.

**Inside an MCP client over stdio.** `latch-gateway stdio` speaks MCP on stdin/stdout and still serves phones over HTTP, for clients that only launch local commands.

## Connect an AI client

Claude Code:

```sh
claude mcp add --transport http latch https://latch.example.com/mcp \
  --header "Authorization: Bearer $LATCH_MCP_TOKEN"
```

Other clients: Streamable HTTP URL `https://latch.example.com/mcp` with header `Authorization: Bearer <LATCH_MCP_TOKEN>`. The app's **Connect** tab creates one key per AI app with ready-to-paste snippets.

## Day-2 operations

- **Pair a phone:** in the Latch app, enter the gateway address and the owner key; the app creates its own single-use code and pairs. Without the app: `curl -X POST https://latch.example.com/v1/admin/pairings -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"name":"Pixel"}'` and enter the code within 10 minutes. 20 wrong guesses a minute lock pairing for a minute.
- **Revoke a phone:** in the app, or `curl -X DELETE https://latch.example.com/v1/admin/devices/<id> -H "Authorization: Bearer $LATCH_ADMIN_TOKEN"`. The phone is disconnected immediately and its token stops working.
- **Rotate the MCP token:** change `LATCH_MCP_TOKEN` and restart; update clients. Phones are unaffected.
- **Rotate the admin token:** change `LATCH_ADMIN_TOKEN` and restart.
- **Suspected leak of a phone token:** revoke that phone and pair again.
- **Emergency stop for everything:** stop the gateway process. Phones show "Connection lost" and run nothing; their own Stop button also always works.
- **Upgrade:** pull the new image and restart. `devices.json` is forward compatible; phones reconnect automatically.
- **Back up:** copy `devices.json` (it holds only hashes, but treat it as sensitive).
