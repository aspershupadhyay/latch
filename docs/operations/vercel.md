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

Create one key per app (in the phone app's **Connect** tab or the web console at your gateway address). Each key gives you two forms:

| Your AI app asks for | Use |
|---|---|
| Only a URL (ChatGPT connectors, Claude.ai custom connectors, many others) | the **secret link** `https://<you>.vercel.app/mcp/<key>` |
| A URL and headers (Claude Code, Cursor, VS Code, Windsurf, the Claude and OpenAI APIs, agent SDKs) | URL `https://<you>.vercel.app/mcp` and header `Authorization: Bearer <key>` |

The secret link is a password in URL form: anyone who has it can use your phone within what you allowed. Revoke the key if it leaks. Some apps' connector features depend on their plan and settings (for example ChatGPT's developer mode); check the app's current documentation. OAuth sign-in, which some apps prefer, is planned.

## Settings

| Variable | Required | Meaning |
|---|---|---|
| `LATCH_ADMIN_TOKEN` | yes | Owner key, at least 32 characters. Without it the gateway reports `setup_required` and refuses pairing and MCP. |
| `KV_REST_API_URL`, `KV_REST_API_TOKEN` | yes | Set automatically by the Upstash store. `UPSTASH_REDIS_REST_URL` / `_TOKEN` also work. |
| `LATCH_MCP_TOKEN` | no | An extra static MCP token, if you prefer one fixed key. |
| `LATCH_PUBLIC_URL` | no | Custom domain to show in links. |
| `LATCH_ALLOWED_ORIGINS` | no | Browser origins allowed to call `/mcp`. |
| `LATCH_POLL_INTERVAL_MS` | no | How often a waiting phone poll checks for work (default 1000). Lower is snappier but uses more Redis commands. |

## Costs and limits (check the providers' current pricing)

- **Vercel Hobby** is free for personal, non-commercial use. Functions run up to 300 s, which covers the 120 s approval wait.
- **Upstash Redis free tier** has a monthly command allowance. A connected phone costs about one command per second while a session is active (the long-poll checks), plus a few per AI action, so an hour of active session is roughly 4,000 commands. Sessions end on their own; idle phones cost nothing.
- Phone commands arrive within about one poll interval (≤ 1 s by default); a self-hosted container (`deploy/docker-compose.yml`) delivers them instantly.

## Privacy

Screen content passes through your Vercel functions and, briefly, your Redis (the latest element list for 2 minutes, command results for 1 minute; screenshots are never cached). Vercel and Upstash are therefore processors of that data under your accounts. If that is not acceptable, run the Docker gateway on hardware you control instead.

## Operate

- **Revoke a phone or an AI key:** phone app or web console. Effective immediately.
- **Rotate the owner key:** change `LATCH_ADMIN_TOKEN` in Vercel → redeploy → on the phone, Settings → Forget this gateway → Connect again with the new key.
- **Stop everything:** pause or delete the Vercel project. Phones show "Reconnecting" and run nothing.
- **Update:** Vercel redeploys when you sync your fork or press Redeploy.
