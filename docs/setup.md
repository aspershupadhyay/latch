# Set up Latch

About five minutes, start to finish.

You need: an **Android phone** (Android 11 or newer), and a free **[Vercel](https://vercel.com/signup)** account. You can make the Vercel account during setup.

## You only copy-paste 3 things

| # | What | Copy it from | Paste it into |
|---|---|---|---|
| 1 | **Owner key** (your master password) | Latch app | Vercel, in the box called `LATCH_ADMIN_TOKEN` |
| 2 | **Gateway address** (looks like `latch-gateway-you.vercel.app`) | Vercel, after you press Deploy | Latch app |
| 3 | **MCP address** `https://<your-address>/mcp` | Latch app, **Connect** tab | Your AI app (Claude, ChatGPT, Codex…), then approve in the app |

That's it. Every step below is just one of these.

## Step 1: Get the app

1. On your phone, download the app: **[latch-android.apk](https://github.com/aspershupadhyay/latch/releases/download/beta/latch-android.apk)**
2. Open the file. If Android says *"For your security, your phone is not allowed to install unknown apps"*, tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
   - **"App blocked to protect your device"?** That's Google Play Protect. Follow [Fix: App blocked by Play Protect](troubleshooting.md#fix-app-blocked-by-play-protect) below.
3. Open **Latch**.

**Updates:** you only do this once. When a newer build exists, Latch shows **Update available** on Home: tap **Update**, then **Update** again on Android's screen if it asks. Your pairing and settings stay. The first time, Android asks you to allow Latch to install apps.

## Step 2: Copy thing #1, the owner key

In the Latch app, tap **Create my own gateway**, then tap **copy** next to **Owner key**.

> [!IMPORTANT]
> Don't leave this screen until Step 4 (you can switch to your browser and come back). Keep this key secret.

## Step 3: Paste it into Vercel

Open Vercel **either way**, whichever is easier. Both open the exact same page:

- **On your phone:** tap **Open Vercel** in the Latch app, **or**
- **On a computer:** click [![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch&root-directory=servers%2Fvercel&project-name=latch-gateway&repository-name=latch-gateway&env=LATCH_ADMIN_TOKEN&envDescription=Paste+the+owner+key+shown+in+the+Latch+app.+It+stays+in+your+Vercel+project+and+on+your+phone.&envLink=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch%2Fblob%2Fmain%2Fdocs%2Foperations%2Fvercel.md&stores=%5B%7B%22type%22%3A%22integration%22%2C%22integrationSlug%22%3A%22upstash%22%2C%22productSlug%22%3A%22upstash-kv%22%2C%22protocol%22%3A%22storage%22%7D%5D)

Then on Vercel:

1. Sign in (or make a free account). If it asks for a plan, pick **Hobby** (free).
2. **Git repository:** keep the name and keep it **private**. Click **Create**.
3. **Upstash Redis:** click **Add**. Plan **Free**, region **`us-east-1`**, read regions **none**.
   Keep `us-east-1`: it sits next to Vercel's default function region (`iad1`, Washington). Picked another region? See [Fix: AI actions are slow](troubleshooting.md#fix-ai-actions-are-slow).
4. **`LATCH_ADMIN_TOKEN`:** paste thing #1 (the owner key from the app).
5. Click **Deploy** and wait about a minute.

## Step 4: Copy thing #2, the address, back into the app

1. On Vercel's **"Congratulations!"** page, copy the address (`latch-gateway-….vercel.app`).
   No such page? Go to the [Vercel dashboard](https://vercel.com/dashboard) → your project → the address next to **Domains**.
2. In the Latch app, paste it into **Paste your gateway address** → tap **Connect this phone**. Done.

## Step 5: Follow the setup the app shows you

Right after connecting, Latch walks you through everything it needs, one step at a time. Nothing turns on without you:

1. **Notifications:** tap **Allow**, so you always see when a session is running and can stop it.
2. **Screen access:** tap **Open accessibility settings**, find **Latch**, and switch it on. This one is required.
   - Android says **"Restricted setting"** or the switch is greyed out? Follow [Fix: Restricted setting](troubleshooting.md#fix-restricted-setting) below.
3. **Stay connected:** tap **Allow**, so your phone doesn't cut the session when the screen is off. You can skip this one. (Xiaomi, realme, OPPO, Samsung need one more switch: [Fix: Session stops when the screen is off](troubleshooting.md#fix-session-stops-when-the-screen-is-off).)

You can open this setup again any time from **Settings → Permissions and setup**.

## Step 6: Choose what the AI may do

The last setup step asks you to pick a starting point:

| Choice | What the AI can do |
|---|---|
| **Just look** | Read what's on screen |
| **Look and tap** | Read, tap, scroll, go back, open apps |
| **Everything** | Also take screenshots and type |

Change single switches any time in the **Access** tab. Want to approve **every single** action? Turn on **Ask me before every action**.

**Choose the apps the AI can use:** **Access → Apps the AI can use** lists every app on your phone with a switch.

- **Switched on:** the AI works there without asking you, including sending and deleting. Everything it does is listed in the **Activity** tab. Each line says who did it: **AI**, **You** (your own taps during a session, and your answers), or **Latch**.
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
- **Size:** up to **2 GB** per file on a self-hosted gateway, and on Vercel once you connect a free **Vercel Blob** store ([how](operations/vercel.md#big-files-vercel-blob)); without Blob, Vercel carries files up to 4 MB.
- **Speed:** the phone and your computer move the bytes themselves, straight to and from the storage, at your connection's speed. A big file keeps moving in the background, with a progress bar on the phone.
- **Faster on the same Wi-Fi:** switch on **Access → Files → Fast transfers on the same Wi-Fi**. When your computer is on the phone's Wi-Fi (or the phone's hotspot), files then go straight between them, still encrypted the same way, usually many times faster. Anywhere else the same command quietly uses your gateway. Off until you switch it on.
- File contents are never logged.

## Step 7: Connect your AI

Works the same in every MCP app (Claude, ChatGPT, Codex, Cursor, VS Code…):

1. In your AI app, add a remote MCP server with this URL: `https://<your-address>/mcp`
2. Your browser opens a Latch page showing a 4-letter code.
3. In the Latch app, open **Connect** → tap **Approve** on the request with the same code. The page never asks for your owner key; if a page does, it isn't yours, so close it.

Done. The AI app shows up in **Connect**, where you can remove it any time.

<details>
<summary>My AI app has no sign-in option (keys instead)</summary>

In the Latch app, **Connect** → **Create key**, then give your app either:
- the **secret link** `https://<your-address>/mcp/<key>` (apps that only take a URL), or
- the URL `https://<your-address>/mcp` plus the header `Authorization: Bearer <key>`.

</details>

## Skills: do it once, repeat it in one go

After your AI does a task (say, filling a grocery cart), ask it to **save it as a skill**. Next time it runs in one call with whatever you need *this* time:

- *"Order bread and 2 paneer"* today, *"order milk, 3 tomatoes, eggs, and rice"* tomorrow: same skill, different list.
- It finds buttons by their words, picks the **ADD** next to the right product even when similar items are listed, and skips pop-ups.
- If something is different (an item is out of stock, a new screen), it stops and your AI handles that step, then carries on.
- Payments, sends, and anything that needs your OK still ask you, exactly as before. Skills live on **your** gateway.
- **Starter skills** come with Latch: web search (Chrome), YouTube search, Maps search, Settings search, and a WhatsApp message. Ask your AI to *"show Latch's starter skills"* and to save the ones you want; nothing runs until it's saved, and a WhatsApp Send still asks you unless WhatsApp is switched on.
- Even without a saved skill, your AI can send a few predictable steps together (open an app, search, tap a result) with `run_steps`, so it doesn't stop to think between each one. Every step still follows the same rules.

## Step 8: Start a session and try it

1. On the app's **Home** tab, pick how long (15, 30, 60, or 120 minutes) and tap **Start session**.
2. A red **● Latch · Stop** button stays on your screen. **Tap it any time to stop everything instantly.**
3. Ask your AI something simple: *"What's on my phone screen right now?"*

Stuck? See [troubleshooting](troubleshooting.md).
