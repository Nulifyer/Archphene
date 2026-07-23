#!/usr/bin/env python3
"""Locate the middle output row in a focused Foot raw screencap."""

import argparse
import collections
import struct
from pathlib import Path


def load(path: Path):
    data = path.read_bytes()
    if len(data) < 12:
        raise SystemExit("truncated screencap")
    width, height, pixel_format = struct.unpack_from("<III", data)
    expected = width * height * 4
    offset = 16 if len(data) == expected + 16 else 12
    if len(data) != expected + offset or pixel_format != 1:
        raise SystemExit("unsupported screencap")
    return width, height, memoryview(data)[offset:]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("frame", type=Path)
    args = parser.parse_args()
    width, height, pixels = load(args.frame)
    left, right = width // 100, width * 95 // 100
    top, bottom = height * 10 // 100, height * 55 // 100
    colors = collections.Counter()
    for y in range(top, bottom, 4):
        row = y * width * 4
        for x in range(left, right, 4):
            index = row + x * 4
            colors[tuple(pixels[index:index + 3])] += 1
    background, _ = colors.most_common(1)[0]

    active = []
    for y in range(top, bottom):
        row = y * width * 4
        count = 0
        for x in range(left, right, 2):
            index = row + x * 4
            color = pixels[index:index + 3]
            if sum(abs(int(color[i]) - background[i]) for i in range(3)) >= 90:
                count += 1
        if count >= 3:
            active.append(y)

    groups = []
    for y in active:
        if not groups or y > groups[-1][-1] + 1:
            groups.append([y])
        else:
            groups[-1].append(y)
    groups = [group for group in groups if len(group) >= 5]
    if len(groups) < 3:
        raise SystemExit(f"expected three Foot text rows, found {len(groups)}")
    # After the indented `echo TOKEN`, the final three glyph groups are the
    # command, its output, and the next prompt. Select the output row.
    group = groups[-2]
    y = (group[0] + group[-1]) // 2
    xs = []
    for row_y in group:
        row = row_y * width * 4
        for x in range(left, right):
            index = row + x * 4
            color = pixels[index:index + 3]
            if sum(abs(int(color[i]) - background[i]) for i in range(3)) >= 90:
                xs.append(x)
    if not xs:
        raise SystemExit("Foot output row contains no selectable glyphs")
    x1 = max(left, min(xs) - 3)
    x2 = min(right - 1, max(xs) + 5)
    if x1 < max(32, width // 20):
        raise SystemExit("Foot output begins inside Android's gesture edge")
    if x2 - x1 < 20:
        raise SystemExit("Foot output selection is implausibly narrow")
    print(x1, y, x2, y)


if __name__ == "__main__":
    main()
