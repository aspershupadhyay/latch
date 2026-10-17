# Live mirror: see and control the phone in real time (R30, proposed)

Owner request (2026-10-17): watch the phone's live screen and control it from the computer with as little delay as possible, built only from platform APIs, with no third-party libraries. This is a design for a future session to implement. Nothing here is built yet. Every number is a target to measure on real phones, not a measured result.

## What the owner gets

- A **Mirror** page served by their own gateway (and a local page on the same Wi-Fi), showing the phone's screen live at up to 60 fps.
- Mouse, touch, scroll wheel, and keyboard on that page act on the phone: tap, drag, scroll, typing, Back, Home, Recents.
- A visible "Mirroring" indicator and Stop on the phone at all times.
- Mirroring is for the **owner**, a person, authenticated with the owner key. It is not an MCP tool for the AI and streams nothing to the AI.

## Latency budget (target: under ~80 ms on the same Wi-Fi)

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

## Milestones

1. Phone: MediaProjection + MediaCodec into a local file. Measure encode time on 2 phones.
2. Same-Wi-Fi TLS stream plus a WebCodecs viewer page. Measure glass-to-glass latency with a phone stopwatch filmed next to the browser.
3. Input from the page through accessibility, including live drags.
4. Remote relay through the Rust gateway; adaptive bitrate.
5. Docs, an ADR, and a real-device run recorded in the handbook (R30).

## Open questions to verify on real phones

- Hardware encoder latency on low-end devices, and which low-latency keys each vendor honours.
- How smooth `dispatchGesture`/`continueStroke` feels for live drags, and how much it adds compared with what the screen shows.
- WebCodecs support in the owner's browsers.
