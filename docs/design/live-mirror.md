# Live mirror: see and control the phone in real time (R30, proposed)

Owner request (2026-10-17): watch the phone's live screen and control it from the computer with as little delay as possible, built only from platform APIs, with no third-party libraries. This is a design for a future session to implement. Nothing here is built yet. Every number is a target to measure on real phones, not a measured result.

## What the owner gets

- A **Mirror** page served by their own gateway (and a local page on the same Wi-Fi), showing the phone's screen live at up to 60 fps.
- Mouse, touch, scroll wheel, and keyboard on that page act on the phone: tap, drag, scroll, typing, Back, Home, Recents.
- A visible "Mirroring" indicator and Stop on the phone at all times.
- Mirroring is for the **owner**, a person, authenticated with the owner key. It is not an MCP tool for the AI and streams nothing to the AI.
- **Watch the AI work live:** while an AI session runs, the mirror shows what the AI does as it does it, so the owner can follow along from the laptop.
- **Interrupt or take over at any time:** Pause, Stop, or simply start using the phone from the mirror; the AI waits until the owner gives control back.

## Works on any computer

The viewer is a web page, so it needs no app install and runs on Windows, macOS, Linux, and ChromeOS, and also on a tablet or another phone.

| Browser | Live video (WebCodecs) | Notes |
|---|---|---|
| Chrome, Edge, Brave, Opera (all desktop systems) | Yes | The reference browsers; hardware decoding on all major GPUs |
| Safari 16.4+ (macOS, iPadOS) | Yes | Test H.264 level limits on older Macs |
| Firefox | Desktop versions with WebCodecs | Check the version during setup; older versions get the fallback |
| Anything else | Fallback | A screenshot refresh (a few frames per second) with the same controls, so nothing is ever blank |

The page detects support with `typeof VideoDecoder` and `VideoDecoder.isConfigSupported()` and says plainly which mode it is in. A packaged desktop app is not needed. If one is ever wanted, the same page can be wrapped without changing the protocol.

## Watching the AI and taking over

This builds on what the phone already has: the AI cursor overlay, the Stop pill, Pause, `bridge.touches` (which tells the owner's touches from the AI's), and the Activity log.

- **AI layer on the mirror:** next to the video, the page shows the AI's current step as the phone already describes it ("Tapping “Search”", "Typing 12 characters"), the AI cursor drawn by the phone (it is part of the screen, so it shows in the video), and a short live list of the latest steps. The step text arrives as small JSON messages beside the video and is never logged with screen content.
- **Interrupt:** **Pause** and **Stop** buttons on the page do exactly what the phone's own buttons do. Pause holds the AI before its next command; Stop ends the session at once (with full ADB, it also cancels running ADB commands, as Stop does today).
- **Take over:** the first touch, click, or key from the owner on the mirror (or on the phone itself) **pauses the AI automatically** and shows "You're in control" on both the page and the phone. Commands the AI sends meanwhile wait (the AI hears that the owner is in control, as it does for Pause today). The owner's actions are logged as the owner's.
- **Give back:** a **Let AI continue** button resumes the AI. Because the screen may have changed, the AI's next command must cite a fresh observation (stale observations are already refused), so the AI looks again before acting.
- **Approvals on the laptop:** approval cards (Send, Pay, Install) can also be answered from the mirror page when the owner allows it, using the same nonce-checked path as answering in the AI app.

## Latency budget (target: under ~80 ms on the same Wi-Fi)

No mirroring is truly zero-latency: capture, encoding, the network, decoding, and the display each add a little. The goal is that it *feels* immediate, like iPhone Mirroring or scrcpy on a good Wi-Fi network: under about 80 ms from a change on the phone to the laptop screen, and under about 100 ms for a click to show its effect. Each stage below gets measured on real phones, and the page shows the live round-trip time so a slow network is visible rather than mysterious.

| Stage | Target | How |
|---|---|---|
| Capture | ≤ 16 ms | Frames go straight into the encoder's input `Surface`; no CPU copies |
| Encode | 4–10 ms | Hardware H.264 through `MediaCodec`, low-latency settings |
| Network, same Wi-Fi | 2–10 ms | Direct phone → browser connection, no relay |
| Decode | 3–8 ms | Browser WebCodecs `VideoDecoder`, hardware decode, `optimizeForLatency` |
| Display | ≤ 16 ms | Draw each decoded frame to a `<canvas>` at once; no `<video>` buffering |
| Input back | 10–40 ms | Pointer events over the same connection, performed through the accessibility service |

