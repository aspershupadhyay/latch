#!/usr/bin/env bash
# Starts a local gateway and, optionally, a simulated phone, for trying Latch
# end to end without hardware (works in a cloud dev container too).
#
#   scripts/dev.sh            gateway + fake phone
#   scripts/dev.sh --no-fake  gateway only (pair a real phone from the console)
#
# Tokens are generated per run and printed once. State lives in ./latch-data.
set -euo pipefail
cd "$(dirname "$0")/.."

cargo build -q -p latch-gateway -p latch-fake-device
bin=target/debug
export LATCH_ADMIN_TOKEN="${LATCH_ADMIN_TOKEN:-$($bin/latch-gateway gen-token)}"
export LATCH_MCP_TOKEN="${LATCH_MCP_TOKEN:-$($bin/latch-gateway gen-token)}"
export LATCH_BIND="${LATCH_BIND:-127.0.0.1:8787}"
base="http://$LATCH_BIND"

$bin/latch-gateway serve &
gateway=$!
trap 'kill $gateway ${fake:-} 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do curl -fs "$base/healthz" >/dev/null 2>&1 && break; sleep 0.1; done

if [ "${1:-}" != "--no-fake" ]; then
  code=$(curl -fs -X POST "$base/v1/admin/pairings" \
    -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d '{"name":"Fake phone"}' | sed -E 's/.*"code":"([^"]+)".*/\1/')
  $bin/latch-fake-device "$base" "$code" &
  fake=$!
fi

cat <<INFO

  Latch gateway running at $base
  Owner console:   $base/          (admin token below)
  MCP endpoint:    $base/mcp       (MCP token below)

  LATCH_ADMIN_TOKEN=$LATCH_ADMIN_TOKEN
  LATCH_MCP_TOKEN=$LATCH_MCP_TOKEN

  Connect Claude Code:
    claude mcp add --transport http latch $base/mcp --header "Authorization: Bearer $LATCH_MCP_TOKEN"

  Ctrl-C to stop.
INFO
wait $gateway
