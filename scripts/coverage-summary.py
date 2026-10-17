#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
"""Prints line and branch coverage from a JaCoCo XML report, overall and by
package, as a Markdown table (R29 e), and fails when line coverage falls
below --min-lines (a ratchet against regressions, not a target).
Usage: coverage-summary.py report.xml [--min-lines 25]"""
import sys
import xml.etree.ElementTree as ET


def counters(node):
    found = {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in node.findall("counter")}
    return found.get("LINE", (0, 0)), found.get("BRANCH", (0, 0))


def pct(covered_missed):
    covered, missed = covered_missed
    total = covered + missed
    return f"{100 * covered / total:.1f}%" if total else "n/a"


root = ET.parse(sys.argv[1]).getroot()
minimum = float(sys.argv[sys.argv.index("--min-lines") + 1]) if "--min-lines" in sys.argv else 0.0
line, branch = counters(root)
overall = line
print("## Android unit-test coverage (JaCoCo)\n")
print(f"**Lines {pct(line)}** ({line[0]} of {sum(line)}), **branches {pct(branch)}** ({branch[0]} of {sum(branch)})\n")
print("| Package | Lines | Branches |\n|---|---|---|")
for package in sorted(root.findall("package"), key=lambda p: p.get("name")):
    line, branch = counters(package)
    name = package.get("name").replace("io/github/aspershupadhyay/latch", "latch").replace("/", ".")
    print(f"| {name} | {pct(line)} | {pct(branch)} |")
covered, missed = overall
if covered + missed and 100 * covered / (covered + missed) < minimum:
    print(f"\nLine coverage fell below {minimum}%.", file=sys.stderr)
    sys.exit(1)
