# Changelog

All notable changes for owners and operators. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow [Semantic Versioning](https://semver.org/) from the first tagged release; the device protocol has its own [version history](docs/protocol/v1.md#version-history).

Every merge to `main` publishes a [beta build](https://github.com/aspershupadhyay/latch/releases/download/beta/latch-android.apk). Tagged releases are made as described in [docs/operations/release.md](docs/operations/release.md).

## [Unreleased]: beta before 0.1.0

The work toward the first alpha. Rows refer to the [roadmap](handbook/17-plan-review-and-revised-delivery.md).

### Safety and security

- Each AI app can be limited to the phone apps you pick, from **Connect** in the app (R18).
- Messages from the phone are signed by a key kept in its keystore; the owner key can be rotated from the app, and changing `LATCH_ADMIN_TOKEN` always recovers access (R19, protocol 1.12).
- The Vercel gateway's database holds screen content only encrypted (R20).
- AI apps sign in with OAuth 2.1 on both gateways and are approved on the phone (R17).
- Pull requests fail on dependencies with known vulnerabilities (RustSec and npm audits in CI).

### New for owners

- `diagnose` checks each phone and names the fix, including Android's battery optimisation (R13, protocol 1.13).
- Phone controls: volume, media, brightness, flashlight, and the Wi-Fi, internet, Bluetooth, and volume panels, each off until switched on (R16, protocol 1.11).
- Icon-only buttons are read on the phone, so an unlabeled Send still asks you (R9).
- Activity groups each AI task into a timeline, and `get_activity` replays it (R14).
- Starter skills: web, YouTube, Maps, and Settings search, and a WhatsApp message (R8).
- The app warns when your relay's database is far from its functions (R3).
- **Connect → Other phones on this relay** lists every paired phone with Remove.

### Speed

- Actions return only what changed on screen (R5).
- `run_steps` does several predictable steps in one call (R4).
- Screenshots are WebP and taken only when asked (R6, protocol 1.10).
- The phone learns how long each app takes to settle (R7).
- Every action reports whether it changed the screen, opened another app, or did nothing (R11, protocol 1.9).
- Repeated elements, such as map results, are listed once (R12).
- **Fast transfers on the same Wi-Fi:** with the switch on (Access → Files), whole files go straight between your computer and the phone, still encrypted, and fall back to your gateway anywhere else (R23, protocol 1.14).

### Fixes

- Disconnecting no longer leaves duplicate entries of the same phone on the relay.

### For developers

- CI runs the real app on an Android emulator against a real gateway (R15).
- A one-file Python client drives several phones in parallel (R15).
- A task evaluation suite runs in CI without model API keys (R10).
- A release pipeline with signed APKs, checksums, a software bill of materials, and build provenance, checked by a dry run (R21).
