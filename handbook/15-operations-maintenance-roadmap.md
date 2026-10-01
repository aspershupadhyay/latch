---
title: "🔭 15 — Operations, Maintenance & Roadmap"
source_page: "https://app.notion.com/p/3ec9b3674a8a811ebc43c60d1e2c0213?pvs=204"
page_id: "3ec9b367-4a8a-811e-bc43-c60d1e2c0213"
last_fetched: "2026-10-01T21:15:26.904Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a811ebc43c60d1e2c0213?pvs=204 -->
## Operating model
Latch core remains local-first. The project may publish optional deployment recipes, but it should not create a mandatory central relay before the product has proven a need and a sustainable operating model.
## Deployment modes
### Local
The gateway runs on the same computer or trusted network as the user. Best for privacy and low operating cost.
### User-hosted remote
The user runs the gateway on their own server, home machine, container host, or approved edge platform. Documentation covers TLS, keys, backups, updates, and firewall boundaries.
### Optional managed service
A future managed service is a separate product decision. It must have an explicit privacy model, cost model, tenancy isolation, deletion policy, incident response, and opt-in consent. Core functionality must not be silently made dependent on it.
## Operational health
The gateway exposes safe health information:
- process health;
- protocol version;
- connected device count without personal content;
- session state summary;
- queue and backpressure state;
- storage and key status;
- last successful device heartbeat.
Health endpoints must not return screen data, credentials, or full device names unless local and explicitly requested.
## Observability
Default telemetry is local and redacted. Optional opt-in diagnostics may include:
- version;
- platform;
- capability outcome code;
- coarse latency;
- crash or refusal category;
- anonymized installation id if the user agrees.
Never collect screen images, UI text, files, or prompt content as default analytics.
## Support and incident response
Maintain runbooks for:
- pairing failures;
- permission drift;
- Android service restrictions;
- iOS suspension or unsupported capability;
- network and TLS problems;
- protocol version mismatch;
- unsafe behavior;
- lost or rotated device keys;
- failed upgrade;
- vulnerable dependency;
- malicious release.
Each runbook has an owner, last review date, safe stop instruction, and escalation path.
## Compatibility maintenance
At every release train:
- check current MCP specification and SDK behavior;
- check Android permission and foreground-service changes;
- check Android distribution and verification requirements;
- check iOS SDK, App Intents, ScreenCaptureKit, and App Store rules;
- rebuild the device matrix;
- rerun core fixtures;
- refresh dependency and license reports;
- update the support table.
Platform documentation is part of the release input, not a one-time research task.
## Deprecation
Every deprecated capability receives:
- a replacement if one exists;
- warning period;
- protocol behavior;
- migration instructions;
- removal target;
- fixture coverage;
- changelog entry.
If a platform removes a capability for safety or policy reasons, remove it rather than hiding it behind an unreliable workaround.
## Roadmap horizons
### Horizon A: trustworthy foundation
Protocol, Rust core, fake device, local MCP, Android pairing, observe, harmless action, stop, revoke, docs, and release proof.
### Horizon B: usable beta
Remote user-hosted mode, broader Android matrix, confirmation flows, diagnostics, iOS companion, independent-client compatibility, accessibility review.
### Horizon C: mature platform
More file and media scopes, workflow checkpoints, multi-device policy, stronger evaluation harness, maintainership, release automation, and formal compatibility reports.
### Horizon D: optional ecosystem
Provider adapters, richer developer SDKs, managed hosting if justified, plugin registry with review, fleet controls for self-hosted operators.
Do not move to the next horizon because it is exciting. Move only when the current horizon has evidence, support capacity, and a clear safety story.
## Retirement criteria
Pause or reduce scope if:
- security incidents exceed the team's response capacity;
- platform policies make a capability unsafe or noncompliant;
- maintenance burden prevents reliable releases;
- users cannot understand the control boundary;
- the project becomes dependent on a single hosted provider;
- the open-source contributor path stops working.
A smaller trustworthy project is a better outcome than a broad unsafe one.
