#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

MAX_FILES = 8192
MAX_UNCOMPRESSED_BYTES = 4 * 1024 * 1024 * 1024
MAX_SBOM_BYTES = 32 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024


def fail(message: str) -> None:
    raise ValueError(message)


def validate_text(value: str, label: str, maximum: int = 160) -> str:
    if (
        not value
        or len(value) > maximum
        or any(ord(character) < 0x20 for character in value)
    ):
        fail(f"{label} is invalid")
    return value


def read_apk(apk: Path) -> tuple[str, list[dict[str, object]], str]:
    if not apk.is_file():
        fail(f"APK is missing: {apk}")
    apk_hash = hashlib.sha256()
    with apk.open("rb") as source:
        while chunk := source.read(CHUNK_BYTES):
            apk_hash.update(chunk)

    files: list[dict[str, object]] = []
    names: set[str] = set()
    total_size = 0
    with zipfile.ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if not entries or len(entries) > MAX_FILES:
            fail("APK file count is outside the SBOM bound")
        for entry in sorted(entries, key=lambda item: item.filename):
            name = entry.filename
            path = PurePosixPath(name)
            if (
                not name
                or "\\" in name
                or path.is_absolute()
                or any(part in {"", ".", ".."} for part in path.parts)
                or name in names
            ):
                fail(f"APK contains an unsafe or duplicate path: {name}")
            names.add(name)
            total_size += entry.file_size
            if total_size > MAX_UNCOMPRESSED_BYTES:
                fail("APK uncompressed bytes exceed the SBOM bound")
            sha1 = hashlib.sha1(usedforsecurity=False)
            sha256 = hashlib.sha256()
            actual_size = 0
            with archive.open(entry, "r") as source:
                while chunk := source.read(CHUNK_BYTES):
                    actual_size += len(chunk)
                    sha1.update(chunk)
                    sha256.update(chunk)
            if actual_size != entry.file_size:
                fail(f"APK entry size changed while reading: {name}")
            files.append(
                {
                    "SPDXID": f"SPDXRef-File-{len(files) + 1:05d}",
                    "fileName": f"./{name}",
                    "checksums": [
                        {"algorithm": "SHA1", "checksumValue": sha1.hexdigest()},
                        {"algorithm": "SHA256", "checksumValue": sha256.hexdigest()},
                    ],
                    "licenseConcluded": "NOASSERTION",
                    "licenseInfoInFiles": ["NOASSERTION"],
                    "copyrightText": "NOASSERTION",
                }
            )
    verification_hashes = [
        next(
            checksum["checksumValue"]
            for checksum in file["checksums"]
            if checksum["algorithm"] == "SHA1"
        )
        for file in files
    ]
    verification_input = "".join(sorted(verification_hashes))
    verification_code = hashlib.sha1(
        verification_input.encode("ascii"), usedforsecurity=False
    ).hexdigest()
    return apk_hash.hexdigest(), files, verification_code


def created_timestamp(source_date_epoch: int) -> str:
    if source_date_epoch < 0:
        fail("source date epoch must be nonnegative")
    return datetime.fromtimestamp(source_date_epoch, timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )


def make_document(
    apk: Path,
    artifact_name: str,
    version: str,
    source_revision: str,
    source_date_epoch: int,
) -> dict[str, object]:
    artifact_name = validate_text(artifact_name, "artifact name")
    version = validate_text(version, "version", 64)
    if not re.fullmatch(r"[0-9a-f]{40}", source_revision):
        fail("source revision must be a full lowercase Git commit ID")
    apk_digest, files, verification_code = read_apk(apk)
    package_id = "SPDXRef-Package-APK"
    relationships = [
        {
            "spdxElementId": "SPDXRef-DOCUMENT",
            "relationshipType": "DESCRIBES",
            "relatedSpdxElement": package_id,
        }
    ]
    relationships.extend(
        {
            "spdxElementId": package_id,
            "relationshipType": "CONTAINS",
            "relatedSpdxElement": file["SPDXID"],
        }
        for file in files
    )
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"{artifact_name} SBOM",
        "documentNamespace": (
            "https://github.com/Nulifyer/Archphene/spdx/" + apk_digest
        ),
        "creationInfo": {
            "created": created_timestamp(source_date_epoch),
            "creators": ["Tool: Archphene release-sbom.py"],
        },
        "documentDescribes": [package_id],
        "packages": [
            {
                "name": artifact_name,
                "SPDXID": package_id,
                "versionInfo": version,
                "sourceInfo": (
                    "Built from https://github.com/Nulifyer/Archphene at revision "
                    + source_revision
                ),
                "downloadLocation": "NOASSERTION",
                "filesAnalyzed": True,
                "packageVerificationCode": {
                    "packageVerificationCodeValue": verification_code
                },
                "checksums": [
                    {"algorithm": "SHA256", "checksumValue": apk_digest}
                ],
                "licenseConcluded": "NOASSERTION",
                "licenseDeclared": "NOASSERTION",
                "copyrightText": "NOASSERTION",
            }
        ],
        "files": files,
        "relationships": relationships,
    }


def canonical_bytes(document: dict[str, object]) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8")


def generate(args: argparse.Namespace) -> None:
    document = make_document(
        args.apk,
        args.artifact_name,
        args.version,
        args.source_revision,
        args.source_date_epoch,
    )
    args.output.write_bytes(canonical_bytes(document))


def verify(args: argparse.Namespace) -> None:
    try:
        raw_sbom = args.sbom.read_bytes()
        if len(raw_sbom) > MAX_SBOM_BYTES:
            fail("SBOM exceeds the verification size bound")
        existing = json.loads(raw_sbom.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"SBOM is unreadable: {error}")
    if not isinstance(existing, dict):
        fail("SBOM document must be an object")
    creation_info = existing.get("creationInfo")
    if not isinstance(creation_info, dict) or not isinstance(
        creation_info.get("created"), str
    ):
        fail("SBOM creation timestamp is missing")
    try:
        parsed = datetime.strptime(
            creation_info["created"], "%Y-%m-%dT%H:%M:%SZ"
        ).replace(tzinfo=timezone.utc)
    except ValueError:
        fail("SBOM creation timestamp is invalid")
    expected = make_document(
        args.apk,
        args.artifact_name,
        args.version,
        args.source_revision,
        int(parsed.timestamp()),
    )
    if raw_sbom != canonical_bytes(expected):
        fail("SBOM does not match the APK or release metadata")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Generate or verify an APK SPDX SBOM"
    )
    subparsers = result.add_subparsers(dest="command", required=True)
    generate_parser = subparsers.add_parser("generate")
    generate_parser.add_argument("--apk", type=Path, required=True)
    generate_parser.add_argument("--artifact-name", required=True)
    generate_parser.add_argument("--version", required=True)
    generate_parser.add_argument("--source-revision", required=True)
    generate_parser.add_argument("--source-date-epoch", type=int, required=True)
    generate_parser.add_argument("--output", type=Path, required=True)
    generate_parser.set_defaults(handler=generate)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--apk", type=Path, required=True)
    verify_parser.add_argument("--sbom", type=Path, required=True)
    verify_parser.add_argument("--artifact-name", required=True)
    verify_parser.add_argument("--version", required=True)
    verify_parser.add_argument("--source-revision", required=True)
    verify_parser.set_defaults(handler=verify)
    return result


def main() -> int:
    try:
        args = parser().parse_args()
        args.handler(args)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"release SBOM error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
