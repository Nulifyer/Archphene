#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/release-native-audit.py"
WORKFLOW = ROOT / ".github/workflows/publish-release-apk.yml"

subprocess.run([sys.executable, str(TOOL)], check=True)
incomplete = subprocess.run(
    [sys.executable, str(TOOL), "--require-complete"],
    check=False,
    capture_output=True,
    text=True,
)
if incomplete.returncode == 0 or "blocked components" not in incomplete.stderr:
    raise SystemExit("native release audit did not fail closed on open blockers")

workflow = WORKFLOW.read_text(encoding="utf-8")
gate = "python3 scripts/release-native-audit.py --require-complete"
if gate not in workflow:
    raise SystemExit("release workflow lacks the complete native-audit gate")
if workflow.index(gate) > workflow.index("Ensure release is a draft"):
    raise SystemExit("native release audit must pass before any draft release write")

print(
    "Release native-audit contract passed: canonical evidence-bound inventory "
    "and fail-closed pre-release gate."
)
