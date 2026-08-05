#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

MAX_SOURCE_BYTES = 512 * 1024 * 1024
MAX_SUPPORT_FILE_BYTES = 4 * 1024 * 1024
SOURCE_URL = "https://sourceware.org/git/glibc.git"
SUPPORT_PATHS = (
    "containers/arm-runtime-builder.Containerfile",
    "scripts/build-ci-package-runtime-arm64.sh",
    "scripts/build-ci-package-runtime.sh",
)


def fail(message: str) -> None:
    raise ValueError(message)


def git(source: Path, *arguments: str, text: bool = True) -> str | bytes:
    result = subprocess.run(
        ["git", "-C", str(source), *arguments],
        check=True,
        capture_output=True,
        text=text,
    )
    return result.stdout


def zip_timestamp(source_date_epoch: int) -> tuple[int, int, int, int, int, int]:
    value = datetime.fromtimestamp(source_date_epoch, timezone.utc)
    if value.year < 1980 or value.year > 2107:
        fail("source date epoch is outside the ZIP timestamp range")
    return value.year, value.month, value.day, value.hour, value.minute, value.second


def zip_info(name: str, timestamp: tuple[int, int, int, int, int, int]) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, timestamp)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def make_bundle(
    source: Path,
    repository: Path,
    expected_commit: str,
    source_date_epoch: int,
) -> bytes:
    actual_commit = str(git(source, "rev-parse", "HEAD")).strip()
    if actual_commit != expected_commit:
        fail("glibc source commit does not match the release audit")
    if str(git(source, "status", "--porcelain")):
        fail("glibc source checkout is not clean")
    remote = str(git(source, "remote", "get-url", "origin")).strip()
    if remote != SOURCE_URL:
        fail("glibc source remote does not match the release audit")
    source_archive = bytes(git(source, "archive", "--format=tar", "HEAD", text=False))
    if not source_archive or len(source_archive) > MAX_SOURCE_BYTES:
        fail("glibc source archive size is outside the bound")
    entries: list[tuple[str, bytes]] = [
        (f"source/glibc-{expected_commit}.tar", source_archive)
    ]
    support_paths = [repository / path for path in SUPPORT_PATHS]
    patch_paths = sorted((repository / "patches/glibc").glob("*.patch"))
    if not patch_paths:
        fail("glibc corresponding source has no Archphene patch")
    support_paths.extend(patch_paths)
    support_index: list[dict[str, str]] = []
    for path in support_paths:
        content = path.read_bytes()
        if not content or len(content) > MAX_SUPPORT_FILE_BYTES:
            fail(f"glibc corresponding-source support file is invalid: {path}")
        relative = path.relative_to(repository).as_posix()
        archive_path = "archphene/" + relative
        entries.append((archive_path, content))
        support_index.append(
            {"path": archive_path, "sha256": hashlib.sha256(content).hexdigest()}
        )
    support_index.sort(key=lambda record: record["path"])
    readme = (
        "# Archphene glibc corresponding source\n\n"
        f"Upstream: {SOURCE_URL}\n\n"
        f"Commit: `{expected_commit}`\n\n"
        "`source/` contains the pristine Git tree as a deterministic tar archive. "
        "Apply every patch under `archphene/patches/glibc/` in filename order. "
        "The two `archphene/scripts/build-ci-package-runtime*.sh` files contain "
        "the exact configure, compiler, linker, and installation commands used "
        "for the distributed x86_64 and AArch64 shared libraries. The AArch64 "
        "container recipe is included under `archphene/containers/`.\n"
    ).encode("utf-8")
    entries.append(("README.md", readme))
    index = {
        "commit": expected_commit,
        "format": "org.archphene.glibc-corresponding-source.v1",
        "sourceArchive": {
            "path": f"source/glibc-{expected_commit}.tar",
            "sha256": hashlib.sha256(source_archive).hexdigest(),
        },
        "sourceUrl": SOURCE_URL,
        "supportFiles": support_index,
    }
    entries.append(
        ("index.json", (json.dumps(index, indent=2, sort_keys=True) + "\n").encode())
    )
    names = [name for name, _ in entries]
    if len(names) != len(set(names)):
        fail("glibc corresponding-source bundle contains a duplicate path")
    timestamp = zip_timestamp(source_date_epoch)
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", allowZip64=False) as archive:
        for name, content in sorted(entries):
            archive.writestr(zip_info(name, timestamp), content)
    return output.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate or verify the glibc corresponding-source bundle"
    )
    parser.add_argument("command", choices=("generate", "verify"))
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--source-date-epoch", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    try:
        args = parser.parse_args()
        if len(args.commit) != 40 or any(character not in "0123456789abcdef" for character in args.commit):
            fail("glibc source commit is invalid")
        expected = make_bundle(
            args.source, args.repository, args.commit, args.source_date_epoch
        )
        if args.command == "generate":
            args.output.write_bytes(expected)
        elif args.output.read_bytes() != expected:
            fail("glibc corresponding-source bundle does not match its inputs")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"glibc corresponding-source error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
