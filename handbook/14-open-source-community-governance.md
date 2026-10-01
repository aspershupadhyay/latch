---
title: "🌱 14 — Open-Source Community & Governance"
source_page: "https://app.notion.com/p/3ec9b3674a8a81d5b431ed009b526151?pvs=204"
page_id: "3ec9b367-4a8a-81d5-b431-ed009b526151"
last_fetched: "2026-10-01T21:15:26.904Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81d5b431ed009b526151?pvs=204 -->
## Open-source promise
The project should be easy to inspect, build, fork, and leave. It should not require a private cloud, a paid account, or a founder's personal explanation to understand the core.
## License
Default recommendation: Apache License 2.0 for code, protocol implementation, SDKs, and schemas. Review the final license choice before repository initialization, especially if future contributors or embedded dependencies introduce obligations.
Documentation and design assets need an explicit treatment, such as a compatible documentation license or a clearly separated asset license. Do not mix code and non-code licensing casually.
## Required repository documents
- README with the safe quick start;
- architecture overview;
- protocol specification;
- platform capability matrix;
- threat model;
- privacy policy;
- [SECURITY.md](http://SECURITY.md) and disclosure process;
- [CONTRIBUTING.md](http://CONTRIBUTING.md);
- CODE_OF_[CONDUCT.md](http://CONDUCT.md);
- governance and maintainer policy;
- release and verification guide;
- support matrix;
- changelog and deprecation policy;
- license and third-party notices.
## Contribution path
A first-time contributor should be able to:
1. read the project boundaries;
2. install prerequisites;
3. run fake-device tests;
4. reproduce a fixture;
5. choose a good first issue;
6. submit a focused change;
7. see CI explain failures;
8. receive review without private context.
Good first issues should be real, bounded, and documented. Do not label speculative roadmap ideas as beginner tasks.
## Decision process
- small implementation choices live in pull requests;
- cross-cutting architecture choices use ADRs;
- protocol changes use an RFC with compatibility, security, and migration sections;
- permission and safety changes require explicit maintainer review;
- public breaking changes need a deprecation window and release note;
- user-facing privacy changes need a plain-language notice.
## Maintainers
Start with a small maintainer group and clear areas:
- protocol and Rust;
- Android;
- iOS;
- security and privacy;
- release and infrastructure;
- documentation and community.
Each area has a backup maintainer before it becomes critical. A maintainer can block a change in their security or platform area, but decisions should be explained in writing.
## Community channels
Use GitHub Issues for reproducible bugs and scoped work. Use Discussions for questions, ideas, and design conversation. Use private security reporting for vulnerabilities.
A support response should request:
- version;
- platform and device;
- capability;
- reproduction steps;
- redacted logs or fixture;
- expected and actual behavior.
Never ask users to paste tokens, raw screenshots, passwords, or private account data.
## Compatibility program
Publish a small compatibility matrix for:
- MCP clients tested;
- gateway deployment modes;
- Android versions and devices;
- iOS versions and devices;
- protocol versions;
- optional adapters.
Invite external maintainers to verify their clients against public fixtures. Compatibility claims must link to a dated test or report.
## Governance health
Review quarterly:
- unresolved security issues;
- bus-factor and reviewer coverage;
- issue response time;
- build reproducibility;
- dependency freshness;
- protocol stability;
- user-reported privacy concerns;
- whether the scope is growing faster than maintainers can safely support.
## Trademark and naming
Open-source code licensing does not automatically grant the right to ship modified builds under the same product name. Decide later whether the product name is protected by a trademark policy. Make it clear which marks are project identity and which parts are forkable code.
## Community safety
The code of conduct applies to users, maintainers, and automated agents. Reject contribution patterns that normalize credential access, covert monitoring, bypassing OS security, or unsafe automation. The project should be technically open without becoming a handbook for abusing other people's devices.
