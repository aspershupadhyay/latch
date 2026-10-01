---
title: "🤖 05 — Android Application Plan"
source_page: "https://app.notion.com/p/3ec9b3674a8a817b88c0ebb58c128f54?pvs=204"
page_id: "3ec9b367-4a8a-817b-88c0-ebb58c128f54"
last_fetched: "2026-10-01T21:09:30.255Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a817b88c0ebb58c128f54?pvs=204 -->
## Android role
Android is the first end-to-end platform because it exposes the clearest path for a user-authorized device agent: AccessibilityService for UI semantics and gestures, MediaProjection for screen capture, native lifecycle controls, and a broad device ecosystem for evaluation.
The app remains a native Kotlin application. The Rust gateway is a separate runtime boundary; it is not hidden inside the Android UI.
## Platform stack
Use the latest stable Android Studio, Android SDK, Kotlin, Android Gradle Plugin, Jetpack Compose, and Compose Material 3 versions available at implementation time. Pin them in Gradle version catalogs and record the support matrix.
Recommended foundations:
- Kotlin with coroutines and Flow;
- Jetpack Compose and Material 3;
- lifecycle-aware ViewModel and state holders;
- Navigation Compose only where navigation complexity warrants it;
- DataStore for small settings and policy preferences;
- Room only when durable structured local history is proven necessary;
- Android Keystore for device identity material;
- a maintained HTTP/WebSocket client with explicit timeouts and certificate policy;
- Kotlin serialization or the selected schema-generated models;
- Gradle convention plugins only after repeated build logic justifies them.
Avoid a second UI toolkit, a service locator, or a large dependency injection framework until the app has a demonstrated need.
## Android components
### App shell
Owns onboarding, status, capability policy, pairing, session control, audit view, diagnostics, and settings. It must work when no permissions are granted.
### AccessibilityService
Owns the narrowest approved observation and gesture operations. It must:
- show a clear user explanation before enablement;
- expose only opted-in capabilities;
- report current package and tree freshness;
- refuse stale or ambiguous targets;
- avoid reading password fields and sensitive nodes;
- stop dispatching gestures immediately after revocation;
- provide a visible service status.
Android platform requirements and policy must be verified against the current documentation: [AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/views/service), [AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/package-summary.html).
### MediaProjection path
Owns screen frames only after the user grants capture consent. The app must handle modern Android foreground-service requirements, one-time or session-specific consent behavior, rotation, app backgrounding, and user stop actions.
Use a dedicated foreground service type and display the platform-visible state honestly. Recheck the current rules: [MediaProjection](https://developer.android.com/media/grow/media-projection), [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
### Device session
Owns pairing, reconnect, heartbeats, command envelopes, local stop, and redacted audit events. The session must fail closed when the gateway disappears or credentials are revoked.
## Android permission and consent sequence
1. Explain the capability in plain language.
2. Let the user select the app or scope when possible.
3. Open the exact OS settings or consent surface.
4. Re-read the resulting permission state.
5. Show what changed and what did not.
6. Require the user to start the session explicitly.
7. Show the active session indicator and global stop.
8. On stop or revoke, tear down capture, gestures, channels, and pending work.
9. Verify the teardown with a test and with a visible state change.
Never infer permission from a successful intent launch. Read the platform state.
## Android screen and UI model
The app uses a state-driven UI rather than a sequence of optimistic screens:
- **Unpaired**
- **Paired**
- **NeedsPermission**
- **Ready**
- **Starting**
- **Active**
- **AwaitingApproval**
- **Paused**
- **Disconnected**
- **Revoked**
- **Error**
Each state has a loading, empty, failure, recovery, and accessibility path.
## Android test matrix
At minimum, test:
- one current Pixel-class phone;
- one Samsung-class device;
- one low-memory or older supported device;
- multiple screen densities and font scales;
- rotation and split-screen where supported;
- screen lock and unlock;
- battery saver and background restrictions;
- permission revoke while active;
- network loss during observation and action;
- service restart and process death;
- TalkBack and large text;
- reduced motion;
- dark and light themes.
The exact API-level floor and ceiling are a product gate, not an assumption. Publish the tested matrix, not an aspirational one.
## Android-specific security
- store device keys in Android Keystore;
- never log raw accessibility text or screen images by default;
- filter password, OTP, payment, and private content;
- require an app allowlist for the first beta;
- bind gestures to a recent node or coordinate snapshot;
- block commands when the foreground package changes unexpectedly;
- provide a system-visible and in-app stop control;
- rate-limit capture and input;
- clear temporary frames after use.
## Acceptance gate
Android is ready for a public alpha only when a clean install can pair locally, grant one capability, observe a fixture app, perform a harmless action, revoke the capability, and prove that the action no longer executes.
