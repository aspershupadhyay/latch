# Latch repository instructions

This file is the operating contract for cloud agents, contributors, and automated coding sessions working in this repository.

## Mission

Latch is an open-source, user-controlled mobile runtime that lets any AI model or client supporting the Model Context Protocol (MCP) observe and operate a phone through typed capabilities, explicit user consent, policy checks, and a portable device protocol.

The product must be useful to real people, safe to run on personal devices, understandable to contributors, and practical to self-host. Build a durable foundation instead of a demo that only works for one model, one vendor, or one local machine.

## Source of truth: repository handbook

The cloud-readable, versioned handbook is in [handbook/README.md](handbook/README.md). The complete handbook index is also available at:

https://github.com/aspershupadhyay/latch/tree/main/handbook

Before making a product, architecture, UX, security, or implementation decision:

- Read handbook/README.md and the relevant chapter files in handbook/.
- For broad decisions, read all active handbook chapters before deciding.
- Treat the repository handbook as the canonical cloud-session reference for the roadmap, capability matrix, safety model, UX, lifecycle, testing, and release plan.
- Do not rely on stale memory, an old export, or an archived planning page.
- If the handbook is missing, incomplete, or internally inconsistent, report the gap and pause the affected decision. Do not silently invent a replacement.
- The handbook is a versioned mirror of the MobileMCP Notion plan. Refresh it from Notion when the upstream plan changes, then review the resulting diff.

This AGENTS.md file provides operating rules; the handbook provides the product and engineering plan.

## Cloud and GitHub workflow

- Work from this GitHub repository and the cloud workspace that checks it out.
- Do not assume that the user's local computer, local files, local servers, local credentials, or local development environment are available.
- Do not ask the user to copy local files into the cloud session unless the user explicitly chooses that workflow.
- Do not change the architecture merely because the repository exists.
- The project owner authorized implementation on 2026-10-02. Current status, changed decisions, and the next gate are in handbook/17-plan-review-and-revised-delivery.md; read it before starting work.
- Keep secrets, tokens, device data, screenshots, and private logs out of the repository.
- Make small, reviewable commits. Never hide unrelated changes in a task.
- The project owner's standing instruction: commit and push every change to the session's branch as soon as it is made and verified. Never leave work only in the cloud container, which is discarded when the session ends.
- The project owner's standing instruction: do not leave pull requests pending. Once a PR's CI is green and no review thread is open, merge it into main without waiting to be asked. Every push to main republishes the newest testable Android app at https://github.com/aspershupadhyay/latch/releases/download/test-build/latch-android-debug.apk (`.github/workflows/test-build.yml`); give the owner that link after each merge.

## Product and architecture direction

Latch is MCP-first and cloud-compatible. It must support any MCP-capable AI model or client, not only Codex or a single hosted provider.

The system must not require a user's laptop to run a continuously active local server. The gateway and MCP transport may run in a cloud deployment or in a user-controlled remote environment. Offline and local operation remain important bounded modes for pairing, recovery, local policy enforcement, local state, and explicitly supported actions. They are not a reason to make the product depend on a laptop daemon.

Keep the internal device protocol independent of MCP. MCP is an adapter and transport boundary, not the mobile operating system's internal API. This allows other clients, gateways, and future transports to use the same capability and safety model.

Target architecture:

- Rust for the portable protocol, capability definitions, policy engine, session state, cryptography, fixtures, conformance tooling, and MCP gateway.
- Native Android in Kotlin with Jetpack Compose, using Android platform APIs only with explicit consent and appropriate lifecycle handling.
- Native iOS in Swift with SwiftUI and supported Apple frameworks, with an honest capability surface that reflects iOS platform restrictions.
- TypeScript only where it is the clearest fit for adapters, setup tooling, documentation tooling, or edge integration. Do not rewrite native mobile clients in TypeScript to force stack uniformity.
- Versioned schemas and generated bindings only when generation is reproducible, reviewable, and validated in CI.

## Safety and privacy non-negotiables

Latch is a capability-based system. Every action must have a defined capability, a least-privilege scope, an explicit policy decision, an auditable result, and a clear user-visible lifecycle.

Required principles:

