---
title: "📜 16 — ADR Index & Decision Log"
source_page: "https://app.notion.com/p/3ec9b3674a8a81038942e0bd12808914?pvs=204"
page_id: "3ec9b367-4a8a-8103-8942-e0bd12808914"
last_fetched: "2026-10-01T21:15:26.904Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81038942e0bd12808914?pvs=204 -->
## How to use this page
Each architecture decision is a short record with context, decision, alternatives, consequences, evidence, and a reversal trigger. The record is immutable in meaning: supersede it with a new ADR rather than silently editing history.
## ADR-001 — Product boundary
**Status:** accepted for planning  
**Decision:** Latch is a user-controlled phone runtime plus protocol and adapters, not an AI model or mandatory hosted relay.  
**Why:** keeps ownership, privacy, and ecosystem compatibility explicit.  
**Reversal trigger:** evidence that local and user-hosted modes cannot serve the target users without a sustainable managed option.
## ADR-002 — Rust core
**Status:** accepted for planning  
**Decision:** Rust owns protocol, policy, session, crypto, fixtures, and the MCP gateway.  
**Alternatives:** TypeScript-only, Kotlin-only, Go, or platform-specific implementations.  
**Why:** typed concurrency, small deployable gateway, predictable state machine, and a durable boundary.  
**Constraint:** no Rust UI rewrite and no mobile FFI before measured need.  
**Reversal trigger:** repeated release evidence that the core cannot meet contributor, integration, or platform requirements.
## ADR-003 — Native mobile applications
**Status:** accepted for planning  
**Decision:** Kotlin and Swift remain native for UI, lifecycle, permissions, accessibility, capture, and OS integrations.  
**Why:** platform behavior and review constraints are product behavior.  
**Reversal trigger:** a specific shared mobile module demonstrates lower total risk than native code without hiding platform differences.
## ADR-004 — MCP as adapter
**Status:** accepted for planning  
**Decision:** MCP is the primary external integration, but the internal device protocol is independent.  
**Why:** support any MCP-capable AI and leave room for future agents, web tools, or adapters.  
**Reversal trigger:** none for the internal boundary; an adapter can be replaced without changing the device protocol.
## ADR-005 — Local-first deployment
**Status:** accepted for planning  
**Decision:** local and user-hosted remote modes are first-class; a central relay is optional.  
**Why:** lower operating burden and stronger user ownership.  
**Risk:** setup may be harder for non-technical users.  
**Mitigation:** guided pairing, setup wizard, exact provider instructions, and safe defaults.
## ADR-006 — Capability and policy model
**Status:** accepted for planning  
**Decision:** every operation is typed, scoped, risk classified, permission checked, and audited.  
**Why:** a generic remote-control tool cannot communicate or enforce meaningful safety.  
**Reversal trigger:** none; the model can gain capabilities but cannot bypass the boundary.
## ADR-007 — Android first end-to-end
**Status:** accepted for planning  
**Decision:** prove the full safe loop on Android before claiming cross-platform computer use.  
**Why:** Android provides the clearest current path for authorized semantic observation and gestures.  
**Risk:** iOS users wait longer.  
**Mitigation:** build protocol and product shell so iOS can add its honest subset later.
## ADR-008 — No arbitrary shell
**Status:** accepted for planning  
**Decision:** the default product never exposes arbitrary shell, credential extraction, MFA bypass, or hidden capture.  
**Why:** these capabilities create unacceptable abuse and trust risk.  
**Reversal trigger:** none for the default distribution; a separate research project would require a separate threat model.
## ADR-009 — Versioned schemas and fixtures
**Status:** accepted for planning  
**Decision:** JSON Schema and golden fixtures are checked in and tested across Rust, Android, iOS, and adapters.  
**Why:** protocol drift is more expensive than a small amount of generated or duplicated glue.  
**Reversal trigger:** a demonstrably better compatibility system with equal inspectability.
## ADR-010 — Stable release evidence
**Status:** accepted for planning  
**Decision:** stable releases require signed artifacts, checksums, SBOM, provenance, support matrix, and rollback evidence.  
**Why:** open source is not a substitute for supply-chain discipline.  
**Reversal trigger:** new ecosystem standards may add requirements, not remove the verification goal.
## Decision hygiene
When a future proposal arrives, ask:
- does it solve a real user or maintainer problem;
- which boundary does it change;
- what new capability or risk does it create;
- how can it be tested and revoked;
- how does it affect Android and iOS truthfully;
- how does it affect independent MCP clients;
- what is the smallest reversible experiment;
- what evidence would make us reverse it?
This index is complete only when each future cross-cutting decision has a place here.
