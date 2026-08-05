#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/publish-release-apk.yml"
RELEASE_NOTES = ROOT / "docs/release-notes.md"
GREENFIELD_BUILD = ROOT / "android/app/build.gradle.kts"
GREENFIELD_BUILDER_BUILD = ROOT / "android/builder/build.gradle.kts"
GREENFIELD_RELEASE_BUILDER = ROOT / "scripts/build-archphene-release-apk.sh"
GREENFIELD_RELEASE_VERIFIER = ROOT / "scripts/verify-release-apk.sh"

workflow = WORKFLOW.read_text(encoding="utf-8")
release_notes = RELEASE_NOTES.read_text(encoding="utf-8")
normalized_release_notes = " ".join(release_notes.split())
greenfield_build = GREENFIELD_BUILD.read_text(encoding="utf-8")
greenfield_builder_build = GREENFIELD_BUILDER_BUILD.read_text(encoding="utf-8")
greenfield_release_builder = GREENFIELD_RELEASE_BUILDER.read_text(encoding="utf-8")
greenfield_release_verifier = GREENFIELD_RELEASE_VERIFIER.read_text(encoding="utf-8")

required_workflow = (
    'push:\n    tags:\n      - "v*"',
    "Ensure release is a draft",
    "python3 scripts/test-atspi-source-contract.py",
    "--notes-file docs/release-notes.md",
    "Archphene-x86_64-$version_name.apk",
    "Archphene-arm64-v8a-$version_name.apk",
    "Archphene-Builder-x86_64-$version_name.apk",
    "Archphene-Builder-arm64-v8a-$version_name.apk",
    "bash scripts/build-archphene-release-apk.sh",
    'unsigned="tooling/build/apk/Archphene-$abi-$VERSION_NAME-unsigned.apk"',
    'builder_unsigned="tooling/build/apk/Archphene-Builder-$abi-$VERSION_NAME-unsigned.apk"',
    "--prebuilt-native",
    '--ks-pass env:KEYSTORE_PASSWORD',
    '--key-pass env:KEY_PASSWORD',
    'bash scripts/verify-release-apk.sh',
    'gh release upload "$RELEASE_TAG" "${assets[@]}" --clobber',
    'gh release edit "$RELEASE_TAG" --draft=false',
    'java-version: "26"',
)
for value in required_workflow:
    if value not in workflow:
        raise SystemExit(f"release workflow contract missing: {value}")

for forbidden in (
    "prototypes/linux-app-manager-stub",
    "build-linux-manager-apk.sh",
    'legacy_name="Archphene-$VERSION_NAME.apk"',
):
    if forbidden in workflow:
        raise SystemExit(f"release workflow still uses a legacy product path: {forbidden}")
for action in (
    "actions/checkout",
    "actions/setup-java",
    "android-actions/setup-android",
    "actions/upload-artifact",
):
    pattern = rf"uses: {re.escape(action)}@[0-9a-f]{{40}} # v[0-9]+"
    if not re.search(pattern, workflow):
        raise SystemExit(f"release action is not commit-pinned: {action}")
if "types: [published]" in workflow:
    raise SystemExit("release workflow must not attach assets after publication")
if workflow.index("Ensure release is a draft") > workflow.index("Build signed ABI-specific APKs"):
    raise SystemExit("release draft must exist before expensive artifact builds")
if workflow.index('gh release upload "$RELEASE_TAG"') > workflow.index(
    'gh release edit "$RELEASE_TAG" --draft=false'
):
    raise SystemExit("release assets must be uploaded before publication")

for value in (
    "Archphene-Builder-<abi>-<version>.apk",
    "private package/runtime state",
    "Virpipe rendering still returns through SHM",
    "Vulkan and XWayland are not supported release paths",
    "GrapheneOS",
    "physical x86_64",
):
    if value not in normalized_release_notes:
        raise SystemExit(f"curated release notes are missing a required boundary: {value}")

for value in (
    'providers.gradleProperty("archpheneVersionCode")',
    'providers.gradleProperty("archpheneApplicationId")',
    'providers.gradleProperty("archpheneVersionName")',
    'archpheneVersionCode.toLong() <= 2_100_000_000L',
    'versionCode = archpheneVersionCode?.toInt() ?: 1',
    'versionName = archpheneVersionName ?: "0.1.0"',
):
    if value not in greenfield_build:
        raise SystemExit(f"greenfield release version contract missing: {value}")

for value in (
    'providers.gradleProperty("archpheneVersionCode")',
    'providers.gradleProperty("archpheneVersionName")',
    'versionCode = archpheneVersionCode?.toInt() ?: 1',
    'versionName = archpheneVersionName ?: "0.1.0"',
):
    if value not in greenfield_builder_build:
        raise SystemExit(f"Builder release version contract missing: {value}")

for value in (
    'flock "$build_lock_fd"',
    './gradlew "${gradle_args[@]}"',
    "--prebuilt-native) prebuilt_native=true",
    '"-ParchpheneAbi=$abi"',
    '-ParchpheneApplicationId=org.archpheneos.manager',
    '"-ParchpheneVersionCode=$version_code"',
    '"-ParchpheneVersionName=$version_name"',
    ':android:app:assembleRelease',
    ':android:builder:assembleRelease',
    'app-release-unsigned.apk',
    'Archphene-$abi-$version_name-unsigned.apk',
    'Archphene-Builder-$abi-$version_name-unsigned.apk',
    'sha256sum "$(basename "$artifact")" > "$(basename "$checksum")"',
):
    if value not in greenfield_release_builder:
        raise SystemExit(f"greenfield release builder contract missing: {value}")

if "--allow-unsigned" in workflow:
    raise SystemExit("release workflow must not bypass production signing verification")

for value in (
    "package-runtime-$architecture.tsv",
    "assets/launcher/launcher-template.apk",
    "libarchphene_compositor.so",
    "libarchphene_virgl_server.so",
    "libgallium-26.1.5.so",
    "android:pageSizeCompat",
    "manager APK is debuggable",
    "manager and Builder release signing identities are invalid",
):
    if value not in greenfield_release_verifier:
        raise SystemExit(f"greenfield release verifier contract missing: {value}")

print(
    "Release workflow contract passed: draft-first greenfield publication, exact "
    "ABI assets, production identity, signing, and content verification."
)
