#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "release/native-components.json"
REQUIRED_COMPONENTS = {
    "arch-package-runtime-aarch64",
    "arch-package-runtime-x86_64",
    "dbus",
    "glibc",
    "gtk3-compat-aarch64",
    "gtk3-compat-x86_64",
    "libepoxy",
    "mbedtls",
    "mesa",
    "pipewire",
    "qt-bridge-aarch64",
    "qt-bridge-x86_64",
    "termux-pulse-packages",
    "virglrenderer",
}
BLOCKER_CATEGORIES = {"corresponding-source", "license", "provenance", "reproducibility"}
REQUIRED_SCOPES = {"glibc": ["builder", "manager"]}


def fail(message: str) -> None:
    raise ValueError(message)


def validate(path: Path) -> tuple[int, int]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or set(document) != {"components", "format"}:
        fail("native release audit document is invalid")
    if document["format"] != "org.archphene.native-release-audit.v1":
        fail("native release audit format is invalid")
    components = document["components"]
    if not isinstance(components, list) or not components or len(components) > 128:
        fail("native release component count is outside the bound")
    ids: list[str] = []
    blocked = 0
    for component in components:
        required_keys = {
            "artifactScopes", "blockers", "declaredLicense", "evidence", "id",
            "kind", "reviewState", "sourcePin", "sourceUrl", "version",
        }
        if not isinstance(component, dict) or set(component) != required_keys:
            fail("native release component record is invalid")
        component_id = component["id"]
        if not isinstance(component_id, str) or not re.fullmatch(r"[a-z0-9_-]{1,64}", component_id):
            fail("native release component ID is invalid")
        ids.append(component_id)
        scopes = component["artifactScopes"]
        expected_scopes = REQUIRED_SCOPES.get(component_id, ["manager"])
        if scopes != expected_scopes:
            fail(f"native release component has an invalid artifact scope: {component_id}")
        if component["reviewState"] not in {"blocked", "verified"}:
            fail(f"native release component review state is invalid: {component_id}")
        blockers = component["blockers"]
        if not isinstance(blockers, list):
            fail(f"native release blockers are invalid: {component_id}")
        if component["reviewState"] == "blocked":
            blocked += 1
            if not blockers:
                fail(f"blocked native component lacks a blocker: {component_id}")
        elif blockers:
            fail(f"verified native component retains a blocker: {component_id}")
        for blocker in blockers:
            if (
                not isinstance(blocker, dict)
                or set(blocker) != {"category", "detail"}
                or blocker["category"] not in BLOCKER_CATEGORIES
                or not isinstance(blocker["detail"], str)
                or not blocker["detail"]
            ):
                fail(f"native release blocker is invalid: {component_id}")
        source_pin = component["sourcePin"]
        if not isinstance(source_pin, dict) or set(source_pin) != {"type", "value"}:
            fail(f"native release source pin is invalid: {component_id}")
        if not isinstance(component["sourceUrl"], str) or not component["sourceUrl"].startswith("https://"):
            fail(f"native release source URL is invalid: {component_id}")
        evidence = component["evidence"]
        if not isinstance(evidence, list) or not evidence or len(evidence) > 8:
            fail(f"native release evidence is invalid: {component_id}")
        for record in evidence:
            if not isinstance(record, dict) or set(record) != {"contains", "path"}:
                fail(f"native release evidence record is invalid: {component_id}")
            evidence_path = Path(record["path"])
            if evidence_path.is_absolute() or ".." in evidence_path.parts:
                fail(f"native release evidence path is unsafe: {component_id}")
            content = (ROOT / evidence_path).read_text(encoding="utf-8")
            if record["contains"] not in content:
                fail(f"native release evidence no longer matches: {component_id}")
    if ids != sorted(ids) or len(ids) != len(set(ids)):
        fail("native release components are not uniquely sorted")
    missing = REQUIRED_COMPONENTS - set(ids)
    if missing:
        fail("native release audit lacks required components: " + ", ".join(sorted(missing)))
    return len(components), blocked


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the native release audit")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--require-complete", action="store_true")
    try:
        args = parser.parse_args()
        total, blocked = validate(args.manifest)
        if args.require_complete and blocked:
            fail(f"native release audit has {blocked} blocked components")
        print(f"Native release audit passed: {total} components, {blocked} blocked.")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"native release audit error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
