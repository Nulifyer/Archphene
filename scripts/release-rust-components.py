#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import tomllib

MAX_COMPONENTS = 512
LICENSE = re.compile(r"[A-Za-z0-9.+()/: -]{1,256}")
LEGACY_LICENSES = {
    "Apache-2.0/MIT": "Apache-2.0 OR MIT",
    "MIT/Apache-2.0": "MIT OR Apache-2.0",
    "Unlicense/MIT": "Unlicense OR MIT",
}


def fail(message: str) -> None:
    raise ValueError(message)


def load_metadata(path: Path) -> dict[str, object]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or document.get("version") != 1:
        fail("Cargo metadata format version is invalid")
    return document


def lock_checksums(path: Path) -> dict[tuple[str, str, str], str]:
    document = tomllib.loads(path.read_text(encoding="utf-8"))
    result: dict[tuple[str, str, str], str] = {}
    for package in document.get("package", []):
        source = package.get("source")
        checksum = package.get("checksum")
        if source is None:
            continue
        if not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
            fail(f"Cargo.lock lacks a SHA-256 checksum for {package.get('name')}")
        key = (package["name"], package["version"], source)
        if key in result:
            fail(f"Cargo.lock has a duplicate component: {key[0]} {key[1]}")
        result[key] = checksum
    return result


def generate(args: argparse.Namespace) -> dict[str, object]:
    metadata = load_metadata(args.metadata)
    packages = {package["id"]: package for package in metadata.get("packages", [])}
    resolve = metadata.get("resolve")
    if not isinstance(resolve, dict):
        fail("Cargo metadata lacks a resolved dependency graph")
    nodes = {node["id"]: node for node in resolve.get("nodes", [])}
    root_ids: list[str] = []
    for root_name in sorted(set(args.root)):
        matches = [
            package_id
            for package_id, package in packages.items()
            if package.get("name") == root_name and package.get("source") is None
        ]
        if len(matches) != 1:
            fail(f"Cargo metadata does not contain one workspace root: {root_name}")
        root_ids.extend(matches)

    seen = set(root_ids)
    pending = list(root_ids)
    while pending:
        package_id = pending.pop()
        node = nodes.get(package_id)
        if not isinstance(node, dict):
            fail(f"Cargo metadata lacks a dependency node: {package_id}")
        for dependency in node.get("deps", []):
            dependency_kinds = dependency.get("dep_kinds", [])
            if not any(kind.get("kind") is None for kind in dependency_kinds):
                continue
            dependency_id = dependency.get("pkg")
            if dependency_id not in packages:
                fail("Cargo dependency references an unknown package")
            dependency_targets = packages[dependency_id].get("targets", [])
            if any(
                "proc-macro" in target.get("kind", [])
                for target in dependency_targets
                if isinstance(target, dict)
            ):
                continue
            if dependency_id not in seen:
                seen.add(dependency_id)
                pending.append(dependency_id)

    checksums = lock_checksums(args.lock)
    components: list[dict[str, str]] = []
    for package_id in seen:
        package = packages[package_id]
        source = package.get("source")
        if source is None:
            continue
        name = package.get("name")
        version = package.get("version")
        license_expression = package.get("license")
        if not all(isinstance(value, str) and value for value in (name, version, source)):
            fail("Cargo component identity is incomplete")
        if not isinstance(license_expression, str) or not LICENSE.fullmatch(
            license_expression
        ):
            fail(f"Cargo component license is missing or invalid: {name} {version}")
        spdx_license = LEGACY_LICENSES.get(license_expression, license_expression)
        if not source.startswith("registry+"):
            fail(f"Cargo release component is not checksum-pinned registry source: {name}")
        checksum = checksums.get((name, version, source))
        if checksum is None:
            fail(f"Cargo component is absent from Cargo.lock: {name} {version}")
        components.append(
            {
                "name": name,
                "version": version,
                "cargoLicense": license_expression,
                "spdxLicense": spdx_license,
                "source": source,
                "sha256": checksum,
                "purl": f"pkg:cargo/{name}@{version}",
            }
        )
    components.sort(key=lambda component: (component["name"], component["version"]))
    if not components or len(components) > MAX_COMPONENTS:
        fail("Cargo release component count is outside the bound")
    return {
        "format": "org.archphene.rust-components.v1",
        "target": args.target,
        "roots": sorted(set(args.root)),
        "components": components,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create a locked Rust release-component manifest"
    )
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--root", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    try:
        args = parser.parse_args()
        if not re.fullmatch(r"[a-z0-9_-]{1,64}", args.target):
            fail("Rust target is invalid")
        document = generate(args)
        args.output.write_text(
            json.dumps(document, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, json.JSONDecodeError, tomllib.TOMLDecodeError) as error:
        print(f"release Rust component error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
