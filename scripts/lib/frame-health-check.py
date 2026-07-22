#!/usr/bin/env python3
"""Reject blank or effectively uniform raw Android screencap evidence."""

import argparse
import struct
from pathlib import Path


def frame(path: Path):
    data = path.read_bytes()
    if len(data) < 12:
        raise SystemExit(f"{path}: truncated screencap")
    width, height, pixel_format = struct.unpack_from("<III", data)
    expected = width * height * 4
    offset = 16 if len(data) == expected + 16 else 12
    if len(data) != expected + offset or pixel_format != 1:
        raise SystemExit(f"{path}: unsupported screencap encoding")
    return width, height, memoryview(data)[offset:]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("frame", type=Path)
    parser.add_argument("--minimum-colors", type=int, default=16)
    parser.add_argument("--minimum-luma-range", type=int, default=20)
    parser.add_argument("--left-percent", type=int, default=5)
    parser.add_argument("--top-percent", type=int, default=10)
    parser.add_argument("--right-percent", type=int, default=95)
    parser.add_argument("--bottom-percent", type=int, default=90)
    args = parser.parse_args()
    if not (0 <= args.left_percent < args.right_percent <= 100
            and 0 <= args.top_percent < args.bottom_percent <= 100):
        raise SystemExit("invalid frame-health crop percentages")
    width, height, pixels = frame(args.frame)
    colors = set()
    lumas = []
    top = height * args.top_percent // 100
    bottom = height * args.bottom_percent // 100
    left = width * args.left_percent // 100
    right = width * args.right_percent // 100
    for y in range(top, bottom, 4):
        row = y * width * 4
        for x in range(left, right, 4):
            index = row + x * 4
            red, green, blue = pixels[index:index + 3]
            colors.add((red // 16, green // 16, blue // 16))
            lumas.append((299 * red + 587 * green + 114 * blue) // 1000)
    lumas.sort()
    low = lumas[len(lumas) // 20]
    high = lumas[len(lumas) * 19 // 20]
    print(
        f"frame={width}x{height} quantized_colors={len(colors)} "
        f"luma_5_95={low}..{high}"
    )
    if len(colors) < args.minimum_colors:
        raise SystemExit("Linux-app evidence is effectively uniform")
    if high - low < args.minimum_luma_range:
        raise SystemExit("Linux-app evidence has insufficient visual structure")


if __name__ == "__main__":
    main()
