"""Unit tests for scripts/quality/traceability.py (traceability matrix + gate)."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from helpers import junit_xml, load_script, req, story_md, write

trace = load_script("traceability")


class TraceabilityFixture(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "docs" / "backlog").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_trace(self, *extra: str) -> tuple[int, str]:
        out = self.root / "docs" / "TRACEABILITY.md"
        rc = trace.main(["--root", str(self.root), "--out", str(out), *extra])
        return rc, out.read_text(encoding="utf-8")

    def add_story(self, sid: str, status: str, reqs: list[str], epic: str = "EPIC-01-x") -> None:
        write(self.root / "docs" / "backlog" / epic / f"{sid}-slug.md", story_md(sid, epic.split("-")[0] + "-01", status, reqs))

    def add_java_test(self, cls: str, reqs: list[str]) -> None:
        body = "\n".join(f'  @Test @Tag("{r}") void t{i}() {{ assertTrue(true); }}' for i, r in enumerate(reqs))
        write(self.root / "backend" / "src" / "test" / "java" / "m" / f"{cls}.java", f"class {cls} {{\n{body}\n}}\n")

    def add_results(self, name: str, cases: list[tuple[str, str, str]]) -> None:
        write(self.root / "backend" / "build" / "test-results" / "test" / f"TEST-{name}.xml", junit_xml(cases))


class TraceabilityGateTests(TraceabilityFixture):
    def test_traceability_empty_backlog_writes_report_and_passes(self) -> None:
        rc, md = self.run_trace()
        self.assertEqual(rc, 0)
        self.assertIn("| total REQs | 0 |", md)
        self.assertIn("| gate | PASS |", md)

    def test_traceability_covered_req_with_passing_result(self) -> None:
        r1 = req("SMS", 1)
        self.add_story("STORY-001", "DONE", [r1])
        self.add_java_test("SmsParserTest", [r1])
        self.add_results("SmsParserTest", [("m.SmsParserTest", "t0", "PASS")])
        rc, md = self.run_trace()
        self.assertEqual(rc, 0, md)
        self.assertIn("| covered (at least one test) | 1 |", md)
        self.assertIn("| passing | 1 |", md)
        self.assertIn(f"| {r1} | requirement 001 | STORY-001 | DONE | 1 |", md)
        self.assertIn("(PASS) | PASS |", md)

    def test_traceability_pending_req_of_ready_story_is_not_a_failure(self) -> None:
        r1 = req("WA", 2)
        self.add_story("STORY-002", "READY", [r1])
        rc, md = self.run_trace()
        self.assertEqual(rc, 0)
        self.assertIn("| pending (story not DONE, no test yet) | 1 |", md)
        self.assertIn("| orphans (DONE story, no test) | 0 |", md)

    def test_traceability_unknown_req_in_test_fails_gate(self) -> None:
        r1, ghost = req("SMS", 1), req("NTF", 9)
        self.add_story("STORY-001", "READY", [r1])
        self.add_java_test("NtfTest", [ghost])
        rc, md = self.run_trace()
        self.assertEqual(rc, 1)
        self.assertIn(f"unknown REQ {ghost}", md)
        self.assertIn("| unknown REQ references | 1 |", md)

    def test_traceability_done_story_without_test_fails_gate(self) -> None:
        r1 = req("DEDUP", 3)
        self.add_story("STORY-003", "DONE", [r1])
        rc, md = self.run_trace()
        self.assertEqual(rc, 1)
        self.assertIn(f"{r1} (STORY-003 is DONE) has no test", md)

    def test_traceability_done_story_with_failing_test_fails_gate(self) -> None:
        r1 = req("AN", 4)
        self.add_story("STORY-004", "DONE", [r1])
        self.add_java_test("AnalyticsTest", [r1])
        self.add_results("AnalyticsTest", [("m.AnalyticsTest", "t0", "FAIL")])
        rc, md = self.run_trace()
        self.assertEqual(rc, 1)
        self.assertIn(f"{r1} (STORY-004 is DONE) has a failing test", md)
        self.assertIn("| failing | 1 |", md)

    def test_traceability_in_progress_story_with_failing_test_is_reported_not_gated(self) -> None:
        r1 = req("AN", 4)
        self.add_story("STORY-004", "IN_PROGRESS", [r1])
        self.add_java_test("AnalyticsTest", [r1])
        self.add_results("AnalyticsTest", [("m.AnalyticsTest", "t0", "FAIL")])
        rc, md = self.run_trace()
        self.assertEqual(rc, 0)
        self.assertIn("| failing | 1 |", md)

    def test_traceability_duplicate_req_across_stories_fails_gate(self) -> None:
        r1 = req("UI", 5)
        self.add_story("STORY-005", "READY", [r1])
        self.add_story("STORY-006", "READY", [r1], epic="EPIC-02-y")
        rc, md = self.run_trace()
        self.assertEqual(rc, 1)
        self.assertIn(f"duplicate REQ {r1} defined in: STORY-005, STORY-006", md)

    def test_traceability_strict_fails_unknown_results_only_with_flag(self) -> None:
        r1 = req("SEC", 7)
        self.add_story("STORY-007", "DONE", [r1])
        self.add_java_test("SecTest", [r1])  # no results XML at all
        rc, md = self.run_trace()
        self.assertEqual(rc, 0)
        self.assertIn("(UNKNOWN) | UNKNOWN |", md)
        rc_strict, md_strict = self.run_trace("--strict")
        self.assertEqual(rc_strict, 1)
        self.assertIn("(--strict)", md_strict)

    def test_traceability_name_match_beats_class_match(self) -> None:
        r1, r2 = req("SMS", 1), req("SMS", 2)
        self.add_story("STORY-001", "DONE", [r1, r2])
        write(self.root / "web" / "src" / "x.test.ts",
              f"describe('{r1} parses', () => it('a', () => expect(1).toBe(1)));\n"
              f"describe('{r2} rejects', () => it('b', () => expect(1).toBe(1)));\n")
        write(self.root / "web" / "test-results" / "junit.xml",
              junit_xml([("x.test.ts", f"{r1} parses a", "PASS"), ("x.test.ts", f"{r2} rejects b", "FAIL")]))
        rc, md = self.run_trace()
        self.assertEqual(rc, 1)
        rows = {line.split("|")[1].strip(): line for line in md.splitlines() if line.startswith("| REQ-")}
        self.assertIn("| PASS |", rows[r1])
        self.assertIn("| FAIL |", rows[r2])

    def test_traceability_hook_tests_and_eval_cases_are_tests(self) -> None:
        r_ops, r_parse = req("OPS", 1), req("PARSE", 1)
        self.add_story("STORY-001", "DONE", [r_ops, r_parse])
        write(self.root / ".claude" / "hooks" / "tests" / "test-guard.sh", f'assert_blocked "{r_ops} blocks rm -rf"\n')
        write(self.root / ".claude" / "state" / "hook-tests.xml", junit_xml([("test-guard", f"{r_ops} blocks rm -rf", "PASS")]))
        write(self.root / "eval" / "datasets" / "sms" / "cases.jsonl",
              json.dumps({"id": "sms-1", "tags": [r_parse, "cib"]}) + "\n" + json.dumps({"id": "sms-2", "tags": ["other"]}) + "\n")
        write(self.root / "eval" / "reports" / "latest.json", json.dumps({"cases": [{"id": "sms-1", "pass": True}]}))
        rc, md = self.run_trace()
        self.assertEqual(rc, 0, md)
        self.assertIn("`.claude/hooks/tests/test-guard.sh`", md)
        self.assertIn("`eval/datasets/sms/cases.jsonl` · sms-1 (PASS)", md)
        self.assertIn("| passing | 2 |", md)

    def test_traceability_implementing_files_listed(self) -> None:
        r1 = req("EXP", 1)
        self.add_story("STORY-001", "READY", [r1])
        write(self.root / "backend" / "src" / "main" / "java" / "Exp.java", f"// implements {r1}\nclass Exp {{}}\n")
        rc, md = self.run_trace()
        self.assertEqual(rc, 0)
        self.assertIn("`backend/src/main/java/Exp.java`", md)

    def test_traceability_output_is_deterministic_and_json_flag_works(self) -> None:
        r1, r2 = req("SMS", 1), req("SMS", 2)
        self.add_story("STORY-001", "DONE", [r2, r1])
        self.add_java_test("ZTest", [r2])
        self.add_java_test("ATest", [r1, r2])
        self.add_results("ATest", [("m.ATest", "t0", "PASS"), ("m.ATest", "t1", "PASS")])
        rc1, md1 = self.run_trace()
        rc2, md2 = self.run_trace()
        self.assertEqual((rc1, rc2), (0, 0))
        self.assertEqual(md1, md2)
        self.assertLess(md1.index(f"| {r1} |"), md1.index(f"| {r2} |"))
        m = trace.build_matrix(self.root, strict=False)
        data = trace.to_json(m)
        self.assertEqual([r["req"] for r in data["reqs"]], [r1, r2])
        self.assertEqual(data["summary"]["gate"], "PASS")

    def test_traceability_usage_error_exit_2(self) -> None:
        self.assertEqual(trace.main(["--root", str(self.root / "missing"), "--out", "x.md"]), 2)


if __name__ == "__main__":
    unittest.main()
