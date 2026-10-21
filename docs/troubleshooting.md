# Troubleshooting

## Help, I'm stuck

**"I pasted the key in Vercel, deployed, and now I don't know what to do."**
Do [Step 4](setup.md#step-4-copy-thing-2-the-address-back-into-the-app): copy your `….vercel.app` address and paste it into the app.

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

**"The mirror stutters on Wi-Fi" / Alt+D says "over WebSocket" on my own computer's gateway.**
The mirror sends video over UDP on the gateway's Wi-Fi port (8788) when it can, and falls back to its WebSocket by itself. A firewall that allows TCP 8788 but not UDP keeps it on the WebSocket: allow UDP too (Linux with ufw: `sudo ufw allow 8788/udp`; macOS and Windows ask once per app, which covers both). Also: the laptop on a cable to the router, 5 GHz Wi-Fi rather than 2.4 GHz, and the phone near the router each cut the delay.

**"I think my key leaked."**
Change `LATCH_ADMIN_TOKEN` in Vercel to a new key → **Redeploy** → in the app, **Settings → Forget this gateway** → connect again with the new key. To kill just one AI app's key, revoke it in the **Connect** tab.

Still stuck? [Open an issue](https://github.com/aspershupadhyay/latch/issues) and describe what you see.


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

1. **Check what you run:** open `https://<your-address>/healthz` in a browser. Up to date means `"protocol": "1.14"`. It also shows `commit` and `repository`.
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
6. **Check from the AI:** ask it to run `list_devices`. The first line must say `Latch gateway, protocol 1.20, 38 tools.` If the AI still can't see `get_activity`, `finish_task`, `adb`, or `gesture` after that, its app is still holding the old tool list: repeat step 5.

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

### Fix: The QR code won't scan

1. Hold the phone 20–30 cm from the screen with the whole code inside the frame. If it is small, pinch or use **Zoom**.
2. Still blurry? Tap the code to focus, or tap **Focus** until it says **Refocus** (or **Close-up** when you are very near).
3. A bright monitor can wash the code out: tap **Light** and slide toward darker. Turning the screen's brightness down helps too.
4. Light-on-dark codes (a dark terminal with `NO_COLOR`) scan as they are.
5. Or take a photo of the code (or a screenshot on the computer, sent to the phone) and tap **Photo**. Or tap **Type** and enter the address and setup code under the QR code.

Setup codes work once, for 15 minutes. If it says the code is wrong or expired, run `latch-gateway pair` on the computer for a new one.

### Fix: The phone won't connect again after I disconnected

- **Settings → Disconnect** keeps the connection: the first screen shows **Reconnect** — one tap, no key or QR code. Only **Forget keys** removes them; then run `latch-gateway pair` on the computer and scan the new code.
- **Mirror:** stopping on the phone no longer needs a new code. Leave the mirror page open; it says *Waiting for the phone* and comes back by itself when you start mirroring again. After **Forget computers** or the emergency **Stop**, type the new code.
- **Wi-Fi dropped:** the phone reconnects the moment Wi-Fi is back, and finds your computer if it got a new address on the same Wi-Fi. If the computer moved to another network, set it up again from **Connect**.
