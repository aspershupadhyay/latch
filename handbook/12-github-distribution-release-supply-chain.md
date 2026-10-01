---
title: "📦 12 — GitHub Distribution, Release & Supply Chain"
source_page: "https://app.notion.com/p/3ec9b3674a8a81d2b9b1c9cccdd10563?pvs=204"
page_id: "3ec9b367-4a8a-81d2-b9b1-c9cccdd10563"
last_fetched: "2026-10-01T21:15:26.904Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81d2b9b1c9cccdd10563?pvs=204 -->
<callout icon="📦" color="green_bg">
	**Shipping rule:** a download is not a release. A release is a verified source revision, documented support matrix, signed artifacts, provenance, checksums, upgrade notes, and a tested rollback path.
</callout>
## Public distribution surfaces
### Source repository
The GitHub repository is the canonical source for code, issues, discussions, documentation, release notes, and security policy. It must be usable without a private account or hidden service.
### Android
Ship:
- source code;
- debug build instructions;
- signed release APK or approved package format;
- checksum and provenance;
- supported device and OS matrix;
- permission and accessibility disclosure;
- upgrade and uninstall instructions;
- a clear warning about installing packages outside official stores.
Direct distribution requirements and current Android verification expectations must be rechecked before launch: [Android Developer Verification](https://developer.android.com/developer-verification).
### iOS
Ship source, signed TestFlight builds for beta, and App Store distribution when the product passes review. Do not promise a public downloadable IPA as a normal open-source install path.
Provide:
- supported OS and device matrix;
- App Store privacy information;
- entitlement explanation;
- TestFlight onboarding;
- data deletion and revoke instructions;
- source-to-build mapping for the release.
### Gateway and CLI
Ship:
- platform binaries where the support cost is justified;
- container images with immutable digests;
- source build;
- local stdio instructions;
- remote Streamable HTTP deployment guide;
- environment variable reference;
- key generation and rotation commands;
- health and diagnostics guide.
### Documentation site
Document the shortest safe path first:
1. install;
2. pair;
3. choose one low-risk capability;
4. run the safe test;
5. connect an MCP client;
6. stop and revoke.
Then document advanced hosting, networking, adapters, and contribution.
## Release channels
- **nightly:** automated, unstable, for maintainers;
- **preview:** manually reviewed, compatibility testing;
- **alpha:** one safe end-to-end loop, known limitations;
- **beta:** external testers, stable enough capability catalog;
- **stable:** support promise and rollback drill;
- **LTS candidate:** only after maintenance capacity exists.
Do not maintain more channels than the team can test.
## Versioning
Use semantic versioning for the public protocol and runtime, with explicit deprecation notices. A protocol-major change needs:
- migration guide;
- compatibility window;
- old-client behavior;
- fixture updates;
- adapter release plan;
- removal date.
Mobile app versions and gateway versions should advertise compatibility separately.
## Release pipeline
1. merge to protected default branch;
2. run formatting, lint, unit, contract, device smoke, accessibility, security, and dependency checks;
3. build source and binaries in clean CI environments;
4. generate SBOM and provenance;
5. sign artifacts;
6. publish checksums;
7. create release candidate notes;
8. run install and upgrade tests from clean machines;
9. test rollback or artifact withdrawal;
10. create an immutable stable release;
11. publish migration, limitations, and verification instructions;
12. monitor issues and close the release checklist.
Reference [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases), and [artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations).
## Artifact verification
Publish:
- SHA-256 checksums;
- signature verification instructions;
- build commit;
- compiler and SDK versions;
- dependency lock state;
- SBOM;
- attestation or provenance link;
- source archive reference.
A user should be able to verify that a downloaded artifact corresponds to a public source revision.
## Upgrade safety
- never overwrite a running gateway without a graceful drain;
- preserve keys and policies across upgrade;
- back up user-owned state only with explicit instruction;
- migrate schema with version checks;
- fail closed on an incompatible protocol;
- show the user what changes;
- support rollback to the previous known-good release;
- document when mobile and gateway versions must move together.
## Privacy and trust copy
The release page must state:
- what runs on the phone;
- what can run on the gateway;
- whether Latch-operated infrastructure is required;
- what data is transmitted;
- how to run locally;
- what is not supported;
- how to report a vulnerability.
No “zero cost” promise should imply that a third-party tunnel, hosting provider, or Apple/Google account is free forever.
## Shipping gate
Do not call the project public stable until a new user can install from GitHub or the approved store, verify the artifact, complete the safe test, connect one independent MCP client, stop the session, and remove the system without hidden services.
