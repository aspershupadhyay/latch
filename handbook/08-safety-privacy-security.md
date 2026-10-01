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
