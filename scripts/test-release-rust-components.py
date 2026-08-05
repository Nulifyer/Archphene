#!/usr/bin/env python3
from pathlib import Path
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/release-rust-components.py"

metadata = {
    "version": 1,
    "packages": [
        {"id": "root", "name": "root", "version": "1.0.0", "source": None},
        {
            "id": "normal",
            "name": "normal",
            "version": "2.0.0",
            "source": "registry+https://github.com/rust-lang/crates.io-index",
            "license": "MIT OR Apache-2.0",
        },
        {
            "id": "dev",
            "name": "dev-only",
            "version": "3.0.0",
            "source": "registry+https://github.com/rust-lang/crates.io-index",
            "license": "MIT",
        },
        {
            "id": "macro",
            "name": "compile-macro",
            "version": "4.0.0",
            "source": "registry+https://github.com/rust-lang/crates.io-index",
            "license": "MIT",
            "targets": [{"kind": ["proc-macro"]}],
        },
    ],
    "resolve": {
        "nodes": [
            {
                "id": "root",
                "deps": [
                    {"pkg": "normal", "dep_kinds": [{"kind": None, "target": None}]},
                    {"pkg": "dev", "dep_kinds": [{"kind": "dev", "target": None}]},
                    {"pkg": "macro", "dep_kinds": [{"kind": None, "target": None}]},
                ],
            },
            {"id": "normal", "deps": []},
            {"id": "dev", "deps": []},
            {"id": "macro", "deps": []},
        ]
    },
}
lock = """version = 4

[[package]]
name = "normal"
version = "2.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

[[package]]
name = "dev-only"
version = "3.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

[[package]]
name = "compile-macro"
version = "4.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
"""

with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    metadata_path = directory / "metadata.json"
    lock_path = directory / "Cargo.lock"
    output = directory / "components.json"
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    lock_path.write_text(lock, encoding="utf-8")
    command = [
        sys.executable,
        str(TOOL),
        "--metadata",
        str(metadata_path),
        "--lock",
        str(lock_path),
        "--target",
        "aarch64-linux-android",
        "--root",
        "root",
        "--output",
        str(output),
    ]
    subprocess.run(command, check=True)
    first = output.read_bytes()
    subprocess.run(command, check=True)
    if first != output.read_bytes():
        raise SystemExit("Rust release component manifest is not deterministic")
    document = json.loads(first)
    if document["components"] != [
        {
            "cargoLicense": "MIT OR Apache-2.0",
            "name": "normal",
            "purl": "pkg:cargo/normal@2.0.0",
            "sha256": "a" * 64,
            "source": "registry+https://github.com/rust-lang/crates.io-index",
            "spdxLicense": "MIT OR Apache-2.0",
            "version": "2.0.0",
        }
    ]:
        raise SystemExit("Rust component traversal included a non-runtime dependency")
    metadata["packages"][1]["license"] = None
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    if subprocess.run(command, check=False, capture_output=True).returncode == 0:
        raise SystemExit("Rust component manifest accepted a missing license")

print(
    "Release Rust component contract passed: deterministic normal-dependency "
    "closure, proc-macro exclusion, locked checksums, licenses, and fail-closed metadata."
)