Over the internet the stream goes through the self-hosted Rust gateway's WebSocket, which adds one round trip. Vercel functions cannot hold a long two-way stream (see R2), so remote mirroring needs the self-hosted gateway or the same-Wi-Fi path (R24).

## Platform pieces (all first-party APIs, no libraries)

### Capture and encode

- `MediaProjection`, which Android shows a consent dialog for each time it starts. Android 14+ requires fresh consent per projection, and Android 15 lets the owner share a single app. A foreground service of type `mediaProjection` holds it.
- A `VirtualDisplay` renders into `MediaCodec.createInputSurface()`, so the pixels stay on the GPU.
- Windows marked `FLAG_SECURE` (banking apps, password screens) appear black. This is Android's protection, and Latch respects it.
- Encoder settings to start from (check each key on the device's API level):
  - H.264 Baseline profile (no B-frames), CBR at 4–8 Mbit/s.
  - 60 fps, a long key-frame interval, and `PARAMETER_KEY_REQUEST_SYNC_FRAME` when a viewer joins or drops frames.
  - `KEY_PRIORITY` real time; `KEY_LATENCY` where supported; `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, so a still screen keeps sending frames; `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` (API 29+).
- Long edge at most 1920 (1280 for weak Wi-Fi), rounded to multiples of 16. Re-create the codec on rotation. Read output with the asynchronous `MediaCodec.Callback` and send each frame as soon as it is ready.

### Transport

- One binary WebSocket message per encoded frame: `{flags, timestamp µs, bytes}`. No base64.
- **Same Wi-Fi (fast path):** a short-lived TLS listener on the phone, exactly like the R23 file lane: a random port, a pinned self-made certificate, a one-time token, closed when mirroring stops.
- **Elsewhere:** phone → Rust gateway (`/v1/mirror`, owner-authenticated) → browser. The gateway relays bytes only; it never decodes or stores frames.
- Backpressure: when the send buffer is full, drop frames until the next key frame and request one. Never queue video. Lower the bitrate when drops repeat.

### Viewer (browser, no libraries)

- WebCodecs `VideoDecoder` (`avc1` at the level the phone reports, `optimizeForLatency: true`). Draw each `VideoFrame` to a canvas immediately, then close it.
- Browsers without WebCodecs get a still-screenshot fallback.
- Input: Pointer Events, wheel, and keys, scaled to phone pixels and sent as small binary messages. The phone performs them through the accessibility service's `dispatchGesture`, using `continueStroke` for live drags (Android 8+), and its global actions for Back/Home/Recents.

## Safety

- A new capability, `screen.mirror`: owner-only, off by default, shown on the phone while active, ended by Stop or session end.
- The viewer is authenticated as the owner (owner key or a one-time link the owner opens from the phone). AI clients cannot open it.
- Frames are never logged or stored. Only "Mirroring started / stopped" goes into Activity.
- Input from the mirror page is the owner's own input, logged as the owner's.

## Reliability ("no bugs" means tested, not hoped)

- The stream heals itself: a lost frame triggers a key-frame request; a dropped connection reconnects and resumes in under a second; rotation, app switches, and the screen turning off are handled explicitly.
- When a protected (`FLAG_SECURE`) screen appears, the page shows "This screen is protected by the app" instead of a frozen picture.
- Tests: a decoder test page fed with recorded H.264 frames in CI (headless Chrome); a chaos test that drops and delays mirror packets; a 1-hour soak on the emulator; and a recorded latency measurement on 2 real phones before R30 is marked DONE.

## Milestones

1. Phone: MediaProjection + MediaCodec into a local file. Measure encode time on 2 phones.
2. Same-Wi-Fi TLS stream plus a WebCodecs viewer page. Measure glass-to-glass latency with a phone stopwatch filmed next to the browser.
3. Input from the page through accessibility, including live drags; the AI step overlay; Pause, Stop, automatic take-over and Let AI continue.
4. Remote relay through the Rust gateway; adaptive bitrate.
5. Docs, an ADR, and a real-device run recorded in the handbook (R30).

## Open questions to verify on real phones

- Hardware encoder latency on low-end devices, and which low-latency keys each vendor honours.
- How smooth `dispatchGesture`/`continueStroke` feels for live drags, and how much it adds compared with what the screen shows.
- WebCodecs support in the owner's browsers.
