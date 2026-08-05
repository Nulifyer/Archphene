#!/usr/bin/env python3
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[1]

license_text = (ROOT / "LICENSE").read_text(encoding="utf-8")
if "Permission is hereby granted, free of charge" not in license_text:
    raise SystemExit("root LICENSE is not the selected MIT license")

workspace = tomllib.loads((ROOT / "Cargo.toml").read_text(encoding="utf-8"))
if workspace["workspace"]["package"]["license"] != "MIT":
    raise SystemExit("Rust workspace package license does not match root MIT license")
for member in workspace["workspace"]["members"]:
    manifest_path = ROOT / member / "Cargo.toml"
    package = tomllib.loads(manifest_path.read_text(encoding="utf-8"))["package"]
    license_value = package.get("license")
    if license_value not in ("MIT", {"workspace": True}):
        raise SystemExit(f"Rust package license differs from MIT: {manifest_path}")

notice = (ROOT / "third_party/android-apksig/NOTICE.txt").read_text(encoding="utf-8")
for required in (
    "Android apksig 9.3.0",
    "Copyright (c) 2016, The Android Open Source Project",
    "Apache License, Version 2.0",
    "https://android.googlesource.com/platform/tools/apksig/",
):
    if required not in notice:
        raise SystemExit(f"apksig notice is missing: {required}")

gradle_contracts = {
    "android/app/build.gradle.kts": (
        "Archphene-MIT.txt",
        "Apache-2.0.txt",
        "AndroidApkSig-NOTICE.txt",
    ),
    "android/builder/build.gradle.kts": ("Archphene-MIT.txt",),
    "android/launcher-template/build.gradle.kts": ("Archphene-MIT.txt",),
}
for relative, required_values in gradle_contracts.items():
    text = (ROOT / relative).read_text(encoding="utf-8")
    for required in (
        "stageArchpheneReleaseLicenses",
        "build/generated/releaseLicenses/assets",
        *required_values,
    ):
        if required not in text:
            raise SystemExit(f"release license staging is missing in {relative}: {required}")

print(
    "Release license contract passed: MIT project metadata and packaged manager, "
    "Builder, app-shell, font, and apksig notice paths are consistent."
)
