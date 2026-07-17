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
    if normalized.startswith(("tooling/", "artifacts/")) or "/out/" in f"/{normalized}/":
        raise SystemExit(f"generated workspace is tracked: {normalized}")
    if relative.suffix.lower() in {".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".apk", ".aab"}:
        raise SystemExit(f"secret or release artifact is tracked: {normalized}")
    path = ROOT / relative
    if path.is_file() and path.stat().st_size > 8 * 1024 * 1024:
        raise SystemExit(f"tracked file exceeds the 8 MiB public-repository limit: {normalized}")

private_key = re.compile(br"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
for relative in tracked:
    path = ROOT / relative
    if path.is_file() and path.stat().st_size <= 8 * 1024 * 1024:
        if private_key.search(path.read_bytes()):
            raise SystemExit(f"private key material is tracked: {relative.as_posix()}")

for manifest_path in sorted((ROOT / "prebuilt").glob("*/manifest.json")):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    base = manifest_path.parent / manifest["architecture"]
    declared = {entry["name"]: entry for entry in manifest["files"]}
    actual = {path.name for path in base.glob("*.so")}
    if actual != set(declared):
        raise SystemExit(f"prebuilt manifest file set differs in {manifest_path.relative_to(ROOT)}")
    for name, entry in declared.items():
        path = base / name
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.stat().st_size != entry["bytes"] or digest != entry["sha256"]:
            raise SystemExit(f"prebuilt provenance mismatch: {path.relative_to(ROOT)}")
    sums = {}
    for line in (manifest_path.parent / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        sums[Path(name).name] = digest
    if sums != {name: entry["sha256"] for name, entry in declared.items()}:
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
    "prebuilt checksums, size/secret policy, and commit-pinned Actions."
)
