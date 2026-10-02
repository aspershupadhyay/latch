#!/usr/bin/env bash
# Runs every check CI runs. Usage: scripts/check.sh [rust|android|interop|all]
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"

rust() {
  echo "== Rust: format, lint, test"
  cargo fmt --all -- --check
  cargo clippy --workspace --all-targets --locked -- -D warnings
  cargo test --workspace --locked
}

android() {
  if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/android-sdk" ]; then export ANDROID_HOME="$HOME/android-sdk"; fi
  if [ -z "${ANDROID_HOME:-}" ]; then
    echo "== Android: skipped (no SDK; run scripts/setup-android-sdk.sh)"
    return
  fi
  echo "== Android: build, unit tests, lint"
  (cd apps/android && ./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug)
}

interop() {
  echo "== Interop: official MCP TypeScript SDK client against gateway + fake phone"
  scripts/interop.sh
}

case "$what" in
  rust) rust ;;
  android) android ;;
  interop) interop ;;
  all) rust; android; interop ;;
  *) echo "usage: $0 [rust|android|interop|all]" >&2; exit 2 ;;
esac
echo "All requested checks passed."
