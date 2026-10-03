---
title: "🔧 17 — Plan Review & Revised Delivery (2026-10)"
source: "repository (not yet mirrored to Notion)"
written: "2026-10-02"
---

<!-- Repo-authored chapter. Mirror it into the MobileMCP Notion handbook, then refresh this file from Notion. -->

> **Status:** implementation authorized by the project owner on 2026-10-02. This chapter records where the original plan would have caused problems, what changed, and what is built and proven so far. Chapters 00–16 stay authoritative for principles; where this chapter changes a decision, it says so and the matching ADR in `docs/adr/` records it.

## 1. Verdict on the original plan

The principles are right and were kept in full: capability-based design, default deny, owner approval for consequential actions, honest platform differences, MCP as an adapter over an independent device protocol, Rust core, native apps. The *delivery shape* had problems that would have stalled or endangered the product:

| # | Problem | Why it would hurt | Change |
|---|---|---|---|
| 1 | 13 sequential phases; the first agent-to-phone loop arrives at phase 6, remote use at phase 8. | Months before any real feedback; protocol mistakes are discovered last, when they are most expensive. | Vertical slices: one thin end-to-end loop first, then harden. (ADR-011) |
| 2 | "Local-first" (ADR-005) conflicts with the requirement that nothing depends on a laptop daemon, and the plan assumed the gateway can reach the phone. | Phones sit behind NAT and carrier-grade NAT and cannot accept inbound connections. A laptop-hosted gateway goes away when the laptop sleeps. | The phone always dials **out** to the gateway over WebSocket. One gateway binary runs in any cloud container or on a LAN; remote mode is the default path, not phase 8. (ADR-012) |
| 3 | MediaProjection for screenshots. | Android 14+ requires fresh consent per session, a `mediaProjection` foreground service, and a status-bar chip — heavy onboarding for a single screenshot. | `AccessibilityService.takeScreenshot()` (API 30+) under the same consent the owner already gives; it also refuses secure windows. MediaProjection deferred to video streaming. minSdk 30. (ADR-013) |
| 4 | Six Rust crates (protocol, policy, session, crypto, fixtures, test-harness) before one loop runs. | Boundaries designed in advance are usually wrong; contributors face empty abstractions. | Four crates that earn their place: `protocol`, `policy`, `fake-device`, gateway. Session and crypto live in the gateway until a second consumer exists. |
| 5 | Custom signed operation envelopes and a crypto crate in the first release. | Home-made crypto is a liability; TLS already authenticates and encrypts the channel. | TLS from the hosting platform + 256-bit random bearer tokens stored as SHA-256 hashes + single-use 10-minute pairing codes + constant-time comparison. Device-key signatures are a later hardening step using standard primitives. (ADR-014) |
| 6 | Use the `rmcp` SDK. | It is on major version 3 and moves quickly; Latch needs only `initialize`, `ping`, `tools/list`, `tools/call`. | A small, spec-pinned JSON-RPC layer (~300 lines) for Streamable HTTP and stdio, verified against the **official MCP TypeScript SDK client** in CI. Reversal trigger recorded. (ADR-015) |
| 7 | Approvals described as a notification or in-app sheet. | Notifications can be disabled; switching to the Latch app interrupts the very app the agent is working in; an agent that can open the notification shade could tap "Approve" itself. | Approval is an accessibility **overlay card** drawn over the current app. The phone runs one command at a time, so while a request waits, the agent cannot issue another command to tap it. Approve stays disabled for 1 s. The notification shade, lock screen, and Latch itself are off limits to agents. (ADR-016) |
| 8 | Freshness ("observe before acting") left to policy prose. | Agents tap stale coordinates after the screen changes; this is the most common way computer-use goes wrong. | Every action must cite the latest `observation_id`; the gateway invalidates it on every action and returns a fresh observation with each action result; the phone re-checks app, element visibility, and position (±8 px) before acting. |
| 9 | Prompt injection mitigations described generally. | Screen text reaches the model verbatim. | Screen text is rendered JSON-quoted under an explicit "untrusted data" banner; it can only *raise* scrutiny (consequential-word detection, secret-field detection), never lower it. |
| 10 | Google Play as an implicit distribution channel. | **ASSUMPTION to verify:** Play's AccessibilityService policy restricts apps that use the API to act autonomously; Android 13+ "restricted settings" block enabling accessibility for sideloaded apps until the owner allows it; Android developer verification applies to sideloading from 2026. | v1 ships as a signed APK on GitHub Releases (F-Droid later). Onboarding explains "Allow restricted settings". Play is a separate, later decision. |
| 11 | iOS in the same release train. | iOS has no public API for cross-app control, and this cloud environment has no macOS to build or test it. The handbook's ScreenCaptureKit link is a macOS framework; iOS capture is ReplayKit broadcast extensions (**OPEN:** recheck). | iOS remains a later companion with an honest, smaller capability set. |
| 12 | Gate evidence assumed a device lab. | Cloud containers have no KVM, so no emulator; no real phone is attached. | A deterministic **fake device** speaks the real protocol, so the full loop (pairing, observe, act, approval, pause, stop, revoke) is tested in CI. Real-device evidence is a separate, named gate (see §4). |

