#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
# Runs every check CI runs. Usage: scripts/check.sh [rust|android|interop|site|audit|all]
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

audit() {
  echo "== Dependency audit: RustSec advisories, npm"
  command -v cargo-audit >/dev/null || cargo install cargo-audit --version 0.22.2 --locked
  cargo audit
  (cd servers/vercel && npm audit --audit-level=moderate)
  (cd tests/interop && npm audit --audit-level=moderate)
}

site() {
  echo "== Website: search tags, structured data, local links"
  node scripts/check-site.mjs
  echo "== License headers"
  python3 scripts/headers.py --check
}

case "$what" in
  rust) rust ;;
  android) android ;;
  interop) interop ;;
  site) site ;;
  audit) audit ;;
  all) rust; android; interop; site; audit ;;
  *) echo "usage: $0 [rust|android|interop|site|audit|all]" >&2; exit 2 ;;
esac
echo "All requested checks passed."
