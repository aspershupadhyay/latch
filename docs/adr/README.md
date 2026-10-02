# Architecture decision records

ADR-001 to ADR-010 live in [handbook chapter 16](../../handbook/16-adr-index-decision-log.md). The records below were made when implementation started (2026-10-02) and are explained in [chapter 17](../../handbook/17-plan-review-and-revised-delivery.md). Supersede a record with a new one; do not rewrite history.

## ADR-011 — Deliver in vertical slices
**Status:** accepted · **Amends:** chapter 13 phase order
**Decision:** build one thin end-to-end loop (protocol → gateway → device → MCP client) first, then harden each layer, instead of finishing phases 1–13 in sequence.
**Why:** protocol and UX mistakes surface only end to end; the sequential plan delayed the first loop to phase 6.
**Reversal trigger:** slices repeatedly ship with unresolved safety gates.

## ADR-012 — The phone dials out; the gateway is cloud-hostable
**Status:** accepted · **Amends:** ADR-005, phase 8
**Decision:** phones open an authenticated WebSocket to the gateway (`/v1/device`). The same gateway binary runs in any cloud container, on a home server, or on a LAN. There is no Latch-operated relay.
**Why:** phones cannot accept inbound connections behind NAT/CGNAT; a laptop daemon is not acceptable.
**Consequence:** a cloud-hosted gateway sees screen content in transit. Self-hosting is the privacy answer.
**Reversal trigger:** a transport that gives end-to-end encryption between phone and MCP client without a trusted gateway.

## ADR-013 — Screenshots through AccessibilityService; minSdk 30
**Status:** accepted · **Amends:** chapter 05 MediaProjection path
**Decision:** use `AccessibilityService.takeScreenshot()` (Android 11+). Defer MediaProjection to a future video-stream capability.
**Why:** no per-session capture consent, no `mediaProjection` foreground service, honours `FLAG_SECURE`.
**Cost:** Android 10 and older are unsupported.
**Reversal trigger:** platform rate limits make single screenshots unusable for agents.

## ADR-014 — Bearer tokens over TLS before custom envelope crypto
**Status:** accepted · **Amends:** crate plan in chapter 04
**Decision:** 256-bit random tokens (stored as SHA-256 hashes, compared in constant time), single-use 10-minute pairing codes with rate limiting, TLS from the hosting platform. No `crypto` crate yet.
**Why:** the threat model is met by standard transport security; custom envelope signing adds risk before it adds value.
**Next step:** device-held keys (Android Keystore) signing results, and OAuth 2.1 for MCP clients.

## ADR-015 — Hand-written MCP layer instead of `rmcp`
**Status:** accepted · **Amends:** chapter 04 stack
**Decision:** implement JSON-RPC 2.0 for `initialize`, `ping`, `tools/list`, `tools/call` over Streamable HTTP (JSON responses, no server stream) and stdio, pinned to MCP versions 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05.
**Why:** a few hundred auditable lines versus a fast-moving 3.x SDK; the surface Latch needs is tiny.
**Guard:** CI drives the gateway with the official MCP TypeScript SDK client (`scripts/interop.sh`).
**Reversal trigger:** Latch needs resources, prompts, sampling, server-initiated streams, or OAuth flows that the SDK already implements well.

## ADR-016 — Approvals as an overlay; one command at a time
**Status:** accepted · **Amends:** chapter 07 human-in-the-loop, chapter 09 confirmation sheet
**Decision:** approval requests are drawn as an accessibility overlay card above the current app, with Approve enabled after one second. Each phone runs strictly one command at a time (gateway lock and device queue). Agents cannot observe or act in Latch itself, the notification shade, or the lock screen, and gestures may not start in the status bar or on the Stop pill.
**Why:** works without notification permission, keeps the owner in context, and makes it impossible for the agent to approve its own request.
**Reversal trigger:** an Android change that lets other apps draw over or interact with accessibility overlays.
