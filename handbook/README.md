---
title: "✨ Latch — Product & Engineering Handbook"
source_page: "https://app.notion.com/p/3ec9b3674a8a81cb9ff4ddf4fce018f3?pvs=204"
page_id: "3ec9b367-4a8a-81cb-9ff4-ddf4fce018f3"
last_fetched: "2026-10-01T22:10:12.779Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81cb9ff4ddf4fce018f3?pvs=204 -->
<callout icon="✦" color="blue_bg">
	**Latch** is an open, user-controlled runtime that lets any MCP-capable AI observe and operate a user's phone through explicit capabilities, visible consent, and a portable protocol.
</callout>
## Status
**Planning state:** pre-implementation  
**Working product name:** Latch  
**Working tagline:** Open device control for MCP-capable AI  
**Source of truth:** this handbook and the linked chapter pages below  
**Operating rule:** no feature enters implementation without an acceptance criterion, a threat decision, a test path, and a rollback story.
The product name is provisional until repository, package, domain, trademark, and ecosystem-name checks are complete. The product may change its public name without changing the architecture.
## North star
A person should be able to:
1. install the official phone application from a trusted release;
2. understand exactly what the AI can see and do;
3. grant only the capabilities they choose;
4. connect any compatible MCP client, regardless of vendor;
5. watch, pause, approve, deny, and revoke the session at any time;
6. run the system locally or bring their own server and network route;
7. leave without losing control of their device or data.
Latch is not an AI model, an always-on surveillance service, or a proprietary cloud relay. It is a transparent device runtime, safety boundary, and protocol implementation.
## Product shape
```plain text
MCP client or another agent
        |
        v
Latch MCP adapter / gateway
        |
        v
Latch device protocol
        |
   +----+----+
   |         |
Android    iOS
agent      agent
```
The internal device protocol is the stable product boundary. MCP is the primary integration adapter, not the only possible consumer. This lets the project support future agent runtimes, local desktop tools, and provider-specific adapters without rewriting the mobile apps.
## Non-negotiable quality bar
- **User control:** every consequential action is visible, attributable, cancellable, and policy checked.
- **Least privilege:** capabilities are separate, revocable, and scoped to a device, app, session, and time window.
- **Portable integration:** MCP server and protocol remain usable by any compliant client, not only one AI product.
- **Honest platform support:** Android and iOS expose different capabilities; the product documents the difference instead of pretending parity.
- **Local-first economics:** local operation and self-hosting are first-class. No mandatory Latch subscription is required for core functionality.
- **Human-readable engineering:** APIs, errors, logs, tests, and documentation read like a careful book.
- **Release discipline:** no public release without reproducible builds where feasible, signed artifacts, checksums, SBOM, provenance, and rollback instructions.
## Delivery map
**Discover → Specify → Threat-model → Prototype → Implement → Verify → Pilot → Release → Operate**
Each phase has a gate. A later phase cannot hide an unresolved safety or correctness problem from an earlier phase.
## Planned chapter library
The linked chapter pages are created after the reset and become the working library:
- Product thesis, users, and use cases
- Scope and capability matrix
- System architecture and runtime boundaries
- Rust core and MCP runtime
- Android application plan
- iOS application plan
- Computer-use feature catalog
- Safety, privacy, and security
- UI/UX design system
- Repository and engineering workflow
- Testing, evaluations, and device lab
- GitHub distribution and release
- Delivery lifecycle, milestones, and gates
- Open-source community and governance
- Operations, maintenance, and roadmap
- ADR index and decision log
## Repository starting shape
```javascript
latch/
  apps/
    android/
    ios/
  crates/
    protocol/
    policy/
    session/
    crypto/
    fixtures/
    test-harness/
  servers/
    mcp/
  packages/
    schemas/
    generated/
  adapters/
    typescript/
  docs/
    handbook/
    protocol/
    platform/
    adr/
    runbooks/
  tests/
    contract/
    integration/
    evals/
    fixtures/
  tools/
  .github/
```
This is a target shape, not permission to create the repository yet.
## Current research anchors
The plan is grounded in current primary documentation and will be rechecked at implementation time:
- [MCP specification changelog](https://modelcontextprotocol.io/specification/draft/changelog)
- [MCP SDK support matrix](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/docs/2026-07-28/sdk.mdx)
- [Official Rust MCP SDK](https://github.com/modelcontextprotocol/rust-sdk)
- [Official TypeScript MCP SDK](https://github.com/modelcontextprotocol/typescript-sdk)
- [Android AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/views/service)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Apple App Intents](https://developer.apple.com/documentation/appintents)
- [Apple ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit)
- [GitHub immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)
- [GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)
## Reset statement
This handbook is a clean product plan created from scratch. It intentionally replaces the previous planning tree. The old planning pages are not part of the new source of truth.
## How to read this book
Start with the product thesis, then read scope, architecture, platform plans, safety, and UX before reading the delivery schedule. The schedule is intentionally downstream of the capability and risk decisions.
- [00 — Start Here: Product Thesis & Operating Rules](./00-product-thesis.md)
- [01 — Product, Users & Use Cases](./01-product-users-use-cases.md)
- [02 — Scope, Non-Goals & Capability Matrix](./02-scope-capability-matrix.md)
- [03 — System Architecture & Runtime Boundaries](./03-system-architecture-runtime-boundaries.md)
- [04 — Rust Core & MCP Runtime](./04-rust-core-mcp-runtime.md)
- [05 — Android Application Plan](./05-android-application-plan.md)
- [06 — iOS Application Plan](./06-ios-application-plan.md)
- [07 — Computer-Use Feature Catalog](./07-computer-use-feature-catalog.md)
- [08 — Safety, Privacy & Security](./08-safety-privacy-security.md)
- [09 — UI/UX Design System](./09-ui-ux-design-system.md)
- [10 — Repository, Code Style & Engineering Workflow](./10-repository-engineering-workflow.md)
- [11 — Testing, Evaluations & Device Lab](./11-testing-evaluations-device-lab.md)
- [12 — GitHub Distribution, Release & Supply Chain](./12-github-distribution-release-supply-chain.md)
- [13 — Delivery Lifecycle, Milestones & Gates](./13-delivery-lifecycle-milestones-gates.md)
- [14 — Open-Source Community & Governance](./14-open-source-community-governance.md)
- [15 — Operations, Maintenance & Roadmap](./15-operations-maintenance-roadmap.md)
- [16 — ADR Index & Decision Log](./16-adr-index-decision-log.md)
