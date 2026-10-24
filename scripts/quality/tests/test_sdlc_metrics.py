"""Unit tests for scripts/quality/sdlc-metrics.py (SDLC metrics report)."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from helpers import load_script, req, story_md, write

metrics = load_script("sdlc-metrics")

LEDGER = (
    "## AI spend ledger\n"
    "| date | story/phase | model mix | USD | note |\n"
    "|---|---|---|---|---|\n"
    "| 2026-10-23 | PHASE-0 | sonnet/opus | 1.25 | bootstrap |\n"
    "| 2026-10-24 | STORY-001 | sonnet | $2.50 | spec+impl |\n"
    "| 2026-10-25 | STORY-001 | opus | 0.75 | review   |\n"
)


class SdlcMetricsFixture(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.out = self.root / "docs" / "SDLC_METRICS.md"
        (self.root / "docs" / "backlog").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_metrics(self) -> str:
        rc = metrics.main(["--root", str(self.root), "--out", str(self.out)])
        self.assertEqual(rc, 0)
        return self.out.read_text(encoding="utf-8")

    def populate(self) -> None:
        epic = self.root / "docs" / "backlog" / "EPIC-01-x"
        write(epic / "STORY-001-a.md", story_md("STORY-001", "EPIC-01", "DONE", [req("SMS", 1)], phase=1, log_rows=[
            "| 2026-10-01 | spec | product-analyst | READY |",
            "| 2026-10-02 | implement | backend-engineer | done |",
            "| 2026-10-03 | verify | qa-verifier | FAIL |",
            "| 2026-10-04 | verify | qa-verifier | FAIL |",
            "| 2026-10-05 | verify | qa-verifier | PASS |",
            "| 2026-10-07 | review | code-reviewer | APPROVE |",
        ]))
        write(epic / "STORY-002-b.md", story_md("STORY-002", "EPIC-01", "BLOCKED", [req("SMS", 2)], phase=1, log_rows=[
            "| 2026-10-01 | spec | product-analyst | READY |",
            "| 2026-10-03 | verify | qa-verifier | FAIL |",
        ]))
        write(epic / "STORY-003-c.md", story_md("STORY-003", "EPIC-01", "READY", [req("WA", 1)], phase=2))
        write(epic / "STORY-004-d.md", story_md("STORY-004", "EPIC-01", "DONE", [req("WA", 2)], phase=2, log_rows=[
            "| 2026-10-01 10:00 | spec | product-analyst | READY |",
            "| 2026-10-02 10:00 | verify | qa-verifier | PASS |",
        ]))
        write(epic / "STORY-001-a.review.md",
              "# Review report — STORY-001\n\n## Findings\n"
              "| # | Severity (BLOCKER/MAJOR/MINOR/NIT) | Reviewer | Location | Why it matters | Suggested fix | Resolution |\n"
              "|---|---|---|---|---|---|---|\n"
              "| 1 | MAJOR | security-reviewer | a.java | pii | redact | fixed |\n"
              "| 2 | MINOR | code-reviewer | b.java | naming | rename | fixed |\n"
              "| 3 | NIT | code-reviewer | c.java | typo | fix | fixed |\n\nSTATUS: APPROVE\n")
        write(epic / "STORY-001-a.verify.md",
              "# Verification report — STORY-001\n\n## Criteria\n| REQ | Result | Evidence |\n|---|---|---|\n"
              f"| {req('SMS', 1)} | PASS | ok |\n\n## Findings\n| Severity | Location | Problem | Reproduce |\n|---|---|---|---|\n"
              "| BLOCKER | x.java | crash | run |\n\n- [MINOR] loose tolerance in MoneyTest\n\nSTATUS: PASS\n")
        write(epic / "STORY-001-a.bug.md", "# Escaped defect\nrounding bug found in production\n")
        write(self.root / "docs" / "LESSONS.md",
              "# Lessons\n\n- 2026-10-20 — STORY-001 rounded piasters wrongly → test gap → property test added.\n"
              "- 2026-10-21 — tooling note without a story.\n")
        write(self.root / "docs" / "PROGRESS.md",
              "# Progress\n`YYYY-MM-DD HH:MM · P<phase> · <STORY-nnn|SDLC> · <stage> · <GREEN|RED|INFO> · <note>`\n"
              "2026-10-23 10:00 · P1 · STORY-001 · verify · RED · tests failed\n"
              "2026-10-23 11:00 · P1 · STORY-001 · verify · GREEN · ok\n"
              "2026-10-23 12:00 · P1 · STORY-002 · implement · RED · check-fast red\n")
        write(self.root / ".claude" / "state" / "hooks.log", "ts stop-gate BLOCK make check-fast red\nts guard-bash allow\nts protect BLOCKED edit\n")
        write(self.root / "docs" / "FLAKY_TESTS.md", "# Flaky\n| test | since | story |\n|---|---|---|\n| a.T.x | 2026-10-01 | STORY-009 |\n| b.T.y | 2026-10-02 | STORY-010 |\n")
        write(self.out, "# SDLC metrics\n\nold summary\n\n## Summary\n| metric | value |\n|---|---|\n| stories done | 99 |\n\n" + LEDGER)


class SdlcMetricsTests(SdlcMetricsFixture):
    def test_metrics_ledger_preserved_verbatim_and_summed(self) -> None:
        self.populate()
        md = self.run_metrics()
        self.assertTrue(md.endswith(LEDGER), md[-400:])
        self.assertIn("| AI spend (USD) | 4.50 |", md)
        self.assertIn("| STORY-001 | 3.25 |", md)
        self.assertIn("| PHASE-0 | 1.25 |", md)
        self.assertNotIn("old summary", md)

    def test_metrics_phase_budgets_preserved_before_ledger(self) -> None:
        self.populate()
        budgets = "## Phase budgets\n| phase | cap | spent |\n|---|---|---|\n| P0 | 150 | 83 |\n"
        old = self.out.read_text(encoding="utf-8").replace("## AI spend ledger", budgets + "\n## AI spend ledger", 1)
        write(self.out, old)
        md = self.run_metrics()
        self.assertIn(budgets, md)
        self.assertLess(md.index("## Phase budgets"), md.index("## AI spend ledger"))
        self.assertTrue(md.endswith(LEDGER), md[-400:])

    def test_metrics_missing_ledger_gets_default_section(self) -> None:
        md = self.run_metrics()
        self.assertIn("## AI spend ledger\n| date | story/phase | model mix | USD | note |", md)
        self.assertIn("| AI spend (USD) | 0.00 |", md)
        self.assertIn("| stories done | 0 |", md)

    def test_metrics_story_counts_by_status_and_phase(self) -> None:
        self.populate()
        md = self.run_metrics()
        self.assertIn("| stories done | 2 |", md)
        self.assertIn("| stories blocked | 1 |", md)
        self.assertIn("| DONE | 2 |", md)
        self.assertIn("| READY | 1 |", md)
        self.assertIn("| total | 4 |", md)
        # phase 1: DRAFT READY IN_PROGRESS BLOCKED DONE DESCOPED total
        self.assertIn("| 1 | 0 | 0 | 0 | 1 | 1 | 0 | 2 |", md)
        self.assertIn("| 2 | 0 | 1 | 0 | 0 | 1 | 0 | 2 |", md)

    def test_metrics_loops_and_cycle_time(self) -> None:
        self.populate()
        md = self.run_metrics()
        self.assertIn("| STORY-001 | DONE | 2 | 6.0 d |", md)
        self.assertIn("| STORY-002 | BLOCKED | 1 | 2.0 d |", md)
        self.assertIn("| STORY-004 | DONE | 0 | 1.0 d |", md)
        self.assertIn("| mean loops per story | 1.00 |", md)
        self.assertIn("| median cycle time | 3.5 d |", md)  # median of 6.0 and 1.0 over DONE stories
        self.assertIn("| verify | 1 | 4.0 d | 4.0 d |", md)  # 10-03 -> 10-07 in STORY-001
        self.assertIn("| spec | 3 |", md)

    def test_metrics_review_findings_by_severity_and_agent(self) -> None:
        self.populate()
        md = self.run_metrics()
        self.assertIn("| review findings (BLOCKER/MAJOR/MINOR) | 1/1/2 |", md)
        self.assertIn("| BLOCKER | 1 |", md)
        self.assertIn("| NIT | 1 |", md)
        self.assertIn("| security-reviewer | 0 | 1 | 0 | 0 | 1 |", md)
        self.assertIn("| code-reviewer | 0 | 0 | 1 | 1 | 2 |", md)
        self.assertIn("| qa-verifier | 1 | 0 | 1 | 0 | 2 |", md)

    def test_metrics_escaped_defects_gate_failures_flaky(self) -> None:
        self.populate()
        md = self.run_metrics()
        self.assertIn("| escaped defects | 2 |", md)
        self.assertIn("| STORY-001 | `docs/LESSONS.md:3` |", md)
        self.assertIn("| STORY-001 | `docs/backlog/EPIC-01-x/STORY-001-a.bug.md` |", md)
        self.assertIn("| gate failures | 4 |", md)  # 2 RED (legend line excluded) + 2 BLOCK
        self.assertIn("| flaky tests quarantined | 2 |", md)

    def test_metrics_coverage_snapshot_reads_optional_reports(self) -> None:
        self.populate()
        write(self.root / "backend" / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml",
              '<report name="b"><counter type="LINE" missed="10" covered="90"/><counter type="BRANCH" missed="25" covered="75"/></report>')
        write(self.root / "backend" / "build" / "reports" / "pitest" / "mutations.xml",
              '<mutations><mutation detected="true"><mutatedClass>a.A</mutatedClass></mutation>'
              '<mutation detected="false"><mutatedClass>a.A</mutatedClass></mutation></mutations>')
        write(self.root / "web" / "coverage" / "coverage-summary.json",
              '{"total": {"lines": {"pct": 91.5}, "branches": {"pct": 80}}}')
        write(self.root / "eval" / "reports" / "2026-10-01.md", "# old\n| metric | value |\n|---|---|\n| amount | 90% |\n")
        write(self.root / "eval" / "reports" / "2026-10-02.md", "# new\nintro\n| metric | value |\n|---|---|\n| amount | 98% |\n\ntext\n")
        md = self.run_metrics()
        self.assertIn("| backend line coverage (JaCoCo) | 90.0% |", md)
        self.assertIn("| backend branch coverage (JaCoCo) | 75.0% |", md)
        self.assertIn("| backend mutation score (PIT) | 50.0% |", md)
        self.assertIn("| web line coverage (Vitest) | 91.5% |", md)
        self.assertIn("| android line coverage (Kover) | – | `no report` |", md)
        self.assertIn("### Latest eval summary (`eval/reports/2026-10-02.md`)", md)
        self.assertIn("| amount | 98% |", md)
        self.assertNotIn("| amount | 90% |", md)

    def test_metrics_output_is_deterministic(self) -> None:
        self.populate()
        first = self.run_metrics()
        second = self.run_metrics()
        self.assertEqual(first, second)

    def test_metrics_usage_error_exit_2(self) -> None:
        self.assertEqual(metrics.main(["--root", str(self.root / "nope")]), 2)


if __name__ == "__main__":
    unittest.main()
