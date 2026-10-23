"""Unit tests for scripts/quality/coverage-gate.py (coverage / mutation / flaky thresholds gate)."""
from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import yaml

from helpers import QUALITY_DIR, load_script, write

gate = load_script("coverage-gate")
THRESHOLDS = QUALITY_DIR / "quality-thresholds.yaml"


def jacoco(packages: dict[str, tuple[int, int, int, int]], overall: tuple[int, int, int, int]) -> str:
    """packages: name -> (line covered, line missed, branch covered, branch missed)."""
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<report name="backend">']
    for name, (lc, lm, bc, bm) in packages.items():
        out.append(f'  <package name="{name}"><class name="{name}/X"/>'
                   f'<counter type="LINE" missed="{lm}" covered="{lc}"/><counter type="BRANCH" missed="{bm}" covered="{bc}"/></package>')
    lc, lm, bc, bm = overall
    out.append(f'  <counter type="LINE" missed="{lm}" covered="{lc}"/><counter type="BRANCH" missed="{bm}" covered="{bc}"/>')
    out.append("</report>")
    return "\n".join(out) + "\n"


def pit(classes: dict[str, tuple[int, int]]) -> str:
    """classes: FQN -> (killed, survived)."""
    out = ["<mutations>"]
    for cls, (killed, survived) in classes.items():
        out.extend(f'<mutation detected="true" status="KILLED"><mutatedClass>{cls}</mutatedClass></mutation>' for _ in range(killed))
        out.extend(f'<mutation detected="false" status="SURVIVED"><mutatedClass>{cls}</mutatedClass></mutation>' for _ in range(survived))
    out.append("</mutations>")
    return "\n".join(out) + "\n"


GOOD_JACOCO = jacoco({
    "com/masroof/domain/money": (97, 3, 92, 8),
    "com/masroof/domain/parsing": (96, 4, 91, 9),
    "com/masroof/domain/dedup": (99, 1, 95, 5),
    "com/masroof/application/analytics": (95, 5, 90, 10),
    "com/masroof/adapters/in/web": (80, 20, 70, 30),
}, (900, 100, 780, 220))
GOOD_PIT = pit({
    "com.masroof.domain.money.Money": (85, 15),
    "com.masroof.domain.parsing.Parser": (82, 18),
    "com.masroof.domain.dedup.Matcher": (90, 10),
    "com.masroof.application.analytics.Calc": (70, 30),
    "com.masroof.domain.household.Rules": (66, 34),
})