## 2. Architecture as built

```text
AI client (Claude, ChatGPT, Cursor, any MCP client)
        │  MCP Streamable HTTP, Authorization: Bearer LATCH_MCP_TOKEN      POST /mcp
        ▼
latch-gateway (Rust, one process, any cloud container or LAN host)
  ├─ policy (pure): capability grants · session pause/expiry · freshness ·
  │                 sensitive fields · approval for consequential controls
  ├─ owner console  GET /  (Bearer LATCH_ADMIN_TOKEN): pair · revoke · activity
  └─ device channel GET /v1/device (WebSocket, per-phone token; phone dials out)
        ▲
        │  Latch device protocol v1 (JSON, packages/schemas/v1)
Android app (Kotlin, Compose, AccessibilityService)
  ├─ redaction, freshness re-check, own-app/system-UI refusal
  ├─ approval overlay card · persistent Stop pill · session notification
  └─ capability switches (all off except device info) · session timer
```

State: the gateway persists only `devices.json` (ids, names, token hashes). Screen content exists only in memory: the in-flight result and one "latest observation" per device (without screenshot) for freshness checks. The audit trail records command names, decisions, and outcome codes — never content.

## 3. MCP tool surface (stable names)

`list_devices`, `observe`, `tap`, `type_text`, `scroll_to`, `wait_for`, `scroll`, `swipe`, `pinch`, `press`, `list_apps`, `launch_app` (the 1.3 additions are described in §9). No shell, file, notification, or credential tools exist. Every action returns the next observation, so the agent loop is *observe → act → read result → act*.

## 4. Revised delivery: slices and gates

| Slice | Content | Status | Evidence |
|---|---|---|---|
| S0 Foundation | Repo layout, license (Apache-2.0), CI, scripts, security policy | DONE | `.github/workflows/ci.yml`, `scripts/check.sh` |
| S1 Protocol | Types, limits, JSON Schema, shared valid/invalid fixtures (Rust + Kotlin) | DONE | `cargo test -p latch-protocol`; Android `ProtocolFixturesTest` |
| S2 Gateway | Policy, MCP (HTTP + stdio), device channel, pairing, revocation, console, audit | DONE | 12 end-to-end tests (WebSocket and long-poll) with fake device; official TS SDK client; container smoke test |
| S2b Own-gateway hosting | Long-poll transport (protocol 1.1), per-app MCP keys + secret links, Vercel gateway with shared contracts, one-click deploy | IN_REVIEW | Vercel gateway e2e with official SDK client on in-memory and Redis/Upstash-REST stores; Rust and TS pass the same 34 policy cases and byte-identical rendering. Not yet deployed to a real Vercel account. |
| S3 Android app | Owner setup (create or join a gateway), pairing, observe, act, approvals, stop, AI-key management, bento UI | IN_REVIEW | Builds, lint clean; app transport and setup code tested on the JVM against both gateways; screens snapshot-tested. **Not yet run on a phone.** |
| S4 Real-device gate | Clean install → create gateway → pair → enable one capability → observe → harmless tap → approval → stop → revoke, on ≥2 phones | IN_PROGRESS | First real run 2026-10-02: realme C55 (Android 15) completed the agent loop with all nine tools through the Vercel gateway; Redmi K50i blocked at install and at "Restricted setting" (§8). Full checklist on ≥2 phones still open: `docs/platform/android.md`. |
| S5 Hardening | OAuth 2.1 for MCP, per-app allowlist, per-client rate limits, device-key signatures, audit export, owner-key rotation flow | IN_PROGRESS | OAuth 2.1 for MCP on the Vercel gateway: discovery (RFC 9728/8414), dynamic registration and client ID metadata documents, PKCE S256, rotating refresh tokens, owner approval in the app or with the owner key; tested end to end with the official MCP SDK client. Container gateway: OAuth still TODO. |
| S6 Alpha release | Signed APK, GHCR image, SBOM, attestations, install docs, known limitations | TODO (pipeline ready) | `.github/workflows/release.yml` |
| S7 iOS companion | Honest subset: broadcast-based screen viewing (ReplayKit), links/Shortcuts, app-owned actions — no cross-app control (no public API) | TODO | Needs a macOS runner and an Apple developer account. |

