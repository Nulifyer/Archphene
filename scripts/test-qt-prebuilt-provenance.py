#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PREBUILT = ROOT / "prebuilt/qt-bridge"
REQUIRED = {
    "libarchphene_kde_config.so",
    "libarchphene_qt_platform_theme.so",
    "libarchphene_qt_style.so",
}
IMAGE = (
    "docker.io/archlinux:base-devel-20260705.0.552420@sha256:"
    "b21289eb1954872de0dc9f88976627e38611b1817be75e50946c83ab7b9c474d"
)


def fail(message: str) -> None:
    raise SystemExit(message)


def entries(document: dict[str, object]) -> dict[str, dict[str, object]]:
    return {entry["name"]: entry for entry in document["files"]}


master = json.loads((PREBUILT / "manifest.json").read_text(encoding="utf-8"))
arm = json.loads(
    (PREBUILT / "manifest-arm64-v8a.json").read_text(encoding="utf-8")
)
x86_entries = entries(master)
arm_entries = entries(arm)
embedded_arm = entries(master["additionalArchitectures"][0])
if set(arm_entries) != REQUIRED or arm_entries != embedded_arm:
    fail("Qt AArch64 provenance manifests disagree or have the wrong files")
if not REQUIRED.issubset(x86_entries):
    fail("Qt x86_64 manifest lacks a staged release plugin")

for architecture, directory, manifest_entries in (
    ("x86_64", "x86_64", x86_entries),
    ("arm64-v8a", "arm64-v8a", arm_entries),
):
    for name in sorted(REQUIRED):
        entry = manifest_entries[name]
        binary = PREBUILT / directory / name
        content = binary.read_bytes()
        if entry["bytes"] != len(content) or entry["sha256"] != hashlib.sha256(
            content
        ).hexdigest():
            fail(f"Qt {architecture} artifact does not match its manifest: {name}")
        commit = entry.get("sourceCommit")
        paths = entry.get("sourcePaths")
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            fail(f"Qt {architecture} source commit is invalid: {name}")
        if not isinstance(paths, list) or not paths or paths != sorted(set(paths)):
            fail(f"Qt {architecture} source paths are invalid: {name}")
        binary_commit = subprocess.check_output(
            ["git", "-C", ROOT, "log", "-1", "--format=%H", "--", binary],
            text=True,
        ).strip()
        if binary_commit != commit:
            fail(f"Qt {architecture} binary/source commit is not bound: {name}")
        for path in paths:
            if Path(path).is_absolute() or ".." in Path(path).parts:
                fail(f"Qt {architecture} source path is unsafe: {name}")
            subprocess.run(
                ["git", "-C", ROOT, "cat-file", "-e", f"{commit}:{path}"],
                check=True,
            )

container = (ROOT / "containers/qt-platform-theme.Containerfile").read_text(
    encoding="utf-8"
)
if f"FROM {IMAGE}" not in container or (
    "https://archive.archlinux.org/repos/2026/07/05/$repo/os/$arch" not in container
):
    fail("Qt build container is not image- and repository-snapshot-pinned")
builder = (ROOT / "scripts/build-qt-platform-theme-podman.sh").read_text(
    encoding="utf-8"
)
for marker in ("sourceCommit=commit", "sourcePaths=source_paths[path.name]"):
    if marker not in builder:
        fail(f"Qt build does not preserve source provenance: {marker}")

print(
    "Qt prebuilt provenance passed: exact binary hashes, historical source "
    "commits/paths, and pinned build image/repository snapshot."
)
