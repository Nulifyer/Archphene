#!/usr/bin/env python3
"""Regression tests for the exported Linux accessibility-tree validator."""

import subprocess
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).parent / "lib" / "accessibility-tree-check.py"


def node(
    path: str,
    text: str,
    bounds: str,
    *,
    clickable: bool = False,
    actions: int = 64,
) -> str:
    return (
        f"NODE|{path}|android.view.View|{text}|null|{bounds}|true|"
        f"{str(clickable).lower()}|false|false|false|{actions}"
    )


class AccessibilityTreeCheckTest(unittest.TestCase):
    def run_checker(self, lines: list[str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            tree = Path(directory) / "tree.txt"
            tree.write_text("\n".join(lines), encoding="utf-8")
            return subprocess.run(
                [
                    str(CHECKER),
                    str(tree),
                    "--expected-text",
                    "Linux App",
                    "--display-width",
                    "1080",
                    "--display-height",
                    "2202",
                ],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_ignores_android_host_decor_outside_the_display(self) -> None:
        result = self.run_checker(
            [
                node("0", "null", "-1 -3 1082 2205"),
                node("0.0", "null", "-1 -3 1082 2205"),
                node("0.0.0", "Linux App", "100 100 980 2100"),
                node("0.0.0.0", "Toolbar", "100 100 980 200"),
                node("0.0.0.1", "Editor", "100 200 980 1800"),
                node("0.0.0.2", "Cancel", "500 1800 700 1900", clickable=True),
                node("0.0.0.3", "OK", "700 1800 900 1900", clickable=True),
            ],
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_an_exported_linux_control_outside_the_display(self) -> None:
        result = self.run_checker(
            [
                node("0", "null", "0 0 1080 2202"),
                node("0.0", "Linux App", "100 100 980 2100"),
                node("0.0.0", "Toolbar", "100 100 980 200"),
                node("0.0.1", "Editor", "100 200 980 1800"),
                node("0.0.2", "Cancel", "500 1800 700 1900", clickable=True),
                node("0.0.3", "OK", "700 2180 900 2250", clickable=True),
            ],
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside the display", result.stderr)

    def test_validates_parallel_application_and_popup_roots(self) -> None:
        result = self.run_checker(
            [
                node("0", "null", "0 0 1080 2202"),
                node("0.0", "Linux App", "0 100 1080 2202"),
                node("0.0.0", "Toolbar", "0 100 1080 200"),
                node("0.0.1", "Editor", "0 200 1080 2000"),
                node("0.1", "Linux App", "20 100 500 300"),
                node("0.1.0", "Quit", "30 120 490 280", clickable=True),
            ],
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