Every slice still runs the handbook's per-phase cycle (chapter 13): decide, threat-model, fixtures, smallest vertical slice, normal/refused/stale/cancelled/revoked tests, review, gate evidence.

## 5. UI plan

**Phone app** (chapter 09 information architecture, Signal Field visual language):

- *Pair* (when unpaired): one-sentence promise, gateway address + 8-character code, privacy summary, "nothing is paired if you back out".
- *Home*: dot-field state visual with text equivalent; headline in plain language ("Session active · 23 min left"); large **Stop all activity**; Pause/Resume; session length chips (15/30/60/120 min); accessibility-service setup card with the restricted-settings hint; "Right now the agent can…" summary; connection card (gateway host, encrypted or not).
- *Capabilities*: one card per capability with "can see / can do", risk badge, switch; "Ask me before every action".
- *Activity*: redacted, in-memory timeline (observe, action, approval, refusal, connection).
- *Settings*: gateway, forget gateway, privacy and network destinations, accessibility shortcut, version.
- *Always on top during a session*: red "● Latch · Stop" pill (tap = emergency stop) and, when needed, the approval card (title is the action, e.g. "Tap “Send” in org.example.chat"; Deny / Approve).

**Gateway owner console** (served at `/`): phones with live state and enabled capabilities, Revoke; pair-a-phone with code and countdown; "Connect an AI client" with ready-to-paste Claude Code and JSON snippets; redacted activity table. Works at phone width and in dark mode.

UX gate (chapter 09) still applies and needs a small study with real users once S4 passes.

## 6. Risks that remain

- **RISK — keyword heuristics are fallible.** Consequential-control and secret-field detection use word lists (several languages). Mitigations: platform signals first (`isPassword`, input type, `isAccessibilityDataSensitive`), "approve every action" mode, word lists only ever add scrutiny. Coordinate taps on unlabeled controls are a known gap.
- **RISK — the cloud gateway sees screen content in transit.** Self-hosting is the answer; there is no Latch-run relay. Documented in `docs/operations/gateway.md`.
- **RISK — Android Settings is reachable.** An agent with gestures can operate system Settings (not Latch, not the shade). Permission dialogs carry "Allow" and require approval; a per-app allowlist (S5) closes the rest.
- **RISK — single MCP bearer token.** Fine for one owner; teams need OAuth (S5).
- **OPEN — Play policy and developer-verification requirements** must be checked against current Google documentation before any store submission.

## 7. Decisions recorded

ADR-011 vertical slices · ADR-012 phone dials out / cloud-hostable gateway · ADR-013 accessibility screenshots, minSdk 30 · ADR-014 tokens over custom crypto · ADR-015 hand-written MCP layer · ADR-016 overlay approvals and one-command-at-a-time · ADR-017 own gateway per owner, long-poll transport · ADR-018 Vercel gateway with shared contracts · ADR-019 per-app MCP keys and secret links · ADR-020 bento layout · ADR-021 owner-chosen apps instead of per-action approvals — see `docs/adr/`.

## 8. Real-device feedback (2026-10-02)

The owner's first audit (realme C55, Android 15, Vercel gateway, one session, not a statistical benchmark) found every tool working but slow: ~11–12 s per action, 5.8–7.4 s per observe, 10.3 s for `list_apps` (147 apps). Install was blocked by Play Protect on one phone and by "Restricted setting" on another.

