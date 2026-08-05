#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/release-rust-licenses.py"

with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    registry = directory / "registry"
    source = registry / "src" / "fixture-index" / "fixture-1.2.3"
    source.mkdir(parents=True)
    license_text = b"Fixture license text.\n"
    notice_text = b"Fixture copyright notice.\n"
    (source / "LICENSE-MIT").write_bytes(license_text)
    nested = source / "vendor"
    nested.mkdir()
    (nested / "NOTICE.txt").write_bytes(notice_text)
    licenses = source / "LICENSES"
    licenses.mkdir()
    (licenses / "MIT.txt").write_bytes(license_text)
    (source / "README.md").write_text("not a license candidate\n", encoding="utf-8")
    components = directory / "components.json"
    components.write_text(
        json.dumps(
            {
                "format": "org.archphene.rust-components.v1",
                "roots": ["fixture-root"],
                "target": "x86_64-linux-android",
                "components": [
                    {
                        "cargoLicense": "MIT",
                        "name": "fixture",
                        "purl": "pkg:cargo/fixture@1.2.3",
                        "sha256": "a" * 64,
                        "source": "registry+https://example.invalid/index",
                        "spdxLicense": "MIT",
                        "version": "1.2.3",
                    }
                ],
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    first = directory / "first.zip"
    second = directory / "second.zip"
    common = [
        "--components",
        str(components),
        "--cargo-registry",
        str(registry),
        "--source-date-epoch",
        "1785888000",
    ]
    subprocess.run(
        [sys.executable, str(TOOL), "generate", *common, "--output", str(first)],
        check=True,
    )
    subprocess.run(
        [sys.executable, str(TOOL), "generate", *common, "--output", str(second)],
        check=True,
    )
    if first.read_bytes() != second.read_bytes():
        raise SystemExit("Rust license bundle generation is not deterministic")
    subprocess.run(
        [sys.executable, str(TOOL), "verify", *common, "--output", str(first)],
        check=True,
    )
    with zipfile.ZipFile(first) as archive:
        if set(archive.namelist()) != {
            "components.json",
            "index.json",
            "licenses/fixture-1.2.3/LICENSE-MIT",
            "licenses/fixture-1.2.3/LICENSES/MIT.txt",
            "licenses/fixture-1.2.3/vendor/NOTICE.txt",
        }:
            raise SystemExit("Rust license bundle contains the wrong paths")
        index = json.loads(archive.read("index.json"))
        if index["components"][0]["files"][0]["sha256"] != hashlib.sha256(
            license_text
        ).hexdigest():
            raise SystemExit("Rust license bundle index has the wrong digest")
    changed = bytearray(first.read_bytes())
    changed[-1] ^= 1
    first.write_bytes(changed)
    if subprocess.run(
        [sys.executable, str(TOOL), "verify", *common, "--output", str(first)],
        check=False,
        capture_output=True,
    ).returncode == 0:
        raise SystemExit("Rust license bundle verification accepted changed bytes")
    (source / "LICENSE-MIT").unlink()
    (licenses / "MIT.txt").unlink()
    (nested / "NOTICE.txt").unlink()
    if subprocess.run(
        [sys.executable, str(TOOL), "generate", *common, "--output", str(first)],
        check=False,
        capture_output=True,
    ).returncode == 0:
        raise SystemExit("Rust license bundle accepted a component without license files")

print(
    "Release Rust license contract passed: deterministic bounded bundles, nested "
    "license discovery, source binding, and tamper rejection."
)
