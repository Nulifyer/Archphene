#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]

required = (
    "README.md",
    "LICENSE",
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "SECURITY.md",
    "SUPPORT.md",
    "CHANGELOG.md",
    "docs/README.md",
    ".github/ISSUE_TEMPLATE/bug-report.yml",
    ".github/ISSUE_TEMPLATE/feature-request.yml",
    ".github/pull_request_template.md",
)
for relative in required:
    if not (ROOT / relative).is_file():
        raise SystemExit(f"required public repository file is missing: {relative}")

for relative in ("README.md", "CONTRIBUTING.md"):
    text = (ROOT / relative).read_text(encoding="utf-8").lower()
    for stale in ("license has not been selected", "license has not yet been selected"):
        if stale in text:
            raise SystemExit(f"stale licensing claim remains in {relative}: {stale}")

tracked = subprocess.run(
    ["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True
).stdout.decode("utf-8").split("\0")
tracked = [Path(value) for value in tracked if value]
for relative in tracked:
    normalized = relative.as_posix()
    path = ROOT / relative
    if path.is_file() and relative.suffix.lower() == ".ps1":
        raise SystemExit(f"Legacy .ps1 host script is tracked; use Bash: {normalized}")
    if normalized.startswith(("tooling/", "artifacts/")) or "/out/" in f"/{normalized}/":
        raise SystemExit(f"generated workspace is tracked: {normalized}")
    if relative.suffix.lower() in {".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".apk", ".aab"}:
        raise SystemExit(f"secret or release artifact is tracked: {normalized}")
    if path.is_file() and path.stat().st_size > 8 * 1024 * 1024:
        raise SystemExit(f"tracked file exceeds the 8 MiB public-repository limit: {normalized}")

for script in sorted(ROOT.rglob("*.sh")):
    if any(part in {".git", "tooling"} for part in script.parts):
        continue
    subprocess.run(["bash", "-n", str(script)], cwd=ROOT, check=True)

private_key = re.compile(br"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
for relative in tracked:
    path = ROOT / relative
    if path.is_file() and path.stat().st_size <= 8 * 1024 * 1024:
        if private_key.search(path.read_bytes()):
            raise SystemExit(f"private key material is tracked: {relative.as_posix()}")

for manifest_path in sorted((ROOT / "prebuilt").glob("*/manifest.json")):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    variants = [{
        "architecture": manifest["architecture"],
        "files": manifest["files"],
    }, *manifest.get("additionalArchitectures", [])]
    architectures = [variant["architecture"] for variant in variants]
    if len(architectures) != len(set(architectures)):
        raise SystemExit(f"duplicate prebuilt architecture in {manifest_path.relative_to(ROOT)}")
    expected_sums = {}
    for variant in variants:
        architecture = variant["architecture"]
        base = manifest_path.parent / architecture
        declared = {entry["name"]: entry for entry in variant["files"]}
        actual = {path.name for path in base.glob("*.so")}
        if actual != set(declared):
            raise SystemExit(
                f"prebuilt manifest file set differs in {manifest_path.relative_to(ROOT)} "
                f"for {architecture}"
            )
        for name, entry in declared.items():
            path = base / name
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if path.stat().st_size != entry["bytes"] or digest != entry["sha256"]:
                raise SystemExit(f"prebuilt provenance mismatch: {path.relative_to(ROOT)}")
            relative = f"{architecture}/{name}"
            if relative in expected_sums:
                raise SystemExit(f"duplicate prebuilt checksum path: {relative}")
            expected_sums[relative] = entry["sha256"]
    sums = {}
    for line in (manifest_path.parent / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        normalized = Path(name).as_posix()
        if normalized in sums:
            raise SystemExit(f"duplicate checksum path in {manifest_path.relative_to(ROOT)}")
        sums[normalized] = digest
    if sums != expected_sums:
        raise SystemExit(f"SHA256SUMS differs from {manifest_path.relative_to(ROOT)}")
uses = re.compile(r"^\s*-?\s*uses:\s*([^\s]+)", re.MULTILINE)
for workflow in sorted((ROOT / ".github/workflows").glob("*.y*ml")):
    for reference in uses.findall(workflow.read_text(encoding="utf-8")):
        if reference.startswith("./"):
            continue
        if not re.search(r"@[0-9a-f]{40}$", reference):
            raise SystemExit(
                f"GitHub Action is not commit-pinned in {workflow.name}: {reference}"
            )

print(
    f"Public repository audit passed: {len(tracked)} tracked paths, required community files, "
    "prebuilt checksums, Bash syntax, size/secret policy, and commit-pinned Actions."
)
