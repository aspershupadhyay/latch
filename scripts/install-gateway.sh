#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
#
# Installs the Latch gateway on macOS or Linux from the beta release:
#
#   curl -fsSL https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.sh | sh
#
# Why a script: a gateway downloaded in a browser gets macOS's quarantine flag,
# and macOS then refuses it ("cannot be opened", "Move to Trash") because it is
# not notarized; chmod cannot change that. Files fetched with curl carry no
# quarantine flag. The script checks both files it installs against the
# published SHA-256: latch-gateway, and the `latch` command (packaging/latch),
# which does everything else: latch start | pair | status | autostart on|off |
# stop | restart | logs | update | uninstall. Your keys are made once in
# ~/.latch/latch.env (readable by you only) and kept. Running it again updates
# the gateway, and a background gateway (latch autostart on) restarts on the new
# version. With Homebrew, `brew install latch` from our tap installs the same
# two files (docs/install.md).
#
# Options (environment): LATCH_INSTALL_DIR (default ~/.local/bin),
# LATCH_HOME (default ~/.latch), LATCH_RELEASE (default the beta download URL).
set -eu

release="${LATCH_RELEASE:-https://github.com/aspershupadhyay/latch/releases/download/beta}"
bin_dir="${LATCH_INSTALL_DIR:-$HOME/.local/bin}"
home="${LATCH_HOME:-$HOME/.latch}"

case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  *) echo "This script is for macOS and Linux. On Windows, download latch-gateway-windows-x86_64.exe from $release" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64 | aarch64) arch=arm64 ;;
  x86_64 | amd64) arch=x86_64 ;;
  *) echo "Unsupported processor: $(uname -m)" >&2; exit 1 ;;
esac
if [ "$os" = linux ] && [ "$arch" = arm64 ]; then
  echo "There is no ready-made Linux arm64 gateway yet; build it with: cargo build --release -p latch-gateway" >&2
  exit 1
fi
file="latch-gateway-$os-$arch"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "Downloading $file …"
curl -fSL --progress-bar -o "$tmp/$file" "$release/$file"
curl -fsSL -o "$tmp/latch" "$release/latch"
curl -fsSL -o "$tmp/latch-gateway.sha256" "$release/latch-gateway.sha256"

for f in "$file" latch; do
  expected="$(grep " \*\{0,1\}$f\$" "$tmp/latch-gateway.sha256" | cut -d' ' -f1)"
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$tmp/$f" | cut -d' ' -f1)"
  else
    actual="$(shasum -a 256 "$tmp/$f" | cut -d' ' -f1)"
  fi
  if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    echo "$f does not match the published checksum; nothing was installed." >&2
    exit 1
  fi
done

mkdir -p "$bin_dir"
install -m 755 "$tmp/$file" "$bin_dir/latch-gateway"
if [ "$os" = macos ]; then
  # Belt and braces: no quarantine flag, and a valid (ad-hoc) signature, which
  # Apple Silicon requires of every program it runs.
  xattr -d com.apple.quarantine "$bin_dir/latch-gateway" 2>/dev/null || true
  codesign --force --sign - "$bin_dir/latch-gateway" 2>/dev/null || true
fi
"$bin_dir/latch-gateway" --version >/dev/null

# `latch`, pointed at the release it came from, so `latch update` stays on it.
sed "s|https://github.com/aspershupadhyay/latch/releases/download/beta|$release|" "$tmp/latch" >"$bin_dir/latch"
chmod 755 "$bin_dir/latch"
# The names older versions installed, kept working.
for name in start autostart; do
  printf '#!/bin/sh\nexec "%s/latch" %s "$@"\n' "$bin_dir" "$name" >"$bin_dir/latch-$name"
  chmod 755 "$bin_dir/latch-$name"
done
"$bin_dir/latch" keys >/dev/null

# Background start was on: bring the gateway back now, on the new version.
case "$os" in
  macos) bg="$HOME/Library/LaunchAgents/io.github.aspershupadhyay.latch-gateway.plist" ;;
  *) bg="$HOME/.config/systemd/user/latch-gateway.service" ;;
esac
if [ -f "$bg" ]; then
  "$bin_dir/latch" autostart on >/dev/null && echo "The background gateway runs the new version."
fi

echo
echo "Installed $("$bin_dir/latch-gateway" --version) in $bin_dir"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "Add it to your PATH:  echo 'export PATH=\"$bin_dir:\$PATH\"' >> ~/.zshrc && export PATH=\"$bin_dir:\$PATH\"" ;;
esac
echo "Start it with:  latch start   (then in the Latch app: Scan the QR code, or Find my computer)"
echo "Or keep it running in the background, also after a restart:  latch autostart on"
echo "Everything else:  latch help   (status, update, uninstall, logs, …)"
