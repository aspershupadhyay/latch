# Connect your phone to a gateway on your Mac, and try everything

This is the whole path for one owner, one Android phone, and one Mac on the same Wi-Fi. Nothing goes through the cloud. Everything runs on your Mac. About 15 minutes.

You need: an Android 11+ phone, a Mac (Apple Silicon or Intel), both on the **same Wi-Fi** (a guest network that isolates devices won't work), and an AI app with MCP (Claude Code below).

## 1. Install the app on the phone

1. On the phone, open **https://github.com/aspershupadhyay/latch/releases/download/beta/latch-android.apk** and install it. Android asks once to allow installs from your browser: allow it.
2. Already have Latch? Update it (Latch updates itself from **Settings → Check for updates**, or install the APK again over it). Wireless-ADB pairing needs a build from 2026-10-18 or later. Older builds fail with `pairing failed: org.conscrypt.Conscrypt`.

## 2. Install the gateway on the Mac

In **Terminal**:

```sh
curl -fsSL https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.sh | sh
```

It picks the right file for your Mac, checks it against the published checksum, installs `latch-gateway` and `latch-start` in `~/.local/bin`, and creates your two keys once in `~/.latch/latch.env` (readable only by you). If it says to add a folder to your PATH, run the line it prints.

> **"Cannot be opened… Move to Trash"?** That happens when the gateway was downloaded in a browser. macOS quarantines it because it isn't notarized by Apple, and `chmod` can't lift that. The command above avoids it. To keep a file you downloaded in a browser instead: `xattr -d com.apple.quarantine ~/Downloads/latch-gateway-macos-arm64`.

## 3. Start it

```sh
latch-start
```

It prints:

- The ready-made `claude mcp add` command for your AI app.
- **Connect your phone**: a **QR code**, and under it the address (`https://192.168.x.x:8788#pin=…`) and a single-use **setup code** (`K7QX-M2PD`) in case the camera can't read it. The code works once and expires after 15 minutes; `latch-gateway pair` (in another Terminal, after `. ~/.latch/latch.env`) shows a new one.
- Where AI apps, the live mirror, and the console are on this computer.

If macOS asks whether `latch-gateway` may accept incoming connections, choose **Allow** (the phone connects on port 8788). Leave this Terminal window open while you use Latch. Stop it with Ctrl-C.

**Don't want to start it by hand?** Run `latch-autostart on` once: the gateway then runs in the background and starts again when you log in (`latch-autostart off` undoes it, `latch-autostart status` checks). `latch-start` then just shows a new QR code.

The address and its `#pin=` stay the same across restarts. If your router gives the computer a new address, the phone finds the gateway again by itself (it checks the pin, so no other device can pass for it); you don't pair again.

## 4. Pair the phone

1. Open Latch → **Set up Latch** → **Computer on Wi-Fi** → **Continue**.
2. Tap **Scan QR code**, allow the camera once, and point it at the QR code in the Terminal. The phone connects and becomes the owner of this gateway: no key to copy.
3. Can't scan? Tap **Can't scan? Enter it by hand** and type the address and the setup code shown under the QR code. (An owner key, `lgt_…`, also works in the code field.)
4. Follow **Set up your phone**: notifications, **screen access** (the accessibility service), and battery. On Android 13+, if the screen-access switch is greyed out: **Settings → Apps → Latch → ⋮ → Allow restricted settings**, then try again. Latch shows this step.

### Switch between Wi-Fi and the hosted relay

**Connect → How your AI reaches this phone** has **Hosted** and **Wi-Fi**. Tap the other one: if you set it up before, Latch switches at once and asks for no key (it keeps both, sealed in the phone's keystore); if not, it keeps this one and walks you through the other once. A running session stops when you switch. **Settings → Disconnect** forgets only the one in use; **Forget** in Connect removes a kept one.

## 5. Connect your AI

Claude Code, using the command `latch-start` printed (same Mac):

```sh
claude mcp add --transport http latch http://127.0.0.1:8787/mcp --header "Authorization: Bearer <AI app key>"
```

Any other MCP app: Streamable HTTP URL `http://127.0.0.1:8787/mcp`, header `Authorization: Bearer <AI app key>`. You can also make one key per AI app on the phone, in the **Connect** tab.

## 6. Try each feature

On the phone, tap **Start** on Home first (a session). Then ask your AI:

| Feature | Ask or do | You should see |
|---|---|---|
| Read the screen | "Use Latch: what's on my phone's screen?" | The AI describes the screen; Activity on the phone lists it |
| Act | "Open Settings and turn on Do Not Disturb" | The AI cursor moves, taps happen on the phone |
| Approvals | "Send 'hi' to <contact> on WhatsApp" | A card on the phone asks before Send |
| Stop | Tap the red **Stop** pill mid-task | The AI's next call fails at once |
| **Full ADB** | Phone: **Developer options → Wireless debugging** on. Latch: **Access → Full ADB access** → switch on → **Pair from a notification** → in Settings tap **Pair device with a code** → pull down the notification shade, tap **Enter code**, type the 6 digits | The notification says **Paired**. Then ask: "Run `getprop ro.product.model` with adb" |
| **Mirror on computer** | Phone: Home → **Mirror on computer** → **Start** (entire screen). Mac: open **http://127.0.0.1:8787/mirror** in Chrome, Edge, or Safari and type the 6-digit code | The live screen. Click, drag, scroll, and type on it. Your first click pauses the AI, and **Let AI continue** resumes it |
| Everything at once | In a second Terminal: `. ~/.latch/latch.env && latch-gateway check-phone` | It runs each check, asks you for the steps it needs, and writes `latch-phone-check-<date>.md` (no personal content). Send that file back so the roadmap can be marked done |

## Update the gateway, or start over

- **Update:** run the install command from step 2 again. Your keys and pairing stay. Then `latch-start`. With `latch-autostart on`, restart it: `latch-autostart off && latch-autostart on`.
- **Start over (forget phones, keys, and the Wi-Fi certificate):** stop the gateway (Ctrl-C, or `latch-autostart off`), then `rm -rf ~/.latch`, run the install command again, and `latch-start`. On the phone: **Settings → Disconnect** (or **Forget** under Connect), then scan the new QR code.

## If something fails

- **The phone can't connect** (address or connection error): the Mac and phone must be on the same Wi-Fi. Allow `latch-gateway` in **System Settings → Network → Firewall → Options**. If the Mac's Wi-Fi address changed (`latch-start` prints the new one), enter it again; the pin stays the same.
- **Wrong pin / certificate refused:** paste the address exactly, with `#pin=`.
- **"That code is wrong, used, or expired":** setup codes work once, for 15 minutes. Run `. ~/.latch/latch.env && latch-gateway pair` for a new QR code.
- **ADB `pairing failed`:** update Latch (step 1). Keep **Wireless debugging** on and the phone on Wi-Fi, and type the code while Settings still shows it (it changes each time you open the dialog).
- **Mirror: "this relay cannot mirror":** the phone is paired with a Vercel relay. Pair it with the gateway on your Mac (step 4).
- **Mirror shows a black screen:** the app on the phone protects its screen (banking, passwords). Android blocks every capture there.
- **Gateway won't start: "configuration error":** run it through `latch-start` (it loads your keys), not `latch-gateway serve` alone.
- Ask your AI to call `diagnose`. It checks the phone and names the fix.