class CoverageGateFixture(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_gate(self) -> tuple[int, str]:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = gate.main(["--root", str(self.root), "--thresholds", str(THRESHOLDS)])
        return rc, buf.getvalue()

    def backend(self, jac: str | None = GOOD_JACOCO, pit_xml: str | None = GOOD_PIT) -> None:
        write(self.root / "backend" / "gradlew", "#!/bin/sh\n")
        if jac is not None:
            write(self.root / "backend" / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml", jac)
        if pit_xml is not None:
            write(self.root / "backend" / "build" / "reports" / "pitest" / "mutations.xml", pit_xml)


class CoverageGateTests(CoverageGateFixture):
    def test_coverage_gate_thresholds_file_matches_s9(self) -> None:
        th = yaml.safe_load(THRESHOLDS.read_text(encoding="utf-8"))
        self.assertEqual((th["backend"]["line"], th["backend"]["branch"]), (85, 75))
        for pkg in ("money", "parsing", "dedup", "analytics"):
            self.assertEqual((th["backend"]["packages"][pkg]["line"], th["backend"]["packages"][pkg]["branch"]), (95, 90))
        for pkg in ("money", "parsing", "dedup"):
            self.assertEqual(th["backend"]["packages"][pkg]["mutation"], 80)
        self.assertEqual(th["backend"]["packages"]["analytics"]["mutation"], 65)
        self.assertEqual(th["backend"]["domain_mutation"], 65)
        for pkg in ("ingestion", "filtering", "outbox", "viewmodel"):
            self.assertEqual(th["android"]["packages"][pkg]["line"], 80)
        for p in ("hooks", "utils", "components"):
            self.assertEqual(th["web"]["paths"][p]["line"], 85)
        self.assertEqual(th["flaky"]["max_quarantined"], 5)

    def test_coverage_gate_skips_modules_that_do_not_exist(self) -> None:
        rc, out = self.run_gate()
        self.assertEqual(rc, 0, out)
        self.assertIn("module not present (backend/gradlew)", out)
        self.assertIn("module not present (android/gradlew)", out)
        self.assertIn("module not present (web/package.json)", out)
        self.assertIn("COVERAGE_GATE: OK", out)

    def test_coverage_gate_missing_report_for_existing_module_fails(self) -> None:
        self.backend(jac=None, pit_xml=None)
        rc, out = self.run_gate()
        self.assertEqual(rc, 1)
        self.assertIn("JaCoCo report", out)
        self.assertIn("PIT report", out)
        self.assertIn("COVERAGE_GATE: FAIL", out)

    def test_coverage_gate_backend_passes_with_good_reports(self) -> None:
        self.backend()
        rc, out = self.run_gate()
        self.assertEqual(rc, 0, out)
        self.assertIn("line coverage (overall)", out)
        self.assertIn("90.0%", out)
        self.assertIn("mutation score (money)", out)
        self.assertIn("mutation score (domain, other packages)", out)
        self.assertNotIn("FAIL", out.replace("COVERAGE_GATE: OK", ""))

    def test_coverage_gate_low_package_coverage_fails(self) -> None:
        bad = GOOD_JACOCO.replace('<package name="com/masroof/domain/money"><class name="com/masroof/domain/money/X"/>'
                                  '<counter type="LINE" missed="3" covered="97"/>',
                                  '<package name="com/masroof/domain/money"><class name="com/masroof/domain/money/X"/>'
                                  '<counter type="LINE" missed="10" covered="90"/>')
        self.assertNotEqual(bad, GOOD_JACOCO)
        self.backend(jac=bad)
        rc, out = self.run_gate()
        self.assertEqual(rc, 1)
        line = next(l for l in out.splitlines() if "line coverage (money)" in l)
        self.assertIn("FAIL", line)
        self.assertIn("90.0%", line)

    def test_coverage_gate_low_mutation_score_fails(self) -> None:
        self.backend(pit_xml=pit({"com.masroof.domain.parsing.Parser": (70, 30), "com.masroof.domain.money.Money": (90, 10)}))
        rc, out = self.run_gate()
        self.assertEqual(rc, 1)
        line = next(l for l in out.splitlines() if "mutation score (parsing)" in l)
        self.assertIn("FAIL", line)
        self.assertIn("70.0%", line)
        # tokens without mutated classes are skipped, not failed
        self.assertIn("no mutated class containing 'dedup'", out)

    def test_coverage_gate_web_and_android(self) -> None:
        write(self.root / "web" / "package.json", "{}")
        write(self.root / "web" / "coverage" / "coverage-summary.json",
              '{"total": {"lines": {"total": 200, "covered": 180, "pct": 90}},'
              ' "C:\\\\w\\\\src\\\\hooks\\\\useA.ts": {"lines": {"total": 100, "covered": 90}},'
              ' "/w/src/utils/money.ts": {"lines": {"total": 100, "covered": 70}},'
              ' "/w/src/components/A.tsx": {"lines": {"total": 50, "covered": 50}}}')
        write(self.root / "android" / "gradlew", "#!/bin/sh\n")
        write(self.root / "android" / "app" / "build" / "reports" / "kover" / "reportDebug.xml",
              jacoco({"com/masroof/ingestion": (85, 15, 0, 0), "com/masroof/outbox": (50, 50, 0, 0)}, (135, 65, 0, 0)))
        rc, out = self.run_gate()
        self.assertEqual(rc, 1)
        lines = out.splitlines()
        self.assertIn("PASS", next(l for l in lines if "line coverage (hooks)" in l))
        self.assertIn("FAIL", next(l for l in lines if "line coverage (utils)" in l))
        self.assertIn("PASS", next(l for l in lines if "line coverage (components)" in l))
        self.assertIn("PASS", next(l for l in lines if "line coverage (ingestion)" in l))
        self.assertIn("FAIL", next(l for l in lines if "line coverage (outbox)" in l))
        self.assertIn("SKIP", next(l for l in lines if "line coverage (viewmodel)" in l))

    def test_coverage_gate_flaky_quarantine_limit(self) -> None:
        rows = "".join(f"| t{i} | 2026-10-0{i} | STORY-00{i} |\n" for i in range(1, 7))
        write(self.root / "docs" / "FLAKY_TESTS.md", "# Flaky\n| test | since | story |\n|---|---|---|\n" + rows)
        rc, out = self.run_gate()
        self.assertEqual(rc, 1)
        self.assertIn("quarantined flaky tests  6", out.replace("   ", " ").replace("  ", "  "))
        self.assertIn("<= 5", out)

    def test_coverage_gate_usage_error_exit_2(self) -> None:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            rc = gate.main(["--root", str(self.root), "--thresholds", str(self.root / "nope.yaml")])
        self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
