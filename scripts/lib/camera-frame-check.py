#!/usr/bin/env python3
"""Reject the GTK/GSK solid-magenta camera presentation regression."""

import argparse
import struct
from pathlib import Path


def frame(path: Path):
    data = path.read_bytes()
    if len(data) < 12:
        raise SystemExit(f"{path}: truncated screencap")
    width, height, pixel_format = struct.unpack_from("<III", data)
    expected = width * height * 4
    if len(data) == expected + 16:
        offset = 16
    elif len(data) == expected + 12:
        offset = 12
    else:
        raise SystemExit(
            f"{path}: expected {expected} RGBA bytes for {width}x{height}, "
            f"found {len(data)} total bytes"
        )
    if pixel_format != 1:
        raise SystemExit(f"{path}: unsupported screencap pixel format {pixel_format}")
    return width, height, memoryview(data)[offset:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("frame", type=Path)
    parser.add_argument("--maximum-magenta-ratio", type=float, default=0.10)
    args = parser.parse_args()
    if not 0 <= args.maximum_magenta_ratio < 1:
        raise SystemExit("--maximum-magenta-ratio must be in [0, 1)")

    width, height, pixels = frame(args.frame)
    left, right = width * 5 // 100, width * 95 // 100
    top, bottom = height * 15 // 100, height * 85 // 100
    magenta = samples = 0
    minimum_luma = 255
    maximum_luma = 0
    for y in range(top, bottom, 2):
        row = y * width * 4
        for x in range(left, right, 2):
            index = row + x * 4
            red, green, blue = pixels[index:index + 3]
            magenta += (
                red >= 220
                and green <= 48
                and blue >= 190
                and red - green >= 160
                and blue - green >= 140
            )
            luma = (299 * red + 587 * green + 114 * blue) // 1000
            minimum_luma = min(minimum_luma, luma)
            maximum_luma = max(maximum_luma, luma)
            samples += 1

    ratio = magenta / samples
    print(
        f"camera_frame={width}x{height} magenta_ratio={ratio:.5f} "
        f"luma_range={minimum_luma}..{maximum_luma}"
    )
    if ratio > args.maximum_magenta_ratio:
        raise SystemExit("camera application frame contains a dominant hot-magenta surface")


if __name__ == "__main__":
    main()
