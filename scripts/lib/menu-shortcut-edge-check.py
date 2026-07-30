#!/usr/bin/env python3
"""Reject visible menu accelerator text clipped against the device edge."""

import argparse
import collections
import re
import struct
import xml.etree.ElementTree as ET
from pathlib import Path


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
    match = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
    return tuple(map(int, match.groups())) if match else None


def raw_frame(path: Path) -> tuple[int, int, memoryview]:
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
    parser.add_argument("ui", type=Path)
    parser.add_argument("--action", required=True)
    args = parser.parse_args()

    root = ET.fromstring(args.ui.read_text())
    action = next(
        (node for node in root.iter("node")
         if node.attrib.get("text") == args.action
         and node.attrib.get("clickable") == "true"),
        None,
    )
    action_bounds = bounds(action) if action is not None else None
    if action_bounds is None:
        raise SystemExit(f"accessible menu action {args.action!r} is missing")
    left, top, right, bottom = action_bounds
    row_height = bottom - top
    if row_height < 16 or right - left < row_height:
        raise SystemExit(f"menu action has invalid bounds: {action_bounds}")

    width, height, pixels = raw_frame(args.frame)
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise SystemExit("menu action is outside the full-device frame")

    # The final row-height-wide region contains only menu background after the
    # phone policy removes the accelerator. Exclude the outer border and the
    # vertical quarters where separators or focus decoration may be painted.
    crop_left = max(left, right - row_height)
    crop_right = right - max(3, row_height // 8)
    crop_top = top + row_height // 4
    crop_bottom = bottom - row_height // 4
    colors: list[tuple[int, int, int]] = []
    for y in range(crop_top, crop_bottom):
        row = y * width * 4
        for x in range(crop_left, crop_right):
            index = row + x * 4
            colors.append(tuple(pixels[index:index + 3]))
    if not colors:
        raise SystemExit("menu edge crop is empty")

    background, _ = collections.Counter(colors).most_common(1)[0]
    different = sum(
        max(abs(channel - base) for channel, base in zip(color, background)) >= 24
        for color in colors
    )
    allowance = max(16, len(colors) // 200)
    if different > allowance:
        raise SystemExit(
            f"menu accelerator reaches the device edge: "
            f"{different} contrasting pixels > {allowance}"
        )
    print(
        f"menu edge clear for {args.action!r}: "
        f"{different} contrasting pixels <= {allowance}"
    )


if __name__ == "__main__":
    main()
