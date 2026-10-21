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
# plus `latch-start`, which starts the gateway for phones on this Wi-Fi and shows
# a QR code the Latch app scans (no key to copy; `latch-gateway pair` shows a new
# one while it runs), and
# `latch-autostart on|off|status`, which keeps it running in the background
# (a LaunchAgent on macOS, a systemd user service on Linux) so it need not be
# started by hand. The address and pin stay the same across restarts; if the
# computer gets a new address on the Wi-Fi, the app finds it again by its pin.
# `latch` wraps it all: latch start | pair | status | update | uninstall | logs.
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
# It shows a QR code: open Latch on the phone and tap "Scan the QR code on my computer".
. "$home/latch.env"
echo
echo "  AI app setup, once (Claude Code):"
echo "    claude mcp add --transport http latch http://127.0.0.1:8787/mcp --header \"Authorization: Bearer \$LATCH_MCP_TOKEN\""
if [ -x "$bin_dir/latch-autostart" ] && { [ "\$("$bin_dir/latch-autostart" status 2>/dev/null | cut -c1-2)" = "on" ] || [ "\$("$bin_dir/latch-autostart" status 2>/dev/null)" = "active" ]; }; then
  echo
  echo "  The gateway already runs in the background (latch-autostart)."
  exec "$bin_dir/latch-gateway" pair
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

# `latch`: one friendly command for everything above, plus update and uninstall.
cat >"$tmp/latch" <<'LATCH'
#!/bin/sh
# Latch gateway on this computer. Run `latch help` for the commands.
set -eu
bin="@BIN@"
home="@HOME@"
release="@RELEASE@"
label=io.github.aspershupadhyay.latch-gateway
env_file="$home/latch.env"
load() { [ -f "$env_file" ] && . "$env_file"; }

background_on() {
  case "$(uname -s)" in
    Darwin) [ -f "$HOME/Library/LaunchAgents/$label.plist" ] ;;
    *) [ -f "$HOME/.config/systemd/user/latch-gateway.service" ] ;;
  esac
}

stop_all() {
  case "$(uname -s)" in
    Darwin) launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true ;;
    *) systemctl --user stop latch-gateway.service 2>/dev/null || true ;;
  esac
  # One started by `latch start` in a terminal, too.
  pkill -f "$bin/latch-gateway serve" 2>/dev/null || true
}

start_background() {
  case "$(uname -s)" in
    Darwin) launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/$label.plist" 2>/dev/null || true ;;
    *) systemctl --user start latch-gateway.service ;;
  esac
}

usage() {
  cat <<USAGE
latch — the Latch gateway on this computer

  latch start            Start it here and show the QR code for your phone
  latch pair [--open]    A new QR code (--open: big, in the browser); also approves
                         phones that use Find my computer
  latch usb [--watch]    Phone on a USB cable: the lowest mirror delay (needs adb, USB debugging)
  latch status           Running? Version, phones, Wi-Fi address
  latch autostart on|off Keep it running in the background, also after a restart
  latch stop             Stop it (background and terminal)
  latch restart          Stop and start the background gateway again
  latch logs             Follow the background gateway's log
  latch update           Install the newest beta, keeping your keys and phones
  latch uninstall [--all]  Remove the programs (--all: also keys and paired phones)
  latch version          The installed version
USAGE
}

cmd="${1:-help}"
[ $# -gt 0 ] && shift
case "$cmd" in
  start) exec "$bin/latch-start" "$@" ;;
  pair) load; exec "$bin/latch-gateway" pair "$@" ;;
  usb) load; exec "$bin/latch-gateway" usb "$@" ;;
  status) load; exec "$bin/latch-gateway" status ;;
  autostart) exec "$bin/latch-autostart" "$@" ;;
  stop) stop_all; echo "Stopped." ;;
  restart)
    stop_all
    if background_on; then start_background; echo "Restarted in the background."; else echo "Stopped. Start it with: latch start (or latch autostart on)"; fi ;;
  logs)
    if [ -f "$home/gateway.log" ]; then exec tail -n 100 -f "$home/gateway.log"; fi
    echo "No background log yet ($home/gateway.log). With latch start, the log is in that terminal." ;;
  update)
    echo "Updating from $release …"
    was_on=no
    background_on && was_on=yes
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL -o "$tmp/install-gateway.sh" "$release/install-gateway.sh"
    LATCH_INSTALL_DIR="$bin" LATCH_HOME="$home" LATCH_RELEASE="$release" sh "$tmp/install-gateway.sh"
    if [ "$was_on" = yes ]; then "$bin/latch-autostart" on >/dev/null && echo "The background gateway runs the new version."; fi
    echo "If latch start runs in a terminal, stop it (Ctrl+C) and start it again." ;;
  uninstall)
    all=no
    [ "${1:-}" = "--all" ] && all=yes
    if [ "$all" = no ] && [ -t 0 ] && [ -d "$home" ]; then
      printf "Also delete your keys and paired phones in %s? Phones must then be linked again. [y/N] " "$home"
      read -r answer || answer=
      case "$answer" in y | Y | yes | YES) all=yes ;; esac
    fi
    "$bin/latch-autostart" off >/dev/null 2>&1 || true
    stop_all
    rm -f "$bin/latch-gateway" "$bin/latch-start" "$bin/latch-autostart"
    if [ "$all" = yes ]; then rm -rf "$home"; echo "Removed the gateway, its keys, and its data."; else echo "Removed the gateway. Your keys and phones stay in $home (latch update or the installer brings it back)."; fi
    rm -f "$bin/latch"
    echo "Also remove Latch from your AI app, e.g.: claude mcp remove latch" ;;
  version | --version | -V) exec "$bin/latch-gateway" --version ;;
  help | --help | -h) usage ;;
  *) echo "unknown command: $cmd" >&2; usage >&2; exit 2 ;;
esac
LATCH
sed -e "s|@BIN@|$bin_dir|g" -e "s|@HOME@|$home|g" -e "s|@RELEASE@|$release|g" "$tmp/latch" >"$bin_dir/latch"
chmod 755 "$bin_dir/latch"

echo
echo "Installed $("$bin_dir/latch-gateway" --version) in $bin_dir"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "Add it to your PATH:  echo 'export PATH=\"$bin_dir:\$PATH\"' >> ~/.zshrc && export PATH=\"$bin_dir:\$PATH\"" ;;
esac
echo "Start it with:  latch start   (then in the Latch app: Scan the QR code, or Find my computer)"
echo "Or keep it running in the background, also after a restart:  latch autostart on"
echo "Everything else:  latch help   (status, update, uninstall, logs, …)"
