---
title: "🧩 02 — Scope, Non-Goals & Capability Matrix"
source_page: "https://app.notion.com/p/3ec9b3674a8a8179a6fcccd84358a707?pvs=204"
page_id: "3ec9b367-4a8a-8179-a6fc-ccd84358a707"
last_fetched: "2026-10-01T21:06:30.873Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a8179a6fcccd84358a707?pvs=204 -->
## Capability model
Every operation belongs to a named capability. A capability is not the same as a UI permission: the runtime maps platform grants, user policy, session scope, and operation risk before execution.
A capability record includes:
- stable identifier;
- human-readable explanation;
- platform support;
- required OS permission or entitlement;
- allowed target scope;
- data sensitivity;
- action risk;
- confirmation rule;
- rate and size limits;
- audit fields;
- release status.
## Release tiers
- **Tier 0 — Observe:** health, device metadata, capability discovery, redacted UI structure.
- **Tier 1 — Navigate:** open an allowed app, focus a view, press back, scroll, wait.
- **Tier 2 — Input:** tap, long-press, swipe, type into an explicitly identified field.
- **Tier 3 — Capture:** fresh screenshot or video frame under active user grant.
- **Tier 4 — Files and media:** read or write only to an explicit user-selected scope.
- **Tier 5 — Consequential:** submit, send, purchase, delete, publish, account or security changes. Default deny or explicit approval.
- **Tier 6 — Restricted:** passwords, OTPs, private keys, biometrics, lock-screen secrets, unrestricted background capture. Not exposed by the default product.
## Initial capability inventory
<table fit-page-width="true" header-row="true">
<tr>
<td>Capability</td>
<td>Android</td>
<td>iOS</td>
<td>Risk</td>
<td>First release</td>
</tr>
<tr>
<td>[device.health](http://device.health)</td>
<td>yes</td>
<td>yes</td>
<td>low</td>
<td>stable</td>
</tr>
<tr>
<td>device.capabilities</td>
<td>yes</td>
<td>yes</td>
<td>low</td>
<td>stable</td>
</tr>
<tr>
<td>session.start-stop</td>
<td>yes</td>
<td>yes</td>
<td>medium</td>
<td>stable</td>
</tr>
<tr>
<td>screen.observe</td>
<td>MediaProjection or approved path</td>
<td>ScreenCaptureKit and user grant where supported</td>
<td>high privacy</td>
<td>beta</td>
</tr>
<tr>
<td>[ui.read](http://ui.read)</td>
<td>AccessibilityService</td>
<td>only approved representations</td>
<td>high privacy</td>
<td>Android beta</td>
</tr>
<tr>
<td>input.tap</td>
<td>Accessibility gesture path</td>
<td>app-owned or approved interaction paths</td>
<td>medium</td>
<td>Android beta</td>
</tr>
<tr>
<td>input.swipe</td>
<td>Accessibility gesture path</td>
<td>app-owned or approved interaction paths</td>
<td>medium</td>
<td>Android beta</td>
</tr>
<tr>
<td>input.type</td>
<td>focused editable field only</td>
<td>app-owned text actions</td>
<td>high privacy</td>
<td>Android beta</td>
</tr>
<tr>
<td>navigation.back</td>
<td>platform-supported path</td>
<td>app-owned navigation only</td>
<td>low</td>
<td>Android beta</td>
</tr>
<tr>
<td>app.launch</td>
<td>explicit package or intent</td>
<td>App Intents or app-owned deep-link path</td>
<td>medium</td>
<td>staged</td>
</tr>
<tr>
<td>file.pick</td>
<td>user-selected document scope</td>
<td>document picker</td>
<td>medium</td>
<td>staged</td>
</tr>
<tr>
<td>[file.read](http://file.read)-write</td>
<td>selected scope only</td>
<td>selected scope only</td>
<td>high privacy</td>
<td>staged</td>
</tr>
<tr>
<td>[notifications.read](http://notifications.read)</td>
<td>explicit, opt-in, narrow scope</td>
<td>restricted and app-specific</td>
<td>high privacy</td>
<td>deferred</td>
</tr>
<tr>
<td>contacts/messages</td>
<td>feature-specific consent</td>
<td>highly constrained</td>
<td>high privacy</td>
<td>deferred</td>
</tr>
<tr>
<td>purchase/send/delete</td>
<td>platform UI plus confirmation</td>
<td>platform UI plus confirmation</td>
<td>critical</td>
<td>restricted</td>
</tr>
<tr>
<td>screen.record</td>
<td>explicit session grant</td>
<td>system-approved capture</td>
<td>critical privacy</td>
<td>deferred</td>
</tr>
<tr>
<td>arbitrary shell</td>
<td>no</td>
<td>no</td>
<td>critical</td>
<td>never default</td>
</tr>
</table>
“yes” means an implementation path exists, not that the platforms provide identical behavior. The capability registry is the authority.
## Platform truth
Android AccessibilityService is a powerful control surface and must be used for an assistive or accessibility-oriented product purpose, with clear disclosure and least privilege. MediaProjection requires user consent and, on modern Android versions, correct foreground-service declarations and lifecycle handling.
iOS is intentionally narrower. App Intents expose approved actions and ScreenCaptureKit provides approved capture workflows, but they do not make arbitrary cross-app control equivalent to Android accessibility gestures. Latch should ship a truthful iOS subset rather than a fragile imitation.
## Scope guardrails
- A server never invents a capability from a client request.
- A device never executes a capability it did not advertise.
- A user policy can reduce a capability but cannot silently expand it.
- A session can reduce scope compared with the device policy.
- An operation can be refused because the screen changed, the target disappeared, the session expired, or confirmation is stale.
- Unsupported operations return structured reasons, not generic errors.
- Capability names are versioned and never silently change semantics.
## Acceptance criteria for a new capability
A capability can enter the stable catalog only when:
- the protocol schema and versioning rule are written;
- Android and iOS behavior is documented separately;
- permission, consent, and revocation are tested;
- allowed and refused examples exist;
- action and data risk are classified;
- audit fields are defined;
- timeout, retry, cancellation, and idempotency behavior are defined;
- fixture-based tests cover success and failure;
- at least one real-device test exists for every claimed platform;
- user-facing copy explains what the capability means;
- security review signs off on the threat boundary.
## Explicit exclusions
The default distribution must not expose:
- password extraction or credential replay;
- OTP interception or MFA bypass;
- biometric prompt automation;
- silent background screen capture;
- destructive actions without a fresh confirmation;
- arbitrary shell or developer-mode shortcuts;
- hidden network tunneling;
- commands that remain active after the user presses the global stop control.
These may be studied as threat cases, but they are not product features.