| Finding | Cause | Change | Status |
|---|---|---|---|
| ~11 s per action | ~20 sequential Upstash REST calls per phone command (~200 ms each when function and database are in different regions), and two phone commands per action (act, then observe) | Batched storage (one pipeline per step, compare-and-delete lock release); protocol 1.2 `observe_after` returns the screen in the action's own answer; faster polling while an agent is active (`hot=1`); region guidance | IN_REVIEW: needs a re-run on the phone for new numbers |
| No way to see where time goes | — | `_meta["latch/timing"]` on tool results: lock wait, phone round trip, total | IN_REVIEW |
| `list_apps` returns everything | — | Optional `query` filter (both gateways) | IN_REVIEW |
| Scrolling with the keyboard open changed a search query ("Bluetooth" → "Bluetooth by") | The whole-screen scroll swipe started on the on-screen keyboard: glide typing | The phone refuses swipes that start or end on the keyboard, with a hint (press back, or swipe above it) | IN_REVIEW |
| `observe`/`press` refused while Latch was in front, with no way out | Own-app rule (correct) without an exit | `press home` is allowed from Latch's own screen; errors and server instructions say to use `launch_app` or home | IN_REVIEW |
| "App blocked to protect your device" | Play Protect enhanced fraud protection blocks accessibility apps installed from a browser, messaging app, or file manager (India since 2024) | Documented legitimate paths (ADB install, store distribution, developer verification, review). **No workaround in the app, by design.** | DONE (docs) · store and verification: TODO |
| "Restricted setting" on Redmi K50i | Android 13+ restricts accessibility for apps installed from files until allowed in App info | Brand-specific steps in setup, ADB fallback, install guide | IN_REVIEW |
| WhatsApp send and a SIM 2 call completed without a clearly surfaced approval | Most likely: a tap judged only by the tapped element's own label (an unlabeled button around a labeled "Send" icon, a phone number, a SIM choice). Not reproduced on the phone, so the exact path is still an assumption | Taps are judged by the element, what is drawn inside it, and the nearest labeled ancestor; phone numbers, "SIM", and phone/in-call apps ask; the phone re-checks every tap with the same rules on its live screen (§9) | IN_REVIEW: needs a re-run of the same WhatsApp and call steps on the phone |

**Second run (2026-10-03, realme C55, Android 15, Vercel gateway at protocol 1.3, driven by Claude through MCP):** all 12 tools worked; `scroll_to`, `type_text submit` in a search box, `wait_for`, `pinch` in/out, double-tap, and a Chrome search completed with no unwanted approval and nothing sent or changed. Findings and changes:

| Finding | Change | Status |
|---|---|---|
| The screen returned after a navigating tap, `back`, or `launch_app` was often the old page or one caught mid-slide (bounds shifted by 7 px) | Settle waits until a fingerprint of the window (text, positions, checked states) has changed from before the action and holds still; transitions animate without accessibility events | IN_REVIEW: re-run on the phone |
| `wait_for` matched the text just typed into the search box | `ui.wait` and `ui.scroll_to` ignore editable elements (phone and fake phone; e2e regression test) | IN_REVIEW |
| Chrome pages report many zero-size, off-screen elements | Observations leave them out and reparent their children | IN_REVIEW |
| Chrome's new-tab search box is a placeholder that does not take Enter | Clearer error: observe and use the field that now has focus | DONE |
| With Latch open, agents stopped and asked the owner to say "go ahead": every screen command was refused while Latch was in front | When Latch's own app screen is in front, a screen command (after its capability check) first goes to the home screen, logs it, and then runs; nothing of Latch is ever read or tapped. Shade and lock screen are still refused | IN_REVIEW: re-run on the phone |
| Approving every send or delete made real tasks unusable (owner) | Access → Apps: every app with a switch. Switched-on apps run without questions except payments, installs, permission prompts, and account deletion; other apps ask once ("This session" / "Always"); money and password apps get a warning and a caution line in both gateways' observations; server instructions ask the AI to report what it did. Activity names each action. Approving inside the AI app was not built (ADR-021) | IN_REVIEW: re-run on the phone |
| No confirmation after an in-app update | A `MY_PACKAGE_REPLACED` receiver posts "Latch updated to …" | IN_REVIEW |
| Every test build needed an uninstall and a new pairing | Builds of `main` are now signed release builds published as the `beta` pre-release (`0.1.0-beta.N`; 3 MB instead of 32 MB, not debuggable, https only) with the release key (`LATCH_KEYSTORE_BASE64` + `LATCH_KEYSTORE_PASSWORD`, checked in CI), increasing version codes, and an in-app updater: `latch-update.json` on the release, https-only from this repository, size + SHA-256 + package/version/signer checks, Android `PackageInstaller` (owner taps Update; Android may confirm). Docs: `docs/platform/android.md` § Updates | IN_REVIEW: needs the secrets set, then one install of the release package and an update on the phone; the minified build must pass the real-device checklist |

