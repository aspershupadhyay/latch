# Latch

**Let your AI use your phone, but only the way you allow.** 📱🔒

Imagine telling Claude or ChatGPT: *"Open my music app and play something chill."* With Latch, it can see your screen and tap for you. You stay the boss the whole time: you pick what it's allowed to do, you can stop it with one tap, and it has to ask you first before anything big (like sending a message or buying something).

It's free, it's open source, and **you own every piece of it.** There's no Latch company server in the middle. Your phone talks to *your* gateway, which lives in *your* free Vercel account.

> 🧪 **Heads up: this is an early test version (alpha).** Everything passes our automatic tests, but it hasn't been tried on lots of real phones yet. If something breaks, please [tell us](https://github.com/aspershupadhyay/latch/issues). That's exactly what testing is for.

---

## How it works (the 10-second version)

```text
  Your AI app            Your gateway                Your phone
 (Claude, ChatGPT)  ──▶  (lives on Vercel)  ◀──  (the Latch app)
```

- **The Latch app** goes on your Android phone. It's the part that sees the screen and taps.
- **The gateway** is a tiny website that's yours. Your AI talks to it, and your phone talks to it.
- **Your AI** gets a secret key from you so it can talk to the gateway.

---

## Set it up (about 5 minutes)

You need: an **Android phone** (Android 11 or newer), and a free **[Vercel](https://vercel.com/signup)** account. You can make the Vercel account during setup.

### 📋 You only copy-paste 3 things

| # | What | Copy it from | Paste it into |
|---|---|---|---|
| 1 | **Owner key** (your master password) | Latch app | Vercel, in the box called `LATCH_ADMIN_TOKEN` |
| 2 | **Gateway address** (looks like `latch-gateway-you.vercel.app`) | Vercel, after you press Deploy | Latch app |
| 3 | **MCP address** `https://<your-address>/mcp` | Latch app, **Connect** tab | Your AI app (Claude, ChatGPT, Codex…), then approve in the app |

That's it. Every step below is just one of these.

### Step 1: Get the app 📲

1. On your phone, download the app: **[latch-android-debug.apk](https://github.com/aspershupadhyay/latch/releases/download/test-build/latch-android-debug.apk)**
2. Open the file. If Android says *"For your security, your phone is not allowed to install unknown apps"*, tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
3. Open **Latch**.

### Step 2: Copy thing #1, the owner key 🔑

In the Latch app, tap **Create my own gateway**, then tap **copy** next to **Owner key**.

> ⚠️ Don't leave this screen until Step 4 (you can switch to your browser and come back). Keep this key secret.

### Step 3: Paste it into Vercel ☁️

Open Vercel **either way**, whichever is easier. Both open the exact same page:

- **On your phone:** tap **Open Vercel** in the Latch app, **or**
- **On a computer:** click this button 👉 [![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch&root-directory=servers%2Fvercel&project-name=latch-gateway&repository-name=latch-gateway&env=LATCH_ADMIN_TOKEN&envDescription=Paste+the+owner+key+shown+in+the+Latch+app.+It+stays+in+your+Vercel+project+and+on+your+phone.&envLink=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch%2Fblob%2Fmain%2Fdocs%2Foperations%2Fvercel.md&stores=%5B%7B%22type%22%3A%22integration%22%2C%22integrationSlug%22%3A%22upstash%22%2C%22productSlug%22%3A%22upstash-kv%22%2C%22protocol%22%3A%22storage%22%7D%5D)

Then on Vercel:

1. Sign in (or make a free account). If it asks for a plan, pick **Hobby** (free).
2. **Git repository:** keep the name and keep it **private**. Click **Create**.
3. **Upstash Redis:** click **Add**. Plan **Free**, region **`us-east-1`**, read regions **none**.
4. **`LATCH_ADMIN_TOKEN`:** paste thing #1 (the owner key from the app).
5. Click **Deploy** and wait about a minute.

### Step 4: Copy thing #2, the address, back into the app 🔗

1. On Vercel's **"Congratulations!"** page, copy the address (`latch-gateway-….vercel.app`).
   No such page? Go to the [Vercel dashboard](https://vercel.com/dashboard) → your project → the address next to **Domains**.
2. In the Latch app, paste it into **Paste your gateway address** → tap **Connect this phone**. Done ✅

### Step 5: Follow the setup the app shows you 👀

Right after connecting, Latch walks you through everything it needs, one step at a time. Nothing turns on without you:

1. **Notifications:** tap **Allow**, so you always see when a session is running and can stop it.
2. **Screen access:** tap **Open accessibility settings**, find **Latch**, and switch it on. This one is required.
   - **Android 13 or newer** might say the setting is **restricted**. That's normal for apps that aren't from the Play Store. Tap **Open App info** in Latch → **⋮** (top right) → **Allow restricted settings**, then switch Latch on again.
3. **Stay connected:** tap **Allow**, so your phone doesn't cut the session when the screen is off. You can skip this one.

You can open this setup again any time from **Settings → Permissions and setup**.

### Step 6: Choose what the AI may do ✅

The last setup step asks you to pick a starting point:

| Choice | What the AI can do |
|---|---|
| **Just look** | Read what's on screen |
| **Look and tap** | Read, tap, scroll, go back, open apps |
| **Everything** | Also take screenshots and type |

Change single switches any time in the **Access** tab. Want to approve **every single** action? Turn on **Ask me before every action**.

### Step 7: Connect your AI 🤖

Works the same in every MCP app (Claude, ChatGPT, Codex, Cursor, VS Code…):

1. In your AI app, add a remote MCP server with this URL: `https://<your-address>/mcp`
2. Your browser opens a Latch page showing a 4-letter code.
3. In the Latch app, open **Connect** → tap **Approve** on the request with the same code. (Or paste your owner key on the page.)

Done. The AI app shows up in **Connect**, where you can remove it any time.

<details>
<summary>My AI app has no sign-in option (keys instead)</summary>

In the Latch app, **Connect** → **Create key**, then give your app either:
- the **secret link** `https://<your-address>/mcp/<key>` (apps that only take a URL), or
- the URL `https://<your-address>/mcp` plus the header `Authorization: Bearer <key>`.

</details>

### Step 8: Start a session and try it 🚀

1. On the app's **Home** tab, pick how long (15, 30, 60, or 120 minutes) and tap **Start session**.
2. A red **● Latch · Stop** button stays on your screen. **Tap it any time to stop everything instantly.**
3. Ask your AI something simple: *"What's on my phone screen right now?"*

---

## Help, I'm stuck

**"I pasted the key in Vercel, deployed, and now I don't know what to do."**
Do [Step 4](#step-4-connect-your-phone-): copy your `….vercel.app` address and paste it into the app.

**"The app shows a different key now / I closed the app."**
No problem. Your real key is saved in Vercel:
1. In [Vercel](https://vercel.com/dashboard), open your project → **Settings → Environment Variables**.
2. Find `LATCH_ADMIN_TOKEN`, click the 👁 eye icon, and copy the value.
3. In the app, go to the start screen → **Connect to a gateway** → **I own it**.
4. Paste your address and that key, then connect.

**"It says the gateway is not set up yet."**
`LATCH_ADMIN_TOKEN` is missing or too short (it needs at least 32 characters). Add it in Vercel → **Settings → Environment Variables**, then press **Redeploy** (under **Deployments**).

**"I can't turn on the accessibility switch."**
See the *restricted settings* tip in [Step 5](#step-5-let-latch-see-the-screen-).

**"I think my key leaked."**
Change `LATCH_ADMIN_TOKEN` in Vercel to a new key → **Redeploy** → in the app, **Settings → Forget this gateway** → connect again with the new key. To kill just one AI app's key, revoke it in the **Connect** tab.

Still stuck? [Open an issue](https://github.com/aspershupadhyay/latch/issues) and describe what you see.

---

## Is it safe? 🛡️

We built Latch so the AI can't sneak around you:

- **Everything starts off.** You switch on each power yourself.
- **Sessions end on their own** when the timer runs out, and the red **Stop** button is always on screen.
- **Big actions need your OK.** Before the AI taps things like *Send, Buy, Pay, Delete,* or *Allow*, a card pops up on your phone asking you.
- **Passwords, PINs, one-time codes, and card numbers are hidden** from the AI, and it can't type into those boxes.
- **Off-limits areas:** the AI can't touch the Latch app itself, your notification shade, or your lock screen.
- **No sneaky stuff exists in the app:** no file access, no reading notifications, no command line.
- **Screen text can't boss the AI around.** Text on screen is treated as plain information, never as instructions.

### What gets stored?

- **Your screen is never saved.** A screen reading passes through your gateway on its way to your AI and is deleted right after (within 60 seconds at most). A text-only copy of the latest screen reading (no picture) is kept for about 2 minutes so taps can be checked against the current screen.
- **Your gateway keeps a short activity list:** what kind of action happened and whether it was allowed. No screen text, no pictures. It keeps the last 1,000 entries for up to 30 days.
- **Keys are stored safely:** scrambled (as hashes) on the gateway, and encrypted on your phone.
- **Nothing goes to us.** There's no Latch server, no tracking, and no analytics.
- **One honest caveat:** whatever your AI sees is sent to that AI company (like Anthropic or OpenAI), because that's how it reads your screen. Their privacy rules apply. Only switch on what you're comfortable sharing.

More detail: [SECURITY.md](SECURITY.md) and [handbook chapter 08](handbook/08-safety-privacy-security.md).

---

## What about iPhone? 🍎

Not yet, and not in the same way. Apple doesn't let any app read or tap other apps on an iPhone, so a full iPhone version isn't possible. A smaller iPhone helper may come later. See [handbook chapter 06](handbook/06-ios-application-plan.md).

---

## For developers 🛠️

<details>
<summary>Tools, folders, and how to build (click to open)</summary>

### What the AI can call (MCP tools)

| Tool | What it does |
|---|---|
| `list_devices` | Lists your paired phones, whether they're online, and what's switched on |
| `observe` | Reads the screen (buttons, text, positions) and, if allowed, takes a screenshot |
| `tap`, `type_text`, `scroll`, `swipe`, `press` | Act on the latest screen reading; each one returns the next reading |
| `list_apps`, `launch_app` | See and open installed apps |

### Other ways to run the gateway

- **Deploy from this page:** use the button in [Step 3](#step-3-paste-it-into-vercel-). Bring your own owner key if you skip the app: at least 32 random characters, e.g. `openssl rand -hex 32`, then in the app choose **Connect to a gateway → I own it**. Full guide: [docs/operations/vercel.md](docs/operations/vercel.md).
- **Your own server:** a small container (`deploy/docker-compose.yml`). See [docs/operations/gateway.md](docs/operations/gateway.md).
- **Try it with no phone at all** (needs Rust): `scripts/dev.sh` starts a gateway and a pretend phone.

### What's in this repo

| Folder | What's inside |
|---|---|
| `apps/android` | The Android app (Kotlin, Jetpack Compose) |
| `servers/vercel` | The gateway for Vercel + Upstash Redis |
| `servers/mcp` | The same gateway in Rust, for containers and servers |
| `crates/protocol` | The phone ↔ gateway language (device protocol v1) |
| `crates/policy` | The rules that decide what's allowed |
| `crates/fake-device` | A pretend phone for tests and demos |
| `packages/schemas/v1` | JSON Schemas and shared test examples |
| `tests/interop` | Checks against the official MCP TypeScript client |
| `handbook/` | The full product and engineering plan |
| `docs/` | Guides for running, platform notes, and decisions |
| `scripts/` | `check.sh`, `dev.sh`, and setup helpers |

### Build and test

```sh
scripts/cloud-session-setup.sh   # once: installs Rust deps, the Android SDK, and the test client
scripts/check.sh                 # runs everything CI runs
```

**Project status:** both gateways pass end-to-end tests with the official MCP client and a pretend phone (including against Redis). The Android app builds, and its tests and lint pass. Next up is testing on real phones ([checklist](docs/platform/android.md#real-device-gate-s4)).

See [CONTRIBUTING.md](CONTRIBUTING.md) to help out.

</details>

---

Made with care. Licensed under [Apache-2.0](LICENSE). 💛
