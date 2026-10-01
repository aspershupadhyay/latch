---
title: "🦀 04 — Rust Core & MCP Runtime"
source_page: "https://app.notion.com/p/3ec9b3674a8a81cfb843e5497475da4e?pvs=204"
page_id: "3ec9b367-4a8a-81cf-b843-e5497475da4e"
last_fetched: "2026-10-01T21:09:30.255Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81cfb843e5497475da4e?pvs=204 -->
<callout icon="🦀" color="orange_bg">
	**Decision:** use Rust from the beginning for the portable protocol, policy, session, crypto, fixture, and MCP gateway layers. Keep the phone presentation and OS integration native.
</callout>
## Why Rust belongs here
The hard part is not making a mobile button fast. The hard part is keeping a security-sensitive, concurrent protocol boundary predictable as devices, clients, and transports multiply.
Rust gives the core:
- explicit ownership and cancellation behavior;
- strong types for capability and policy states;
- small static or container-friendly binaries;
- good performance for concurrent sessions without a garbage collector;
- a natural home for the MCP gateway and user-hosted CLI;
- a shared language for protocol tests, fixtures, and release tooling.
Rust is not a reason to rewrite Android or iOS. It is the boundary that benefits from deterministic behavior and long-lived compatibility.
## Stack
Use the latest stable Rust toolchain available at implementation time and pin it in the repository.
**Core libraries**
- Tokio for async runtime;
- official MCP Rust SDK, rmcp, for protocol integration;
- Serde and JSON Schema tooling for wire representations;
- thiserror for library-facing typed errors;
- tracing for structured diagnostics;
- rustls or the transport library selected by the MCP SDK for TLS;
- cargo-deny or equivalent for dependency and license checks;
- proptest or equivalent for state-machine and parser properties.
The exact versions are a release decision. Lock them in Cargo.lock, document the minimum supported Rust version, and update deliberately rather than floating in production.
The Rust MCP SDK and SDK support matrix must be rechecked against the current MCP specification before each release: [MCP SDK support matrix](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/docs/2026-07-28/sdk.mdx), [Rust SDK](https://github.com/modelcontextprotocol/rust-sdk), [MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk).
## Crate responsibilities
### protocol
- versioned request, response, event, error, and capability types;
- negotiation and compatibility;
- JSON Schema generation;
- bounded payload validation;
- golden serialization fixtures.
### policy
- capability registry;
- risk classification;
- device, app, session, and user policy;
- confirmation decision;
- rate and size limits;
- allow and deny explanations.
### session
- pairing and lifecycle state;
- command correlation;
- cancellation and deadlines;
- idempotency keys;
- reconnect behavior;
- stale observation checks.
### crypto
- device identity keys;
- pairing transcript;
- key rotation and revocation;
- signed operation envelopes;
- secure random identifiers;
- no plaintext secrets in logs.
### fixtures
- deterministic fake devices;
- approved test screens;
- action traces;
- redacted failure cases;
- cross-language conformance data.
### test-harness
- in-memory transports;
- fake clocks;
- property-test helpers;
- protocol compatibility checks;
- failure injection.
### servers/mcp
- MCP initialize and capability discovery;
- tools and structured results;
- authentication and authorization hooks;
- gateway health;
- user-hosted deployment configuration;
- adapter-specific mapping with no policy bypass.
## Mobile integration strategy
The first Android and iOS apps do not embed a full Rust runtime merely to share code. They use the published protocol schema, typed native models, and golden fixtures. This keeps app builds debuggable and platform permissions native.
Reconsider a Rust mobile library only if measurement proves that duplicated policy or parsing is causing drift. If that happens, introduce a narrow FFI package with an explicit ABI and test it against the same fixtures. Do not introduce FFI as a symbol of performance.
## MCP behavior
The gateway must:
- advertise only currently available tools;
- include descriptions that explain safety and platform limits;
- return structured content and stable error codes;
- keep tool names stable after release;
- reject unknown capabilities before they reach a device;
- surface confirmation-required as a distinct result state;
- support cancellation and deadlines;
- avoid putting screen data into logs or error strings;
- expose health and diagnostics separately from control tools.
The gateway should support local stdio and remote Streamable HTTP where the client supports them. Legacy transport compatibility is an adapter concern and must not pollute the core state model.
## Performance targets
Targets are hypotheses until benchmarked:
- protocol validation p95 below 5 ms on a laptop for normal payloads;
- no unbounded allocations from client-controlled payload sizes;
- gateway memory stable under a fixed device/session workload;
- command cancellation observed within one device heartbeat;
- screen payloads bounded by dimensions, format, and rate;
- reconnect does not duplicate a non-idempotent action.
Measure end-to-end latency separately from gateway overhead. Do not claim “blazingly fast” from a microbenchmark that excludes the phone, OS permission, network, or human confirmation.
## Security requirements
- validate every external field at the gateway and device;
- use constant-time comparison for tokens where applicable;
- rotate and revoke device keys;
- bind commands to device, session, capability, and deadline;
- cap concurrent sessions and in-flight commands;
- redact structured events at the boundary;
- make dangerous operations impossible to reach through a low-risk capability;
- pin or audit dependencies and publish an SBOM;
- run fuzzing against parsers and state transitions.
## Developer experience
A new contributor should be able to:
1. install the pinned toolchain;
2. run one command for formatting, linting, unit tests, fixtures, and documentation checks;
3. run the MCP server locally in a fake-device mode;
4. inspect a readable protocol trace;
5. add a capability with a failing test first.
The first Rust release should be boring: small API surface, strong fixtures, clear errors, and no premature plugin framework.
