---
title: "📚 10 — Repository, Code Style & Engineering Workflow"
source_page: "https://app.notion.com/p/3ec9b3674a8a81269846ca6f40da8f09?pvs=204"
page_id: "3ec9b367-4a8a-8126-9846-ca6f40da8f09"
last_fetched: "2026-10-01T21:13:45.538Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81269846ca6f40da8f09?pvs=204 -->
## Repository principle
The repository should read like a well-organized technical library. A contributor should know where a decision lives, where a public contract lives, where platform code lives, and how to prove a change.
## Target folder layout
```javascript
latch/
  apps/
    android/
      app/
      build-logic/
      docs/
    ios/
      Latch/
      LatchTests/
      LatchUITests/
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
    threat-models/
  tests/
    contract/
    integration/
    evals/
    fixtures/
  tools/
  .github/
    workflows/
    ISSUE_TEMPLATE/
    pull_request_template.md
```
Keep generated artifacts separate from hand-written source. Never edit generated files without updating their source.
## Source-of-truth order
1. normative protocol schemas and fixtures;
2. Rust core behavior and tests;
3. native platform adapters;
4. adapters and presentation;
5. docs and examples.
If two layers disagree, fix the lower source of truth and add a regression test.
## Code style
- choose clear names over abbreviations;
- prefer small functions and explicit state transitions;
- keep policy decisions pure where possible;
- pass cancellation and deadlines explicitly;
- avoid hidden global state;
- make ownership and lifecycle visible;
- return typed errors with recovery hints;
- do not catch an error only to discard it;
- keep UI state separate from transport and policy;
- document security assumptions beside the boundary they protect.
Rust uses rustfmt, clippy, typed errors, and structured tracing. Kotlin uses ktfmt or the selected formatter, compiler warnings as errors where practical, coroutines with lifecycle awareness, and explicit state models. Swift uses swift-format or the selected formatter, strict concurrency checks as feasible, and actors or isolation for shared state.
## Issue workflow
Every issue includes:
- problem statement;
- user or maintainer affected;
- scope and non-goals;
- capability and risk classification;
- acceptance criteria;
- test and fixture plan;
- docs and release-note impact;
- dependency and migration impact;
- owner and status.
Use statuses:
**TODO** → **IN_PROGRESS** → **IN_REVIEW** → **DONE**
Use **BLOCKED** only with a named blocker, evidence of attempted resolution, and the next unblock action.
Suggested labels:
- area: protocol, rust, android, ios, mcp, ux, docs, release;
- type: feature, bug, security, performance, docs, maintenance;
- risk: low, medium, high, critical;
- stage: discovery, alpha, beta, stable;
- platform: android, ios, gateway, all.
## Branch and review rules
- protect the default branch;
- use short-lived branches with a readable prefix;
- require CI before merge;
- require a second reviewer for security, protocol, permission, and release changes;
- keep pull requests narrow and reversible;
- include a behavior summary, test evidence, screenshots or traces when UI changes, and known risks;
- do not merge generated changes without their source diff;
- do not merge a public API change without documentation and compatibility notes.
## Commit and release hygiene
Use commits that explain intent. Keep formatting-only changes separate. Tag stable releases only from protected CI after checks pass.
Every release candidate contains:
- change summary;
- migration notes;
- supported matrix;
- known limitations;
- security review status;
- artifact checksums;
- rollback plan.
## Dependency policy
Before adding a dependency, record:
- why the standard library or existing stack is insufficient;
- maintenance and license status;
- security and transitive dependency cost;
- platform size and startup impact;
- removal or replacement path.
Run automated dependency review but do not treat a green scanner as a complete security review.
## Documentation workflow
Every public feature updates:
- user guide;
- protocol reference;
- platform limitation note;
- troubleshooting;
- security and privacy explanation;
- release notes;
- example or fixture.
Write examples that can be executed or verified. Mark illustrative examples as illustrative.
## Definition of done
A change is done only when:
- implementation is complete;
- tests prove normal and failure paths;
- security and privacy impact is reviewed;
- UI has loading, empty, error, disabled, and recovery states;
- docs are updated;
- metrics or diagnostics exist when operationally useful;
- rollback or removal is understood;
- CI is green;
- the final diff is reviewed for unrelated edits.