Not done, deliberately: caching app lists or observations in the gateway (a cached answer would skip the policy and capability check of the moment), and compound actions (`tap → type → submit` under one policy check), which need their own threat review.

## 9. Feature roadmap (owner request, 2026-10-02)

The owner asked for the full feature list, ordered by priority, to be built one wave at a time. Every feature gets its own capability switch (off by default), protocol and fixture changes, tests on both gateways, and README steps. Items that change §3 ("no file or credential tools") or a chapter 08 rule need an ADR in the same change. All items are TODO unless marked otherwise.

**P0 — blocker before new features** · IN_REVIEW (built 2026-10-02, not yet run on a phone)

- Trace and fix the WhatsApp send / SIM 2 call that completed without a surfaced approval (§8). New capabilities must not build on a broken approval path.
- Approval tiers chosen by the owner: *normal* actions run without asking; *consequential* ones (send, post, call, delete, Enter in a chat) ask and may be saved for the session or "always in this app"; *critical* ones (money, installs, Android permission prompts, account deletion) are asked every time (`confirm.remember`, protocol 1.3). Saved answers live only on the phone. Approving inside the AI app (MCP elicitation) is not built: whoever holds the AI key would see that prompt too, so it would only ever cover consequential actions, behind an owner switch. TODO, needs its own threat review.

**Wave 1 — speed and visibility** · IN_REVIEW (built 2026-10-02, not yet run on a phone): smart settle (`observe_after.quiet_ms`), `wait_for`, `scroll_to`, `type_text submit`, cursor overlay, keep-awake, double tap, drag (`hold_ms`), pinch. Tap-by-text was not built: every action already returns fresh element ids, so it would save no round trip. The Rust gateway now also observes inside the action's own phone command.

| Feature | Outcome |
|---|---|
| Cursor overlay | A non-touchable dot moves to each tap point and pulses; swipes leave a short trail; a highlight box shows the target element. Hidden from screen readers. |
| Smart settle | Wait until the screen stops changing instead of a fixed 500 ms. |
| `tap` by text, `scroll_to`, `wait_for` | The phone finds, scrolls to, or waits for an element itself: fewer round trips. |
| Keyboard action | Press the keyboard's own Enter / Search / Send / Done. |
| Wake and keep-awake | Turn the screen on and keep it on during a session so a task is not cut off by the lock. |
| More gestures | Double tap, long-press-drag, drag and drop, pinch, fling, pull to refresh. |

**Wave 2 — files and posting** (ADR required: adds file tools)

| Feature | Outcome |
|---|---|
| `ask_owner` hand-off | The agent asks the owner to do something (log in, unlock, choose); a screen-reader-friendly card waits until done. |
| Gallery read | List photos and videos (name, date, size, thumbnail) within the media the owner allowed. |
| Folder grant with CRUD | One owner-picked folder (Storage Access Framework): list, read, create, edit, rename, delete. Delete and overwrite always need approval. |
| Send / read file | Move a file between the agent and the granted folder, with size and type limits; contents never logged. |
| `share_to_app` | Open an app's share sheet with chosen files (for example, post three photos to Instagram in ~4 calls). Posting still needs approval. |

**Wave 3 — polish**

Action batches (each step checked by policy), screen diffs, WebSocket on the Vercel gateway, per-app allowlist (S5), phone controls (volume, media, brightness, Wi-Fi / Bluetooth panels, flashlight), clipboard write, saved workflows stored as data.

**Wave 4 — phone-held unlock** (ADR required: changes the chapter 02 Tier 6 and chapter 07 rules)

The owner types the PIN once into Latch on the phone; it is stored encrypted on the phone and never sent to the gateway or the agent. The agent gets `unlock_phone()` with no arguments, and the phone enters the PIN itself. Requirements before it ships, not after: off by default with a clear warning; works only during an owner-started session; second-factor gate (authenticator or owner approval); audit entry; Stop pill still works. App passwords stay out of agent hands: autofill, passkeys, or `ask_owner`. Accessibility setup guide alongside it: Voice Access, Switch Access, Extend Unlock / Smart Lock, keep-awake.

**Later / optional:** notification reading (Play Protect restricts it), clipboard read, owner-started screen recording, scheduled tasks.

**Not planned:** the agent typing a PIN, password, or OTP itself; reading SMS or OTP codes; "all files" access; a shell; silent recording.
