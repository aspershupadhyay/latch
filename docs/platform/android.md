# Android

Status: **IN_REVIEW** — builds, unit tests, and lint pass in CI. Not yet verified on a physical phone; see [the real-device gate](#real-device-gate-s4).

## Support

| | |
|---|---|
| Minimum | Android 11 (API 30), needed for accessibility screenshots |
| Target / compile | API 36 (Android 16) |
| Toolchain | AGP 8.13.2, Kotlin 2.4.20, Gradle 8.14.5, JDK 17+ |
| Distribution | Signed APK on GitHub Releases. Not on Google Play (see below). |

Library versions are pinned in `apps/android/gradle/libs.versions.toml`. The newest AndroidX releases (Compose BOM 2026.08+, core 1.19, lifecycle 2.11, OkHttp 5.5) require compileSdk 37 and AGP 9; moving there is a planned, deliberate upgrade.

## Build

```sh
scripts/setup-android-sdk.sh                      # headless SDK into ~/android-sdk
export ANDROID_HOME=~/android-sdk
cd apps/android
./gradlew assembleDebug testDebugUnitTest lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (package io.github.aspershupadhyay.latch.debug)
# Signed release build: set LATCH_KEYSTORE_PATH and LATCH_KEYSTORE_PASSWORD, then ./gradlew assembleRelease
```

Every push to `main` also publishes a signed **release** build to the `beta` pre-release (`.github/workflows/test-build.yml`), versioned `0.1.0-beta.N`, so the newest beta is always at `releases/download/beta/latch-android.apk` (package `io.github.aspershupadhyay.latch`). Release builds are minified (about 3 MB instead of 32 MB for debug), not debuggable, and accept only https gateways. Debug builds (package `…latch.debug`) are for local development and allow http gateways on a laptop or emulator.

## Updates inside the app

Latch updates itself from its GitHub releases and keeps pairing, saved approvals, and settings (`app/src/main/java/io/github/aspershupadhyay/latch/update/`).

1. When Latch opens (at most every 30 minutes, switch in Settings → *Check when Latch opens*), it reads `latch-update.json` from the release: `version_code`, `version_name`, `apk_url`, `sha256`, `size`, `commit`. Betas read the `beta` pre-release (`LATCH_UPDATE_MANIFEST_URL`); tagged releases read the newest published release. Builds up to `0.1.0-test.12` read `test-build`, which the workflow keeps as a copy of `beta`. Local debug builds read `beta` too, but their different package and key keep them from updating.
2. A higher `version_code` than the installed one shows **Update available** on Home and in Settings. CI numbers builds: test builds `run_number + 100`, releases `X*1000000 + Y*1000 + Z` from the `vX.Y.Z` tag. Local builds are version 1.
3. **Update** stops a running session (installing restarts Latch), downloads the APK, and checks it before Android sees it: the address must be under this repository's `releases/download/` over https; the file must have the announced size and SHA-256; it must be Latch's own package, the announced and a newer version, and signed by a certificate the installed app already has.
4. Android's `PackageInstaller` installs it. The first time, Android asks the owner to allow Latch to install apps (*Install unknown apps → Allow from this source*). Android then shows its own Update screen; on Android 12+ it may skip that screen because Latch is updating itself. A silent update without the owner's tap on **Update** is never attempted.

What protects the owner is Android's rule that an update must carry the same signing key as the installed app. The checks above give clear messages; they are not what makes it safe. A GitHub account takeover could publish a bad manifest, but not a build signed with the key, unless the key also leaked.

### The release signing key

Android installs an update only when it is signed with the same key as the installed app. Betas and tagged releases are both signed with the release key, from two repository secrets:

- `LATCH_KEYSTORE_BASE64`: a base64 PKCS12 keystore holding the key under the alias `latch`.
- `LATCH_KEYSTORE_PASSWORD`: its password. (`LATCH_KEY_ALIAS` and `LATCH_KEY_PASSWORD` are optional and default to `latch` and the store password.)

`test-build.yml` checks the keystore and prints its SHA-256 certificate fingerprint; a missing or broken secret fails the build, since Android cannot install an unsigned APK. To create a key on any computer with a JDK:

```sh
keytool -genkeypair -keystore latch.jks -storetype PKCS12 -alias latch \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Latch"     # asks for a password
base64 -w0 latch.jks > latch.jks.b64                                # macOS: base64 -i latch.jks
```

Paste `latch.jks.b64` and the password into GitHub → repository **Settings → Secrets and variables → Actions → New repository secret**.

**This key is permanent.** Keep a private backup of the keystore and password (a password manager or private cloud storage). If it is lost, every installed Latch has to be uninstalled and reinstalled; if it leaks, someone could sign builds that install over Latch. Rotating it means the same reinstall for everyone.

Moving from the old debug test builds (package `…latch.debug`) to release builds takes one install of the new app; it is a different package, so both can sit side by side until the old one is uninstalled. Pair the new one again.

## Owner setup on the phone

1. Install the APK (allow installs from your browser or file manager when Android asks).
2. Open Latch, enter the gateway address and the owner key (or a pairing code made with the owner API).
3. The app opens **Set up your phone** right after pairing and asks for each permission in turn, saying why:
   1. Notifications (Android 13+): the session notification with its Stop button. Skippable.
   2. Screen access: Android Settings → Accessibility → Latch remote control → on. Required; the step stays open until the service is on.
      - Android 13+ may say the setting is **restricted** for sideloaded apps. The step links to *App info*: **⋮ → Allow restricted settings**, then try again.
   3. Battery: an exemption from battery optimization, so the system does not cut a running session. Skippable.
   4. Access: a starting preset (*Just look*, *Look and tap*, *Everything*); nothing is chosen for the owner. Fine-tune each switch later in the Access tab.
   5. Apps (Access → *Apps the AI can use*, ADR-021): every app with a switch. In an app that is switched on the AI acts without asking, except payments, installs, permission prompts, and account deletion, which ask every time. An app that is off is asked about the first time the AI needs it (*Not now* / *This session* / *Always*); until then the AI cannot see it. Apps that may hold money, accounts, or passwords show a warning before they are switched on.
   The owner can finish later; Home then shows *Finish setup*, and *Start session* opens the setup instead of starting a session that could do nothing. Settings → *Permissions and setup* reopens it.
4. Home → choose a session length → *Start session*. A red "● Latch · Stop" pill stays on screen until the session ends.

## What the app enforces on the phone

- Commands run strictly one at a time.
- Actions must cite the latest observation; the foreground app must be unchanged and the target element visible, enabled, and within 8 px of where it was.
- Sensitive elements (password input types, `isAccessibilityDataSensitive`, and editable fields labelled like PIN, OTP, CVV, card number…) are sent without text and can never be tapped, swiped from, or typed into.
- Agents cannot observe or act while Latch itself, the notification shade, or the lock screen (`com.android.systemui`) is in front; gestures cannot start in the status bar or on the Stop pill; `launch_app` refuses Latch and System UI.
- Approval requests appear as a card over the current app; the allow buttons enable after 1 s; unanswered requests expire before the command deadline.
- The phone judges every tap, swipe, and type-and-Enter itself with the gateway's rules and word lists (`packages/schemas/v1/policy/words.json`, bundled as an asset), using its own copy of the screen plus what the target shows right now, and asks the owner even when the gateway did not. Critical actions (money, installs, permission prompts, account deletion) are asked every time; others may be saved for the session or "always in this app". Saved answers stay on the phone, are listed under **Access → Always allowed**, and are wiped when the phone forgets the gateway. In apps switched on in **Access → Apps**, the non-critical ones run without asking and Activity lists them as "Done without asking" (ADR-021).
- **Activity → Export a PDF report** (roadmap R25) turns the log into a PDF for the latest AI task, today, the last 24 hours, or all 7 days kept: a summary (actions, questions to the owner and answers, done without asking, refusals, files, apps), each AI task, and every line oldest first with who did it (AI, you, or Latch). It is made on the phone with Android's own `PdfDocument`, saved to **Downloads/Latch reports**, and offered to the share sheet. Like the log, it never holds screen text, typed text, passwords, codes, or the clipboard. Its fingerprint is a SHA-256 chained over every line, so two copies can be compared and a changed, added, or removed line shows. An AI can read a saved report only like any other file in Downloads, with the owner's file switches.
- When nothing a tap would hit carries a label, the phone reads the words on that control from its pixels with Google ML Kit's on-device text recognition (ADR-032) and judges them with the same word lists, so an icon-only Send or Pay still asks. The words only raise the judgement; they never appear in titles, Activity, the gateway, or the AI. Owner switch: **Settings → Read unlabeled buttons** (on by default). ML Kit starts only on first use; it sends Google usage metrics (device model, app version, latency, sizes, a per-installation id), never the picture or the words. A protected window or a failure leaves the judgement as without reading.
- The cursor overlay is a separate untouchable, unfocusable window hidden from screen readers and from screenshots. *Keep the screen on* sets `FLAG_KEEP_SCREEN_ON` on the Stop pill window for the session.
- Pause, Stop (pill, notification, or app), session expiry, revocation, and switching off the accessibility service all stop command execution immediately.
- The device token is encrypted with a non-exportable Android Keystore key; backups and device transfer exclude all app data.

## Known limitations

- Only the active window's element tree is read; dialogs from other windows and the on-screen keyboard are not included.
- Screenshots are scaled to a 1280 px long edge; the platform limits them to roughly one per second.
- Reading unlabeled buttons needs a screen capture, so it cannot see inside `FLAG_SECURE` windows, and recognizes Latin-script text only (ML Kit's bundled Latin model).
- Apps that set `FLAG_SECURE` (banking, some video apps) cannot be captured; their element tree may still be readable unless they mark data sensitive.
- `type_text` replaces the field's whole content. With `submit` it then presses the field's IME action (`ACTION_IME_ENTER`); fields that do not handle it report an error after the text was typed.
- After `launch_app` the phone waits until the opened app is in front (within the settle budget) before observing, so the agent does not get the previous app's screen.
- After an action the phone waits until the screen has changed and holds still: two fingerprints of the active window (text, positions, checked states) in a row are equal and no accessibility event arrived for `quiet_ms`. Page transitions animate without events; positions catch them. If nothing changes within 450 ms the action had no visible effect and the screen is taken as it is.
- Observations leave out zero-size and fully off-screen elements (web pages report many); their children keep the nearest kept ancestor as parent.
- Smart settle relies on accessibility events; an app that animates without pause (video, spinners) uses the whole 1.5 s budget.
- An agent with gestures can operate Android Settings (outside the shade). Turn on *Ask me before every action* when that matters; a per-app allowlist is planned.
- Google Play's accessibility policy may not allow this use (**to verify** before any store submission); Android developer verification requirements for sideloaded apps must be checked before each release.

## Real-device gate (S4)

### Scripted check first (R29 f)

On the computer that runs the gateway, with the phone connected and full ADB access on (**Access → Full ADB access**, paired with a code):

```
LATCH_MCP_TOKEN=<your MCP token> latch-gateway check-phone
```

It drives the phone over MCP exactly as an AI app would and then asks you, one at a time, to turn Wi-Fi off and on during a question on the phone, to press Stop and Start, and to let it reboot the phone (type `s` to skip any). It checks: the phone is ready; the screen can be read; a two-finger rotate; `adb id` runs as `uid=2000(shell)`; `seq 1 100000` arrives whole (588 895 bytes over `continue_from`); `screencap -p` comes back as a PNG image; a 25-second command finishes; an answer survives a Wi-Fi drop and nothing runs twice (R29 a); Stop ends access and Start brings it back; after a reboot the phone reconnects without a cable and ADB works again. It writes `latch-phone-check-<date>.md`, which names the phone's model and app protocol but never its name, its screen, or command output, so it can be attached to the R21 issue as it is. Options: `--url http://127.0.0.1:8787/mcp` (the default follows `LATCH_BIND`), `--device <id>` when several phones are connected, `--quick` to skip the 25-second step, `--no-prompts` to skip every step that needs you, `--report <file>`. The exit code is 0 only when no step failed. The ready-made gateway downloads on the beta release include it.

### Manual checklist

Run on at least two phones (for example a Pixel-class device on Android 16 and a Samsung-class device on Android 14) and record model, Android version, app build, gateway build, and results in the release notes.

1. Clean install → pair → only `device.info` enabled; `observe` from an MCP client returns `permission_missing`.
2. Enable the accessibility service and `ui.observe`; `observe` returns the Settings app tree; a password field (any login screen) appears as `<sensitive, redacted, not actionable>`.
3. Enable `screen.capture`; `observe` returns an image; a `FLAG_SECURE` app returns `screen_protected`.
4. Enable `input.gesture`; tap "Network & internet" in Settings by element id; the next observation shows the new page.
5. Re-use an old `observation_id` → `stale_observation`.
6. In a messaging app, type a draft (`input.text`), tap Send → the approval card appears over the app; Deny → `user_denied`, nothing sent; repeat and Approve → sent once.
7. Open the notification shade by hand; `observe` → `policy_refused`.
8. During a long `scroll` sequence, tap the Stop pill → the next tool call returns `device_unavailable` within one second; measure the time.
9. Revoke the phone from the owner API (`DELETE /v1/admin/devices/<id>`) → the app shows "revoked"; reconnect attempts fail.
10. Switch off the accessibility service mid-session → actions return `permission_missing`; the Stop pill disappears.
11. TalkBack on: the Home screen, capability switches, and approval card are announced with their text; large font (200%) does not clip the Stop button.
12. Regression for the 2026-10-02 run: in WhatsApp, `type_text` then `tap` Send, and separately `type_text` with `submit` → each shows an approval card; tapping a contact's phone number or a SIM choice in the dialer → approval card.
13. Approve with **Always in WhatsApp** → the same Send asks no more; **Access → Always allowed** lists it; Remove → it asks again. A *Pay* or *Install* button never offers "Always".
14. Cursor on: the pointer glides to each target: a hand over buttons and links, a text cursor while typing, a grabbing hand on swipes, scrolls, and drags, two fingertips on a pinch, a filling ring on a long press. About 4 s after the AI's last action it fades away. It is absent from `observe` screenshots, and TalkBack does not announce it. Cursor off: no pointer.
15. Gateway update warning: with a gateway older than the app (protocol below 1.3), Home shows **Update your gateway**; after updating it and starting a new session, the card is gone.
16. Keep the screen on: the screen stays on for 5 minutes of an idle session; switched off, it times out normally.
17. Speed: compare `_meta["latch/timing"]` for `tap` with the 2026-10-02 numbers; `wait_for`, `scroll_to` (Settings → "About phone"), `pinch` on Maps, double-tap, and a `hold_ms` drag on the home screen each work in one call.

Added for the roadmap rows that wait on a phone (chapter 17 §10; each row says what is left):

18. **Timing (R1, R7):** at least 10 calls each of `observe`, `tap`, `type_text`, `list_apps`, `launch_app`; record the median and p90 of `total_ms` and `phone_ms` from `_meta["latch/timing"]` in the R1 row.
19. **Device keys (R19, ADR-037):** after pairing, the gateway's device list shows the phone and sessions work; uninstall and reinstall the app, pair again, and sessions work with the new phone's key (the gateway refuses the old entry's key from anyone else).
20. **Owner key (R19, ADR-038):** Settings → **Rotate owner key** → the dialog says the key was saved; Connect still lists AI apps; the old key is refused by `curl -H "Authorization: Bearer <old key>" <gateway>/v1/admin/devices` (401).
21. **Unlabeled buttons (R9):** in an app that is not switched on, an icon-only Send (WhatsApp's) asks for approval; note the APK size.
22. **Screen changes and effects (R5, R11):** a tap that changes a switch returns only the changed lines; a tap on empty space says the screen did not visibly change.
23. **Screenshots (R6):** an `observe` with `screenshot: true` returns WebP on a 1.10+ gateway; note size and encode time.
24. **Starter skills and replay (R8, R14):** `list_skills {starters: true}` labels match the phone's apps; after a task, Activity → **By task** shows it with its steps.
25. **Repeats (R12):** Google Maps search results are listed once each, and the Stop pill does not cover them.
26. **Phone controls (R16):** each group stays refused until switched on in **Access → Phone controls**; volume, brightness (after "Modify system settings"), flashlight, and the Wi-Fi panel each work once.
27. **Old pairings:** *Settings → Disconnect from this relay*, then pair again at once: `list_devices` lists the phone once. **Connect → Other phones on this relay** lists the other phones; **Remove** asks first, and the removed phone drops off `list_devices`.
28. **More of the tree (R26, protocol 1.16):** in Clock, the selected tab shows `[selected]`; on Instagram's feed a post lists its own `actions=[…]` and the like button has `state=` or `label?=`; a long-press menu in any app lists its items first and marks the screen behind as `covered`; `observe {marks: true}` returns a screenshot with ember `n12` tags on what can be tapped; an empty caption box shows `hint=` and no text.
