---
title: "🖱️ 07 — Computer-Use Feature Catalog"
source_page: "https://app.notion.com/p/3ec9b3674a8a81de97c0f96ccb1a76c4?pvs=204"
page_id: "3ec9b367-4a8a-81de-97c0-f96ccb1a76c4"
last_fetched: "2026-10-01T21:09:30.255Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81de97c0f96ccb1a76c4?pvs=204 -->
## Feature rule
Computer use is a catalog of bounded primitives, not one magic “control phone” button. Each primitive has a target, precondition, observation requirement, policy class, confirmation behavior, timeout, cancellation behavior, and audit event.
## Observation features
### Device and session observation
- health and connectivity;
- platform, OS, app version, and capability versions;
- active session and approval state;
- current foreground application where the platform exposes it;
- last command and last failure;
- clock and latency diagnostics.
### Screen observation
- fresh screenshot with size and color metadata;
- bounded crop only when the user has authorized it;
- frame freshness and capture timestamp;
- optional low-rate stream for a visible session;
- no silent continuous capture.
### Semantic observation
- redacted UI tree;
- roles, labels, bounds, enabled state, and stable target hints;
- visible text only where policy allows;
- focused element type;
- scrollable containers and actionable controls;
- source freshness and confidence.
### Evidence bundles
A structured observation may include a screenshot hash, tree hash, app identity, timestamp, and redaction summary. It should not automatically include raw personal content in logs.
## Navigation features
- launch an explicitly allowed app;
- open an approved deep link or App Intent;
- press back where the platform supports it;
- scroll a named container;
- wait for a condition with a deadline;
- refresh observation;
- return to the safe home state;
- pause and resume the session.
Navigation actions should be idempotent where possible. “Open app” must not mean “execute arbitrary URI.”
## Input features
- tap a semantic target;
- tap a bounded coordinate tied to a fresh screen snapshot;
- long press with a duration limit;
- swipe within a named container;
- type into a focused, explicitly allowed field;
- clear a field only with a bounded target;
- select from a known list;
- press a visible button.
Typing is high-risk because it can cross from ordinary text into secrets. Password, OTP, payment, and authentication fields are never typed by the default runtime.
## App and workflow features
- run a sequence of low-risk primitives;
- checkpoint after each observation;
- stop when the expected target is absent;
- request confirmation before side effects;
- resume from a user-approved checkpoint;
- export a redacted trace;
- replay only against a test fixture by default.
Workflow definitions remain data, not executable code. Ordinary UI/workflow tools never evaluate agent-provided scripts. The separately granted Android ADB shell capability in [ADR-045](../docs/adr/README.md#adr-045--owner-authorized-adb-shell-through-mcp) may execute owner-approved shell commands; screen content and command output remain untrusted data and can never supply consent.
## Files and media
Staged features:
1. user-selected file picker;
2. explicit read of the selected file;
3. explicit write to a selected location;
4. bounded image or document metadata;
5. media selection with preview and confirmation.
The ordinary file tools remain limited to owner-selected scopes. Every file result includes scope, size, type, and redaction rules. ADR-045's separate ADB mode can reach files available to Android's shell identity; it must disclose that broader boundary and must not claim the document-picker scope confines it.
## Communication and external side effects
Messages, publishing, email, posting, purchases, deletion, account changes, and security changes are separate high-risk capabilities. The default behavior is:
1. prepare a draft or preview;
2. show destination and data summary;
3. require fresh confirmation;
4. execute once with an idempotency key;
5. show the result;
6. write a redacted audit event.
## Multi-device features
Later features:
- list paired devices;
- device labels and trust status;
- per-device capability policies;
- one active control session by default;
- explicit device selection for every command;
- transfer or revoke trust;
- offline device state;
- fleet management only for self-hosted advanced deployments.
The first release must avoid ambiguous default-device selection if more than one phone is paired.
## Human-in-the-loop features
- global stop;
- per-command approval;
- approval with expiry;
- deny and explain;
- pause session;
- revoke device;
- emergency disconnect;
- clear active session;
- safe mode that disables all side-effect capabilities;
- approval history without sensitive payloads.
A confirmation must name the capability, target app, target, intended effect, data class, and expiry. “Allow access?” is not enough.
## Evaluation scenarios
Build a task suite with:
- harmless navigation;
- semantic tap;
- coordinate tap after layout shift;
- text entry into a non-sensitive field;
- denied permission;
- stale screen;
- app change;
- network loss;
- device process death;
- revocation during an action;
- confirmation expiry;
- double-send attempt;
- large screen payload;
- unsupported iOS capability.
Success is not just task completion. Score safety, refusal quality, recovery, latency, and user comprehension.

## Android ADB shell (accepted; R27 TODO)
Provide MCP discovery/status and command execution for the explicitly paired Android device, over owner-authorized Wi-Fi wireless debugging or USB ADB. The command contract must carry a device, client/session authorization, deadline, and bounded output; results distinguish stdout, stderr, exit status, truncation, timeout, cancellation, and disconnection.
The owner sees the exact command and target before approval. Existing app consent, Auto mode, saved skills, and untrusted content cannot turn this grant on. Keep deep-link validation unchanged: `open_link` is not a shell shortcut. See [ADR-045](../docs/adr/README.md#adr-045--owner-authorized-adb-shell-through-mcp) for architecture constraints, residual risk, tests, and rollback.

