# Your own Latch relay on Cloudflare

Each person runs their own relay in their own Cloudflare account. Latch, the project, runs nothing and never sees your keys, your phone, or your screens. Cloudflare's free plan covers personal use; you pay Cloudflare directly if you outgrow it.

```text
AI app ──MCP (HTTPS)──▶ your Worker ──▶ one Durable Object (the relay, with its own SQLite) ◀──HTTPS long-poll── Latch app on your phone
```

It is the same TypeScript gateway as the [Vercel relay](vercel.md) (`servers/vercel`), with `wrangler.jsonc` and `cloudflare/worker.ts` for Workers ([ADR-075](../adr/README.md#adr-075--many-relays-vercel-and-cloudflare-in-one-tap-each-used-together-wi-fi-beside-them)). Every request goes to one Durable Object, so a phone's long poll and an AI app's tool call meet in memory: no database round trips, which makes it the quickest hosted relay. Nothing else to provision: no KV, R2, D1, or card.

## Set it up (from the phone, nothing to type)

1. In the Latch app: **Create my own gateway** → **Cloudflare relay** → **Open Cloudflare**. Already set up? **Connect** → the **+ Cloudflare** segment.
2. Sign in or make a free Cloudflare account, connect GitHub when it asks (Cloudflare copies the relay into a repository of your own and builds it with Workers Builds), keep every name, and press **Create and deploy**.
3. Open the `….workers.dev` address Cloudflare shows. The page says **Scan this to make it yours**. In the app tap **Scan QR code**. The phone sends the key it made (never shown) once, the relay keeps it as its owner (`POST /v1/claim`), and the phone pairs as the owner.
4. In **Connect**, give your AI app the link. With a Vercel relay too, Connect lists both links: add both to your AI app.

Without the app, the button does the same deploy: [![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch%2Ftree%2Fmain%2Fservers%2Fvercel). Then scan the page with Latch.

### Who owns a new relay

Cloudflare's button cannot fill in values, so a new relay ships with `LATCH_CLAIM=first`: **the first Latch phone to scan it within 24 hours of its first visit owns it**, and no other phone can claim it after that. If someone else claimed it first, your phone says *this relay already has an owner* and never connects; delete the Worker and deploy again. `*.workers.dev` addresses are not published in certificate logs. To skip claiming, add a secret `LATCH_ADMIN_TOKEN` (at least 32 characters) in **Workers → latch-relay → Settings → Variables and Secrets**; it turns claiming off.

## Using it with other relays

The phone keeps one connection per relay (Vercel, Cloudflare, and the gateway on your computer) and connects all of them in every session. Each relay counts its own use of its free allowance and the app shows it in **Connect**. Each relay also knows the others' MCP addresses: `diagnose` lists them, and from 80 % of an allowance every tool answer names them, so an AI whose relay stops answering continues on the next. See [hosting options](hosting.md) for every place a gateway can run.

## Settings

Set them in **Workers → latch-relay → Settings → Variables and Secrets** (secrets for keys), or in `wrangler.jsonc` in your copy of the repository.

| Variable | Default | Meaning |
|---|---|---|
| `LATCH_CLAIM` | `first` | The first phone to scan the page (within 24 h) owns the relay. Remove it, and set `LATCH_ADMIN_TOKEN`, to choose the key yourself. |
| `LATCH_ADMIN_TOKEN` | — | Owner key you choose (secret, ≥ 32 characters). When set, claiming is off. |
| `LATCH_MCP_TOKEN` | — | An extra fixed MCP token. |
| `LATCH_PUBLIC_URL` | — | A custom domain to show in links. |
| `LATCH_ALLOWED_ORIGINS` | — | Browser origins allowed to call `/mcp`. |
| `LATCH_CLIENT_CALLS_PER_MINUTE` | 120 | Tool calls each AI app may make per minute (`0` for no limit). |
| `LATCH_USAGE_LIMITS` | `requests=100000,store_rows=100000` | The daily allowances the relay compares its counts with (requests, and SQLite rows written). |
| `LATCH_POLL_INTERVAL_MS`, `LATCH_HOT_POLL_INTERVAL_MS`, `LATCH_RESULT_INTERVAL_MS`, `LATCH_SETTLE_MS` | as on Vercel | Timing, see [vercel.md](vercel.md#settings). |

## Limits and costs (check Cloudflare's current pricing)

- **Free plan:** 100,000 Worker requests a day; each also counts as a Durable Object request. A session's long polls and tool calls are a few thousand requests an hour, so a day of normal use fits. The relay's count resets at 00:00 UTC; Connect shows it.
- **Durable Object time:** an object is billed while it is awake. A phone in a session keeps it awake; the free plan's daily allowance covers one object awake all day.
- **Files:** up to 4 MB through the relay itself, as on Vercel without Blob. Bigger files through an S3-compatible bucket (R2 or Backblaze B2) are planned, not built (ADR-075); use the Rust gateway for big files today.
- **Bundle:** about 700 KB, inside the free plan's 3 MB limit.

## Privacy

Screen content passes through your Worker and is held briefly in your Durable Object's storage (the same short expiries as on Vercel). Values that carry screen content are sealed with AES-256-GCM; on a claimed relay the key is derived from a relay secret stored in the same object, so this protects against nothing Cloudflare itself could not read: code and data are with the same provider either way. If the relay itself must not see screens, run the gateway on hardware you control ([gateway.md](gateway.md)).

## Operate

- **Revoke a phone or an AI key:** in the phone app. Effective immediately.
- **Rotate the owner key:** in the app, Settings → **Rotate owner key** (ADR-038).
- **Lost the phone:** add a secret `LATCH_ADMIN_TOKEN` and deploy; it becomes the owner key and the claimed one stops working.
- **Stop everything:** delete the Worker, or disable its `workers.dev` route. Phones show "Reconnecting" and run nothing.
- **Update:** pull the newest Latch into your copy of the repository (GitHub's **Sync fork**, or merge from `aspershupadhyay/latch`); Workers Builds redeploys. The Durable Object keeps its data.
- **Test it locally:** `cd servers/vercel && npm ci && npm run smoke:cloudflare` runs the relay in Cloudflare's own runtime (workerd, through `wrangler dev`), claims it, pairs the fake phone, and runs tools.
