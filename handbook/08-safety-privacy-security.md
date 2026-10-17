---
title: "🛡️ 08 — Safety, Privacy & Security"
source_page: "https://app.notion.com/p/3ec9b3674a8a81b2a277ce178e95f1b3?pvs=204"
page_id: "3ec9b367-4a8a-81b2-a277-ce178e95f1b3"
last_fetched: "2026-10-01T21:13:45.538Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81b2a277ce178e95f1b3?pvs=204 -->
<callout icon="🛡️" color="red_bg">
	**Safety posture:** Latch is a remote-control boundary. Treat every screen, tool call, device key, and confirmation as security-sensitive.
</callout>
## Threat model
### Assets
- device identity keys;
- pairing secrets and session tokens;
- live screen and UI-tree data;
- files and selected media;
- user policies and allowlists;
- audit history;
- MCP endpoint credentials;
- action integrity and user intent.
### Actors
- an honest but curious MCP client;
- a compromised or prompt-injected agent;
- a malicious remote client;
- a malicious app on the phone;
- a person with temporary physical access;
- a compromised gateway or dependency;
- a confused user who grants too much scope.
### Primary threats
- unauthorized pairing;
- replay of an old command or approval;
- confused-deputy routing to the wrong device;
- screen or UI data exfiltration;
- prompt injection causing a dangerous action;
- action after revoke or stop;
- stale observation leading to a wrong target;
- denial of service through large payloads or command floods;
- supply-chain compromise;
- logs or diagnostics containing private content.
## Security boundaries
1. **OS boundary:** Android and iOS decide whether a platform capability exists.
2. **Device policy boundary:** the user selects the capabilities, apps, data scopes, and session duration.
3. **Protocol boundary:** the gateway validates typed requests and capability version.
4. **Transport boundary:** every channel authenticates, encrypts, expires, and can be revoked.
5. **Action boundary:** the device rechecks policy and target freshness immediately before execution.
6. **Human boundary:** consequential actions stop for explicit, contextual approval.
A downstream layer may refuse an action that an upstream layer allowed. No layer may silently expand scope.
## Pairing and identity
- generate a device identity key in the platform keystore;
- use a short-lived, user-visible pairing code or local approval;
- show both device name and fingerprint during pairing;
- bind the resulting trust to a specific gateway and user intent;
- rotate keys and provide a visible revoke action;
- reject expired, reused, or context-mismatched pairing material;
- keep a device trust list with last-seen and capability state;
- never use a static shared secret in documentation or fixtures.
## Authorization
Authorization is the intersection of:
- user policy;
- device policy;
- session scope;
- capability risk;
- target app and data class;
- fresh observation;
- current platform permission;
- confirmation state.
The gateway must never treat “the MCP client can call the tool” as authorization.
## Confirmation design
Require approval for:
- messages, email, posting, publishing, purchases, transfers, deletion;
- account, security, identity, or permission changes;
- sharing files or screen content outside the selected scope;
- actions on a new app or destination;
- any action classified as high impact by the policy engine.
The approval record contains operation id, capability, target app, effect summary, data class, scope, expiry, and user decision. Approval is single-use unless the user explicitly selects a bounded repeat policy.
## Prompt injection posture
Screen text and UI labels are untrusted data. The runtime must:
- mark observations as data, not instructions;
- keep policy and tool descriptions outside the observed content;
- refuse hidden instructions in app content;
- require confirmation based on operation risk, not agent confidence;
- stop when a target or app changes unexpectedly;
- allow the user to view the exact source observation for a pending confirmation.
## Privacy
Default behavior:
- no central collection of screen data;
- no raw screen data in logs;
- no analytics unless opt-in and minimized;
- retention limits for audit events;
- local export with redaction preview;
- delete session data on revoke where technically possible;
- document every network destination;
- make remote mode visibly different from local mode.
Sensitive fields should be redacted before the data leaves the phone. If redaction is uncertain, refuse or require an explicit user-selected scope.
## Logging and diagnostics
Structured events may contain:
- timestamp;
- event type;
- device and session pseudonymous ids;
- capability;
- outcome code;
- latency;
- policy decision;
- correlation id.
They must not contain raw screenshots, full UI text, keys, bearer tokens, passwords, OTPs, file contents, or arbitrary prompt content.
A diagnostic export includes a redaction summary, version information, selected event codes, and reproduction steps. The user previews it before sharing.
## Supply-chain security
- pin and audit dependencies;
- run dependency and license checks;
- use secret scanning and CodeQL where applicable;
- build in GitHub Actions with protected environments;
- publish SBOM and provenance;
- sign release artifacts;
- verify checksums;
- use immutable releases for stable tags;
- document how users verify an artifact.
Reference: [GitHub immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases) and [artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations).
## Incident response
Prepare runbooks for:
- leaked gateway credential;
- compromised device key;
- unsafe release;
- accidental data exposure;
- malicious dependency;
- action executing after revoke;
- denial of service;
- app-store rejection or policy change.
Every runbook has detection, immediate containment, user communication, artifact withdrawal, key rotation, patch, regression test, and post-incident review.
## Security gates
No public beta without:
- threat model review;
- abuse-case tests;
- pairing and revocation tests;
- redaction tests;
- dependency and license report;
- secret-scanning clean result;
- documented disclosure policy;
- emergency stop measured on supported devices;
- no known critical vulnerability without an owner and mitigation.

