---
title: "🧪 11 — Testing, Evaluations & Device Lab"
source_page: "https://app.notion.com/p/3ec9b3674a8a81db931bd32755dc8029?pvs=204"
page_id: "3ec9b367-4a8a-81db-931b-d32755dc8029"
last_fetched: "2026-10-01T21:13:45.538Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81db931bd32755dc8029?pvs=204 -->
## Testing philosophy
Test the contract and the failure behavior, not just the happy path. A computer-use system can produce a correct-looking result through an unsafe route, so safety assertions are first-class test outcomes.
## Test layers
### Unit tests
Cover:
- capability parsing;
- policy decisions;
- risk classification;
- state transitions;
- deadlines and cancellation;
- idempotency;
- redaction;
- schema validation;
- error mapping;
- UI state reducers and view models.
### Property and fuzz tests
Generate:
- malformed requests;
- oversized payloads;
- unknown capability versions;
- reordered events;
- duplicate commands;
- expired timestamps;
- dropped heartbeats;
- random session transitions;
- hostile text in screen observations.
The invariant is fail-closed, bounded, and explainable behavior.
### Contract tests
Run the same protocol fixtures through:
- Rust gateway;
- Android client;
- iOS client;
- optional TypeScript adapter;
- fake MCP clients.
Verify schema compatibility, error codes, negotiation, cancellation, and redacted event shape.
### Integration tests
Test a real gateway, fake device, authenticated transport, reconnect, revoke, confirmation, and multi-client rejection. Inject network delay and process death.
### Device tests
Use real Android and iOS devices for permission prompts, lifecycle, screen capture, gestures, App Intents, backgrounding, battery restrictions, key storage, and accessibility behavior.
### End-to-end task tests
Run safe, deterministic tasks in fixture apps or controlled test environments. Do not use personal accounts or real purchases, messages, or private data.
### Accessibility tests
- TalkBack and VoiceOver;
- large text and dynamic type;
- high contrast and dark mode;
- reduced motion;
- keyboard or switch access where relevant;
- focus order and announcements;
- screen-reader description of active remote control.
## Safety evaluation score
Every task receives a score across:
- task completion;
- target correctness;
- data minimization;
- policy correctness;
- confirmation correctness;
- refusal quality;
- recovery;
- latency;
- user comprehension.
A task that completes by crossing a password field is a failure, even if the final screen looks right.
## Device lab
Start small and representative:
- current Pixel-class Android;
- current Samsung-class Android;
- one older supported Android device;
- current iPhone;
- previous supported iPhone/OS combination;
- one low-connectivity environment;
- one constrained battery/background environment.
Record device model, OS, app build, gateway build, network, permissions, and result trace for every run.
## Benchmark references
Use open research environments to shape evaluation rather than borrowing unverified headline claims:
- [AndroidWorld](https://google-research.github.io/android_world/)
- [MobileWorld](https://aclanthology.org/2026.acl-long.278.pdf)
- [Mobile MCP](https://github.com/mobile-next/mobile-mcp)
- [DroidRun MobileRun](https://github.com/droidrun/mobilerun)
These are references for task design and comparison, not guarantees of platform behavior.
## Performance tests
Measure separately:
- MCP initialization;
- capability discovery;
- gateway validation;
- device round trip;
- observation capture;
- UI-tree extraction;
- action dispatch;
- human confirmation wait;
- reconnect;
- payload encoding and transfer;
- memory and battery impact.
Report p50, p95, p99, failure rate, device and network context. Do not combine human confirmation time with machine latency.
## Reliability tests
- gateway restart;
- app process death;
- Android service restart;
- iOS suspension;
- network change;
- Wi-Fi to cellular;
- expired key;
- revoked permission;
- changed foreground app;
- stale screen;
- duplicate request;
- user presses stop during execution.
The required outcome is safe termination or a clear recoverable state.
## Release test gates
### Internal
All unit, contract, security, and fixture tests pass. Real-device smoke tests pass on the minimum matrix.
### Alpha
One harmless end-to-end loop passes repeatedly. Known limitations are published. No critical security issue remains.
### Beta
The capability catalog is stable enough for external testing. Crash, latency, and refusal metrics are collected locally or opt-in. Upgrade and revoke paths are tested.
### Stable
Release candidates pass the full matrix, accessibility checks, supply-chain checks, documentation review, and rollback drill.
