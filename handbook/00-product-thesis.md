---
title: "🧭 00 — Start Here: Product Thesis & Operating Rules"
source_page: "https://app.notion.com/p/3ec9b3674a8a81faadd8de688ad944dc?pvs=204"
page_id: "3ec9b367-4a8a-81fa-add8-de688ad944dc"
last_fetched: "2026-10-01T21:06:30.873Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81faadd8de688ad944dc?pvs=204 -->
<callout icon="🧭" color="blue_bg">
	**Purpose:** establish the decisions that every later chapter must obey. If a later feature conflicts with this page, the feature is wrong or this page needs an explicit decision record.
</callout>
## Product thesis
Latch is a local-first, open-source device runtime for AI agents. It makes a phone observable and operable through a capability-based contract that a person can inspect, approve, pause, and revoke.
The product is valuable only if it improves three things at the same time:
- **Usefulness:** an agent can complete real, bounded tasks on a phone.
- **Trust:** the person can see what is happening and remain in control.
- **Portability:** the runtime works with any client that can speak the published protocol, with MCP as the first-class integration.
A fast demo that quietly grants broad control is a product failure. A safe runtime that cannot complete a useful task is also a product failure.
## The problem
Today, phone automation is split between brittle scripts, vendor-specific agent demos, accessibility tools that are difficult to configure, and closed cloud services that make the user trust an opaque relay. Developers have to rebuild the same device connection, permission model, screen observation, action schema, and safety controls for every agent.
Latch creates one documented boundary:
- the phone exposes only the capabilities the owner granted;
- the runtime converts those capabilities into stable, typed device operations;
- the MCP adapter exposes those operations to compliant AI clients;
- the user owns the connection, keys, logs, and hosting choice.
## Working name
**Latch** is a working product name, not a legal or ecosystem claim. It communicates a controlled connection: the user can open it, pause it, or close it.
Before repository initialization, run a name check across:
- GitHub organization and repository names;
- package and crate registries;
- app-store names and bundle identifiers;
- domains and social handles;
- trademark databases in the intended launch markets;
- search results for confusingly similar developer tools.
If the name is unavailable, change the name before public release. Do not create permanent package names around an unverified brand.
## Product principles
1. **User-owned by default.** Local mode, self-hosting, and exportable data are first-class.
2. **Capability before convenience.** A tool can do only what its active capability grants.
3. **Consent is a state, not a screen.** Consent has scope, expiry, provenance, and revocation.
4. **Observe before acting.** The runtime prefers a fresh observation and a bounded action over blind replay.
5. **Explain every failure.** Errors tell the operator what was refused, why, and what safe next action exists.
6. **MCP is an adapter.** The internal protocol stays independent of any one AI vendor.
7. **Android and iOS are not twins.** Shared contracts, platform-native implementations, honest capability differences.
8. **Small public surface.** Fewer stable primitives beat a huge tool catalog with inconsistent behavior.
9. **No silent data exhaust.** Telemetry is opt-in, minimized, redacted, and useful.
10. **Proof over opinion.** Performance, compatibility, and reliability claims need repeatable evidence.
## Operating rules
- No implementation begins from a verbal idea alone. Write an issue with the user problem, non-goals, acceptance criteria, threat impact, test plan, and documentation plan.
- Every cross-cutting decision gets an ADR with alternatives and a reversal condition.
- Every feature has a capability identifier and a release status: proposed, experimental, beta, stable, deprecated, or removed.
- Every release has a known supported device matrix. “Works on Android” is not an acceptance criterion.
- Every background or remote feature has a kill switch and a recovery path.
- Any command that can send, publish, purchase, delete, change account state, reveal a secret, or alter security settings is blocked or requires explicit confirmation.
- No feature is considered done when code exists. Done means code, tests, docs, observability, threat review, and user-facing failure states exist.
## Evidence bar
A claim is ready for public documentation only when it has:
- a source link or a reproducible test;
- an environment description;
- a date and version;
- known limitations;
- an owner for revalidation.
Platform behavior changes. Revalidate Apple, Android, MCP, and distribution assumptions during every release train.
## Decision vocabulary
Use these labels in issues and chapters:
- **FACT:** verified in source code, platform documentation, or a repeatable test.
- **ASSUMPTION:** plausible but unverified.
- **DECISION:** selected option with a reason.
- **RISK:** a failure mode with impact and likelihood.
- **OPEN:** a question that blocks a decision.
- **GATE:** evidence required before moving to the next phase.
## First proof
The first meaningful proof is not a polished interface. It is a complete, harmless loop:
1. a user pairs one Android device locally;
2. the device publishes a small capability set;
3. a compliant MCP client discovers typed tools;
4. the client requests a screen observation;
5. the user approves a harmless tap in a test app;
6. the device performs it;
7. the event appears in a redacted audit trail;
8. the user revokes the session and the command stops.
This loop must be deterministic enough to test, boring enough to debug, and explicit enough to trust.
