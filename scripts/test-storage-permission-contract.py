#!/usr/bin/env python3
"""Reject broad Android storage permissions in source, APKs, and devices."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
FORBIDDEN = frozenset(
    {
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    }
)
MANIFESTS = tuple(sorted((ROOT / "android").glob("*/src/*/AndroidManifest.xml")))


def run(command: list[str]) -> str:
    return subprocess.run(
        command,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ).stdout


def reject(context: str, permissions: set[str]) -> None:
    unsafe = sorted(permissions & FORBIDDEN)
    if unsafe:
        raise SystemExit(f"{context} requests broad storage: {', '.join(unsafe)}")


def check_source() -> None:
    if not MANIFESTS:
        raise SystemExit("no production Android manifests found")
    for manifest in MANIFESTS:
        root = ET.parse(manifest).getroot()
        requested = {
            name
            for node in root.findall("uses-permission")
            if (name := node.get(ANDROID_NAME)) is not None
        }
        reject(str(manifest.relative_to(ROOT)), requested)


def android_tool(name: str) -> str:
    direct = shutil.which(name)
    if direct:
        return direct
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise SystemExit(f"{name} is unavailable and no Android SDK is configured")
    candidates = sorted(
        (Path(sdk) / "build-tools").glob(f"*/{name}"),
        reverse=True,
    )
    if not candidates:
        candidate = Path(sdk) / "platform-tools" / name
        candidates = [candidate] if candidate.is_file() else []
    if not candidates:
        raise SystemExit(f"{name} is unavailable in {sdk}")
    return str(candidates[0])


def check_apks(apks: list[Path]) -> None:
    if not apks:
        return
    aapt2 = android_tool("aapt2")
    for apk in apks:
        if not apk.is_file():
            raise SystemExit(f"APK does not exist: {apk}")
        output = run([aapt2, "dump", "permissions", str(apk)])
        requested = {
            line.split("name='", 1)[1].split("'", 1)[0]
            for line in output.splitlines()
            if line.startswith("uses-permission: name='")
        }
        reject(str(apk), requested)


def requested_permissions(package_dump: str) -> set[str]:
    lines = package_dump.splitlines()
    start = next(
        (
            index
            for index, line in enumerate(lines)
            if line.strip() == "requested permissions:"
        ),
        None,
    )
    if start is None:
        return set()
    result: set[str] = set()
    for line in lines[start + 1 :]:
        stripped = line.strip()
        if stripped.endswith("permissions:") or stripped.endswith(":"):
            break
        if stripped:
            result.add(stripped)
    return result


def check_devices(serials: list[str]) -> None:
    if not serials:
        return
    adb = android_tool("adb")
    for serial in serials:
        packages = {
            line.removeprefix("package:").strip()
            for line in run([adb, "-s", serial, "shell", "pm", "list", "packages"]).splitlines()
            if line.startswith("package:")
        }
        manager = "org.archphene.app.debug"
        if manager not in packages:
            raise SystemExit(f"{serial} does not have {manager} installed")
        targets = sorted(
            package
            for package in packages
            if package in {manager, "org.archphene.app", "org.archphene.builder.debug",
                           "org.archphene.builder"}
            or package.startswith("org.archphene.linux.")
        )
        for package in targets:
            package_dump = run([adb, "-s", serial, "shell", "dumpsys", "package", package])
            reject(f"{serial}:{package}", requested_permissions(package_dump))
        print(f"{serial}: {len(targets)} Archphene packages use scoped storage")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", action="append", default=[], type=Path)
    parser.add_argument("--serial", action="append", default=[])
    arguments = parser.parse_args()

    check_source()
    check_apks(arguments.apk)
    check_devices(arguments.serial)
    print(f"{len(MANIFESTS)} Android source manifests reject broad storage")


if __name__ == "__main__":
    main()
