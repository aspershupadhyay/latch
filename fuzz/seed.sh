#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
# Seeds each fuzz target's corpus with the shared protocol fixtures, so the
# fuzzer starts from real messages. Usage: fuzz/seed.sh (from anywhere).
set -euo pipefail
cd "$(dirname "$0")"
fixtures=../packages/schemas/v1/fixtures
mkdir -p corpus/gateway_message corpus/device_message corpus/policy
cp "$fixtures"/*/g2d-*.json corpus/gateway_message/
cp "$fixtures"/*/d2g-*.json corpus/device_message/
# policy: four switch/flag bytes (everything on, session live), then a command.
for f in "$fixtures"/valid/g2d-command-*.json; do
  python3 -c 'import json,sys; sys.stdout.buffer.write(b"\xff\xff\xff\x01" + json.dumps(json.load(open(sys.argv[1]))["command"]).encode())' \
    "$f" > "corpus/policy/$(basename "$f")"
done
