# Your own Latch gateway on Vercel

Each person runs their own gateway in their own Vercel account. Latch, the project, runs nothing and never sees your keys, your phone, or your screens. On the free tiers this costs nothing for personal use; you pay Vercel and Upstash directly if you outgrow them.

```text
AI app ──MCP (HTTPS)──▶ your Vercel project (servers/vercel) ◀──HTTPS long-poll── Latch app on your phone
                               │
                         your Upstash Redis
```

## The two-minute path (from the phone)

1. Install the Latch app and choose **Create my own gateway**.
2. **Copy your owner key.** The app generates a random 256-bit key. It is the only credential that controls your gateway; it stays on your phone (encrypted) and in your Vercel project.
3. **Open Vercel.** The app opens a Deploy page preconfigured with this repository, `servers/vercel` as the root directory, and an Upstash Redis store.
   - Sign in or create a free Vercel account.
   - Keep the suggested **Upstash Redis** store (it creates `KV_REST_API_URL` and `KV_REST_API_TOKEN` for you).
   - Paste the owner key into **LATCH_ADMIN_TOKEN**.
   - Press **Deploy**.
4. **Paste your address** (for example `latch-gateway-you.vercel.app`) back into the app and tap **Connect this phone**. The app checks the gateway, creates its own pairing code with the owner key, and pairs itself.
5. In the app open **Connect**, create a key for your AI app, and paste it there.

Without the app, use the button: [![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch&root-directory=servers%2Fvercel&project-name=latch-gateway&repository-name=latch-gateway&env=LATCH_ADMIN_TOKEN&envDescription=Paste+the+owner+key+shown+in+the+Latch+app.+It+stays+in+your+Vercel+project+and+on+your+phone.&envLink=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch%2Fblob%2Fmain%2Fdocs%2Foperations%2Fvercel.md&stores=%5B%7B%22type%22%3A%22integration%22%2C%22integrationSlug%22%3A%22upstash%22%2C%22productSlug%22%3A%22upstash-kv%22%2C%22protocol%22%3A%22storage%22%7D%5D) and generate the key yourself (`openssl rand -hex 32`).

## Connecting an AI app

**Just the URL (recommended).** Add `https://<you>.vercel.app/mcp` as a remote MCP server in any MCP client: Claude, ChatGPT, Codex, Cursor, VS Code, Windsurf, or your own agent. The client finds the sign-in on its own (MCP authorization: OAuth 2.1 with PKCE, discovered through `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server`), registers itself, and opens a Latch page in your browser. Approve it in the Latch app (**Connect** tab; check the 4-character code matches) or on that page with your owner key. The app then appears in your list of AI apps; revoke it there and its access stops at once.

- Clients register with dynamic client registration (RFC 7591) or a client ID metadata document (an `https://` client_id).
- Public clients only, authorization code + PKCE S256. Redirects must be https, loopback http (any port), or an app scheme such as `cursor://`.
- Access tokens last 1 hour; refresh tokens 90 days and rotate on every use. Tokens are stored as SHA-256 hashes.

**Keys, for clients without OAuth.** Create one key per app in the **Connect** tab or the web console. Each key comes in two forms:

| Your AI app asks for | Use |
|---|---|
| Only a URL | the **secret link** `https://<you>.vercel.app/mcp/<key>` |
| A URL and headers | URL `https://<you>.vercel.app/mcp` and header `Authorization: Bearer <key>` |

The secret link is a password in URL form: anyone who has it can use your phone within what you allowed. Revoke the key if it leaks. Some apps' connector features depend on their plan and settings (for example ChatGPT's developer mode); check the app's current documentation.

The container gateway (`servers/mcp`) supports keys and secret links; OAuth there is planned.

## Settings

| Variable | Required | Meaning |
|---|---|---|
| `LATCH_ADMIN_TOKEN` | yes | Owner key, at least 32 characters. Without it the gateway reports `setup_required` and refuses pairing and MCP. |
| `KV_REST_API_URL`, `KV_REST_API_TOKEN` | yes | Set automatically by the Upstash store. `UPSTASH_REDIS_REST_URL` / `_TOKEN` also work. |
| `LATCH_MCP_TOKEN` | no | An extra static MCP token, if you prefer one fixed key. |
| `LATCH_PUBLIC_URL` | no | Custom domain to show in links. |
| `LATCH_ALLOWED_ORIGINS` | no | Browser origins allowed to call `/mcp`. |
| `LATCH_POLL_INTERVAL_MS` | no | How often a waiting phone poll checks for work (default 500). Lower is snappier but uses more Redis commands. |
| `LATCH_HOT_POLL_INTERVAL_MS` | no | The same while an agent is actively using the phone (the phone sends `hot=1` for 60 s after each command; default 100). |
| `LATCH_RESULT_INTERVAL_MS` | no | How often a waiting tool call checks for the phone's answer (default 100). |
| `LATCH_SETTLE_MS` | no | How long the phone lets the screen settle after an action before observing it (default 500, at most 3000). |

## Speed

One phone action costs about a dozen Redis round trips (authentication, lock, queue, the phone's poll and answer, the audit write), batched where possible. Each round trip takes as long as the network path between your **Vercel function region** and your **Upstash region**:

- Same region (e.g. `iad1` and `us-east-1`, the defaults in the README): a few milliseconds each, so an action takes well under a second plus the 500 ms settle time.
- Different continents (e.g. Vercel `iad1` and Upstash in Mumbai): ~200 ms each, which adds several seconds to **every** action.

If your phone and AI are far from the US, move both: create the Upstash database in the region nearest to you and set Vercel → **Settings → Functions → Function Region** to the matching region, then redeploy.

**Diagnose:** every tool result carries `_meta["latch/timing"]` with `lock_wait_ms` (waiting for an earlier command), `phone_ms` (from queueing until the phone's answer: transport, phone work, and approval), and `total_ms` (everything the gateway spent). A large `total_ms - phone_ms` points at slow storage round trips; a large `phone_ms` at the phone's network or work. The owner console's activity table shows the same total as latency.

Since protocol 1.2 the phone returns the screen after an action in the same answer, so an action costs one phone round trip, not two. Agents should not call `observe` again after an action.

## Costs and limits (check the providers' current pricing)

- **Vercel Hobby** is free for personal, non-commercial use. Functions run up to 300 s, which covers the 120 s approval wait.
- **Upstash Redis free tier** has a monthly command allowance. A connected, idle phone costs about two commands per second while a session is active (the long-poll checks), about ten per second for a minute after each AI action (faster checks while an agent is working), plus about a dozen per action. An idle session hour is roughly 7,000 commands; an hour of continuous agent work roughly 40,000. Sessions end on their own; idle phones cost nothing.
- Phone commands arrive within about one poll interval (≤ 0.5 s idle, ≤ 0.1 s while an agent is working); a self-hosted container (`deploy/docker-compose.yml`) delivers them instantly.

## Privacy

Screen content passes through your Vercel functions and, briefly, your Redis (the latest element list for 2 minutes, command results for 1 minute; screenshots are never cached). Vercel and Upstash are therefore processors of that data under your accounts. If that is not acceptable, run the Docker gateway on hardware you control instead.

## Operate

- **Revoke a phone or an AI key:** phone app or web console. Effective immediately.
- **Rotate the owner key:** change `LATCH_ADMIN_TOKEN` in Vercel → redeploy → on the phone, Settings → Forget this gateway → Connect again with the new key.
- **Stop everything:** pause or delete the Vercel project. Phones show "Reconnecting" and run nothing.
- **Update:** Vercel redeploys when you sync your fork or press Redeploy.
