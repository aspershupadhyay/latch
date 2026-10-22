# Latch mirror channel v1

The live mirror (roadmap R30, [ADR-051](../adr/README.md#adr-051--live-mirror-the-phones-screen-in-the-owners-browser-through-the-self-hosted-gateway)) is a separate channel beside the device protocol: the phone streams its screen to the self-hosted gateway, and the owner's browser watches and sends input back. The device protocol ([v1.md](v1.md)) is unchanged. Implementations: `servers/mcp/src/mirror.rs` (gateway), `servers/mcp/src/page/mirror.js` (browser), `apps/android/.../mirror/` (phone). Shared message fixtures: `packages/schemas/v1/mirror/`.

## Endpoints (Rust gateway only)

| Route | Who | Credential |
|---|---|---|
| `GET /mirror`, `/mirror.js`, `/mirror.css` | browser | none: the page is static |
| `GET /v1/mirror/phone` (WebSocket) | phone | `Authorization: Bearer <device token>`, then a start signed with the device key |
| `GET /v1/mirror/view` (WebSocket) | browser | its first message: the 6-digit code or the viewer key; same-origin only |
| `POST /v1/mirror/files?name=<file name>` | browser | `x-latch-mirror-key: <viewer key>` of a mirror someone watches; same-origin only (ADR-057) |
| `GET /v1/mirror/qr.svg?k=<viewer key>` | browser | none: draws the page's own key as a QR code (ADR-069) |
| `POST /v1/mirror/outbox?name=<file name>` | phone | `Authorization: Bearer <device token>`; a page must be watching this phone's mirror (ADR-069) |
| `GET /v1/mirror/outbox/<token>` | browser | the 256-bit token itself, sent only to the watching page; single use (ADR-069) |

`POST /v1/mirror/files` takes the raw file as its body (up to `LATCH_MAX_TRANSFER_MB`). The gateway stores it encrypted exactly as an upload link (protocol 1.7), sends the phone `{"t":"file","url":"<its address>/v1/blobs/<token>","key":"<64 hex>","iv":"<32 hex>","sha256":"<64 hex>","name":"…","size":n}` over the mirror link, and answers `{"ok":true,"text":"sent; …"}` or `{"error":"…"}`. The phone fetches, decrypts, and checks it as `file.fetch` does (no AI session needed) and tells the page with a `notice`. Names with `/`, `\`, control characters, or over 200 characters are refused.

**Scan to mirror (ADR-069).** A page without a key makes one (32 random bytes, base64url, 43 characters), keeps it in `localStorage`, and shows `GET /v1/mirror/qr.svg?k=<key>`: a QR code of `<origin>/mirror#k=<key>&pin=<Wi-Fi pin>` (no `&pin` without a Wi-Fi listener), where `<origin>` is the address the browser used. Latch accepts it only when the pin equals its Wi-Fi gateway's pin, or, without a pin, when the origin is its gateway's address; it then keeps that key as its mirror key (replacing the one kept by ADR-058) and starts or moves its mirror to it. The page meanwhile joins with the key every 1.5 s (5 s after a minute, for 30 minutes), getting `4404` until the phone mirrors with it. The 6-digit code still works on the viewer socket for older apps; the page no longer asks for it.

**Files to the computer (ADR-069).** The phone streams the raw file to `POST /v1/mirror/outbox` (up to `LATCH_MAX_TRANSFER_MB`); the gateway refuses with `409` before storing anything when no page watches, stores it encrypted as any link (protocol 1.7, key in memory only), and sends the watching page `{"t":"file_ready","url":"/v1/mirror/outbox/<token>","name":"…","size":n,"sha256":"<64 hex>"}`. The page saves it with a download link; the gateway decrypts on the way out with `Content-Disposition: attachment`, deletes the stored copy at the end, and answers `404` to a second request. Names follow the rule above.

The Vercel gateway has none of these (it cannot hold a stream); the phone gets 404 and says the self-hosted gateway is needed.

## Phone socket

```text
phone                                         gateway
  ◀── {"t":"challenge","version":1,"connection":"m_<32 hex>"}
  ── signed {"t":"start","version":1,"key":"<43 base64url>"} ──▶      (plain JSON only if the phone has no device key)
  ◀── {"t":"ready","code":"123456"|null,"code_expires_ms":n,"urls":["http://127.0.0.1:8787/mirror", ...]}
  ◀── {"t":"viewer","on":true,"codec":"h264"|"jpeg"} / {"t":"viewer","on":false}
  ◀── {"t":"code","code":null}                   (the code was used or burned)
  ◀── {"t":"sync"}                               (send a key frame now)
  ◀── {"t":"udp","port":8788}                    (R56: this gateway takes video and sound over UDP; sent once after ready)
  ── {"t":"udp","id":"<16 hex>","key":"<64 hex>"} ──▶   (R56: only after the line above; a new random id and AES-256 key per connection; never stored or relayed)
  ◀── viewer input, as below
  ── binary frames ──▶
  ── {"t":"screen","w":1080,"h":2400,"rotation":0} ──▶
  ── {"t":"ai","session":"off|active|paused","owner":bool,"working":bool,"steps":[{"at":ms,"by":"ai|owner|latch","text":"…","app":"…"}]} ──▶
  ── {"t":"pong","at":n,"phone_us":n} ──▶        (answers a viewer ping; phone_us, since R35, is the phone's frame clock now, so the page lines frame timestamps up with its own)
  ── {"t":"stats","codec":…,"frames":n,"key_frames":n,"drops":n,"kbps":n,"target_kbps":n,"w":n,"h":n,"encode_ms":n,"encode_max_ms":n} ──▶   (R35: the last second, once a second while the page asked; the gateway adds "gateway_drops")
  ── {"t":"notice","text":"…"} ──▶                (one line for the page, e.g. "Type passwords on the phone")
  ── {"t":"quality","saving":bool,"w":n,"h":n,"fps":n} ──▶   (R38: a smaller picture while the network is slow, or full quality again; kept for a viewer who joins later)
  ── {"t":"status","battery":0-100,"charging":bool,"network":"wifi|cellular|ethernet|none","signal":0-4} ──▶   (any field may be missing; ADR-057)
  ── {"t":"input","mode":"keys"|"basic"} ──▶       (keys: the Full ADB input helper runs; ADR-057)
  ── {"t":"clipboard","text":"…"} ──▶             (only in answer to the owner's copy or cut; ADR-057)
  ── {"t":"end"} ──▶                              (mirroring ended; the viewer is closed with 4410)
```

The signed envelope is the device protocol's: `{"type":"signed","payload":"<start JSON>","sig":"<base64 DER ECDSA P-256>"}` over `latch-signed-v1\n<connection>\n<payload>`.

**Binary frames:** byte 0 kind (`1` H.264 Annex B access unit, `2` JPEG, `3` sound: 48 kHz stereo 16-bit little-endian PCM, 20 ms a frame (ADR-055), `4` a full-resolution PNG screenshot the owner asked for (ADR-057), `5` one Opus packet of the same sound, 20 ms, sent instead of `3` once the page asked for it (R39, ADR-059), `6` an H.265 Annex B access unit whose key frames carry VPS, SPS, and PPS (R40)), byte 1 flags (bit 0: key frame), bytes 2–9 timestamp in µs (big-endian), then the payload; at most 8 MiB. H.264 key frames carry their SPS and PPS, so a decoder can start at any key frame. The browser derives its codec string (`avc1.PPCCLL`) from the SPS.

A phone that disconnects without `end` keeps its viewer for 15 s; reconnecting with the same key resumes (`{"t":"phone","online":false|true}` tells the viewer). Revoking the phone ends the mirror.

## Viewer socket

```text
browser                                       gateway
  ── {"t":"join","code":"123456"|"key":"…","codecs":["h265","h264","jpeg"]} ──▶   (h265 only from a browser whose worker decodes it, R40; the gateway picks h265, then h264, then jpeg)
  ◀── {"t":"joined","version":1,"device":"Pixel 8","codec":"h264","key":"…"}   (keep the key to reconnect)
  ◀── the phone's screen / ai / pong / notice / status / input / clipboard / stats / quality messages, {"t":"phone","online":bool}, binary frames
  ◀── {"t":"volume","percent":0-100}             (an AI app set the computer's volume, ADR-056)
  ◀── {"t":"file_ready","url":"/v1/mirror/outbox/<token>","name":"…","size":n,"sha256":"…"}   (a file the owner sent from the phone, ADR-069)
  ── {"t":"touch","phase":"down|move|up|cancel","x":int,"y":int} ──▶
  ── {"t":"wheel","x":int,"y":int,"dx":int,"dy":int} ──▶
  ── {"t":"text","text":"…"} ──▶                 (1–256 characters, no control characters)
  ── {"t":"key","key":"backspace|delete|enter|tab|left|right|up|down|home|end|select_all|word_backspace|word_left|word_right|newline"} ──▶
  ── {"t":"button","name":"back|home|recents|notifications|quick_settings"} ──▶
  ── {"t":"paste","text":"…"} ──▶                (1–4096 characters; line breaks and tabs kept. Onto the phone's clipboard and into the field in focus)
  ── {"t":"copy"} / {"t":"cut"} ──▶              (the phone answers with clipboard, or a notice when nothing is selected)
  ── {"t":"shot"} ──▶                             (the phone answers with a kind 4 frame, or a notice)
  ── {"t":"sound","on":bool} ──▶                  (the page plays the phone's sound; the phone's speaker goes quiet, ADR-055)
  ── {"t":"screen_light","on":bool} ──▶           (gateway only: Full ADB screen light, ADR-056)
  ── {"t":"notifications","show":bool} ──▶        (gateway only: Android 15's screen-share protection through Full ADB, ADR-057)
  ── {"t":"control","action":"pause|resume|stop|end"} ──▶   (stop ends the AI session; end ends mirroring)
  ── {"t":"ping","at":number} / {"t":"sync"} / {"t":"codec","codec":"jpeg"} ──▶
  ── {"t":"stats","on":bool} ──▶                  (R35: the page's stats overlay, Alt+D)
  ── {"t":"clipboard_follow","on":bool} ──▶       (R44: the owner's switch, off by default: with Full ADB, text copied on the phone comes as a clipboard message by itself; sensitive clips never)
  ── {"t":"pinch","phase":"start|move|end","x":n,"y":n,"scale":0.05–20,"angle":±720} ──▶   (R42: two fingers around x,y; with Full ADB live through the input helper, without it one gesture at the end)
  ── {"t":"audio_codec","codec":"opus|pcm"} ──▶   (R39: Opus when the browser's WebCodecs decodes it; the phone falls back to PCM when its encoder fails)
```

Coordinates are phone screen pixels (0–16384). The gateway drops anything else, rebuilds what passes from its fields, and limits a viewer to 1000 messages a second. `screen_light` and `notifications` never reach the phone's mirror link: the gateway runs them as Full ADB commands and answers with a `notice`. A viewer message is at most 16 KiB; a phone message at most 64 KiB (clipboard text). A code joins only when nobody watches; the key takes over from another window (that one is closed with 4409).

**Close codes:** `4401` refused (wrong or expired code, malformed key, bad start), `4404` no mirror for this key right now (the phone keeps its key across mirrors, so the page waits and joins again when the owner starts mirroring, ADR-058), `4409` watching elsewhere now, `4410` mirroring ended.

## Media over UDP (R56, ADR-061)

On a gateway with a Wi-Fi listener, video and sound frames (kinds 1, 3, 5, 6) can go over UDP to the Wi-Fi port, so a lost packet no longer holds later frames (TCP waits 200 ms or more to resend a lost packet at the end of a burst). Every packet:

```text
version 1 (1) ‖ session id (8) ‖ counter (8, big-endian) ‖ AES-256-GCM ciphertext ‖ tag (16)
associated data: the first 17 bytes · nonce: direction (1: phone→gateway, 2: gateway→phone) ‖ 00 00 00 ‖ counter
```

Counters start at 0 for each direction and never repeat under one key; each side drops a counter it has seen or one 64 or more behind its newest. Plaintexts, by their first byte:

| Type | From | Body |
|---|---|---|
| `1` hello | phone | none; every 250 ms until acknowledged, then every second |
| `2` hello ack | gateway | none; the phone then sends frames over UDP |
| `3` fragment | phone | frame number (u32), index (u16), count (u16), at most 1200 bytes of the frame as the WebSocket would carry it |
| `4` request again | gateway | frame number (u32), n (u16), n indices (u16); n = 0 asks for the whole frame |
| `5` report | gateway | packets received (u32), lost (u32), frames skipped (u32) in the last 250 ms |

The gateway hands complete frames on in order. A missing piece is asked for again once a later frame shows it missing (every 8 ms, up to 4 times); a frame still missing 50 ms after a later one is complete is skipped, the viewer waits for a key frame, and the phone is asked for one (`sync`). The phone keeps its last 240 frames (6 MiB) to send again, backs the bitrate off when a report shows 2 % loss or a skipped frame, and goes back to the WebSocket when the gateway is silent for 3 s. The gateway answers only the address of the latest packet that opened. Test vectors: `packages/schemas/v1/mirror/udp.json` (made with an independent AES-GCM; both implementations must match it byte for byte).

## Flow control

Video is never queued. The gateway holds at most 3 frames per viewer (50 ms at 60 frames a second); when that is full it drops frames until the next key frame and asks the phone for one (at most every 250 ms). The phone drops a delta frame when its socket's send queue passes 96 KiB (a key frame at 512 KiB), asks its encoder for a key frame (or, where the encoder supports intra refresh, lets the next frames heal the picture in half a second without a key frame, R37), and lowers the bitrate while drops repeat; at the lowest bitrate it steps down to a 1280 and then a 960 long edge at 30 frames a second, and back up after 15 clean seconds (R38). A decoder error on the page asks for a key frame; only three in ten seconds make it fall back to stills. A still screen repeats its last frame twice a second (R46). Its socket has no Nagle delay and a 128 KiB send buffer, so a slow network shows up as drops at once rather than as seconds of video queued in the kernel. Sound has its own queue of 8 frames (160 ms), so it never fills the video queue or costs a key frame, and is dropped rather than played late; screenshots wait with the text messages and are never dropped.

## Input helper (ADR-057)

With Full ADB on, the phone starts `io.github.aspershupadhyay.latch.session.ShellInput` through its own ADB shell (`CLASSPATH=<APK> app_process / … <port> <token>`). It listens on `127.0.0.1:<port>` for one connection that sends `auth <64 hex token>` first, and then takes one command per line: `d|m|u|c x y` (touch down, move, up, cancel), `s x y dx dy` (wheel, phone pixels), `k code meta` (a key press), `t <base64 UTF-8>` (printable ASCII as key presses), `cg` (read the clipboard; answered `cg <base64>` or `cg -`), `p n` (answered `p n` once everything before it was injected). It exits when the connection closes or when nobody proves the token within 15 s. Without it (`{"t":"input","mode":"basic"}`) the phone uses the accessibility service as before: gestures in pieces, typing by setting the field's text.
