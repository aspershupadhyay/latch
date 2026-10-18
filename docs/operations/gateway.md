# Running the Latch gateway

The gateway is one small binary (or an ~11 MB container image) that AI clients and phones both connect to. Phones dial **out** to it, so it can run anywhere both can reach: a cloud container, a VPS, a home server, or a laptop on the same Wi-Fi for development. Latch runs no relay of its own; you operate the gateway.

> **Privacy:** screen content passes through the gateway's memory while a session is active. Run it on infrastructure you trust. It never writes screen content to disk or logs.

## Download

**macOS and Linux, one line** (checks the checksum, installs `latch-gateway` and `latch-start` in `~/.local/bin`, and makes your keys once in `~/.latch/latch.env`):

```sh
curl -fsSL https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.sh | sh
```

Then `latch-start` runs it for phones on this Wi-Fi and prints the owner key, the AI key, and the address for the phone. Step by step with the phone: [docs/connect-your-phone.md](../connect-your-phone.md).

**Why not the browser download on a Mac:** a file downloaded in a browser gets macOS's quarantine flag, and macOS refuses a quarantined program that Apple has not notarized ("cannot be opened… Move to Trash"); `chmod +x` cannot change that. `curl` sets no such flag. If you already downloaded it in a browser: `xattr -d com.apple.quarantine ~/Downloads/latch-gateway-macos-arm64 && chmod +x ~/Downloads/latch-gateway-macos-arm64`, or open it once, then **System Settings → Privacy & Security → Open Anyway**.

