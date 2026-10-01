---
title: "🍎 06 — iOS Application Plan"
source_page: "https://app.notion.com/p/3ec9b3674a8a81bfa651ec1c141cfc02?pvs=204"
page_id: "3ec9b367-4a8a-81bf-a651-ec1c141cfc02"
last_fetched: "2026-10-01T21:09:30.255Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81bfa651ec1c141cfc02?pvs=204 -->
## iOS role
iOS is a first-class client with a deliberately smaller capability surface. The goal is a trustworthy, native companion that exposes approved actions and captures only through Apple-supported flows. The goal is not to pretend that iOS offers arbitrary cross-app automation.
## Platform stack
Use the latest stable Xcode, Swift, iOS SDK, Swift language mode, SwiftUI, and Apple platform frameworks available at implementation time. Record the minimum deployment target and device matrix in the release plan.
Recommended foundations:
- SwiftUI for presentation;
- Observation and structured concurrency for state and tasks;
- URLSession networking with explicit cancellation and timeouts;
- Keychain and Secure Enclave where appropriate for identity material;
- App Intents for approved app and system actions;
- ScreenCaptureKit only where the current OS and entitlement path support the requested flow;
- Swift Package Manager for dependencies;
- XCTest and UI tests for contract and state coverage.
Keep platform state in an actor or isolated model. Do not let views own network sessions or security decisions.
## iOS capability strategy
### Always-on core
- device health;
- pairing and key rotation;
- capability discovery;
- session start and stop;
- audit view;
- policy editor;
- diagnostics export;
- app-owned commands.
### Approved integration surfaces
Use App Intents for actions that the app or system intentionally exposes. Document which actions are discoverable and whether they require the app to be foregrounded.
Reference: [App Intents](https://developer.apple.com/documentation/appintents) and current updates, including long-running intent capabilities where applicable: [App Intents updates](https://developer.apple.com/documentation/updates/appintents).
### Capture
Use ScreenCaptureKit and the current user-approved system picker or capture flow where supported. Capture must be explicit, visible, cancellable, bounded, and stopped when the grant ends.
Reference: [ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit) and the current iOS capture guidance: [Capturing screen content on iOS](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios).
Do not promise arbitrary cross-app UI tree access or arbitrary gesture injection unless Apple provides an approved public API for the exact behavior.
## iOS state model
- **Unpaired**
- **Paired**
- **Ready**
- **NeedsUserAction**
- **CaptureGranted**
- **Active**
- **AwaitingApproval**
- **Paused**
- **Backgrounded**
- **Disconnected**
- **Revoked**
- **Unsupported**
- **Error**
The UI must explain when the app is backgrounded or the operating system suspends work. A remote agent must receive a truthful unsupported or paused result, not a timeout that looks like a device bug.
## Privacy and consent
- show a pre-permission explanation in the app;
- request the system permission only at the moment the capability is enabled;
- keep capture sessions short and visible;
- store no screen frames unless the user explicitly exports a diagnostic;
- never read or transmit secrets from text fields;
- require fresh confirmation for external side effects;
- make revocation and key rotation easy to find;
- describe all network destinations in settings.
## iOS test matrix
Test:
- current supported iPhone model;
- small and large displays;
- current stable OS plus the previous supported major where practical;
- light, dark, large text, VoiceOver, and Reduce Motion;
- app backgrounding and suspension;
- permission changes in Settings;
- capture grant ending;
- offline pairing and reconnect;
- App Intent execution;
- keychain failure and device restore;
- expired confirmation and stale observation.
Any claim about a new OS API must carry its availability annotation, fallback, and release-note entry.
## App Store and distribution constraints
App Store distribution, TestFlight, privacy manifests, review metadata, and entitlements are part of the feature design. Prepare the release record using the current [App Store Connect workflow](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-workflow), [TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/), and [App privacy guidance](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/).
Do not build an iOS experience that depends on undocumented APIs, private entitlements, or review-time behavior that cannot be explained to users.
## Acceptance gate
The iOS alpha is ready only when a clean install can pair, expose its honest capability set, complete one approved app-owned task, show a user-approved capture flow where supported, pause safely when backgrounded, and revoke the session without lingering work.
