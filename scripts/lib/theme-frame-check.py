#!/usr/bin/env python3
"""Compare Linux-app pixels in two raw Android screencap frames."""

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


def compare(first_path: Path, second_path: Path, left_percent=5, top_percent=15,
            right_percent=95, bottom_percent=85):
    width, height, first = frame(first_path)
    other_width, other_height, second = frame(second_path)
    if (width, height) != (other_width, other_height):
        raise SystemExit("theme frames have different dimensions")

    # Exclude Android status/navigation bars and the outermost edge. The
    # compositor-backed Linux surface occupies the center of every wrapper.
    left, right = width * left_percent // 100, width * right_percent // 100
    top, bottom = height * top_percent // 100, height * bottom_percent // 100
    first_luma = second_luma = difference = changed = samples = 0
    for y in range(top, bottom, 2):
        row = y * width * 4
        for x in range(left, right, 2):
            index = row + x * 4
            fr, fg, fb = first[index], first[index + 1], first[index + 2]
            sr, sg, sb = second[index], second[index + 1], second[index + 2]
            first_luma += 299 * fr + 587 * fg + 114 * fb
            second_luma += 299 * sr + 587 * sg + 114 * sb
            pixel_difference = (abs(fr - sr) + abs(fg - sg) + abs(fb - sb)) / 3
            difference += pixel_difference
            changed += pixel_difference >= 20
            samples += 1
    return (
        first_luma / samples / 1000,
        second_luma / samples / 1000,
        difference / samples,
        changed / samples,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "mode", choices=("light-dark", "different", "similar", "inspect")
    )
    parser.add_argument("first", type=Path)
    parser.add_argument("second", type=Path, nargs="?")
    parser.add_argument("--minimum-luma-delta", type=float, default=40)
    parser.add_argument("--minimum-difference", type=float, default=2)
    parser.add_argument("--minimum-changed-ratio", type=float, default=0.2)
    parser.add_argument("--maximum-difference", type=float, default=1.5)
    parser.add_argument("--maximum-changed-ratio", type=float, default=0.05)
    parser.add_argument("--left-percent", type=int, default=5)
    parser.add_argument("--top-percent", type=int, default=15)
    parser.add_argument("--right-percent", type=int, default=95)
    parser.add_argument("--bottom-percent", type=int, default=85)
    args = parser.parse_args()

    if not (0 <= args.left_percent < args.right_percent <= 100
            and 0 <= args.top_percent < args.bottom_percent <= 100):
        raise SystemExit("invalid comparison crop percentages")
    if args.mode == "inspect":
        width, height, pixels = frame(args.first)
        left = width * args.left_percent // 100
        right = width * args.right_percent // 100
        top = height * args.top_percent // 100
        bottom = height * args.bottom_percent // 100
        values = []
        for y in range(top, bottom, 2):
            row = y * width * 4
            for x in range(left, right, 2):
                index = row + x * 4
                values.append(
                    (299 * pixels[index]
                     + 587 * pixels[index + 1]
                     + 114 * pixels[index + 2]) / 1000
                )
        mean = sum(values) / len(values)
        variance = sum((value - mean) ** 2 for value in values) / len(values)
        print(
            f"luma={mean:.1f} deviation={variance ** 0.5:.1f} "
            f"range={max(values) - min(values):.1f}"
        )
        if variance ** 0.5 < 5:
            raise SystemExit("Linux-app surface is blank or visually incomplete")
        return
    if args.second is None:
        parser.error(f"{args.mode} requires a second frame")

    first_luma, second_luma, difference, changed = compare(
        args.first, args.second, args.left_percent, args.top_percent,
        args.right_percent, args.bottom_percent)
    print(
        f"first_luma={first_luma:.1f} second_luma={second_luma:.1f} "
        f"mean_difference={difference:.1f} changed_ratio={changed:.3f}"
    )
    if args.mode == "light-dark":
        if first_luma - second_luma < args.minimum_luma_delta:
            raise SystemExit("Linux-app pixels did not change from light to dark")
    elif difference < args.minimum_difference:
        if args.mode == "different":
            raise SystemExit("Linux-app pixels did not visibly change")
    if args.mode == "similar":
        if difference > args.maximum_difference:
            raise SystemExit("Linux-app frames differ beyond the allowed tolerance")
        if changed > args.maximum_changed_ratio:
            raise SystemExit("too much of the Linux-app surface changed")
    elif changed < args.minimum_changed_ratio:
        raise SystemExit("too little of the Linux-app surface changed")


if __name__ == "__main__":
    main()
