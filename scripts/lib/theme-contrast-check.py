#!/usr/bin/env python3
"""Check generated semantic theme pairs for minimum WCAG contrast."""

import argparse
import configparser
import re
from pathlib import Path


def rgb(value: str):
    parts = [int(part.strip()) for part in value.split(",")]
    if len(parts) != 3 or any(part < 0 or part > 255 for part in parts):
        raise ValueError(value)
    return tuple(parts)


def luminance(color):
    channels = []
    for value in color:
        normalized = value / 255
        channels.append(
            normalized / 12.92
            if normalized <= 0.04045
            else ((normalized + 0.055) / 1.055) ** 2.4
        )
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(first, second):
    high, low = sorted((luminance(first), luminance(second)), reverse=True)
    return (high + 0.05) / (low + 0.05)


def require(label, first, second, minimum):
    ratio = contrast(first, second)
    print(f"{label}={ratio:.2f}:1")
    if ratio < minimum:
        raise SystemExit(f"{label} contrast {ratio:.2f}:1 is below {minimum}:1")


def check_kde(path: Path):
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    parser.optionxform = str
    parser.read(path)
    for section in ("Colors:Window", "Colors:View", "Colors:Button", "Colors:Tooltip"):
        require(
            section,
            rgb(parser[section]["ForegroundNormal"]),
            rgb(parser[section]["BackgroundNormal"]),
            4.5,
        )
        require(
            section + "/inactive",
            rgb(parser[section]["ForegroundInactive"]),
            rgb(parser[section]["BackgroundNormal"]),
            3.0,
        )
    section = parser["Colors:Selection"]
    require(
        "Colors:Selection",
        rgb(section["ForegroundNormal"]),
        rgb(section["BackgroundNormal"]),
        3.0,
    )


def check_gtk(path: Path):
    text = path.read_text(errors="replace")
    accent = re.search(r"@define-color accent_bg_color rgb\((\d+),(\d+),(\d+)\);", text)
    foreground = re.search(r"@define-color accent_fg_color rgb\((\d+),(\d+),(\d+)\);", text)
    if accent is None and foreground is None:
        print("GTK accent contrast: toolkit theme owns non-Material colors")
        return
    if accent is None or foreground is None:
        raise SystemExit("GTK Material accent foreground/background pair is incomplete")
    require(
        "GTK Material accent",
        tuple(map(int, foreground.groups())),
        tuple(map(int, accent.groups())),
        3.0,
    )


def check_foot(path: Path):
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    parser.read(path)
    theme = parser["main"].get("initial-color-theme")
    if theme not in ("dark", "light"):
        raise SystemExit("Foot initial color theme is missing")
    for variant in ("dark", "light"):
        section = parser[f"colors-{variant}"]
        require(f"Foot {variant} terminal", rgb_hex(section["foreground"]),
                rgb_hex(section["background"]), 4.5)
        require(f"Foot {variant} selection", rgb_hex(section["selection-foreground"]),
                rgb_hex(section["selection-background"]), 3.0)


def rgb_hex(value: str):
    value = value.strip().lstrip("#")
    if not re.fullmatch(r"[0-9a-fA-F]{6}", value):
        raise ValueError(value)
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("kde", "gtk-accent", "foot"))
    parser.add_argument("file", type=Path)
    args = parser.parse_args()
    if args.mode == "kde":
        check_kde(args.file)
    elif args.mode == "gtk-accent":
        check_gtk(args.file)
    else:
        check_foot(args.file)


if __name__ == "__main__":
    main()
