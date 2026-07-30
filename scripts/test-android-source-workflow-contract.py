#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/validate-android-source.yml"
RELEASE_WORKFLOW = ROOT / ".github/workflows/publish-release-apk.yml"
ROOT_BUILD = ROOT / "build.gradle.kts"
APP_BUILD = ROOT / "android/app/build.gradle.kts"
BUILDER_BUILD = ROOT / "android/builder/build.gradle.kts"

workflow = WORKFLOW.read_text(encoding="utf-8")
release_workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
root_build = ROOT_BUILD.read_text(encoding="utf-8")

for action in (
    "actions/checkout",
    "actions/setup-java",
    "android-actions/setup-android",
):
    pattern = rf"uses: {re.escape(action)}@[0-9a-f]{{40}} # v[0-9]+"
    if not re.search(pattern, workflow):
        raise SystemExit(f"Android source action is not commit-pinned: {action}")

required_workflow = (
    'java-version: "26"',
    '-ParchpheneSourceValidation=true',
    ":android:app:testDebugUnitTest",
    ":android:launcher-template:testDebugUnitTest",
    ":android:builder:testDebugUnitTest",
    ":android:app:lintDebug",
    ":android:launcher-template:lintDebug",
    ":android:builder:lintDebug",
)
for value in required_workflow:
    if value not in workflow:
        raise SystemExit(f"Android source workflow contract missing: {value}")

required_root = (
    "val requiredJdkFeature = 26",
    'setOf("lintDebug", "testDebugUnitTest")',
    "archpheneSourceValidation may run only lintDebug and testDebugUnitTest",
)
for value in required_root:
    if value not in root_build:
        raise SystemExit(f"Android source Gradle contract missing: {value}")

for build_file in (APP_BUILD, BUILDER_BUILD):
    text = build_file.read_text(encoding="utf-8")
    if 'gradleProperty("archpheneSourceValidation")' not in text:
        raise SystemExit(f"source validation is not declared in {build_file}")
    if "if (!sourceValidation.get()) {" not in text:
        raise SystemExit(f"native/runtime staging is not guarded in {build_file}")

if 'java-version: "26"' not in release_workflow:
    raise SystemExit("release workflow does not provision JDK 26")

print(
    "Android source workflow contract passed: JDK 26, pinned actions, "
    "unit/lint-only source validation, and guarded native/runtime staging."
)
