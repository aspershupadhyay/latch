#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
# Installs a headless Android SDK (no emulator) so the Android app can be
# compiled and unit-tested in a cloud container or CI runner.
#
# Usage: scripts/setup-android-sdk.sh            (installs into $ANDROID_HOME or ~/android-sdk)
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.1.0"

if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] && [ -d "$ANDROID_HOME/platforms/android-36" ]; then
  echo "Android SDK already present at $ANDROID_HOME"
  exit 0
fi

mkdir -p "$ANDROID_HOME/cmdline-tools"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
unzip -q "$tmp/tools.zip" -d "$tmp"
rm -rf "$ANDROID_HOME/cmdline-tools/latest"
mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# `yes` exits with SIGPIPE once sdkmanager stops reading; that is expected.
(set +o pipefail; yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null)
"$sdkmanager" --sdk_root="$ANDROID_HOME" "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null

echo "Android SDK installed at $ANDROID_HOME"
echo "export ANDROID_HOME=$ANDROID_HOME"
