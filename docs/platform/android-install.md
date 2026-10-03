# Installing Latch on Android: Play Protect and "Restricted setting"

Latch works through an Android **accessibility service**: that is the only
public way an app can read the screen and tap for you. Fraud apps abuse the
same permission, so Android and Google Play Protect put extra checks in
front of any app that uses it **and was not installed from an app store**.
Those checks are working as designed. Latch does not try to hide from them
or get around them; there is no honest way to, and a "trick" that worked
would also work for malware. This page explains each check and the owner's
legitimate way through.

## 1. "App blocked to protect your device" (Google Play Protect)

**What it is.** Play Protect's *enhanced fraud protection* (live in India
since October 2024, earlier in Singapore and other countries) blocks
installs of apps that declare one of four permissions often used for fraud:
`RECEIVE_SMS`, `READ_SMS`, notification listener, and **accessibility**.
It applies only to installs from *internet-sideloading sources*: a web
browser, a messaging app, or a file manager. The block has no "install
anyway" button.

**What you can do (pick one):**

- **Install from a computer with ADB** (recommended for testers). ADB installs
  are not an internet-sideloading source, and Android does not mark them
  with "Restricted setting" either:
  1. On the phone: *Settings → About phone*, tap *Build number* 7 times, then
     *Developer options → USB debugging* on.
  2. On the computer (Android platform-tools):
     `adb install -r latch-android.apk`
- **Pause Play Protect only for the install.** Play Store → profile picture
  → *Play Protect* → ⚙ → turn off *Scan apps with Play Protect*, install
  Latch, then **turn it back on**. Play Protect keeps protecting the rest of
  the phone afterwards. This is the owner's informed choice for their own
  device; Latch never asks for it inside the app.
- **Wait for a store build.** An app store that installs with Android's
  session API (F-Droid, and later possibly Google Play) is not an
  internet-sideloading source. This is the long-term path; see
  "What the project is doing" below.

## 2. "Restricted setting" when switching Latch on in Accessibility

**What it is.** Since Android 13, an app installed from a downloaded or
local file cannot be granted accessibility (Android 15 extends this to
more permissions) until the owner allows it in *App info*. Store installs
and ADB installs are not affected.

**Steps** (the Latch setup screen shows the same steps, worded for your
phone's brand):

1. In Latch, tap **Turn on screen access** and try to switch Latch on
   **once**. Android shows "Restricted setting": tap **OK**.
   The allow option only appears after this attempt.
2. Tap **Open App info** in Latch.
3. Tap **⋮** (top right) → **Allow restricted settings**, confirm with your
   PIN or fingerprint.
   - *Xiaomi / Redmi / POCO (HyperOS, MIUI):* if App info has no ⋮, open
     *Settings → Apps → Manage apps → Latch* and use ⋮ there.
   - *realme / OPPO / OnePlus (realme UI, ColorOS), Samsung (One UI),
     Pixel:* ⋮ is at the top right of App info.
4. Go back and switch Latch on again under *Accessibility → Downloaded apps*.

**If the menu never appears**, install with `adb install` (above); or, with
the app already installed and USB debugging on, allow it from the computer:
`adb shell cmd appops set <package> ACCESS_RESTRICTED_SETTINGS allow`
(the test build's package is `io.github.aspershupadhyay.latch`).

## What the project is doing about it

- **Least privilege.** The manifest asks only for internet, notifications,
  battery-optimisation exemption, and the accessibility service. No SMS, no
  notification listener, no `QUERY_ALL_PACKAGES`, no overlay permission.
- **Honest declaration.** `isAccessibilityTool="false"`, so apps can hide
  sensitive views from Latch, and the service runs only during a session the
  owner starts.
- **Signed releases and developer verification.** Release APKs are signed
  with one stable key (`release.yml`). Google's Android developer
  verification starts on 30 September 2026 in Brazil, Indonesia, Singapore,
  and Thailand and expands from 2027 (India included); the maintainer
  should register the package name and signing key before then so installs
  keep working.
- **Store distribution.** F-Droid first; Google Play only after its
  accessibility API policy review (handbook chapter 17, item 10).
- **Play Protect review.** Developers can ask Google to review a Play
  Protect warning for their app (Play Console help: "Play Protect
  warnings"). That is worth doing once the release key and verification are
  in place, because a review is tied to the signing key. Test builds are
  signed with the release key since 2026-10-03.

Sources: Google's announcement of the pilot
(security.googleblog.com, February 2024, and blog.google India, October 2024),
Android 13/15 restricted-settings behaviour (Android Authority, Esper),
developer verification dates (The Hacker News, June 2026).