## Full-access Android ADB mode (ADR-046; 2026-10-17)
[ADR-046](../docs/adr/README.md#adr-046--unrestricted-owner-authorized-adb-over-wireless-mcp) supersedes ADR-045's extra per-command controls. This is an explicitly owner-enabled general-purpose ADB bridge, with the same device-side access as normal direct ADB. The generic confirmation, app/file scope, mandatory expiry, and bounded-command rules elsewhere in this handbook describe ordinary capability mode and must not be reapplied to full-access ADB.

- The owner enables ADB mode and completes Android wireless-debugging pairing. Authenticate the MCP client and executor, bind requests to the selected phone, protect keys, and provide owner Stop/revoke. An ordinary session timer does not expire this grant.
- After that initial authorization, do not add command allowlists/denylists, app/folder scope, per-command confirmation, policy timeouts, input/output caps, rate/concurrency quotas, or content-based result filters. Preserve the actual device ADB identity and all commands/operations it can perform. Android permissions and ADB feature support remain authoritative; Latch does not create permissions the device lacks.
- Return complete, unfiltered results to the authorized client. Use streaming/continuation/backpressure for finite transport or memory capacity. Surface actual transport/platform failures honestly; technical frame limits must not become an undocumented command restriction or silent truncation.
- Keep ADB keys, pairing material, raw commands, and output out of persistent diagnostics/telemetry by default. This logging rule must not redact the authorized client's command result. Treat output as untrusted data; it does not change the initial owner grant or become instructions for the gateway host.
- Owner Stop/revoke prevents new dispatch and closes managed channels. An explicit cancel is best effort, like disconnecting ADB: disclose detached processes and completed effects instead of promising rollback. Do not automatically replay uncertain side effects after reconnect.
- Use authenticated wireless debugging without a USB bootstrap or mandatory laptop daemon. Keep the MCP endpoint authenticated; do not expose an unauthenticated ADB server to the internet. Initial owner grant and Android pairing must not be bypassed.
- Verify both gateways enforce authentication and target binding while adding no command-policy restrictions. Test cable-free enrollment, arbitrary benign command/script execution, representative ADB operations, full output delivery, long-running commands, continued access beyond the ordinary session timer, owner Stop/revoke, disconnect, and reconnect without duplication.

The owner explicitly accepts the broader access and resulting risk of standard ADB. Do not represent this mode as an app/folder sandbox or claim keyword filters make it one. Ordinary UI/file features and current shipping contracts retain their existing protections; runtime implementation of this separate mode is still R27 TODO.
