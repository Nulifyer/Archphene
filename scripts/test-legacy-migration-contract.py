#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
state = (
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/LegacyPrototypeState.kt"
).read_text(encoding="utf-8")
application = (
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/ArchpheneApplication.kt"
).read_text(encoding="utf-8")
activity = (
    ROOT / "android/app/src/main/kotlin/org/archphene/app/MainActivity.kt"
).read_text(encoding="utf-8")
service = (
    ROOT
    / "android/app/src/main/kotlin/org/archphene/app/runtime/ArchpheneRuntimeService.kt"
).read_text(encoding="utf-8")
manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
release_notes = (ROOT / "docs/release-notes.md").read_text(encoding="utf-8")

for marker in (
    "files/package-runtime",
    "files/runtime-packs",
    "shared_prefs/linux-app-manager-state.xml",
    "shared_prefs/archphene-managed-packages-v1.xml",
):
    if marker not in state:
        raise SystemExit(f"legacy migration detector is missing marker: {marker}")

if application.index("LegacyPrototypeState.detectedMarker") > application.index(
    "ArchphenePreferences.start"
):
    raise SystemExit("application preferences start before legacy-state rejection")
if activity.index("LegacyPrototypeState.detectedMarker") > activity.index(
    "startService(Intent(this, ArchpheneRuntimeService::class.java))"
):
    raise SystemExit("manager Activity starts runtime work before legacy-state rejection")
if service.index("LegacyPrototypeState.detectedMarker") > service.index("startBootstrap()"):
    raise SystemExit("runtime service bootstraps before legacy-state rejection")
if not (
    'android:name=".LegacyMigrationActivity"' in manifest
    and 'android:exported="false"' in manifest.split(
        'android:name=".LegacyMigrationActivity"', 1
    )[1].split("/>", 1)[0]
):
    raise SystemExit("legacy migration Activity is missing or exported")
for boundary in (
    "does not migrate its private package/runtime state",
    "uninstall the prototype manager and Terminal",
):
    if boundary not in " ".join(release_notes.split()):
        raise SystemExit(f"release notes are missing migration boundary: {boundary}")

print(
    "Legacy migration contract passed: prototype state blocks preferences, runtime "
    "bootstrap, and implicit in-place migration."
)
