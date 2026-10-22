# Releasing Latch

How an alpha (and later every) release is made, checked, and published. Roadmap R21, phase S6. The workflow is `.github/workflows/release.yml`.

## Before a release

1. **Real-device gate (S4).** Every step of [the checklist](../platform/android.md#real-device-gate-s4) passed on at least two phones from different makers. Record each phone's model, Android version, app build, and gateway build, plus any step that failed, in the release notes. A release never ships with a step that failed for a safety reason (an approval that did not appear, a refused field that was readable, Stop that did not stop).
2. **CI green on `main`** for the commit you will tag.
3. **Signing secrets** in the repository settings (Settings → Secrets and variables → Actions): `LATCH_KEYSTORE_BASE64` (the release keystore, base64), `LATCH_KEYSTORE_PASSWORD`, `LATCH_KEY_ALIAS`, `LATCH_KEY_PASSWORD`. The beta build (`test-build.yml`) uses the same key, so phones with the beta can update to the release. Without the secrets the APK is unsigned (`-unsigned.apk`) and the in-app updater ignores it. Keep an offline copy of the keystore: a lost key means every phone must uninstall to move to a new one.
4. **Dry run.** Actions → **Release** → **Run workflow** on `main`, with the version you plan to tag. The run builds and tests everything, signs the APK, makes the container image (not pushed), the SBOM, and `SHA256SUMS`, and keeps them as the `release-dry-run` artifact for 7 days. It pushes, attests, and publishes nothing. Install the APK from it on one phone to check that it updates over the beta.

## Releasing

```sh
git tag -a v0.1.0 -m "Latch 0.1.0 (alpha)"   # on the commit that passed the gate (-s to sign it)
git push origin v0.1.0
```

The tag run publishes:

| File | What it is |
|---|---|
| `latch-android-vX.Y.Z.apk` | The app, signed with the release key |
| `latch-android-vX.Y.Z-<abi>.apk` | The same app for one chip type (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`): about a third of the size; the updater picks it (R55) |
| `latch-update.json` | What the app's updater reads: version, URL, SHA-256, size, and each chip type's APK |
| `latch-gateway-linux-x86_64`, `latch-fake-device-linux-x86_64` | Gateway and fake phone binaries |
| `latch-vX.Y.Z-<system>.tar.gz` / `.zip` | The gateway with the `latch` command, LICENSE, and NOTICE for `macos-arm64`, `macos-x86_64`, `linux-x86_64`, and `windows-x86_64`: what Homebrew and Scoop install |
| `latch.rb`, `latch.json`, `latch-vX.Y.Z-winget-manifests.tar.gz` | The Homebrew formula, the Scoop manifest, and the winget manifests for these archives |
| `ghcr.io/aspershupadhyay/latch-gateway:vX.Y.Z` (and `:latest`) | Container image |
| `latch-sbom.spdx.json` | SPDX software bill of materials for the repository |
| `SHA256SUMS` | Checksums of every file above |

Every file and the container image get a build-provenance attestation. The release is created as a **draft**: write the notes (below), then publish it by hand.

Before anything is published, the run installs the Homebrew package on macOS and the Scoop package on Windows from the archives it just built, checks that the gateway answers, and removes them again; the dry run does the same.

## Packages

This repository is the Homebrew tap (`Formula/latch.rb`) and the Scoop bucket (`bucket/latch.json`), so owners install with `brew install latch` and `scoop install latch` ([docs/install.md](../install.md), ADR-068). The tag run opens a pull request, **Packages: Latch vX.Y.Z on Homebrew and Scoop**, with both files made from the release's archives. **Merge it right after publishing the draft release**, so the links in it work; that is what `brew upgrade` and `scoop update` then offer. If the run could not open the pull request (Settings → Actions → General → *Allow GitHub Actions to create and approve pull requests*), the branch `packages/vX.Y.Z` holds the same commit.

winget is not submitted yet (ADR-068: it would install the gateway without the `latch` command). When it is, unpack `latch-vX.Y.Z-winget-manifests.tar.gz` into a fork of [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) and open a pull request there, or run `wingetcreate submit` on the folder.

## Checking a release

Anyone can check that a file came from this repository's workflow and was not changed:

```sh
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify latch-android-v0.1.0.apk --repo aspershupadhyay/latch
gh attestation verify oci://ghcr.io/aspershupadhyay/latch-gateway:v0.1.0 --repo aspershupadhyay/latch
apksigner verify --print-certs latch-android-v0.1.0.apk   # same certificate as the beta
```

## Release notes

Write them in plain words for owners, not contributors:

- What is new for owners (taken from the roadmap rows that reached DONE or IN_REVIEW since the last release).
- **Tested on:** each phone from the S4 gate (model, Android version), the gateways used (container and/or Vercel), and the date.
- **Known limitations** (keep this list current):
  - Android only. The iOS companion (S7) is not built, and iOS has no public API to operate other apps.
  - Play Protect may block installs from a browser or chat app, and Android 13+ asks you to allow "Restricted settings" for sideloaded apps. See `docs/platform/android-install.md`. Latch does not try to work around either.
  - A cloud gateway sees screen content in transit. The Vercel gateway's database holds it only sealed (ADR-039). Self-host the container gateway if the gateway itself must not see screens.
  - Word lists that spot Send, Pay, and similar buttons, and password fields, are a safety net, not a guarantee. Turn on *Ask me before every action* for apps that matter.
  - The protocol and the MCP tool list may still change before 1.0. Phones and gateways warn when they are too far apart.
- How to update: the app checks GitHub for the newest release, and gateways update by redeploying (Vercel), pulling the new image, or `brew upgrade latch` / `scoop update latch` / `latch update` on a computer.

## If a release is bad

- Unpublish it or mark it as a pre-release so the app's updater stops offering it. The updater reads only the newest published release.
- Fix the problem on `main` and tag a higher version. Never re-tag a version: phones compare version codes, and `SHA256SUMS` and attestations name the old files.
- A container tag can be moved back with `docker buildx imagetools create -t ghcr.io/aspershupadhyay/latch-gateway:latest ghcr.io/aspershupadhyay/latch-gateway:<good tag>`.
