#!/usr/bin/env bash
# Interop check: the official MCP TypeScript SDK client drives a fresh gateway
# and fake phone through observe -> launch -> tap.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo build -q -p latch-gateway -p latch-fake-device
bin=target/debug
data=$(mktemp -d)
export LATCH_ADMIN_TOKEN=$($bin/latch-gateway gen-token) LATCH_MCP_TOKEN=$($bin/latch-gateway gen-token)
export LATCH_BIND=127.0.0.1:${LATCH_INTEROP_PORT:-8799} LATCH_DATA_DIR=$data
base="http://$LATCH_BIND"
$bin/latch-gateway serve 2>"$data/gateway.log" &
gateway=$!
trap 'kill $gateway ${fake:-} 2>/dev/null || true; rm -rf "$data"' EXIT
for _ in $(seq 1 50); do curl -fs "$base/healthz" >/dev/null 2>&1 && break; sleep 0.1; done
code=$(curl -fs -X POST "$base/v1/admin/pairings" -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Interop"}' | sed -E 's/.*"code":"([^"]+)".*/\1/')
$bin/latch-fake-device "$base" "$code" 2>/dev/null &
fake=$!
for _ in $(seq 1 50); do curl -fs "$base/v1/admin/devices" -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" | grep -q '"connected":true' && break; sleep 0.1; done
(cd tests/interop && npm ci --silent --no-audit --no-fund && LATCH_URL="$base" node mcp-sdk-client.mjs)
