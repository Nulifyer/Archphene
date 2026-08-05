#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

MAX_COMPONENTS = 512
MAX_FILES_PER_COMPONENT = 32
MAX_FILE_BYTES = 1024 * 1024
MAX_TOTAL_BYTES = 16 * 1024 * 1024
LICENSE_FILE = re.compile(
    r"^(licen[sc]e|copying|unlicense|notice|copyright)([._-].*)?$", re.IGNORECASE
)


def fail(message: str) -> None:
    raise ValueError(message)


def component_document(path: Path) -> tuple[bytes, dict[str, object]]:
    raw = path.read_bytes()
    document = json.loads(raw.decode("utf-8"))
    if not isinstance(document, dict):
        fail("Rust component manifest is invalid")
    components = document.get("components")
    if (
        document.get("format") != "org.archphene.rust-components.v1"
        or not isinstance(components, list)
        or not components
        or len(components) > MAX_COMPONENTS
    ):
        fail("Rust component manifest is invalid")
    return raw, document


def zip_timestamp(source_date_epoch: int) -> tuple[int, int, int, int, int, int]:
    if source_date_epoch < 315532800:
        fail("source date epoch predates the ZIP format")
    value = datetime.fromtimestamp(source_date_epoch, timezone.utc)
    if value.year > 2107:
        fail("source date epoch exceeds the ZIP format")
    return value.year, value.month, value.day, value.hour, value.minute, value.second


def zip_info(name: str, timestamp: tuple[int, int, int, int, int, int]) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, timestamp)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def source_directory(registry: Path, name: str, version: str) -> Path:
    candidates = sorted(registry.glob(f"src/*/{name}-{version}"))
    candidates = [
        candidate
        for candidate in candidates
        if candidate.is_dir() and not candidate.is_symlink()
    ]
    if len(candidates) != 1:
        fail(f"Cargo registry does not contain one source tree: {name} {version}")
    return candidates[0]


def is_license_file(path: Path, source: Path) -> bool:
    relative = path.relative_to(source)
    return LICENSE_FILE.fullmatch(path.name) is not None or any(
        parent.lower() in {"license", "licenses"} for parent in relative.parts[:-1]
    )


def make_archive(
    components_path: Path, registry: Path, source_date_epoch: int
) -> bytes:
    component_raw, document = component_document(components_path)
    timestamp = zip_timestamp(source_date_epoch)
    entries: list[tuple[str, bytes]] = [("components.json", component_raw)]
    index_components: list[dict[str, object]] = []
    total_bytes = len(component_raw)
    seen_paths = {"components.json", "index.json"}
    for component in document["components"]:
        if not isinstance(component, dict) or not all(
            isinstance(component.get(key), str)
            for key in ("name", "version", "purl", "cargoLicense", "spdxLicense")
        ):
            fail("Rust component identity or license is invalid")
        name = component["name"]
        version = component["version"]
        if not re.fullmatch(r"[A-Za-z0-9_+-]{1,128}", name) or not re.fullmatch(
            r"[A-Za-z0-9.+_-]{1,64}", version
        ):
            fail("Rust component package identity is invalid")
        if component["purl"] != f"pkg:cargo/{name}@{version}":
            fail("Rust component purl does not match its identity")
        source = source_directory(registry, name, version)
        files = sorted(
            path
            for path in source.rglob("*")
            if path.is_file() and not path.is_symlink() and is_license_file(path, source)
        )
        if not files or len(files) > MAX_FILES_PER_COMPONENT:
            fail(f"Rust component license file count is outside the bound: {name}")
        indexed_files: list[dict[str, str]] = []
        for path in files:
            relative = PurePosixPath(path.relative_to(source).as_posix())
            if any(part in {"", ".", ".."} for part in relative.parts):
                fail(f"Rust component has an unsafe license path: {name}")
            content = path.read_bytes()
            if not content or len(content) > MAX_FILE_BYTES or b"\0" in content:
                fail(f"Rust component license file is invalid: {name}/{relative}")
            total_bytes += len(content)
            if total_bytes > MAX_TOTAL_BYTES:
                fail("Rust license bundle exceeds the uncompressed size bound")
            archive_path = f"licenses/{name}-{version}/{relative}"
            if archive_path in seen_paths:
                fail(f"Rust license bundle path is duplicated: {archive_path}")
            seen_paths.add(archive_path)
            entries.append((archive_path, content))
            indexed_files.append(
                {"path": archive_path, "sha256": hashlib.sha256(content).hexdigest()}
            )
        index_components.append(
            {
                "cargoLicense": component["cargoLicense"],
                "files": indexed_files,
                "purl": component["purl"],
                "spdxLicense": component["spdxLicense"],
            }
        )
    index = {
        "format": "org.archphene.rust-license-bundle.v1",
        "componentManifestSha256": hashlib.sha256(component_raw).hexdigest(),
        "components": index_components,
    }
    entries.append(
        ("index.json", (json.dumps(index, indent=2, sort_keys=True) + "\n").encode())
    )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", allowZip64=False) as archive:
        for name, content in sorted(entries):
            archive.writestr(zip_info(name, timestamp), content)
    return output.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate or verify a deterministic Rust license bundle"
    )
    parser.add_argument("command", choices=("generate", "verify"))
    parser.add_argument("--components", type=Path, required=True)
    parser.add_argument("--cargo-registry", type=Path, required=True)
    parser.add_argument("--source-date-epoch", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    try:
        args = parser.parse_args()
        expected = make_archive(
            args.components, args.cargo_registry, args.source_date_epoch
        )
        if args.command == "generate":
            args.output.write_bytes(expected)
        elif args.output.read_bytes() != expected:
            fail("Rust license bundle does not match its sources and metadata")
    except (
        OSError,
        ValueError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        zipfile.BadZipFile,
    ) as error:
        print(f"release Rust license error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
