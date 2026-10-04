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

1. On your phone, download the app: **[latch-android.apk](https://github.com/aspershupadhyay/latch/releases/download/beta/latch-android.apk)**
2. Open the file. If Android says *"For your security, your phone is not allowed to install unknown apps"*, tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
   - **"App blocked to protect your device"?** That's Google Play Protect. Follow [Fix: App blocked by Play Protect](#fix-app-blocked-by-play-protect) below.
3. Open **Latch**.

**Updates:** you only do this once. When a newer build exists, Latch shows **Update available** on Home: tap **Update**, then **Update** again on Android's screen if it asks. Your pairing and settings stay. The first time, Android asks you to allow Latch to install apps.

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
   Keep `us-east-1`: it sits next to Vercel's default function region (`iad1`, Washington). Picked another region? See [Fix: AI actions are slow](#fix-ai-actions-are-slow).
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
   - Android says **"Restricted setting"** or the switch is greyed out? Follow [Fix: Restricted setting](#fix-restricted-setting) below.
3. **Stay connected:** tap **Allow**, so your phone doesn't cut the session when the screen is off. You can skip this one. (Xiaomi, realme, OPPO, Samsung need one more switch: [Fix: Session stops when the screen is off](#fix-session-stops-when-the-screen-is-off).)

You can open this setup again any time from **Settings → Permissions and setup**.

### Step 6: Choose what the AI may do ✅

The last setup step asks you to pick a starting point:

| Choice | What the AI can do |
|---|---|
| **Just look** | Read what's on screen |
| **Look and tap** | Read, tap, scroll, go back, open apps |
| **Everything** | Also take screenshots and type |

Change single switches any time in the **Access** tab. Want to approve **every single** action? Turn on **Ask me before every action**.

**Choose the apps the AI can use:** **Access → Apps the AI can use** lists every app on your phone with a switch.

- **Switched on:** the AI works there without asking you, including sending and deleting. Everything it does is listed in the **Activity** tab.
- **Switched off:** the first time the AI needs that app, your phone asks once: **Not now**, **This session**, or **Always**. Until you say yes, the AI can't even see that app.
- **Not near your phone?** Turn on **Access → Answer from your AI chat too**. Then when Latch asks you something, your AI asks you the same question in the chat, and you can answer there or on the phone, whichever comes first. (Apps that support MCP "elicitation" show it as a pop-up; others, like Claude's connectors, have the AI ask you and pass on your answer.)
- **Money and password apps** (banking, UPI, wallets, password managers) show a warning before you switch them on, and the AI is told to check with you in the chat before acting there.
- **Payments, app installs, Android permission pop-ups, and deleting accounts ask you on the phone**, even in apps you switched on, unless you turn on **Also allow payments and permissions** on the same screen. Latch never types passwords, PINs, or one-time codes, so a payment that needs your PIN still needs you.
- **Auto mode** (off by default): one switch on the **Access** tab lets the AI use every app and finish tasks without asking, including payments, installs, and permission pop-ups. Latch asks for your consent first and you pick **for this session** or **until I turn it off**. **Ask me before every action** still overrides it, and the red Stop button ends everything.

On the same tab, under **While the AI works**:

- **Show where the AI taps** (on by default): an indigo pointer, like a computer's, glides along a gentle curve to each spot the AI uses, with a small label saying what it's doing ("Latch · Tapping", "Typing", "Swiping"…). Every touch shows a fingertip mark and ripple; a long press fills a ring; a pinch shows both fingers. It also shows work that happens off screen ("Looking at the screen", "Saving “clip.mp4”", a progress bar while a file moves) and stays, saying "Thinking…", while the task goes on; it fades away once the AI has been quiet for 45 seconds. It can't press anything, and it's hidden from screenshots, so the AI never sees it.
- **Keep the screen on** (on by default): during a session the screen stays on, so a task isn't cut off by the lock screen. Turn it off to save battery.

**Files (optional, all off by default):** switch on **See files**, **Save and change files**, and **Share to apps** on the **Access** tab, then under **Files**:

- **Pick a folder for the AI**: Android's folder picker opens; choose one folder (for example *Documents/AI*). The AI may read, save, rename, and delete files **in that folder only**, and make subfolders in it.
- **Photos**: allow all photos, or only the ones you choose. Saving new pictures to your gallery works without this.
- The AI can also save files to your phone's **Download** folder (it sees only files Latch saved there).
- **Replacing or deleting a file asks you on the phone**, unless Auto mode is on. **Ask me before every action** asks for every change.
- **Share to apps** opens any app's Share screen (Instagram, YouTube, X, LinkedIn, WhatsApp, Gmail…) with the files, only for apps you switched on. Posting or sending is still a tap under your app rules.
- **Copy to clipboard** (off by default) lets the AI put text on your clipboard, for example a caption to paste into an app that ignores shared text. Latch never reads your clipboard.

Things you can then ask your AI:

- *"Post the 3 photos in my ToPost folder to Instagram with the caption in caption.txt."*
- *"Copy my latest screenshot from the phone into my Downloads folder."* (an AI on your computer, like Claude Code, saves it with a download link)
- *"Save report.pdf from my Downloads on the computer into a folder called abc on my phone."*

Files travel between your computer and the phone through private links that expire after 15 minutes, at full quality (nothing is re-encoded or compressed):

- **Encrypted end to end between your devices.** Your computer encrypts a file (AES-256) before it leaves, with a key made for that one file; only your phone gets the key and decrypts it. The storage in between only ever holds scrambled bytes, and the phone checks the file's SHA-256 before keeping it, so a file changed on the way is never saved. The same in the other direction.
- **Size:** up to **2 GB** per file on a self-hosted gateway, and on Vercel once you connect a free **Vercel Blob** store ([how](docs/operations/vercel.md#big-files-vercel-blob)); without Blob, Vercel carries files up to 4 MB.
- **Speed:** the phone and your computer move the bytes themselves, straight to and from the storage, at your connection's speed. A big file keeps moving in the background, with a progress bar on the phone.
- File contents are never logged.

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
Do [Step 4](#step-4-copy-thing-2-the-address-back-into-the-app-): copy your `….vercel.app` address and paste it into the app.

**"The app shows a different key now / I closed the app."**
No problem. Your real key is saved in Vercel:
1. In [Vercel](https://vercel.com/dashboard), open your project → **Settings → Environment Variables**.
2. Find `LATCH_ADMIN_TOKEN`, click the 👁 eye icon, and copy the value.
3. In the app, go to the start screen → **Connect to a gateway** → **I own it**.
4. Paste your address and that key, then connect.

**"It says the gateway is not set up yet."**
`LATCH_ADMIN_TOKEN` is missing or too short (it needs at least 32 characters). Add it in Vercel → **Settings → Environment Variables**, then press **Redeploy** (under **Deployments**).

**"The app won't install" / "I can't turn on the accessibility switch" / "It's slow" / "The session keeps dropping."**
See [Phone and Vercel settings](#phone-and-vercel-settings) right below.

**"I think my key leaked."**
Change `LATCH_ADMIN_TOKEN` in Vercel to a new key → **Redeploy** → in the app, **Settings → Forget this gateway** → connect again with the new key. To kill just one AI app's key, revoke it in the **Connect** tab.

Still stuck? [Open an issue](https://github.com/aspershupadhyay/latch/issues) and describe what you see.

---

## Phone and Vercel settings

Every setting Latch may need, with the exact taps. Menu names differ a little between phone brands, so each fix lists the common ones.

### Fix: App blocked by Play Protect

**What you see:** *"App blocked to protect your device"*, with only an **OK** button.

**Why:** Google Play Protect blocks apps that use Android's accessibility feature when they're installed from a browser, chat app, or file manager. Fraud apps use the same feature, so this check is on by default in India and some other countries. Latch needs accessibility to see the screen and tap, and it doesn't try to sneak past this check. Pick **one** of these:

**Option A: install from a computer (best, no settings to change back).**
Installing over USB isn't blocked, and Android doesn't mark it "restricted" either.
1. On the phone, turn on developer mode: **Settings → About phone** → tap **Build number** 7 times.
   - Xiaomi / Redmi / POCO: **Settings → About phone** → tap **OS version** (or **MIUI version**) 7 times.
   - realme / OPPO: **Settings → About device → Version** → tap **Build number** 7 times.
   - Samsung: **Settings → About phone → Software information** → tap **Build number** 7 times.
2. Turn on **USB debugging**: **Settings → System → Developer options** (Xiaomi: **Settings → Additional settings → Developer options**) → **USB debugging** on. Xiaomi also needs **Install via USB** on.
3. On the computer, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools), download [latch-android.apk](https://github.com/aspershupadhyay/latch/releases/download/beta/latch-android.apk), connect the phone by USB, tap **Allow** on the phone, and run:
   ```
   adb install -r latch-android.apk
   ```
4. Done. You can turn **USB debugging** off again.

**Option B: pause Play Protect just for the install.**
1. Open the **Play Store** → tap your profile picture (top right) → **Play Protect** → ⚙️ (top right).
2. Turn off **Scan apps with Play Protect** → install Latch.
3. Go back to the same screen and **turn it on again**. Play Protect keeps protecting your other apps, and Latch stays installed.

### Fix: Restricted setting

**What you see:** under **Accessibility → Downloaded apps**, Latch is greyed out, or tapping it says *"Restricted setting: For your security, this setting is currently unavailable."*

**Why:** Android 13 and newer protect accessibility for any app installed from a file, until you say you trust it. You only do this once.

1. **Try once first.** In Latch tap **Turn on screen access** (or go to **Settings → Accessibility → Downloaded apps → Latch**) and tap the switch. When *"Restricted setting"* appears, tap **OK**. Android only shows the allow option *after* this attempt.
2. **Open Latch's App info.** In Latch tap **Open App info**. Or:
   - Most phones: long-press the Latch icon → **App info** (ⓘ).
   - Xiaomi / Redmi / POCO: **Settings → Apps → Manage apps → Latch**.
   - realme / OPPO / OnePlus: **Settings → Apps → App management → Latch**.
   - Samsung: **Settings → Apps → Latch**.
3. Tap **⋮** (three dots, top right) → **Allow restricted settings** → confirm with your PIN, pattern, or fingerprint.
   - No ⋮ on the screen? You skipped step 1, or you're on a different App info page. Do step 1, then open App info from the **Settings → Apps** path above.
4. Go back to **Accessibility → Downloaded apps → Latch** and switch it on. Read the notice, then tap **Allow**.

**Still no "Allow restricted settings"?** Use [Option A](#fix-app-blocked-by-play-protect) (install from a computer). Apps installed that way are never restricted. If Latch is already installed and USB debugging is on, this one command does the same thing:
```
adb shell cmd appops set io.github.aspershupadhyay.latch ACCESS_RESTRICTED_SETTINGS allow
```

### Fix: Session stops when the screen is off

Some phones close background apps to save battery, which ends your session. Allow Latch to run (it only runs during sessions you start):

- **Every phone:** in Latch's setup, tap **Allow** at **Stay connected**.
- **Xiaomi / Redmi / POCO:** **Settings → Apps → Manage apps → Latch** → **Autostart** on, then **Battery saver** → **No restrictions**.
- **realme / OPPO / OnePlus:** **Settings → Apps → App management → Latch → Battery usage** → turn on **Allow background activity** (and **Allow auto launch** if shown).
- **Samsung:** **Settings → Apps → Latch → Battery** → **Unrestricted**.
- **Pixel and others:** **Settings → Apps → Latch → App battery usage** → **Unrestricted**.

Also keep **Accessibility → Latch** on. Some phones switch it off after a battery-saver cleanup. If the app says screen access is off, switch it on again.

### Fix: Update your gateway

**What you see:** the Latch app shows **Update your gateway**, or your AI says tools like `scroll_to`, `wait_for`, or `pinch` don't exist.

**Why:** the phone app updates when you install a new APK, but your gateway on Vercel only updates when Vercel deploys new code. The Deploy button makes a **copy** of Latch in your own GitHub (usually called `latch-gateway`), and that copy doesn't receive new versions by itself.

1. **Check what you run:** open `https://<your-address>/healthz` in a browser. Up to date means `"protocol": "1.3"` or newer. It also shows `commit` and `repository`.
2. **Update the code Vercel deploys.** Pick one:
   - **Follow Latch directly** (simplest; every Latch release reaches you automatically): Vercel → your project → **Settings → Git** → **Disconnect**, then **Connect Git Repository** → `aspershupadhyay/latch`. Then **Settings → Build and Deployment → Root Directory** → `servers/vercel` → **Save**. This means you trust each new Latch release as soon as it's published.
   - **Keep your own copy and review each version first:** when you want an update, download the newest Latch code (GitHub → **Code → Download ZIP** on `aspershupadhyay/latch`), replace the files in your copy with it, commit, and push. Vercel deploys your copy by itself.
3. **Deploy:** Vercel → **Deployments** → ⋯ on the newest one → **Redeploy**. Wait until it says **Ready**.
4. **Check again:** `https://<your-address>/healthz` shows the new `protocol`.
5. **Refresh your AI.** AI apps remember Latch's list of tools from when you connected, so they don't see new ones until you refresh:
   - **ChatGPT:** Settings → **Apps & Connectors** → Latch → **Refresh** (or remove it and add it again), then start a new chat.
   - **Claude:** Settings → **Connectors** → Latch → **Disconnect**, then **Connect** again, then start a new chat.
   - **Claude Code / Codex / other apps:** restart them.

   Use your normal address (`https://<your-address>/mcp`), not a one-off Vercel deployment link with random letters in it: those never update.
6. **Check from the AI:** ask it to run `list_devices`. The first line must say `Latch gateway, protocol 1.3, 12 tools.` If the AI still can't see `scroll_to`, `wait_for`, or `pinch` after that, its app is still holding the old tool list: repeat step 5.

### Fix: AI actions are slow

Every phone action goes back and forth between your Vercel function and your Upstash database about a dozen times. When the two sit on different continents, each trip costs ~200 ms, and every action gets several seconds slower.

**Check both regions:**
1. **Upstash:** [Vercel dashboard](https://vercel.com/dashboard) → your project → **Storage** → your Redis database → note its **region** (for example `us-east-1` or `ap-south-1`).
2. **Vercel function:** your project → **Settings → Functions → Function Region**.

**Make them match:**

| Your Upstash region | Pick this Vercel Function Region |
|---|---|
| `us-east-1` (N. Virginia) | **Washington, D.C., USA (`iad1`)**, the default |
| `ap-south-1` (Mumbai) | **Mumbai, India (`bom1`)** |
| `ap-southeast-1` (Singapore) | **Singapore (`sin1`)** |
| `eu-central-1` (Frankfurt) | **Frankfurt, Germany (`fra1`)** |
| `eu-west-1` (Ireland) | **Dublin, Ireland (`dub1`)** |
| `us-west-1` (N. California) | **San Francisco, USA (`sfo1`)** |

3. Click **Save**, then go to **Deployments** → ⋯ on the newest one → **Redeploy**.

**See where the time goes:** every answer the AI gets includes `latch/timing` (`total_ms`, `phone_ms`, `lock_wait_ms`). If `total_ms` is much bigger than `phone_ms`, the regions are the problem. If `phone_ms` is big, the phone's network or the phone itself is slow. The **Activity** list in your gateway's web page shows the same total per action.

### Fix: The AI says Latch's own screen is off limits

That's on purpose: an AI can never read or tap Latch itself, so it can't approve its own requests. Switch to the app you want the AI to use, or let it press **Home** or open an app. Both are allowed from Latch's screen.

---

## Is it safe? 🛡️

We built Latch so the AI can't sneak around you:

- **Everything starts off.** You switch on each power yourself.
- **Sessions end on their own** when the timer runs out, and the red **Stop** button is always on screen.
- **Only the apps you pick.** The AI can use an app only after you switch it on (or say yes when it first asks). In apps you switched on it doesn't ask again; anywhere else, before the AI taps things like *Send, Post, Call, Delete,* a card pops up on your phone. Tap **Allow once**, or save your answer: **This session**, or **Always in Instagram** (for example).
- **Money, installs, permissions, and deleting accounts ask.** Buttons like *Pay, Buy, Transfer, Install,* Android permission prompts, and *Delete account* ask every time, unless you yourself switch on **Also allow payments and permissions** (off until you do).
- **The phone double-checks.** Even if the gateway missed a risky button, the phone checks the same rules on what it sees right now and asks you anyway.
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
| `tap`, `type_text`, `scroll`, `swipe`, `press` | Act on the latest screen reading; each one returns the next reading. `tap` can double-tap; `swipe` with `hold_ms` drags; `type_text` with `submit` also presses Enter/Search/Send |
| `scroll_to` | Scrolls until a text is visible, in one call |
| `wait_for` | Waits until a text appears (or disappears), in one call |
| `pinch` | Zooms in or out with two fingers |
| `list_apps`, `launch_app` | See and open installed apps |
| `ask_owner` | Asks you to do a step only a person should do (log in, unlock, a code); waits for **Done** |
| `list_files`, `read_file` | List photos you allowed, Downloads, or your Latch folder; look at a text file or picture |
| `get_file_link`, `upload_link` | Private, encrypted 15-minute links to copy whole files (up to 2 GB) between your computer and the phone (openssl + curl) |
| `transfer_status` | Waits for a big file that is still moving |
| `set_clipboard` | Puts text on the phone's clipboard to paste (never reads it) |
| `write_file`, `create_folder`, `rename_file`, `delete_file` | Save and organise files; replacing and deleting ask you unless Auto mode is on |
| `share_to_app` | Opens any app's Share screen with files, ready to post or send |

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
