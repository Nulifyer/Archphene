#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "release/termux-pulse-recipes.json"
BINARY_MANIFEST = ROOT / "native/archphene-audio/termux-pulse-packages.tsv"
PULSEAUDIO_COMMIT = "1f020889c9aa44ea0f63d7222e8c2b62c3f45f68"
def fail(message: str) -> None:
    raise ValueError(message)


def value(text: str, key: str) -> str | None:
    match = re.search(
        rf'^{re.escape(key)}=(?:"([^"]+)"|\'([^\']+)\'|([^\s#]+))$',
        text,
        re.MULTILINE,
    )
    if match is None:
        return None
    return next(item for item in match.groups() if item is not None)


def binary_packages() -> dict[str, tuple[str, list[tuple[str, int, str, str]]]]:
    result: dict[str, tuple[str, list[tuple[str, int, str, str]]]] = {}
    for line in BINARY_MANIFEST.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("|")
        if len(fields) != 6:
            fail("Termux Pulse binary manifest row is invalid")
        package, architecture, version, size, digest, repository_path = fields
        parsed_size = int(size)
        source_path = Path(repository_path)
        if architecture not in {"aarch64", "x86_64"} or not re.fullmatch(
            r"[0-9a-f]{64}", digest
        ) or parsed_size <= 0 or parsed_size > 128 * 1024 * 1024 or (
            source_path.is_absolute() or ".." in source_path.parts
        ):
            fail(f"Termux Pulse binary identity is invalid: {package}")
        existing_version, rows = result.setdefault(package, (version, []))
        if existing_version != version:
            fail(f"Termux Pulse package versions disagree: {package}")
        rows.append((architecture, parsed_size, digest, repository_path))
    for package, (_, rows) in result.items():
        if sorted(row[0] for row in rows) != ["aarch64", "x86_64"]:
            fail(f"Termux Pulse package lacks exact ABI binaries: {package}")
    return result


def validate(repository: Path, verify_binaries: bool) -> int:
    document = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or set(document) != {
        "format", "packages", "repository"
    }:
        fail("Termux Pulse source manifest is invalid")
    if document["format"] != "org.archphene.termux-pulse-recipes.v1" or (
        document["repository"] != "https://github.com/termux/termux-packages.git"
    ):
        fail("Termux Pulse source manifest identity is invalid")
    binaries = binary_packages()
    origin = subprocess.run(
        ["git", "-C", str(repository), "remote", "get-url", "origin"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if origin != document["repository"]:
        fail("Termux Pulse recipe repository remote is invalid")
    records = document["packages"]
    if not isinstance(records, list) or not records:
        fail("Termux Pulse source records are invalid")
    names: list[str] = []
    for record in records:
        if not isinstance(record, dict) or set(record) not in (
            {"binaryPackage", "recipeCommit", "recipePackage", "version"},
            {
                "binaryPackage", "recipeCommit", "recipePackage",
                "sourceArchive", "version",
            },
            {
                "binaryPackage", "recipeCommit", "recipePackage", "sourceArchive",
                "upstreamGitCommit", "version",
            },
        ):
            fail("Termux Pulse source record is invalid")
        package = record["binaryPackage"]
        string_values = [value for key, value in record.items() if key != "sourceArchive"]
        if not all(isinstance(item, str) for item in string_values) or not re.fullmatch(
            r"[a-z0-9+_.-]{1,64}", package
        ) or not re.fullmatch(r"[a-z0-9+_.-]{1,64}", record["recipePackage"]):
            fail("Termux Pulse source record values are invalid")
        names.append(package)
        if package not in binaries or binaries[package][0] != record["version"]:
            fail(f"Termux Pulse recipe version does not match binaries: {package}")
        commit = record["recipeCommit"]
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            fail(f"Termux Pulse recipe commit is invalid: {package}")
        recipe_path = f"packages/{record['recipePackage']}/build.sh"
        recipe = subprocess.run(
            ["git", "-C", str(repository), "show", f"{commit}:{recipe_path}"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        version = value(recipe, "TERMUX_PKG_VERSION")
        revision = value(recipe, "TERMUX_PKG_REVISION")
        built_version = version + (f"-{revision}" if revision else "") if version else None
        if built_version != record["version"] or value(recipe, "TERMUX_PKG_LICENSE") is None:
            fail(f"Termux Pulse recipe metadata does not match: {package}")
        source_url = value(recipe, "TERMUX_PKG_SRCURL")
        source_digest = value(recipe, "TERMUX_PKG_SHA256")
        source_archive = record.get("sourceArchive")
        if package == "libandroid-execinfo":
            if source_archive is not None or "TERMUX_PKG_SKIP_SRC_EXTRACT=true" not in recipe:
                fail("Termux libandroid-execinfo recipe source is not self-contained")
        elif package == "pulseaudio":
            upstream = record.get("upstreamGitCommit")
            if source_url != "git+https://github.com/pulseaudio/pulseaudio" or not (
                upstream == PULSEAUDIO_COMMIT
            ):
                fail("Termux PulseAudio upstream source pin is invalid")
        elif not source_url or not re.fullmatch(r"[0-9a-f]{64}", source_digest or ""):
            fail(f"Termux Pulse upstream archive is not checksum-pinned: {package}")
        if package != "libandroid-execinfo":
            if (
                not isinstance(source_archive, dict)
                or set(source_archive) != {"sha256", "url"}
                or not isinstance(source_archive["url"], str)
                or not source_archive["url"].startswith("https://")
                or not re.fullmatch(r"[0-9a-f]{64}", source_archive["sha256"])
            ):
                fail(f"Termux Pulse source archive record is invalid: {package}")
            if package not in {"pulseaudio"} and source_archive["sha256"] != source_digest:
                fail(f"Termux Pulse source archive checksum differs from recipe: {package}")
    if names != sorted(set(names)) or set(names) != set(binaries):
        fail("Termux Pulse source records are not the exact sorted binary set")
    if verify_binaries:
        download_root = ROOT / "tooling/downloads/termux-pulse"
        for package, (_, rows) in binaries.items():
            for architecture, expected_size, expected_digest, repository_path in rows:
                binary = download_root / architecture / Path(repository_path).name
                content = binary.read_bytes()
                if len(content) != expected_size or hashlib.sha256(content).hexdigest() != (
                    expected_digest
                ):
                    fail(f"Termux Pulse binary changed: {package} {architecture}")
    return len(records)


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Termux Pulse source recipes")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--verify-binaries", action="store_true")
    try:
        args = parser.parse_args()
        count = validate(args.repository, args.verify_binaries)
        print(f"Termux Pulse source audit passed: {count} exact recipe commits.")
    except (
        OSError, ValueError, json.JSONDecodeError, subprocess.CalledProcessError
    ) as error:
        print(f"Termux Pulse source audit error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
