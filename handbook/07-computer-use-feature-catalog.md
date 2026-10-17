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
Workflow definitions remain data, not executable code. Ordinary UI/workflow tools never evaluate agent-provided scripts. The owner-enabled full-access ADB mode in [ADR-046](../docs/adr/README.md#adr-046--unrestricted-owner-authorized-adb-over-wireless-mcp) may execute arbitrary device-side commands and scripts without approval per command; screen content and command output remain untrusted data and can never supply consent.
## Files and media
Staged features:
1. user-selected file picker;
2. explicit read of the selected file;
3. explicit write to a selected location;
4. bounded image or document metadata;
5. media selection with preview and confirmation.
The ordinary file tools remain limited to owner-selected scopes. Every file result includes scope, size, type, and redaction rules. ADR-046's separate full-access ADB mode can reach files available to the actual Android ADB identity; it must disclose that broader boundary and must not claim the document-picker scope confines it.
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

## Full-access Android ADB over wireless MCP (accepted; R27 TODO)
After initial owner enablement and Android pairing, expose the same device-side access as direct ADB through authenticated MCP over Wi-Fi: arbitrary shell commands/scripts and the device operations its ADB peer supports, including file transfer, package management, diagnostics, and forwarding where supported. Do not substitute accessibility/app-UID operations or a restricted list of public-API commands.
No Latch command allowlist/denylist, app/folder scope, per-command approval, mandatory session expiry, policy timeout, input/output cap, rate/concurrency quota, or output redaction applies to this mode. Preserve output and exit/error semantics; stream or resume large/long-running results rather than truncate them. A caller may choose a deadline or cancellation; transport failure is an error, not permission to lose output silently or replay a side effect.
Keep authenticated endpoint access, protected ADB keys, exact target binding, initial owner authorization, and owner Stop/revoke. Do not require a USB cable for setup, pairing, reconnect, or operation. Android remains the authority on its permissions and supported ADB operations. [ADR-046](../docs/adr/README.md#adr-046--unrestricted-owner-authorized-adb-over-wireless-mcp) overrides the earlier approval/limit requirements in ADR-045; ordinary UI/file tools keep their existing contracts.
