#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import zipfile

MAX_FILES_PER_COMPONENT = 128
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 200000
CHUNK_BYTES = 1024 * 1024
LICENSE_FILE = re.compile(
    r"^(licen[sc]e|copying|unlicense|notice|copyright)([._-].*)?$", re.IGNORECASE
)


def fail(message: str) -> None:
    raise ValueError(message)


def is_license_path(path: PurePosixPath) -> bool:
    return LICENSE_FILE.fullmatch(path.name) is not None or any(
        part.lower() in {"license", "licenses"} for part in path.parts[:-1]
    )


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def archive_files(path: Path, expected_digest: str) -> list[tuple[str, bytes]]:
    if path.stat().st_size <= 0 or path.stat().st_size > MAX_ARCHIVE_BYTES:
        fail(f"native source archive size is outside the bound: {path}")
    if file_digest(path) != expected_digest:
        fail(f"native source archive checksum changed: {path}")
    result: list[tuple[str, bytes]] = []
    with tarfile.open(path, "r:*") as archive:
        members = archive.getmembers()
        if not members or len(members) > MAX_ARCHIVE_MEMBERS:
            fail(f"native source archive member count is outside the bound: {path}")
        for member in sorted(members, key=lambda item: item.name):
            name = PurePosixPath(member.name)
            if (
                not member.isfile()
                or name.is_absolute()
                or any(part in {"", ".", ".."} for part in name.parts)
                or not is_license_path(name)
            ):
                continue
            if member.size <= 0 or member.size > MAX_FILE_BYTES:
                fail(f"native source license entry size is invalid: {name}")
            source = archive.extractfile(member)
            if source is None:
                fail(f"native source license entry is unreadable: {name}")
            content = source.read(MAX_FILE_BYTES + 1)
            result.append((name.as_posix(), content))
            if len(result) > MAX_FILES_PER_COMPONENT:
                fail(f"native source archive has too many license files: {path}")
    return result


def git_files(path: Path, expected_commit: str) -> list[tuple[str, bytes]]:
    actual_commit = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if actual_commit != expected_commit:
        fail(f"native license source commit changed: {path}")
    result: list[tuple[str, bytes]] = []
    for candidate in sorted(path.rglob("*")):
        if not candidate.is_file() or candidate.is_symlink():
            continue
        relative = PurePosixPath(candidate.relative_to(path).as_posix())
        if is_license_path(relative):
            if candidate.stat().st_size <= 0 or candidate.stat().st_size > MAX_FILE_BYTES:
                fail(f"native Git license file size is invalid: {relative}")
            result.append((relative.as_posix(), candidate.read_bytes()))
            if len(result) > MAX_FILES_PER_COMPONENT:
                fail(f"native Git source has too many license files: {path}")
    return result


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


def make_bundle(manifest_path: Path, root: Path, source_date_epoch: int) -> bytes:
    manifest_raw = manifest_path.read_bytes()
    document = json.loads(manifest_raw.decode("utf-8"))
    if not isinstance(document, dict) or document.get("format") != (
        "org.archphene.native-release-audit.v1"
    ):
        fail("native release audit manifest is invalid")
    raw_components = document.get("components")
    if not isinstance(raw_components, list) or not raw_components:
        fail("native release audit components are invalid")
    components: dict[str, dict[str, object]] = {}
    for component in raw_components:
        if not isinstance(component, dict) or not isinstance(component.get("id"), str):
            fail("native release audit component record is invalid")
        if component["id"] in components:
            fail("native release audit component is duplicated")
        components[component["id"]] = component
    sources = document.get("licenseSources")
    if not isinstance(sources, list) or not sources:
        fail("native release audit has no license sources")
    timestamp = zip_timestamp(source_date_epoch)
    entries: list[tuple[str, bytes]] = [("native-components.json", manifest_raw)]
    index_components: list[dict[str, object]] = []
    total_bytes = len(manifest_raw)
    for source in sources:
        if (
            not isinstance(source, dict)
            or set(source) != {"componentId", "path", "type"}
            or not all(isinstance(value, str) for value in source.values())
        ):
            fail("native release license source record is invalid")
        component_id = source["componentId"]
        component = components.get(component_id)
        if not isinstance(component, dict):
            fail(f"native license source has no component: {component_id}")
        source_path = root / source["path"]
        source_pin = component.get("sourcePin")
        if not isinstance(source_pin, dict) or not isinstance(source_pin.get("value"), str):
            fail(f"native component source pin is invalid: {component_id}")
        expected_pin = source_pin["value"]
        if source["type"] == "archive":
            files = archive_files(source_path, expected_pin)
        elif source["type"] == "git-tree":
            files = git_files(source_path, expected_pin)
        else:
            fail(f"native license source type is invalid: {component_id}")
        if not files or len(files) > MAX_FILES_PER_COMPONENT:
            fail(f"native component license file count is outside the bound: {component_id}")
        indexed_files: list[dict[str, str]] = []
        for relative, content in files:
            if not content or len(content) > MAX_FILE_BYTES or b"\0" in content:
                fail(f"native component license file is invalid: {component_id}/{relative}")
            total_bytes += len(content)
            if total_bytes > MAX_TOTAL_BYTES:
                fail("native license bundle exceeds the uncompressed size bound")
            archive_path = f"licenses/{component_id}/{relative}"
            entries.append((archive_path, content))
            indexed_files.append(
                {"path": archive_path, "sha256": hashlib.sha256(content).hexdigest()}
            )
        index_components.append(
            {
                "declaredLicense": component["declaredLicense"],
                "files": indexed_files,
                "id": component_id,
                "sourcePin": source_pin,
                "sourceUrl": component["sourceUrl"],
                "version": component["version"],
            }
        )
    index = {
        "components": index_components,
        "format": "org.archphene.native-license-bundle.v1",
        "nativeManifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
    }
    entries.append(
        ("index.json", (json.dumps(index, indent=2, sort_keys=True) + "\n").encode())
    )
    names = [name for name, _ in entries]
    if len(names) != len(set(names)):
        fail("native license bundle contains a duplicate path")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", allowZip64=False) as archive:
        for name, content in sorted(entries):
            archive.writestr(zip_info(name, timestamp), content)
    return output.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate or verify the native release license bundle"
    )
    parser.add_argument("command", choices=("generate", "verify"))
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source-date-epoch", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    try:
        args = parser.parse_args()
        expected = make_bundle(args.manifest, args.root, args.source_date_epoch)
        if args.command == "generate":
            args.output.write_bytes(expected)
        elif args.output.read_bytes() != expected:
            fail("native license bundle does not match its sources and metadata")
    except (
        OSError,
        ValueError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
        tarfile.TarError,
    ) as error:
        print(f"native release license error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
