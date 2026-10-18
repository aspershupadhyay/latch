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
# quarantine flag. The script also checks the file against the published
# SHA-256, and creates your keys once in ~/.latch/latch.env (readable by you only)
# plus `latch-start`, which starts the gateway for phones on this Wi-Fi, and
# `latch-autostart on|off|status`, which keeps it running in the background
# (a LaunchAgent on macOS, a systemd user service on Linux) so it need not be
# started by hand. The address and pin stay the same across restarts; if the
# computer gets a new address on the Wi-Fi, the app finds it again by its pin.
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
curl -fsSL -o "$tmp/latch-gateway.sha256" "$release/latch-gateway.sha256"

expected="$(grep " $file\$" "$tmp/latch-gateway.sha256" | cut -d' ' -f1)"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$tmp/$file" | cut -d' ' -f1)"
else
  actual="$(shasum -a 256 "$tmp/$file" | cut -d' ' -f1)"
fi
if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
  echo "The download does not match the published checksum; nothing was installed." >&2
  exit 1
fi

mkdir -p "$bin_dir"
install -m 755 "$tmp/$file" "$bin_dir/latch-gateway"
if [ "$os" = macos ]; then
  # Belt and braces: no quarantine flag, and a valid (ad-hoc) signature, which
  # Apple Silicon requires of every program it runs.
  xattr -d com.apple.quarantine "$bin_dir/latch-gateway" 2>/dev/null || true
  codesign --force --sign - "$bin_dir/latch-gateway" 2>/dev/null || true
fi
"$bin_dir/latch-gateway" --version >/dev/null

# Keys, made once and kept.
mkdir -p "$home"
chmod 700 "$home"
if [ ! -f "$home/latch.env" ]; then
  umask 077
  {
    echo "# Latch gateway keys. Keep this file private."
    echo "export LATCH_ADMIN_TOKEN=$("$bin_dir/latch-gateway" gen-token)"
    echo "export LATCH_MCP_TOKEN=$("$bin_dir/latch-gateway" gen-token)"
    echo "export LATCH_DATA_DIR=\"$home/data\""
    echo "export LATCH_WIFI=1"
  } >"$home/latch.env"
fi

cat >"$bin_dir/latch-start" <<EOF
#!/bin/sh
# Starts the Latch gateway for phones on this Wi-Fi, with the keys in $home/latch.env.
. "$home/latch.env"
echo "Owner key (the Latch app asks for it once):"
echo "    \$LATCH_ADMIN_TOKEN"
echo "AI app key (Claude Code: claude mcp add --transport http latch http://127.0.0.1:8787/mcp --header \"Authorization: Bearer \$LATCH_MCP_TOKEN\"):"
echo "    \$LATCH_MCP_TOKEN"
echo "Mirror page on this computer: http://127.0.0.1:8787/mirror"
echo
if [ -x "$bin_dir/latch-autostart" ] && [ "\$("$bin_dir/latch-autostart" status 2>/dev/null | cut -c1-2)" = "on" ] || [ "\$("$bin_dir/latch-autostart" status 2>/dev/null)" = "active" ]; then
  echo "The gateway already runs in the background (latch-autostart). Address for the phone:"
  grep -A1 "Phones on this Wi-Fi" "$home/gateway.log" 2>/dev/null | tail -1 || true
  exit 0
fi
exec "$bin_dir/latch-gateway" serve
EOF
chmod 755 "$bin_dir/latch-start"

cat >"$bin_dir/latch-autostart" <<EOF
#!/bin/sh
# Keeps the Latch gateway running in the background, also after a restart or log-in.
#   latch-autostart on | off | status
set -eu
label=io.github.aspershupadhyay.latch-gateway
log="$home/gateway.log"
case "\$(uname -s)" in
  Darwin)
    plist="\$HOME/Library/LaunchAgents/\$label.plist"
    case "\${1:-status}" in
      on)
        mkdir -p "\$HOME/Library/LaunchAgents"
        cat >"\$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>\$label</string>
  <key>ProgramArguments</key><array><string>/bin/sh</string><string>-c</string><string>. "$home/latch.env" &amp;&amp; exec "$bin_dir/latch-gateway" serve</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>\$log</string>
  <key>StandardOutPath</key><string>\$log</string>
</dict></plist>
PLIST
        launchctl bootout "gui/\$(id -u)/\$label" 2>/dev/null || true
        launchctl bootstrap "gui/\$(id -u)" "\$plist"
        echo "The gateway now runs in the background and starts when you log in. Log: \$log" ;;
      off)
        launchctl bootout "gui/\$(id -u)/\$label" 2>/dev/null || true
        rm -f "\$plist"
        echo "The gateway no longer runs in the background." ;;
      status)
        if launchctl print "gui/\$(id -u)/\$label" >/dev/null 2>&1; then echo "on (log: \$log)"; else echo off; fi ;;
      *) echo "usage: latch-autostart on|off|status" >&2; exit 2 ;;
    esac ;;
  *)
    unit="\$HOME/.config/systemd/user/latch-gateway.service"
    case "\${1:-status}" in
      on)
        mkdir -p "\$HOME/.config/systemd/user"
        cat >"\$unit" <<UNIT
[Unit]
Description=Latch gateway
After=network-online.target

[Service]
ExecStart=/bin/sh -c '. "$home/latch.env" && exec "$bin_dir/latch-gateway" serve'
Restart=always
RestartSec=3
StandardOutput=append:\$log
StandardError=append:\$log

[Install]
WantedBy=default.target
UNIT
        systemctl --user daemon-reload
        systemctl --user enable --now latch-gateway.service
        echo "The gateway now runs in the background. Log: \$log"
        echo "To keep it running while you are logged out: loginctl enable-linger \$USER" ;;
      off)
        systemctl --user disable --now latch-gateway.service 2>/dev/null || true
        rm -f "\$unit"
        systemctl --user daemon-reload
        echo "The gateway no longer runs in the background." ;;
      status) systemctl --user is-active latch-gateway.service || true ;;
      *) echo "usage: latch-autostart on|off|status" >&2; exit 2 ;;
    esac ;;
esac
EOF
chmod 755 "$bin_dir/latch-autostart"

echo
echo "Installed $("$bin_dir/latch-gateway" --version) in $bin_dir"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "Add it to your PATH:  echo 'export PATH=\"$bin_dir:\$PATH\"' >> ~/.zshrc && export PATH=\"$bin_dir:\$PATH\"" ;;
esac
echo "Start it with:  latch-start"
echo "Or keep it running in the background, also after a restart:  latch-autostart on"