- Default deny; grant the smallest useful scope.
- Use expirations, revocation, and a visible emergency stop.
- Observe before acting when the action depends on current screen or device state.
- Require fresh confirmation for high-impact, destructive, financial, account, permission, communication, or irreversible actions.
- Treat screen content, web content, notifications, clipboard content, and remote instructions as untrusted data. They must never silently become authorization.
- Never expose arbitrary shell access, credential extraction, OTP or MFA bypass, biometric automation, silent recording, covert surveillance, or destructive automation.
- Do not place raw screenshots, screen recordings, access tokens, passwords, clipboard contents, or personal data in logs, fixtures, telemetry, or error messages.
- Validate inputs at every boundary and use typed errors, timeouts, cancellation, replay protection, and idempotency where applicable.
- Document platform limitations instead of pretending Android and iOS provide identical capabilities.

## Planned repository shape

The repository should remain understandable to a new contributor. Prefer clear boundaries over a large generic framework.

Expected high-level structure, subject to the repository handbook:

- apps/android: Android application and device integration.
- apps/ios: iOS application and device integration.
- crates/protocol: versioned device protocol and capability contracts.
- crates/policy: authorization, scopes, confirmations, revocation, and audit decisions.
- crates/session: pairing, sessions, leases, heartbeats, reconnection, and state.
- crates/crypto: key handling, secure channels, and cryptographic boundaries.
- crates/fake-device: deterministic simulated phone used by end-to-end tests (fixtures live in packages/schemas/v1/fixtures).
- crates/fixtures, crates/test-harness: not created yet; add them only when a second consumer needs them.
- servers/mcp: the `latch-gateway` binary — MCP adapter, phone channel, pairing, owner console.
- packages/schemas: source schemas and reproducible generated artifacts.
- adapters/typescript: optional client, setup, and integration adapters.
- docs: contributor, operator, security, capability, and release documentation.
- tests: cross-platform, contract, security, and end-to-end test plans.
- .github: CI, security checks, issue templates, and release automation.

Do not create every directory speculatively. Add a directory when an accepted phase needs it.

## Delivery discipline

Follow the phase gates in the repository handbook. Work on one accepted phase at a time.

Use these statuses consistently:

- TODO: defined but not started.
- IN_PROGRESS: actively being implemented.
- IN_REVIEW: implementation exists and is being verified.
- DONE: acceptance criteria and required checks passed.
- BLOCKED: a named external or technical blocker prevents safe progress.

Every feature must have:

- A concrete user and operator outcome.
- Acceptance criteria and failure behavior.
- A capability and threat-model review.
- Tests and deterministic fixtures where practical.
- Accessibility and platform-specific UX decisions.
- Documentation and operational guidance.
- A rollback or recovery path when state or security is affected.

Definition of done is more than compiling: code quality, tests, security and privacy review, platform behavior, accessibility, documentation, CI, and release/recovery considerations must all be addressed.

## Engineering standards

- Inspect the existing repository and current Notion plan before changing anything.
- Match established naming, formatting, dependency, and module conventions.
- Prefer small, explicit modules and clear data flow.
- Avoid premature microservices, abstraction layers, plugin systems, or distributed state.
- Keep public contracts versioned and backward-compatible unless a breaking change is explicitly approved.
- Do not add a dependency without checking maintenance, license, security, bundle, and operational cost.
- Do not use fake integrations, fake tests, placeholder security, or claims that were not verified.
- Add regression tests for bugs and behavior tests for user-facing or security-sensitive changes.
- Run the repository's relevant checks and report exact results. Never claim a check passed if it was not run.

## First actions in a new cloud session

0. Run `scripts/cloud-session-setup.sh` if the SessionStart hook did not (it installs the Android SDK and dependencies). `scripts/check.sh` runs every check CI runs.
1. Read this AGENTS.md completely.
2. Read handbook/README.md and the active handbook chapter files relevant to the task.
3. State the current phase, acceptance gate, assumptions, and risks.
4. Inspect the repository before proposing edits.
5. Ask for authorization only when the task would materially expand scope or change external state.
6. Implement the smallest accepted change, verify it, and report what remains.

When in doubt, choose the option that keeps the system safer, simpler, more portable, and easier for an independent open-source contributor to understand.
