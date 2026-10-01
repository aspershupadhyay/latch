---
title: "👥 01 — Product, Users & Use Cases"
source_page: "https://app.notion.com/p/3ec9b3674a8a81c7874bcdb0e2870d2b?pvs=204"
page_id: "3ec9b367-4a8a-81c7-874b-cdb0e2870d2b"
last_fetched: "2026-10-01T21:06:30.873Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81c7874bcdb0e2870d2b?pvs=204 -->
## Who this is for
### Primary user: the technical owner
A developer, researcher, accessibility practitioner, or power user who wants an AI client to work with a phone they control. They understand tools and networking eventually, but they should not need to understand mobile internals to stay safe.
**Job:** connect an agent to a device, choose boundaries, accomplish a task, inspect what happened, and disconnect.
### Secondary user: the automation builder
A person building an agent or workflow that needs a phone action without maintaining a custom Android or iOS integration.
**Job:** discover capabilities, call stable typed operations, handle permission and confirmation states, and recover from device or policy failures.
### Secondary user: the cautious non-developer
A person who wants a guided setup and does not want to expose a phone to a hosted service.
**Job:** install, understand permissions in plain language, use local mode, and get a safe answer when a requested action is unavailable.
### Ecosystem user: the maintainer
A contributor who improves the protocol, app adapters, test fixtures, docs, or release tooling.
**Job:** reproduce a behavior, understand the boundary, change one layer, and prove compatibility without needing private project knowledge.
## Jobs to be done
- “Help me connect an AI client to my phone without handing my whole phone to a company.”
- “Show me exactly what the agent can see and do before I start a session.”
- “Let me automate repetitive low-risk actions while I approve anything consequential.”
- “Give me a machine-readable contract that works across agent vendors.”
- “When Android or iOS blocks a capability, tell me the real platform reason.”
- “Let me export diagnostics without leaking screen contents, tokens, or personal data.”
- “Let me reproduce an issue from a fixture instead of sending my private screen to a ticket.”
## Core scenarios
### Scenario A: local task
The user and the MCP client run on the same trusted network. The user pairs the device with a short-lived code, enables screen and input capabilities, starts a session, and asks the agent to complete a low-risk task in a test or approved app.
Success means the task completes, the app visibly reports the active session, and a stop action takes effect quickly.
### Scenario B: user-owned remote host
The user runs the Latch gateway on a computer or server they control. The gateway uses authenticated Streamable HTTP for MCP and a separate authenticated device channel. The phone may be on another network.
Success means no Latch-owned relay is required, the user can rotate keys, and the public endpoint can be shut down without leaving a background device session alive.
### Scenario C: workflow with a confirmation boundary
The agent reads a page, fills non-sensitive fields, and reaches a submit or send action. Latch pauses, describes the exact action, names the target application and data class, and waits for approval. Denial leaves the session in a recoverable state.
Success means there is no ambiguity about what is being approved and a stale approval cannot be replayed.
### Scenario D: capability mismatch
An MCP client requests a capability unsupported on the current iOS version. The server returns a structured unsupported result containing the capability, platform reason, alternative, and documentation link.
Success means the agent can choose a different plan instead of retrying blindly.
### Scenario E: incident recovery
The user sees unexpected behavior, presses Stop, revokes the device key, exports redacted diagnostics, and restarts from a clean pairing.
Success means stop and revoke work even if the MCP client is misbehaving or unreachable.
## User experience outcomes
A first-time user should answer these questions without reading source code:
- What can the agent see right now?
- What can it do right now?
- Is the session local or remote?
- Which app is in scope?
- Is a human approval required?
- How do I stop everything?
- What information is stored?
- What will stop working if I revoke a permission?
## Product success measures
Do not optimize for downloads alone. Track evidence in four groups:
**Activation**
- time from install to first safe observation;
- pairing completion rate;
- percentage of users who understand the capability screen in usability tests.
**Reliability**
- observation success rate;
- action success rate by capability and device model;
- median and tail command latency;
- reconnect and recovery success rate;
- false-confirmation and stale-state rate.
**Trust and safety**
- emergency stop completion time;
- permission revocation effectiveness;
- percentage of consequential commands paused correctly;
- privacy review findings per release;
- redaction tests passing.
**Open-source health**
- reproducible setup success from a clean machine;
- time for a new contributor to run the first fixture;
- issue response and regression closure time;
- number of independent MCP clients verified against the contract.
## Non-goals for the first stable line
- autonomous unrestricted control of every app;
- harvesting notifications, contacts, messages, or files by default;
- bypassing OS permission prompts, device security, CAPTCHA, MFA, or biometric confirmation;
- pretending iOS has Android-style arbitrary UI control;
- a Latch-hosted multi-tenant relay as a requirement;
- training a model or shipping a proprietary agent;
- a marketplace of unreviewed remote actions;
- a background service that cannot be explained or stopped.
The product earns a broader scope only after the safety and reliability evidence supports it.
