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
2. Open Latch, enter the gateway address and the pairing code from the console.
3. The app opens **Set up your phone** right after pairing and asks for each permission in turn, saying why:
   1. Notifications (Android 13+): the session notification with its Stop button. Skippable.
   2. Screen access: Android Settings → Accessibility → Latch remote control → on. Required; the step stays open until the service is on.
      - Android 13+ may say the setting is **restricted** for sideloaded apps. The step links to *App info*: **⋮ → Allow restricted settings**, then try again.
   3. Battery: an exemption from battery optimization, so the system does not cut a running session. Skippable.
   4. Access: a starting preset (*Just look*, *Look and tap*, *Everything*); nothing is chosen for the owner. Fine-tune each switch later in the Access tab.
   The owner can finish later; Home then shows *Finish setup*, and *Start session* opens the setup instead of starting a session that could do nothing. Settings → *Permissions and setup* reopens it.
4. Home → choose a session length → *Start session*. A red "● Latch · Stop" pill stays on screen until the session ends.

## What the app enforces on the phone

- Commands run strictly one at a time.
- Actions must cite the latest observation; the foreground app must be unchanged and the target element visible, enabled, and within 8 px of where it was.
- Sensitive elements (password input types, `isAccessibilityDataSensitive`, and editable fields labelled like PIN, OTP, CVV, card number…) are sent without text and can never be tapped, swiped from, or typed into.
- Agents cannot observe or act while Latch itself, the notification shade, or the lock screen (`com.android.systemui`) is in front; gestures cannot start in the status bar or on the Stop pill; `launch_app` refuses Latch and System UI.
- Approval requests appear as a card over the current app; the allow buttons enable after 1 s; unanswered requests expire before the command deadline.
- The phone judges every tap, swipe, and type-and-Enter itself with the gateway's rules and word lists (`packages/schemas/v1/policy/words.json`, bundled as an asset), using its own copy of the screen plus what the target shows right now, and asks the owner even when the gateway did not. Critical actions (money, installs, permission prompts, account deletion) are asked every time; others may be saved for the session or "always in this app". Saved answers stay on the phone, are listed under **Access → Always allowed**, and are wiped when the phone forgets the gateway.
- The cursor overlay is a separate untouchable, unfocusable window hidden from screen readers and from screenshots. *Keep the screen on* sets `FLAG_KEEP_SCREEN_ON` on the Stop pill window for the session.
- Pause, Stop (pill, notification, or app), session expiry, revocation, and switching off the accessibility service all stop command execution immediately.
- The device token is encrypted with a non-exportable Android Keystore key; backups and device transfer exclude all app data.

## Known limitations

- Only the active window's element tree is read; dialogs from other windows and the on-screen keyboard are not included.
- Screenshots are scaled to a 1280 px long edge; the platform limits them to roughly one per second.
- Apps that set `FLAG_SECURE` (banking, some video apps) cannot be captured; their element tree may still be readable unless they mark data sensitive.
- `type_text` replaces the field's whole content. With `submit` it then presses the field's IME action (`ACTION_IME_ENTER`); fields that do not handle it report an error after the text was typed.
- After `launch_app` the phone waits until the opened app is in front (within the settle budget) before observing, so the agent does not get the previous app's screen.
- After an action the phone waits until the screen has changed and holds still: two fingerprints of the active window (text, positions, checked states) in a row are equal and no accessibility event arrived for `quiet_ms`. Page transitions animate without events; positions catch them. If nothing changes within 450 ms the action had no visible effect and the screen is taken as it is.
- Observations leave out zero-size and fully off-screen elements (web pages report many); their children keep the nearest kept ancestor as parent.
- Smart settle relies on accessibility events; an app that animates without pause (video, spinners) uses the whole 1.5 s budget.
- An agent with gestures can operate Android Settings (outside the shade). Turn on *Ask me before every action* when that matters; a per-app allowlist is planned.
- Google Play's accessibility policy may not allow this use (**to verify** before any store submission); Android developer verification requirements for sideloaded apps must be checked before each release.

## Real-device gate (S4)

Run on at least two phones (for example a Pixel-class device on Android 16 and a Samsung-class device on Android 14) and record model, Android version, app build, gateway build, and results in the release notes.

1. Clean install → pair → only `device.info` enabled; `observe` from an MCP client returns `permission_missing`.
2. Enable the accessibility service and `ui.observe`; `observe` returns the Settings app tree; a password field (any login screen) appears as `<sensitive, redacted, not actionable>`.
3. Enable `screen.capture`; `observe` returns an image; a `FLAG_SECURE` app returns `screen_protected`.
4. Enable `input.gesture`; tap "Network & internet" in Settings by element id; the next observation shows the new page.
5. Re-use an old `observation_id` → `stale_observation`.
6. In a messaging app, type a draft (`input.text`), tap Send → the approval card appears over the app; Deny → `user_denied`, nothing sent; repeat and Approve → sent once.
7. Open the notification shade by hand; `observe` → `policy_refused`.
8. During a long `scroll` sequence, tap the Stop pill → the next tool call returns `device_unavailable` within one second; measure the time.
9. Revoke the phone in the console → the app shows "revoked"; reconnect attempts fail.
10. Switch off the accessibility service mid-session → actions return `permission_missing`; the Stop pill disappears.
11. TalkBack on: the Home screen, capability switches, and approval card are announced with their text; large font (200%) does not clip the Stop button.
12. Regression for the 2026-10-02 run: in WhatsApp, `type_text` then `tap` Send, and separately `type_text` with `submit` → each shows an approval card; tapping a contact's phone number or a SIM choice in the dialer → approval card.
13. Approve with **Always in WhatsApp** → the same Send asks no more; **Access → Always allowed** lists it; Remove → it asks again. A *Pay* or *Install* button never offers "Always".
14. Cursor on: the pointer glides to each target: a hand over buttons and links, a text cursor while typing, a grabbing hand on swipes, scrolls, and drags, two fingertips on a pinch, a filling ring on a long press. About 4 s after the AI's last action it fades away. It is absent from `observe` screenshots, and TalkBack does not announce it. Cursor off: no pointer.
17. Gateway update warning: with a gateway older than the app (protocol below 1.3), Home shows **Update your gateway**; after updating it and starting a new session, the card is gone.
15. Keep the screen on: the screen stays on for 5 minutes of an idle session; switched off, it times out normally.
16. Speed: compare `_meta["latch/timing"]` for `tap` with the 2026-10-02 numbers; `wait_for`, `scroll_to` (Settings → "About phone"), `pinch` on Maps, double-tap, and a `hold_ms` drag on the home screen each work in one call.
