#!/usr/bin/env python3
"""Validate a real Linux application's Android accessibility tree."""

import argparse
import re
from pathlib import Path


BOUNDS = re.compile(r"(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tree", type=Path)
    parser.add_argument("--expected-text", required=True)
    parser.add_argument("--display-width", type=int, required=True)
    parser.add_argument("--display-height", type=int, required=True)
    parser.add_argument("--minimum-nodes", type=int, default=5)
    args = parser.parse_args()
    text = args.tree.read_text(errors="replace")
    nodes = []
    for line in text.splitlines():
        if not line.startswith("NODE|"):
            continue
        fields = line.split("|")
        if len(fields) < 11:
            raise SystemExit("accessibility node uses the obsolete incomplete schema")
        bounds = BOUNDS.fullmatch(fields[5].strip())
        if not bounds:
            raise SystemExit(f"invalid accessibility bounds: {fields[5]!r}")
        left, top, right, bottom = map(int, bounds.groups())
        if right <= left or bottom <= top:
            raise SystemExit(f"empty accessibility bounds: {fields[5]}")
        if left < 0 or top < 0 or right > args.display_width or bottom > args.display_height:
            raise SystemExit(f"accessibility node is outside the display: {fields[5]}")
        if fields[6] not in ("true", "false"):
            raise SystemExit("accessibility node is missing enabled state")
        nodes.append(fields)
    if len(nodes) < args.minimum_nodes:
        raise SystemExit(f"only {len(nodes)} real accessibility nodes were exported")
    folded = text.casefold()
    if args.expected_text.casefold() not in folded:
        raise SystemExit(f"tree does not identify {args.expected_text!r}")
    interactive = sum(
        fields[7] == "true" or fields[8] == "true" or fields[10] != "0"
        for fields in nodes
    )
    if interactive < 1:
        raise SystemExit("tree contains no actionable Linux control")
    print(f"nodes={len(nodes)} interactive={interactive} expected={args.expected_text}")


if __name__ == "__main__":
    main()
