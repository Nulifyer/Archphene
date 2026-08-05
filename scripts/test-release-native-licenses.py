#!/usr/bin/env python3
from pathlib import Path
import hashlib
import io
import json
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/release-native-licenses.py"

with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    archive_path = directory / "fixture.tar.gz"
    archive_content = b"Archive license.\n"
    with tarfile.open(archive_path, "w:gz") as archive:
        info = tarfile.TarInfo("fixture-1.0/LICENSE")
        info.size = len(archive_content)
        info.mtime = 0
        archive.addfile(info, io.BytesIO(archive_content))
    git_tree = directory / "git-source"
    git_tree.mkdir()
    (git_tree / "COPYING").write_text("Git license.\n", encoding="utf-8")
    subprocess.run(["git", "init", "--quiet", str(git_tree)], check=True)
    subprocess.run(["git", "-C", str(git_tree), "add", "COPYING"], check=True)
    subprocess.run(
        [
            "git", "-C", str(git_tree), "-c", "user.name=Fixture",
            "-c", "user.email=fixture@example.invalid", "commit", "--quiet",
            "-m", "fixture",
        ],
        check=True,
    )
    commit = subprocess.run(
        ["git", "-C", str(git_tree), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    manifest = directory / "native-components.json"
    components = [
        {
            "declaredLicense": "MIT", "id": "archive", "sourcePin": {
                "type": "sha256", "value": hashlib.sha256(archive_path.read_bytes()).hexdigest()
            }, "sourceUrl": "https://example.invalid/archive", "version": "1.0"
        },
        {
            "declaredLicense": "MIT", "id": "git", "sourcePin": {
                "type": "git-commit", "value": commit
            }, "sourceUrl": "https://example.invalid/git", "version": "1.0"
        },
    ]
    manifest.write_text(
        json.dumps(
            {
                "components": components,
                "format": "org.archphene.native-release-audit.v1",
                "licenseSources": [
                    {"componentId": "archive", "path": archive_path.name, "type": "archive"},
                    {"componentId": "git", "path": git_tree.name, "type": "git-tree"},
                ],
            },
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    first = directory / "first.zip"
    second = directory / "second.zip"
    common = [
        "--manifest", str(manifest), "--root", str(directory),
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
        raise SystemExit("native license bundle is not deterministic")
    subprocess.run(
        [sys.executable, str(TOOL), "verify", *common, "--output", str(first)],
        check=True,
    )
    with zipfile.ZipFile(first) as archive:
        names = set(archive.namelist())
        if "licenses/archive/fixture-1.0/LICENSE" not in names or (
            "licenses/git/COPYING" not in names
        ):
            raise SystemExit("native license bundle lacks expected source licenses")
    changed = bytearray(first.read_bytes())
    changed[-1] ^= 1
    first.write_bytes(changed)
    if subprocess.run(
        [sys.executable, str(TOOL), "verify", *common, "--output", str(first)],
        check=False,
        capture_output=True,
    ).returncode == 0:
        raise SystemExit("native license bundle accepted changed bytes")

print(
    "Release native-license contract passed: deterministic archive and Git "
    "license collection, source-pin binding, and tamper rejection."
)
