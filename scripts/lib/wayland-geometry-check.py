#!/usr/bin/env python3
"""Validate finalized Archphene toplevel and popup frames against the output."""

import argparse
import re
from pathlib import Path


OUTPUT = re.compile(r"output frame=(\d+)x(\d+)")
WINDOW = re.compile(
    r"window id=(\d+).*?mapped=true .*?"
    r"geometry=(-?\d+),(-?\d+) (\d+)x(\d+).*?"
    r"compositedFrame=(-?\d+),(-?\d+) (\d+)x(\d+).*?"
    r"title=(.*?) appId="
)
POPUP = re.compile(r"(\d+):(-?\d+),(-?\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)")
PRESENTED = re.compile(
    r"Presented Linux frame .*?"
    r"selected=(\d+)x(\d+) surface=(\d+)x(\d+) "
    r"original=(\d+)x(\d+) logical=(\d+)x(\d+).*?"
    r"output=(\d+)x(\d+) mode=(\d+)x(\d+).*?"
    r"geometry=(-?\d+),(-?\d+) (\d+)x(\d+) "
    r"root=(-?\d+),(-?\d+) (\d+)x(\d+) "
    r"content=(-?\d+),(-?\d+) (\d+)x(\d+)"
)
PRESENTED_REASONS = re.compile(
    r"Presented Linux frame .*?reasons=(\d+),(\d+)"
)


def contained(label: str, x: int, y: int, width: int, height: int,
              output_width: int, output_height: int) -> None:
    if width <= 0 or height <= 0:
        return
    if x < 0 or y < 0 or x + width > output_width or y + height > output_height:
        raise SystemExit(
            f"{label} is outside {output_width}x{output_height}: "
            f"{x},{y} {width}x{height}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path)
    parser.add_argument("--require-title")
    parser.add_argument("--require-popup", action="store_true")
    args = parser.parse_args()
    text = args.log.read_text(errors="replace")
    outputs = OUTPUT.findall(text)
    if not outputs:
        validate_presented_frames(text, args.require_title, args.require_popup)
        return
    output_width, output_height = map(int, outputs[-1])

    # A client can map once at the previous output size and immediately commit
    # its resized buffer. Only its final logged state is evidence for the
    # settled frame. GTK and Foot also use CSD extents outside the content:
    # validate the interactive content rectangle, not the clipped shadow/title
    # frame surrounding it.
    final_windows = {}
    for match in WINDOW.finditer(text):
        final_windows[match.group(1)] = match
    windows = 0
    title_found = args.require_title is None
    for match in final_windows.values():
        geometry_x, geometry_y, width, height = map(int, match.groups()[1:5])
        frame_x, frame_y = map(int, match.groups()[5:7])
        title = match.group(10)
        if args.require_title and args.require_title in title:
            title_found = True
        if width > 0 and height > 0:
            contained(f"window content {title!r}",
                      frame_x + geometry_x, frame_y + geometry_y,
                      width, height,
                      output_width, output_height)
            windows += 1
    if not windows:
        raise SystemExit("no finalized mapped window frame found")
    if not title_found:
        raise SystemExit(f"mapped window title does not contain {args.require_title!r}")

    finalized_popups = 0
    for line in text.splitlines():
        if "popup registry=" not in line:
            continue
        for match in POPUP.finditer(line.split("popup registry=", 1)[1]):
            _, x, y, width, height, buffer_width, buffer_height, _, _ = map(
                int, match.groups()
            )
            if buffer_width <= 0 or buffer_height <= 0:
                continue
            contained("popup", x, y, width, height, output_width, output_height)
            finalized_popups += 1
    if args.require_popup and not finalized_popups:
        raise SystemExit("no finalized popup frame found")
    print(
        f"output={output_width}x{output_height} mapped_frames={windows} "
        f"finalized_popups={finalized_popups}"
    )


def validate_presented_frames(
    text: str, require_title: str | None, require_popup: bool
) -> None:
    """Validate the current manager's bounded presentation diagnostic."""
    matches = list(PRESENTED.finditer(text))
    if not matches:
        raise SystemExit("no compositor output frame found")
    if require_title is not None:
        raise SystemExit("current presentation diagnostic does not include a title")
    popup_presented = any(
        int(match.group(2)) & (1 << 1)
        for match in PRESENTED_REASONS.finditer(text)
    )
    if require_popup and not popup_presented:
        raise SystemExit("current presentation diagnostic does not include popup state")

    values = list(map(int, matches[-1].groups()))
    (
        selected_width, selected_height,
        surface_width, surface_height,
        original_width, original_height,
        logical_width, logical_height,
        output_width, output_height,
        mode_width, mode_height,
        geometry_x, geometry_y, geometry_width, geometry_height,
        root_x, root_y, root_width, root_height,
        content_x, content_y, content_width, content_height,
    ) = values
    dimensions = [
        selected_width, selected_height, surface_width, surface_height,
        original_width, original_height, logical_width, logical_height,
        output_width, output_height, mode_width, mode_height,
    ]
    if any(value <= 0 for value in dimensions):
        raise SystemExit("presentation diagnostic contains an empty dimension")
    selected_size = (selected_width, selected_height)
    if popup_presented:
        if selected_size != (surface_width, surface_height):
            raise SystemExit("composited popup presentation does not match Android surface")
    elif selected_size != (original_width, original_height):
        raise SystemExit("selected presentation buffer does not match its source")
    if (surface_width, surface_height) != (mode_width, mode_height):
        raise SystemExit("Android surface does not match the advertised output mode")
    if (logical_width, logical_height) != (output_width, output_height):
        raise SystemExit("logical client size does not match the Wayland output")

    contained(
        "window geometry", geometry_x, geometry_y, geometry_width, geometry_height,
        logical_width, logical_height,
    )
    contained(
        "root frame", root_x, root_y, root_width, root_height,
        logical_width, logical_height,
    )
    contained(
        "content frame", content_x, content_y, content_width, content_height,
        logical_width, logical_height,
    )
    print(
        f"output={logical_width}x{logical_height} mapped_frames=1 "
        f"finalized_popups={int(popup_presented)}"
    )


if __name__ == "__main__":
    main()
