#!/usr/bin/env python3

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *markers: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise SystemExit(f"{path}: missing Quick launch markers: {missing}")


require(
    "android/app/src/main/AndroidManifest.xml",
    'android:name=".launcher.QuickLaunchActivity"',
    'android:exported="false"',
)
require(
    "android/app/src/main/kotlin/org/archphene/app/MainActivity.kt",
    "quickLaunchCandidate(packageName)",
    "QuickLaunchActivity.createIntent",
)
require(
    "android/app/src/main/kotlin/org/archphene/app/launcher/QuickLaunchActivity.kt",
    "TRANSACTION_OPEN_QUICK",
    "TRANSACTION_ATTACH_SURFACE",
    "TRANSACTION_INPUT",
    "TRANSACTION_CLOSE",
)
require(
    "android/app/src/main/kotlin/org/archphene/app/launcher/LauncherSessionService.kt",
    "callingUid != applicationInfo.uid",
    "runtime.authorizeQuickLauncher",
    "runtime.openQuickLauncherProcess",
)
require(
    "crates/archphene-launcher/src/lib.rs",
    "pub fn authorize_quick_launch",
    "!descriptor.desired_present",
    "descriptor.desired_generation != generation",
    "descriptor_id_hex.as_bytes() != expected_id",
)
require(
    "crates/archphene-android/src/lib.rs",
    'version == "Q1"',
    'version == "G8"',
    "runtime.open_quick_launcher_process",
)

print("Quick launch contract passed")
