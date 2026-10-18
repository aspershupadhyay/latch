# Latch mirror channel v1

The live mirror (roadmap R30, [ADR-051](../adr/README.md#adr-051--live-mirror-the-phones-screen-in-the-owners-browser-through-the-self-hosted-gateway)) is a separate channel beside the device protocol: the phone streams its screen to the self-hosted gateway, and the owner's browser watches and sends input back. The device protocol ([v1.md](v1.md)) is unchanged. Implementations: `servers/mcp/src/mirror.rs` (gateway), `servers/mcp/src/page/mirror.js` (browser), `apps/android/.../mirror/` (phone). Shared message fixtures: `packages/schemas/v1/mirror/`.

## Endpoints (Rust gateway only)

| Route | Who | Credential |
|---|---|---|
| `GET /mirror`, `/mirror.js`, `/mirror.css` | browser | none: the page is static |
| `GET /v1/mirror/phone` (WebSocket) | phone | `Authorization: Bearer <device token>`, then a start signed with the device key |
| `GET /v1/mirror/view` (WebSocket) | browser | its first message: the 6-digit code or the viewer key; same-origin only |

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
  ◀── viewer input, as below
  ── binary frames ──▶
  ── {"t":"screen","w":1080,"h":2400,"rotation":0} ──▶
  ── {"t":"ai","session":"off|active|paused","owner":bool,"working":bool,"steps":[{"at":ms,"by":"ai|owner|latch","text":"…","app":"…"}]} ──▶
  ── {"t":"pong","at":n} ──▶                      (answers a viewer ping)
  ── {"t":"notice","text":"…"} ──▶                (one line for the page, e.g. "Type passwords on the phone")
  ── {"t":"end"} ──▶                              (mirroring ended; the viewer is closed with 4410)
```

The signed envelope is the device protocol's: `{"type":"signed","payload":"<start JSON>","sig":"<base64 DER ECDSA P-256>"}` over `latch-signed-v1\n<connection>\n<payload>`.

**Binary frames:** byte 0 kind (`1` H.264 Annex B access unit, `2` JPEG), byte 1 flags (bit 0: key frame), bytes 2–9 timestamp in µs (big-endian), then the payload; at most 8 MiB. H.264 key frames carry their SPS and PPS, so a decoder can start at any key frame. The browser derives its codec string (`avc1.PPCCLL`) from the SPS.

A phone that disconnects without `end` keeps its viewer for 15 s; reconnecting with the same key resumes (`{"t":"phone","online":false|true}` tells the viewer). Revoking the phone ends the mirror.

## Viewer socket

```text
browser                                       gateway
  ── {"t":"join","code":"123456"|"key":"…","codecs":["h264","jpeg"]} ──▶
  ◀── {"t":"joined","version":1,"device":"Pixel 8","codec":"h264","key":"…"}   (keep the key to reconnect)
  ◀── the phone's screen / ai / pong / notice messages, {"t":"phone","online":bool}, binary frames
  ── {"t":"touch","phase":"down|move|up|cancel","x":int,"y":int} ──▶
  ── {"t":"wheel","x":int,"y":int,"dx":int,"dy":int} ──▶
  ── {"t":"text","text":"…"} ──▶                 (1–256 characters, no control characters)
  ── {"t":"key","key":"backspace|delete|enter|tab|left|right|up|down"} ──▶
  ── {"t":"button","name":"back|home|recents"} ──▶
  ── {"t":"control","action":"pause|resume|stop|end"} ──▶   (stop ends the AI session; end ends mirroring)
  ── {"t":"ping","at":number} / {"t":"sync"} / {"t":"codec","codec":"jpeg"} ──▶
```

Coordinates are phone screen pixels (0–16384). The gateway drops anything else, rebuilds what passes from its fields, and limits a viewer to 1000 messages a second. A code joins only when nobody watches; the key takes over from another window (that one is closed with 4409).

**Close codes:** `4401` refused (wrong or expired code, unknown key, bad start), `4409` watching elsewhere now, `4410` mirroring ended.

## Flow control

Video is never queued. The gateway holds at most 6 frames per viewer; when that is full it drops frames until the next key frame and asks the phone for one (at most every 250 ms). The phone drops frames the same way when its socket's send queue passes 512 KiB, asks its encoder for a key frame, and lowers the bitrate while drops repeat.
