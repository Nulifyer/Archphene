#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/release-sbom.py"


def run(*arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(TOOL), *arguments],
        check=check,
        text=True,
        capture_output=True,
    )


with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    apk = directory / "fixture.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("lib/x86_64/libfixture.so", b"native-fixture")
    first = directory / "first.spdx.json"
    second = directory / "second.spdx.json"
    components = directory / "components.json"
    license_bundle = directory / "licenses.zip"
    license_bundle.write_bytes(b"deterministic Rust license bundle fixture")
    components.write_text(
        json.dumps(
            {
                "format": "org.archphene.rust-components.v1",
                "roots": ["fixture"],
                "target": "x86_64-linux-android",
                "components": [
                    {
                        "cargoLicense": "MIT/Apache-2.0",
                        "name": "fixture-dependency",
                        "purl": "pkg:cargo/fixture-dependency@2.0.0",
                        "sha256": "a" * 64,
                        "source": "registry+https://github.com/rust-lang/crates.io-index",
                        "spdxLicense": "MIT OR Apache-2.0",
                        "version": "2.0.0",
                    }
                ],
            },
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    common = (
        "--apk",
        str(apk),
        "--artifact-name",
        "Archphene-x86_64-1.2.3.apk",
        "--version",
        "1.2.3",
        "--source-revision",
        "0123456789abcdef0123456789abcdef01234567",
        "--source-date-epoch",
        "1785888000",
        "--rust-components",
        str(components),
        "--rust-target",
        "x86_64-linux-android",
        "--rust-license-bundle",
        str(license_bundle),
    )
    run("generate", *common, "--output", str(first))
    run("generate", *common, "--output", str(second))
    if first.read_bytes() != second.read_bytes():
        raise SystemExit("release SBOM generation is not deterministic")
    run(
        "verify",
        "--apk",
        str(apk),
        "--sbom",
        str(first),
        "--artifact-name",
        "Archphene-x86_64-1.2.3.apk",
        "--version",
        "1.2.3",
        "--source-revision",
        "0123456789abcdef0123456789abcdef01234567",
        "--rust-components",
        str(components),
        "--rust-target",
        "x86_64-linux-android",
        "--rust-license-bundle",
        str(license_bundle),
    )
    document = json.loads(first.read_text(encoding="utf-8"))
    if (
        document["spdxVersion"] != "SPDX-2.3"
        or len(document["files"]) != 2
        or document["packages"][0]["filesAnalyzed"] is not True
        or len(document["packages"]) != 2
        or document["packages"][1]["licenseDeclared"] != "MIT OR Apache-2.0"
        or document["relationships"][-1]["relationshipType"] != "STATIC_LINK"
        or hashlib.sha256(license_bundle.read_bytes()).hexdigest()
        not in document["comment"]
    ):
        raise SystemExit("release SBOM lacks the required SPDX file inventory")
    component_bytes = components.read_bytes()
    changed_components = json.loads(component_bytes)
    changed_components["components"][0]["sha256"] = "b" * 64
    components.write_text(json.dumps(changed_components), encoding="utf-8")
    if run(
        "verify",
        "--apk",
        str(apk),
        "--sbom",
        str(first),
        "--artifact-name",
        "Archphene-x86_64-1.2.3.apk",
        "--version",
        "1.2.3",
        "--source-revision",
        "0123456789abcdef0123456789abcdef01234567",
        "--rust-components",
        str(components),
        "--rust-target",
        "x86_64-linux-android",
        "--rust-license-bundle",
        str(license_bundle),
        check=False,
    ).returncode == 0:
        raise SystemExit("release SBOM verification accepted changed Rust metadata")
    components.write_bytes(component_bytes)
    license_bundle.write_bytes(b"changed license bundle")
    if run(
        "verify",
        "--apk",
        str(apk),
        "--sbom",
        str(first),
        "--artifact-name",
        "Archphene-x86_64-1.2.3.apk",
        "--version",
        "1.2.3",
        "--source-revision",
        "0123456789abcdef0123456789abcdef01234567",
        "--rust-components",
        str(components),
        "--rust-target",
        "x86_64-linux-android",
        "--rust-license-bundle",
        str(license_bundle),
        check=False,
    ).returncode == 0:
        raise SystemExit("release SBOM verification accepted a changed license bundle")
    license_bundle.write_bytes(b"deterministic Rust license bundle fixture")
    document["files"][0]["checksums"][1]["checksumValue"] = "0" * 64
    first.write_text(json.dumps(document), encoding="utf-8")
    if run(
        "verify",
        "--apk",
        str(apk),
        "--sbom",
        str(first),
        "--artifact-name",
        "Archphene-x86_64-1.2.3.apk",
        "--version",
        "1.2.3",
        "--source-revision",
        "0123456789abcdef0123456789abcdef01234567",
        "--rust-components",
        str(components),
        "--rust-target",
        "x86_64-linux-android",
        "--rust-license-bundle",
        str(license_bundle),
        check=False,
    ).returncode == 0:
        raise SystemExit("release SBOM verification accepted a changed file digest")
    unsafe_apk = directory / "unsafe.apk"
    with zipfile.ZipFile(unsafe_apk, "w") as archive:
        archive.writestr("../escape", b"unsafe")
    if run(
        "generate",
        "--apk",
        str(unsafe_apk),
        "--artifact-name",
        "unsafe.apk",
        "--version",
        "1.2.3",
        "--source-revision",
        "0123456789abcdef0123456789abcdef01234567",
        "--source-date-epoch",
        "1785888000",
        "--rust-components",
        str(components),
        "--rust-target",
        "x86_64-linux-android",
        "--rust-license-bundle",
        str(license_bundle),
        "--output",
        str(directory / "unsafe.spdx.json"),
        check=False,
    ).returncode == 0:
        raise SystemExit("release SBOM generation accepted an unsafe APK path")

print(
    "Release SBOM contract passed: deterministic SPDX 2.3 APK file inventory, "
    "locked Rust components, tamper rejection, and bounded paths."
)
