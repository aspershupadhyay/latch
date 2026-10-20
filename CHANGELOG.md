# Changelog

All notable changes for owners and operators. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow [Semantic Versioning](https://semver.org/) from the first tagged release; the device protocol has its own [version history](docs/protocol/v1.md#version-history).

Every merge to `main` publishes a [beta build](https://github.com/aspershupadhyay/latch/releases/download/beta/latch-android.apk). Tagged releases are made as described in [docs/operations/release.md](docs/operations/release.md).

## [Unreleased]: beta before 0.1.0

The work toward the first alpha. Rows refer to the [roadmap](handbook/17-plan-review-and-revised-delivery.md).

### Added

- **Find my computer: connect with no QR code** (ADR-060): tap **Find my computer** in the app; the phone finds the gateway on your Wi-Fi and shows a 6-digit number, the same number appears in the `latch start` window, and you type `y` there when they match. The comparison is built so that another device on the Wi-Fi cannot make the numbers match (commit, then reveal).
- **The `latch` command**: `latch start`, `pair`, `status`, `autostart on|off`, `stop`, `restart`, `logs`, `update` (keeps keys and phones), `uninstall` (asks about keys and phones), `version`, `help`. `latch-gateway status` shows whether the gateway runs, its phones, and its Wi-Fi address.
- **A much sharper mirror during videos and games** (owner report 2026-10-20): the bitrate now follows the picture (about 28 Mbit/s for a 1080×2400 phone at 60 fps instead of a fixed 8–12), the phone is sent at its own size up to 2560 px (a 1080-wide screen is no longer rescaled), H.264 uses the High profile and variable bitrate where the phone has them, and busy frames are no longer dropped as if the Wi-Fi were slow (the send queue follows the bitrate instead of a fixed 96 KiB), so motion no longer drags the picture down to 1280 px at 30 fps. On a truly slow Wi-Fi it still steps down: 1920 at 60 first.
- **Scroll with a trackpad without clicking things**: small trackpad steps add up until they really scroll, the drag allows for Android's touch slop, sideways jitter does not swipe, a scroll never starts at the screen's top or bottom edge (the shade and the home gesture), and a trackpad tap right as a scroll ends is ignored.
- **Connect in one tap**: the first screen has **Scan the QR code on my computer**. The terminal's QR code has the standard 4-module white border and solid areas painted as background, so a font with line spacing no longer breaks it up; the gateway also writes the same code as a large page (`latch-gateway pair --open` opens it in the browser).
- **See where the mirror's delay goes**: Alt+D on the mirror page shows encode, network, decode, and draw time, frame rate, bitrate, dropped frames, and glass-to-glass delay (R35).
- **A smoother mirror**: the video is decoded and drawn off the page's main thread and the sound plays from the audio thread, so typing and the AI panel never hold a frame back (R36, R47); a dropped frame heals without a burst of data where the phone supports it (R37); on a slow Wi-Fi the picture gets smaller and the page says "Saving bandwidth" (R38).
- **Less data for the mirror**: sound as Opus, about 96 kbit/s instead of 1.5 Mbit/s, when your browser decodes it (R39); H.265 video where the phone has a hardware encoder and the browser decodes it (R40).
- **Pinch from the computer**: Ctrl+scroll or a trackpad pinch zooms on the phone (Safari also turns) (R42).
- **Copies follow**: a switch on the mirror page brings text you copy on the phone to your computer by itself (Full ADB, off by default; passwords never) (R44).
- **A lighter app**: faster waits and screenshots for AI actions, quicker start, a cheaper activity log, and less work while nothing runs (R48, R49); a lighter gateway per action and per frame (R50).
- **Reconnect without a new code** (ADR-058): stop mirroring on the phone and start it again, and the mirror page on your computer comes back by itself (it waits for the phone); no new 6-digit code. **Forget computers** on Home, or the emergency Stop, makes computers need a code again.
- **Disconnect keeps your connection**: Settings → Disconnect now keeps the keys, and the first screen offers **Reconnect** in one tap, with no QR code or key. **Forget keys** removes them as before.
- **Back at once when the Wi-Fi is**: the phone's link to the gateway and the mirror reconnect the moment the network returns, and the mirror finds your computer at a new Wi-Fi address on its own.
- **A new QR scanner**: full-screen camera with a frame, reads codes anywhere in view and light-on-dark terminal codes, pinch or slider zoom, tap to focus, flashlight, a focus mode for cameras that stay blurry (Auto, Refocus, Close-up), a brightness slider for bright monitors, reading a photo of the code instead, and a clear message when a QR code is not Latch's.
- **Mirror feels like a second screen** (ADR-057): with Full ADB on, clicks, drags, scrolling, and typing reach the phone as real touches and keys, so drags follow the pointer live and typing behaves as on a keyboard ("Smooth input" on the page). Lower delay throughout: no Nagle buffering on the phone's socket, a small send buffer, shorter queues, a higher bitrate.
- **Copy and paste between computer and phone**: Ctrl/Cmd+V pastes text from the computer into the phone (a pasted picture or file is sent to the phone); Ctrl/Cmd+C or X copies what is selected on the phone to the computer.
- **Send files by dropping them on the mirror** (or the upload button, or pasting): they are saved in the phone's Downloads, encrypted on the way and checked.
- **Screenshot and recording buttons**: a full-resolution PNG screenshot and a screen recording with sound, saved on the computer.
- **Notifications visible while mirroring**: a bell button (and `phone_control` `mirror_notifications`) turns off Android 15's screen-share hiding through Full ADB, until you turn it back on.
- **Phone status on the mirror page**: battery, charging, Wi-Fi or mobile, and signal bars.
- **Keyboard shortcuts** in the mirror (Alt+/ lists them): Back, Home, Recents, notifications, quick settings, screen off, full screen, rotate, sound, volume, screenshot, record, send files, pop-out; Ctrl/Cmd+A, word delete and word moves, Shift+Enter for a new line. A **pop-out window** keeps the phone on top (Chrome, Edge).
- **Choose how your AI reaches the phone** when you set up Latch: **Hosted relay** (your free Vercel relay, works anywhere) or **Computer on Wi-Fi** (the gateway on your computer, nothing leaves home). Switch any time in **Connect**; Latch keeps both, so switching back asks for no key (R31, ADR-052).
- **Both connected at once**: during a session the phone connects through your hosted relay and your computer on Wi-Fi together, so your AI can use either (Connect → **Keep both connected**, on by default). Live mirror uses the Wi-Fi one (ADR-053).
- **Scan a QR code to connect over Wi-Fi**: `latch-start` shows a QR code; in Latch, **Computer on Wi-Fi → Scan QR code** connects the phone and makes it the owner, with no key to copy. Under the QR code: the address and an 8-letter setup code for typing instead. Codes work once, for 15 minutes; `latch-gateway pair` shows a new one (R32, ADR-054).
- **Phone screen off, everything keeps running**: a button on the mirror page (and `phone_control` `screen_light` for AI apps) turns only the screen's light off through Full ADB; the mirror, sound, and AI keep working. The power button lights it again (ADR-056).
- **AI apps set the mirror's volume**: `phone_control` `mirror_volume` (0-100 %) sets the computer's volume for the phone's sound; phone volumes were already there.
- **Volume slider** next to the speaker button in the mirror; it sets the computer's volume and is remembered.
- **Sound plays on the computer only**: while the mirror page plays the phone's sound, the phone's own speaker goes quiet; its volume comes back when you mute the page, close it, or stop mirroring.
- **Phone sound in the live mirror**: videos, music, and games play on the computer, with a speaker button to mute. The phone asks once for audio recording. Calls and apps that block capture stay silent (Android allows no more) (ADR-055).
- **Mirror: full screen and rotation that work**: full screen fills the screen with the phone buttons floating below, clicks and drags land correctly on a letterboxed picture, a **Rotate view** button turns the picture for landscape videos, and a phone turned sideways now mirrors at landscape size instead of a tiny picture in a tall frame. Typing quickly from the computer no longer puts letters at the start of the field (WhatsApp).
- **Live mirror fits the window and feels faster**: the phone always fits without zooming, phone-style Back/Home/Recents icons, and lower delay (no Nagle buffering on the gateway's sockets, a shorter decode queue).
- **A tidier gateway terminal**: one start-up summary (phone QR code, AI app URL, mirror, console, data folder), then short local-time log lines; the TLS noise from the phone's address search is gone.
- **The Wi-Fi gateway keeps its address**: when your computer gets a new address on the Wi-Fi, the phone finds it again by its pin, without pairing again. `latch-autostart on` keeps the gateway running in the background, also after a restart.

### Fixed

- **Dependency audit:** `rustls-webpki` 0.103.16 (RUSTSEC-2026-0382, certificate verification could accept distrusted intermediates; the gateway's own Wi-Fi certificate is pinned, but outbound TLS uses it too).

- **Mirror stutter while sound plays**: the phone's sound shared the gateway's three-frame video queue, so a few sound frames filled it and video frames were dropped and forced key frames. Sound now has its own queue.
- **WhatsApp put "Message" in front of what you typed from the mirror**, and Backspace deleted it letter by letter: a field that reports its hint as text is now read as empty.
- **Wireless-ADB pairing failed on every phone** with `pairing failed: org.conscrypt.Conscrypt`: the app looked for a TLS class that exists only off Android. It now uses Android's own exporter (Android 12+ public API; Android 11 through its TLS socket), with an on-device test in the emulator job.
- **macOS refused the gateway** ("cannot be opened… Move to Trash") when downloaded in a browser: install it with `curl -fsSL https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.sh | sh`, which avoids the quarantine flag, checks the checksum, creates your keys, and adds `latch-start`. The macOS files are now signed ad hoc and verified in CI. Guide: [docs/connect-your-phone.md](docs/connect-your-phone.md).

### Safety and security

- Each AI app can be limited to the phone apps you pick, from **Connect** in the app (R18).
- Messages from the phone are signed by a key kept in its keystore; the owner key can be rotated from the app, and changing `LATCH_ADMIN_TOKEN` always recovers access (R19, protocol 1.12).
- The Vercel gateway's database holds screen content only encrypted (R20).
- AI apps sign in with OAuth 2.1 on both gateways and are approved on the phone (R17).
- Pull requests fail on dependencies with known vulnerabilities (RustSec and npm audits in CI).

### New for owners

- **Mirror on computer:** see the phone's screen live in your computer's browser and use it with mouse and keyboard; watch the AI's steps and take over with one click (R30, ADR-051). Needs the self-hosted gateway; on the gateway's own computer it stays on your Wi-Fi.
- **Every gesture:** the new `gesture` tool does any touch gesture with up to 10 fingers (rotate, two- and three-finger swipes, edge swipes, curved paths, press-then-drag), and still asks you before a finger presses Send or Pay (R28, protocol 1.18).
- **Full ADB, more like a cable:** binary output with `base64` (a screenshot comes back as an image), and ADB commands run beside the other tools instead of making them wait (R28).
- **Longer sessions:** 4- and 8-hour sessions, and a one-tap **+** next to Stop to extend while it runs.
- **Full ADB access over Wi-Fi** (off by default): switch it on in **Access → Full ADB access**, pair Android's wireless debugging once with no cable, and your AI app can run any `adb shell` command through the new `adb` tool, with complete stdout, stderr, and exit codes (R27, ADR-046, protocol 1.17). Stop ends a running command at once; reconnecting after a restart needs no cable or new pairing.
- **Activity → Export a PDF report**: every step the AI took, what it asked you and how you answered, and what was refused, for the latest task, today, the last 24 hours, or the whole week; saved to Downloads and ready to share, with a fingerprint that changes if any line does (R25).
- More of what ADB test agents do, without ADB: lock screen, screenshot, split screen, and arrow keys; open a link straight in an app (payment links always ask, message links ask); start an app fresh; read battery, network, and storage with `device_status`; set the ringer, Do Not Disturb, rotation, and screen timeout; nine more settings pages (R25, protocol 1.15).
- `diagnose` checks each phone and names the fix, including Android's battery optimisation (R13, protocol 1.13).
- Phone controls: volume, media, brightness, flashlight, and the Wi-Fi, internet, Bluetooth, and volume panels, each off until switched on (R16, protocol 1.11).
- Icon-only buttons are read on the phone, so an unlabeled Send still asks you (R9).
- Activity groups each AI task into a timeline, and `get_activity` replays it (R14).
- Starter skills: web, YouTube, Maps, and Settings search, and a WhatsApp message (R8).
- The app warns when your relay's database is far from its functions (R3).
- **Connect → Other phones on this relay** lists every paired phone with Remove.

### Speed

- Actions return only what changed on screen (R5).
- `run_steps` does several predictable steps in one call (R4).
- Screenshots are WebP and taken only when asked (R6, protocol 1.10).
- The phone learns how long each app takes to settle (R7).
- Every action reports whether it changed the screen, opened another app, or did nothing (R11, protocol 1.9).
- Repeated elements, such as map results, are listed once (R12).
- **No cloud needed at home:** run the gateway on your computer with `LATCH_WIFI=1` (also from your AI app over stdio). The phone connects over your Wi-Fi with TLS, trusting only that gateway's own certificate by the pin in its address (R24).
- **Fast transfers on the same Wi-Fi:** with the switch on (Access → Files), whole files go straight between your computer and the phone, still encrypted, and fall back to your gateway anywhere else (R23, protocol 1.14).

### Fixes

- **Full ADB could not reach any phone:** the app announced protocol 1.16 while implementing 1.17, so both gateways refused every `adb` call as "update the app". A test now keeps the app, the Vercel gateway, and the protocol on the same version.
- **Safety:** a swipe that barely moves is a tap to Android but was not checked like one, so it could press Send or Pay without asking. Swipes and gestures now ask exactly like taps.
- Disconnecting no longer leaves duplicate entries of the same phone on the relay.
- Full ADB: the wireless-debugging connection now upgrades to TLS the way Android expects; Stop and cancel end a running command instead of waiting for it; ADB commands are no longer cut off at 20 seconds by the self-hosted gateway; multibyte and control-character output is never corrupted at chunk edges; big `adb` calls (up to the protocol's 128 Ki-character command and 512 KiB stdin) are no longer refused by the MCP endpoint; a stuck **Pair** button after rotating the phone.

### For developers

- CI runs the real app on an Android emulator against a real gateway (R15).
- A one-file Python client drives several phones in parallel (R15).
- A task evaluation suite runs in CI without model API keys (R10).
- A release pipeline with signed APKs, checksums, a software bill of materials, and build provenance, checked by a dry run (R21).