Every merge to `main` publishes the files themselves (~7 MB) next to the Android beta, at `https://github.com/aspershupadhyay/latch/releases/download/beta/<file>`: `latch-gateway-linux-x86_64`, `latch-gateway-macos-arm64` (Apple Silicon), `latch-gateway-macos-x86_64` (Intel Mac), or `latch-gateway-windows-x86_64.exe`, with checksums in `latch-gateway.sha256`. The macOS files are signed ad hoc (Apple Silicon runs nothing unsigned) but not notarized. Building from source (`cargo build --release -p latch-gateway`) needs about 2 GB for the Rust toolchain while building.

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
| `LATCH_CLIENT_CALLS_PER_MINUTE` | no | Tool calls each AI app may make per minute (default 120, `0` for no limit). Over it, the AI reads `rate_limited: …` and waits; other AI apps are unaffected (roadmap R18). |
| `LATCH_WIFI` | no | `1`: also serve phones on this Wi-Fi with TLS on `0.0.0.0:8788` ([below](#phones-on-the-same-wi-fi-no-cloud)). |
| `LATCH_WIFI_BIND` | no | The Wi-Fi listener's address instead, e.g. `0.0.0.0:9443` (implies `LATCH_WIFI`). |
| `RUST_LOG` | no | Log filter, default `info`. Logs never contain screen text, typed text, or tokens. |

Generate tokens with `latch-gateway gen-token` (or `openssl rand -hex 32`).

## Endpoints

| Path | Who | Auth |
|---|---|---|
| `POST /mcp` | MCP clients (Streamable HTTP, JSON responses) | OAuth access token, a key from the app, or `Authorization: Bearer $LATCH_MCP_TOKEN` |
| `/.well-known/oauth-*`, `/oauth/*` | MCP clients signing in (roadmap R17) | none to start; the owner approves each app in the Latch app |
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

**Local development.** `scripts/dev.sh` builds and starts a gateway with fresh tokens plus a simulated phone. Debug builds of the Android app accept `http://` addresses; release builds require `https://`. For a real phone on the same Wi-Fi, see the next section.

**Inside an MCP client over stdio.** `latch-gateway stdio` speaks MCP on stdin/stdout and still serves phones over HTTP, for clients that only launch local commands.

## Phones on the same Wi-Fi (no cloud)

Run the gateway on your own computer and let the phone reach it over your home Wi-Fi. Nothing leaves your home network, and every action is a local round trip (ADR-042, roadmap R24).

1. Start it with `LATCH_WIFI=1`. The simplest way is to let your AI app start it over stdio, for example Claude Code:

   ```sh
   claude mcp add latch -e LATCH_ADMIN_TOKEN=<owner key> -e LATCH_WIFI=1 -e LATCH_DATA_DIR=$HOME/.latch -- latch-gateway stdio
   ```

   Or run `LATCH_WIFI=1 latch-gateway serve` with both tokens set, and add `http://127.0.0.1:8787/mcp` to your AI app as usual.
2. The gateway prints, on its error output:

   ```
   Phones on this Wi-Fi: in the Latch app, choose "Computer on Wi-Fi" and enter
       https://192.168.1.5:8788#pin=Qm9vb…
   ```

3. In the app, enter that whole address (with `#pin=`) and the owner key. The phone connects with TLS and trusts exactly this gateway's own certificate: the pin is the SHA-256 of its public key. A wrong or missing pin is refused.

The certificate is made once and kept in `LATCH_DATA_DIR/wifi/` (the key readable by you only), so restarts keep the same pin. If your computer's Wi-Fi address changes, the app finds the gateway again on its own: it tries port 8788 on the other addresses of the phone's /24 and accepts only the pinned key (ADR-052). On another network or a larger subnet, enter the new address; the pin stays. `latch-autostart on` (from `scripts/install-gateway.sh`) keeps the gateway running in the background as a LaunchAgent or systemd user service. Allow incoming connections on port 8788 if your firewall asks. Away from home the phone cannot reach this gateway; use a cloud gateway for that.

## Connect an AI client

**Just the URL (recommended).** Add `https://latch.example.com/mcp` as a remote MCP server in any MCP client. The client finds the sign-in on its own (MCP authorization: OAuth 2.1 with PKCE, through `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server`), registers itself, and opens a Latch page in your browser. Approve it in the Latch app (**Connect** tab; check the 4-character code matches). The page can only deny: it never asks for your owner key. The app then appears in your list of AI apps; revoke it there and its access stops at once. Set `LATCH_PUBLIC_URL` so the sign-in addresses are your public `https://` address. Registrations and refresh tokens (as hashes) are kept in `devices.json`, so a restart does not sign apps out; access tokens last an hour and are refreshed. Unlike the Vercel gateway, this one does not fetch client ID metadata documents (it never fetches URLs a client names); clients register instead.

**With a key.** Claude Code:

```sh
claude mcp add --transport http latch https://latch.example.com/mcp \
  --header "Authorization: Bearer $LATCH_MCP_TOKEN"
```

Other clients: Streamable HTTP URL `https://latch.example.com/mcp` with header `Authorization: Bearer <LATCH_MCP_TOKEN>`. The app's **Connect** tab creates one key per AI app with ready-to-paste snippets.

## Day-2 operations

- **Pair a phone:** in the Latch app, enter the gateway address and the owner key; the app creates its own single-use code and pairs. Without the app: `curl -X POST https://latch.example.com/v1/admin/pairings -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"name":"Pixel"}'` and enter the code within 10 minutes. 20 wrong guesses a minute lock pairing for a minute.
- **Revoke a phone:** in the app, or `curl -X DELETE https://latch.example.com/v1/admin/devices/<id> -H "Authorization: Bearer $LATCH_ADMIN_TOKEN"`. The phone is disconnected immediately and its token stops working.
- **Rotate the MCP token:** change `LATCH_MCP_TOKEN` and restart; update clients. Phones are unaffected.
- **Rotate the owner key:** in the app, Settings → **Rotate owner key** (ADR-038). The gateway issues a new key once, the app saves it, and the old key, `LATCH_ADMIN_TOKEN` included, stops working at once; leave `LATCH_ADMIN_TOKEN` as it is. Without the app: `curl -X POST https://latch.example.com/v1/admin/owner-key -H "Authorization: Bearer <current owner key>"`; the answer's `owner_key` is the only copy.
- **Lost the owner key:** set a new `LATCH_ADMIN_TOKEN` and restart. The new token works, and the rotated key stops working for good. Phones, AI keys, and OAuth apps stay connected.
- **Suspected leak of a phone token:** revoke that phone and pair again.
- **Emergency stop for everything:** stop the gateway process. Phones show "Connection lost" and run nothing; their own Stop button also always works.
- **Upgrade:** pull the new image and restart. `devices.json` is forward compatible; phones reconnect automatically.
- **Back up:** copy `devices.json` (it holds only hashes, but treat it as sensitive).
- **Limit an AI app to some phone apps** (roadmap R18): in the app, Connect → tap the AI app → *Only the apps I pick*. Without the app: `curl -X PATCH https://latch.example.com/v1/admin/clients/<id> -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"apps":["com.whatsapp","com.android.settings"]}'` (or `"apps"` when creating the key). That AI app can then open, share to, and act in only those apps, and the gateway never shows it a screen from any other app (it may still press Home and read your file folders if you allowed them). `{"apps":null}` lifts the limit. The phone's own app switches still apply on top. Keys from `LATCH_MCP_TOKEN` have no limit.
- **Sign an AI app out:** revoke it in the app's **Connect** tab (or `curl -X DELETE …/v1/admin/clients/<id>`): its OAuth tokens stop at once.
