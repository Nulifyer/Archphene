#!/usr/bin/env python3
from pathlib import Path
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
    )
    document = json.loads(first.read_text(encoding="utf-8"))
    if (
        document["spdxVersion"] != "SPDX-2.3"
        or len(document["files"]) != 2
        or document["packages"][0]["filesAnalyzed"] is not True
    ):
        raise SystemExit("release SBOM lacks the required SPDX file inventory")
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
        "--output",
        str(directory / "unsafe.spdx.json"),
        check=False,
    ).returncode == 0:
        raise SystemExit("release SBOM generation accepted an unsafe APK path")

print(
    "Release SBOM contract passed: deterministic SPDX 2.3 APK file inventory, "
    "tamper rejection, and bounded paths."
)
