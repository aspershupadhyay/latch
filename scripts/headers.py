#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
"""Every source file carries the license and copyright lines NOTICE requires.

  scripts/headers.py          add missing headers
  scripts/headers.py --check  fail if any file lacks them (CI)
"""
import subprocess
import sys

SPDX = "SPDX-License-Identifier: AGPL-3.0-only"
COPY = "Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors"
STYLES = {
    "//": (".rs", ".kt", ".kts", ".ts", ".mjs", ".js", ".swift"),
    "#": (".sh", ".py", ".yml", ".yaml", ".toml"),
    "css": (".css",),
    "html": (".html",),
}
# Third-party or generated files keep their own headers.
SKIP = ("apps/android/gradlew", "/generated/", "node_modules/", "package-lock.json", "Cargo.lock")


def style(path):
    for s, exts in STYLES.items():
        if path.endswith(exts):
            return s
    return None


def header(s):
    if s == "css":
        return f"/* {SPDX}\n   {COPY} */\n"
    if s == "html":
        return f"<!-- {SPDX} · {COPY} -->\n"
    return f"{s} {SPDX}\n{s} {COPY}\n"


def files():
    out = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.split()
    return [f for f in out if style(f) and not any(k in f for k in SKIP)]


def main():
    check = "--check" in sys.argv
    missing = []
    for path in files():
        text = open(path, encoding="utf-8").read()
        if SPDX in text[:400]:
            continue
        missing.append(path)
        if check:
            continue
        s = style(path)
        lines = text.splitlines(keepends=True)
        # Keep a shebang or doctype first.
        keep = 1 if lines and (lines[0].startswith("#!") or lines[0].lower().startswith("<!doctype")) else 0
        open(path, "w", encoding="utf-8").write("".join(lines[:keep]) + header(s) + "".join(lines[keep:]))
    if check and missing:
        print("missing license headers (run scripts/headers.py):\n  " + "\n  ".join(missing), file=sys.stderr)
        sys.exit(1)
    print(f"{'checked' if check else 'updated'}: {len(missing)} file(s) {'missing' if check else 'given'} headers")


if __name__ == "__main__":
    main()
