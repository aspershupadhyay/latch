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

`list_devices`, `observe`, `tap`, `type_text`, `scroll`, `swipe`, `press`, `list_apps`, `launch_app`. No shell, file, notification, or credential tools exist. Every action returns the next observation, so the agent loop is *observe → act → read result → act*.

## 4. Revised delivery: slices and gates

| Slice | Content | Status | Evidence |
|---|---|---|---|
| S0 Foundation | Repo layout, license (Apache-2.0), CI, scripts, security policy | DONE | `.github/workflows/ci.yml`, `scripts/check.sh` |
| S1 Protocol | Types, limits, JSON Schema, shared valid/invalid fixtures (Rust + Kotlin) | DONE | `cargo test -p latch-protocol`; Android `ProtocolFixturesTest` |
| S2 Gateway | Policy, MCP (HTTP + stdio), device channel, pairing, revocation, console, audit | DONE | 8 end-to-end tests with fake device; official TS SDK client; container smoke test |
| S3 Android app | Pairing, observe, act, approvals, stop, capability UI | IN_REVIEW | Builds, unit tests, lint clean. **Not yet run on a phone.** |
| S4 Real-device gate | Clean install → pair → enable one capability → observe → harmless tap → approval → stop → revoke, measured on ≥2 phones | BLOCKED | Needs a physical Android phone (owner) or a device farm with accessibility-service support. Checklist: `docs/platform/android.md`. |
| S5 Hardening | OAuth 2.1 for MCP, per-app allowlist, rate limits per client, device-key signatures, audit export, key rotation | TODO | |
| S6 Alpha release | Signed APK, GHCR image, SBOM, attestations, install docs, known limitations | TODO (pipeline ready) | `.github/workflows/release.yml` |
| S7 iOS companion | Honest subset, macOS runner | TODO | |

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

ADR-011 vertical slices · ADR-012 phone dials out / cloud-hostable gateway · ADR-013 accessibility screenshots, minSdk 30 · ADR-014 tokens over custom crypto · ADR-015 hand-written MCP layer · ADR-016 overlay approvals and one-command-at-a-time — see `docs/adr/`.
