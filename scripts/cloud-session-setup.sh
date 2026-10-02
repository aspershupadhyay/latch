#!/usr/bin/env bash
# Prepares a fresh cloud container (Claude Code on the web, Codespaces, CI) to
# build and test everything in this repository. Safe to run repeatedly.
#
# Needs network access to: static.rust-lang.org, crates.io, dl.google.com,
# maven.google.com, repo.maven.apache.org, services.gradle.org, registry.npmjs.org.
set -euo pipefail
cd "$(dirname "$0")/.."

# Rust: rust-toolchain.toml pins the version; this installs it if missing.
if ! command -v cargo >/dev/null; then
  curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal
  # shellcheck disable=SC1091
  . "$HOME/.cargo/env"
fi
cargo fetch --locked

# Android SDK (headless; no emulator: cloud containers have no KVM).
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}" scripts/setup-android-sdk.sh
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}" >> "$CLAUDE_ENV_FILE"
fi

# Interop test client.
(cd tests/interop && npm ci --silent --no-audit --no-fund)
echo "Cloud session ready: scripts/check.sh runs everything."
