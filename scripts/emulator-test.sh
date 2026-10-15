#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
# Emulator end-to-end (roadmap R15): the real Android app and its accessibility
# service on an emulator, paired with a fresh container gateway, driven over MCP
# by EmulatorGatewayTest. Needs ANDROID_HOME (scripts/setup-android-sdk.sh) and
# hardware acceleration (/dev/kvm). Throwaway emulators only: the test switches
# the accessibility service on with a shell command.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?set ANDROID_HOME (scripts/setup-android-sdk.sh)}"
api="${LATCH_EMULATOR_API:-34}"
image="system-images;android-$api;google_apis;x86_64"
tools="$ANDROID_HOME/cmdline-tools/latest/bin"
adb="$ANDROID_HOME/platform-tools/adb"

# The image is about 1 GB from Google's servers, which sometimes fail a download.
for attempt in 1 2 3; do
  "$tools/sdkmanager" --sdk_root="$ANDROID_HOME" "emulator" "$image" >/dev/null && break
  [ "$attempt" = 3 ] && { echo "could not download the emulator image" >&2; exit 1; }
  sleep 15
done
echo no | "$tools/avdmanager" create avd -n latch-ci -k "$image" --force >/dev/null

cargo build -q -p latch-gateway
data=$(mktemp -d)
export LATCH_ADMIN_TOKEN=$(target/debug/latch-gateway gen-token) LATCH_MCP_TOKEN=$(target/debug/latch-gateway gen-token)
# The emulator reaches the computer's 127.0.0.1 as 10.0.2.2.
export LATCH_BIND=127.0.0.1:8787 LATCH_DATA_DIR=$data
target/debug/latch-gateway serve 2>"$data/gateway.log" &
gateway=$!
"$ANDROID_HOME/emulator/emulator" -avd latch-ci -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect >"$data/emulator.log" 2>&1 &
emulator=$!
trap 'kill $gateway $emulator 2>/dev/null || true; cp "$data"/*.log . 2>/dev/null || true; rm -rf "$data"' EXIT

"$adb" wait-for-device
for _ in $(seq 1 180); do
  [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 2
done
[ "$("$adb" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { echo "emulator did not boot" >&2; exit 1; }
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$adb" shell settings put global "$scale" 0
done
"$adb" shell input keyevent 82 # dismiss the lock screen

(cd apps/android && ./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.gateway=http://10.0.2.2:8787 \
  -Pandroid.testInstrumentationRunnerArguments.ownerKey="$LATCH_ADMIN_TOKEN" \
  -Pandroid.testInstrumentationRunnerArguments.mcpToken="$LATCH_MCP_TOKEN") || {
  "$adb" logcat -d >logcat.txt 2>/dev/null || true
  echo "== Latch and test lines from logcat" >&2
  grep -i -E "latch|TestRunner|AccessibilityManager|AndroidRuntime" logcat.txt | tail -80 >&2 || true
  echo "== gateway log" >&2
  tail -30 "$data/gateway.log" >&2 || true
  exit 1
}
echo "Emulator end-to-end passed."
