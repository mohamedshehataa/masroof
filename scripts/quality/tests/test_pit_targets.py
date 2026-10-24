"""Unit tests for scripts/quality/pit-targets.py (does the PIT target set contain production classes?)."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from helpers import QUALITY_DIR, load_script, write

targets = load_script("pit-targets")

KTS = '''pitest {
    targetClasses = setOf(
        "eg.masroof.domain.*", "eg.masroof.*.money.*", "eg.masroof.*.parsing.*",
        "eg.masroof.*.dedup.*", "eg.masroof.*.analytics.*",
    )
}
'''


class PitTargetsTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        write(self.root / "backend" / "build.gradle.kts", KTS)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def java(self, rel: str) -> None:
        write(self.root / "backend" / "src" / "main" / "java" / rel, "class X {}\n")

    def test_pit_targets_no_sources_means_zero(self) -> None:
        self.assertEqual(targets.matching_sources(self.root), [])

    def test_pit_targets_non_target_and_package_info_do_not_count(self) -> None:
        self.java("eg/masroof/adapters/in/rest/HealthController.java")
        self.java("eg/masroof/domain/package-info.java")
        self.assertEqual(targets.matching_sources(self.root), [])

    def test_pit_targets_domain_class_counts(self) -> None:
        self.java("eg/masroof/domain/money/Money.java")
        self.assertEqual(targets.matching_sources(self.root), ["eg.masroof.domain.money.Money"])

    def test_pit_targets_wildcard_package_counts(self) -> None:
        self.java("eg/masroof/application/analytics/Calc.java")
        self.assertEqual(targets.matching_sources(self.root), ["eg.masroof.application.analytics.Calc"])

    def test_pit_targets_missing_glob_is_error_exit_2(self) -> None:
        write(self.root / "backend" / "build.gradle.kts", "pitest { }\n")
        self.assertEqual(targets.main(["--root", str(self.root)]), 2)

    def test_pit_targets_real_build_file_parses(self) -> None:
        globs = targets.target_globs(QUALITY_DIR.parents[1] / "backend" / "build.gradle.kts")
        self.assertIn("eg.masroof.domain.*", globs)


if __name__ == "__main__":
    unittest.main()
