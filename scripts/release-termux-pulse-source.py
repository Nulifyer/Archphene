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
import urllib.request
import zipfile

MAX_DOWNLOAD_BYTES = 256 * 1024 * 1024
MAX_BUNDLE_BYTES = 1024 * 1024 * 1024
LICENSE_FILE = re.compile(
    r"^(licen[sc]e|copying|unlicense|notice|copyright)([._-].*)?$", re.IGNORECASE
)


def fail(message: str) -> None:
    raise ValueError(message)


def digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def license_files(content: bytes) -> list[tuple[str, bytes]]:
    result: list[tuple[str, bytes]] = []
    with tarfile.open(fileobj=io.BytesIO(content), mode="r:*") as archive:
        for member in sorted(archive.getmembers(), key=lambda item: item.name):
            path = PurePosixPath(member.name)
            candidate = LICENSE_FILE.fullmatch(path.name) is not None or any(
                part.lower() in {"license", "licenses"} for part in path.parts[:-1]
            )
            if not member.isfile() or path.is_absolute() or ".." in path.parts or not candidate:
                continue
            if member.size <= 0 or member.size > 4 * 1024 * 1024:
                fail(f"Termux Pulse license file size is invalid: {path}")
            source = archive.extractfile(member)
            if source is None:
                fail(f"Termux Pulse license file is unreadable: {path}")
            result.append((path.as_posix(), source.read()))
    return result


def download(cache: Path, url: str, expected: str) -> bytes:
    if cache.is_file() and digest(cache.read_bytes()) == expected:
        return cache.read_bytes()
    cache.unlink(missing_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "Archphene-release/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        content = response.read(MAX_DOWNLOAD_BYTES + 1)
    if not content or len(content) > MAX_DOWNLOAD_BYTES or digest(content) != expected:
        fail(f"Termux Pulse source download changed: {url}")
    cache.write_bytes(content)
    return content


def zip_info(name: str, epoch: int) -> zipfile.ZipInfo:
    value = datetime.fromtimestamp(epoch, timezone.utc)
    if value.year < 1980 or value.year > 2107:
        fail("source date epoch is outside the ZIP timestamp range")
    info = zipfile.ZipInfo(
        name, (value.year, value.month, value.day, value.hour, value.minute, value.second)
    )
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def make_bundle(manifest: Path, repository: Path, cache: Path, epoch: int) -> bytes:
    raw_manifest = manifest.read_bytes()
    document = json.loads(raw_manifest.decode("utf-8"))
    if document.get("format") != "org.archphene.termux-pulse-recipes.v1":
        fail("Termux Pulse source manifest is invalid")
    entries: list[tuple[str, bytes]] = [("termux-pulse-recipes.json", raw_manifest)]
    index: list[dict[str, object]] = []
    cache.mkdir(parents=True, exist_ok=True)
    for record in document["packages"]:
        package = record["binaryPackage"]
        recipe_path = f"packages/{record['recipePackage']}"
        recipe = subprocess.run(
            [
                "git", "-C", str(repository), "archive", "--format=tar",
                record["recipeCommit"], "build-package.sh", "scripts", recipe_path,
            ],
            check=True,
            capture_output=True,
        ).stdout
        if not recipe:
            fail(f"Termux Pulse recipe archive is empty: {package}")
        recipe_name = f"recipes/{package}-{record['recipeCommit']}.tar"
        entries.append((recipe_name, recipe))
        source_record = record.get("sourceArchive")
        sources: list[tuple[str, bytes]] = [(recipe_name, recipe)]
        source_name = None
        if source_record is not None:
            source_name = f"sources/{package}-{source_record['sha256']}.tar"
            source = download(
                cache / f"{package}-{source_record['sha256']}.source",
                source_record["url"],
                source_record["sha256"],
            )
            entries.append((source_name, source))
            sources.append((source_name, source))
        indexed_licenses: list[dict[str, str]] = []
        seen: set[tuple[str, str]] = set()
        for source_label, source in sources:
            for relative, content in license_files(source):
                identity = (relative, digest(content))
                if identity in seen:
                    continue
                seen.add(identity)
                license_name = f"licenses/{package}/{len(indexed_licenses):03d}-{Path(relative).name}"
                entries.append((license_name, content))
                indexed_licenses.append(
                    {"path": license_name, "sha256": digest(content), "sourcePath": relative}
                )
        if not indexed_licenses:
            fail(f"Termux Pulse source has no license file: {package}")
        index.append(
            {
                "binaryPackage": package,
                "licenses": indexed_licenses,
                "recipe": {"path": recipe_name, "sha256": digest(recipe)},
                "source": (
                    None
                    if source_name is None
                    else {"path": source_name, "sha256": source_record["sha256"]}
                ),
                "version": record["version"],
            }
        )
    index_document = {
        "format": "org.archphene.termux-pulse-source-bundle.v1",
        "manifestSha256": digest(raw_manifest),
        "packages": index,
    }
    entries.append(
        ("index.json", (json.dumps(index_document, indent=2, sort_keys=True) + "\n").encode())
    )
    if len({name for name, _ in entries}) != len(entries):
        fail("Termux Pulse source bundle contains a duplicate path")
    if sum(len(content) for _, content in entries) > MAX_BUNDLE_BYTES:
        fail("Termux Pulse source bundle exceeds the size bound")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", allowZip64=True) as archive:
        for name, content in sorted(entries):
            archive.writestr(zip_info(name, epoch), content)
    return output.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(description="Build Termux Pulse corresponding source")
    parser.add_argument("command", choices=("generate", "verify"))
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--source-date-epoch", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    try:
        args = parser.parse_args()
        expected = make_bundle(
            args.manifest, args.repository, args.cache, args.source_date_epoch
        )
        if args.command == "generate":
            args.output.write_bytes(expected)
        elif args.output.read_bytes() != expected:
            fail("Termux Pulse source bundle does not match its inputs")
    except (
        OSError, ValueError, UnicodeDecodeError, json.JSONDecodeError,
        subprocess.CalledProcessError, tarfile.TarError,
    ) as error:
        print(f"Termux Pulse source error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
