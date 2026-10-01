---
title: "🗺️ 13 — Delivery Lifecycle, Milestones & Gates"
source_page: "https://app.notion.com/p/3ec9b3674a8a814f8a16e4576f8c109e?pvs=204"
page_id: "3ec9b367-4a8a-814f-8a16-e4576f8c109e"
last_fetched: "2026-10-01T21:15:26.904Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a814f8a16e4576f8c109e?pvs=204 -->
## Working method
Build one accepted phase at a time. Work may be parallelized inside a phase only when the dependency and acceptance evidence are clear. Do not begin a later platform feature to distract from an unresolved protocol, safety, or release gate.
Every phase produces an artifact that can be reviewed independently.
## Phase 0 — Product and repository foundation
**Build:** name validation, license decision, repository skeleton, issue forms, CI shell, documentation style, security policy, code of conduct, contribution guide.
**Gate:** a clean checkout explains how future contributors will build, test, report, and make decisions. No product feature code is required.
## Phase 1 — Normative protocol
**Build:** capability vocabulary, request/result/error types, versioning, JSON Schema, fixtures, negotiation, redaction model.
**Gate:** Rust tests and at least one native client can serialize, validate, reject, and explain the shared fixtures.
## Phase 2 — Rust core and fake device
**Build:** policy engine, session machine, command lifecycle, cancellation, idempotency, fake device, in-memory transport, trace output.
**Gate:** a fake-device end-to-end loop passes with denial, expiry, revoke, duplicate, and timeout cases.
## Phase 3 — Rust MCP gateway
**Build:** local stdio server, MCP initialization, discovery, typed tools, structured results, health, authentication hooks.
**Gate:** at least two independent MCP clients discover the same safe tool set and receive identical contract behavior.
## Phase 4 — Android shell and pairing
**Build:** native app shell, Signal Field UI, device identity, pairing, capability screen, local session, stop/revoke.
**Gate:** clean Android install pairs locally and stops a session without depending on an agent.
## Phase 5 — Android observation
**Build:** AccessibilityService observation path, redaction, MediaProjection capture path, foreground service lifecycle, bounded frames, audit events.
**Gate:** user-granted observation works on the declared device matrix and sensitive fields are excluded by tests.
## Phase 6 — Android action executor
**Build:** semantic tap, bounded coordinate tap, swipe, safe text input, navigation, app allowlist, stale-target checks.
**Gate:** harmless fixture tasks pass; changed app, stale target, revocation, and process death fail safely.
## Phase 7 — Human approval and safety
**Build:** confirmation protocol, policy editor, high-risk classifications, approval expiry, global stop, incident diagnostics.
**Gate:** every listed consequential scenario pauses or refuses correctly; stop and revoke are measured on devices.
## Phase 8 — User-hosted remote mode
**Build:** remote Streamable HTTP MCP, device channel, key rotation, deployment docs, local and self-hosted options, rate limits.
**Gate:** remote mode works without Latch-owned relay, and a user can shut down the endpoint and prove the device session is closed.
## Phase 9 — Alpha release
**Build:** Android alpha packaging, gateway binaries or container, checksums, SBOM, attestations, issue templates, troubleshooting, known limitations.
**Gate:** an external tester can install, verify, pair, complete the safe test, connect an MCP client, stop, and uninstall.
## Phase 10 — iOS companion
**Build:** SwiftUI shell, pairing, capability discovery, App Intents, approved capture path, honest unsupported states, Keychain identity.
**Gate:** iOS completes its declared approved task set and clearly explains background and cross-app limits.
## Phase 11 — Compatibility and evaluation
**Build:** broader device lab, independent MCP client matrix, benchmark tasks, accessibility review, performance report, crash and refusal tracking.
**Gate:** public claims are backed by dated evidence and known failure modes are documented.
## Phase 12 — Public beta
**Build:** release channels, migration policy, security disclosure workflow, community triage, versioned protocol stability, upgrade and rollback.
**Gate:** no unresolved critical issue, stable safe capability catalog, responsive incident ownership, reproducible release.
## Phase 13 — Stable 1.0 and maintenance
**Build:** support promise, deprecation policy, release cadence, roadmap review, maintainer succession, compatibility dashboard.
**Gate:** the project can maintain the promises it makes. If not, reduce the promise before declaring 1.0.
## Cycle inside every phase
1. discover the user or failure;
2. write the decision and non-goals;
3. threat-model the boundary;
4. define fixtures and acceptance;
5. implement the smallest vertical slice;
6. test normal, refused, stale, cancelled, and revoked paths;
7. review security, accessibility, and docs;
8. run the phase gate;
9. record evidence and known risks;
10. close or explicitly carry forward work.
## Gate evidence format
Every gate review contains:
- phase objective;
- changed files or artifacts;
- commands and environments;
- screenshots or traces where relevant;
- test results;
- known failures;
- threat review;
- decision log updates;
- rollback or removal path;
- explicit pass, fail, or blocked outcome.
No “looks good” gate. The evidence is the gate.
