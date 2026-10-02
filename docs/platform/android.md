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
```

Release builds are minified and signed in CI when `LATCH_KEYSTORE_BASE64`, `LATCH_KEYSTORE_PASSWORD`, `LATCH_KEY_ALIAS`, and `LATCH_KEY_PASSWORD` repository secrets exist.

## Owner setup on the phone

1. Install the APK (allow installs from your browser or file manager when Android asks).
2. Open Latch, enter the gateway address and the pairing code from the console.
3. Home → *Turn on the Latch accessibility service* → Android Settings → Accessibility → Latch remote control → on.
   - Android 13+ may say the setting is **restricted** for sideloaded apps. Open *App info* for Latch, tap **⋮ → Allow restricted settings**, then try again.
4. Capabilities → switch on only what you want (everything except device information starts off).
5. Home → choose a session length → *Start session*. A red "● Latch · Stop" pill stays on screen until the session ends.

## What the app enforces on the phone

- Commands run strictly one at a time.
- Actions must cite the latest observation; the foreground app must be unchanged and the target element visible, enabled, and within 8 px of where it was.
- Sensitive elements (password input types, `isAccessibilityDataSensitive`, and editable fields labelled like PIN, OTP, CVV, card number…) are sent without text and can never be tapped, swiped from, or typed into.
- Agents cannot observe or act while Latch itself, the notification shade, or the lock screen (`com.android.systemui`) is in front; gestures cannot start in the status bar or on the Stop pill; `launch_app` refuses Latch and System UI.
- Approval requests appear as a card over the current app; Approve enables after 1 s; unanswered requests expire before the command deadline.
- Pause, Stop (pill, notification, or app), session expiry, revocation, and switching off the accessibility service all stop command execution immediately.
- The device token is encrypted with a non-exportable Android Keystore key; backups and device transfer exclude all app data.

## Known limitations

- Only the active window's element tree is read; dialogs from other windows and the on-screen keyboard are not included.
- Screenshots are scaled to a 1280 px long edge; the platform limits them to roughly one per second.
- Apps that set `FLAG_SECURE` (banking, some video apps) cannot be captured; their element tree may still be readable unless they mark data sensitive.
- `type_text` replaces the field's whole content and never presses Enter.
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
