#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/release-glibc-source.py"

with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    source = directory / "source"
    repository = directory / "repository"
    source.mkdir()
    repository.mkdir()
    subprocess.run(["git", "init", "--quiet", str(source)], check=True)
    subprocess.run(
        ["git", "-C", str(source), "remote", "add", "origin", "https://sourceware.org/git/glibc.git"],
        check=True,
    )
    (source / "COPYING.LIB").write_text("Fixture LGPL source.\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(source), "add", "COPYING.LIB"], check=True)
    subprocess.run(
        [
            "git", "-C", str(source), "-c", "user.name=Fixture", "-c",
            "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture",
        ],
        check=True,
    )
    commit = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    for relative in (
        "containers/arm-runtime-builder.Containerfile",
        "scripts/build-ci-package-runtime-arm64.sh",
        "scripts/build-ci-package-runtime.sh",
        "patches/glibc/0001-fixture.patch",
    ):
        path = repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"fixture {relative}\n", encoding="utf-8")
    first = directory / "first.zip"
    second = directory / "second.zip"
    common = [
        "--source", str(source), "--repository", str(repository), "--commit", commit,
        "--source-date-epoch", "1785888000",
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
        raise SystemExit("glibc corresponding-source bundle is not deterministic")
    subprocess.run(
        [sys.executable, str(TOOL), "verify", *common, "--output", str(first)],
        check=True,
    )
    with zipfile.ZipFile(first) as archive:
        names = set(archive.namelist())
        if not any(name.startswith("source/glibc-") for name in names) or (
            "archphene/patches/glibc/0001-fixture.patch" not in names
        ):
            raise SystemExit("glibc corresponding-source bundle is incomplete")
    (source / "untracked").write_text("dirty\n", encoding="utf-8")
    if subprocess.run(
        [sys.executable, str(TOOL), "verify", *common, "--output", str(first)],
        check=False,
        capture_output=True,
    ).returncode == 0:
        raise SystemExit("glibc corresponding-source verification accepted a dirty tree")

print(
    "Release glibc source contract passed: deterministic pristine source, exact "
    "patch/build inputs, and dirty-tree rejection."
)
