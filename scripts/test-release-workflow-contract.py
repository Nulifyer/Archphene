#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/publish-release-apk.yml"
CLIENT = (
    ROOT
    / "prototypes/linux-app-manager-stub/src/org/archpheneos/manager"
    / "GitHubReleaseClient.java"
)

workflow = WORKFLOW.read_text(encoding="utf-8")
client = CLIENT.read_text(encoding="utf-8")

required_workflow = (
    'push:\n    tags:\n      - "v*"',
    "Ensure release is a draft",
    "python3 scripts/test-atspi-source-contract.py",
    'args=(--verify-tag --draft',
    "Create one-time v1.0.0 updater migration asset",
    "if: env.VERSION_NAME == '1.0.1'",
    'cp "$RUNNER_TEMP/$APK_X86_NAME" "$RUNNER_TEMP/$legacy_name"',
    "Archphene-x86_64-$version_name.apk",
    "Archphene-arm64-v8a-$version_name.apk",
    'gh release upload "$RELEASE_TAG" "${assets[@]}" --clobber',
    'gh release edit "$RELEASE_TAG" --draft=false',
)
for value in required_workflow:
    if value not in workflow:
        raise SystemExit(f"release workflow contract missing: {value}")

if workflow.count('legacy_name="Archphene-$VERSION_NAME.apk"') != 1:
    raise SystemExit("release workflow has an unexpected ABI-neutral asset")
if workflow.count("if: env.VERSION_NAME == '1.0.1'") != 1:
    raise SystemExit("legacy x86 migration must be scoped only to v1.0.1")
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

for forbidden in (
    "LEGACY_UNIVERSAL_VERSION",
    "fallbackApkName",
    "String fallbackApk",
):
    if forbidden in client:
        raise SystemExit(f"self-update parser still accepts a universal fallback: {forbidden}")

required_client = (
    'String apkName = "Archphene-" + releaseAbi + "-" + version + ".apk";',
    "List<Artifact> universalX86",
    "List<Artifact> universalArm",
    "List<Artifact> mixedX86",
    "!universalX86.isEmpty()",
    "!universalArm.isEmpty()",
)
for value in required_client:
    if value not in client:
        raise SystemExit(f"self-update ABI test contract missing: {value}")

print(
    "Release workflow contract passed: draft-first publication, exact ABI assets, "
    "and one-time x86 v1.0.0 migration."
)
