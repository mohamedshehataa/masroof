"""REQ-OPS-150: the PR workflow's aggregate `gate` job (check-run "PR gate") fails when any check job fails.

Static guard on .github/workflows/pr.yml, plus a run of the gate step's own shell logic.
Live evidence: PR #2 runs 40092342642 (security failed -> gate failed) and 40093004627 (all green -> gate green).
"""
from __future__ import annotations

import os
import shutil
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
PR_YML = ROOT / ".github" / "workflows" / "pr.yml"
# Jobs that are not checks: module detection, PR labelling, the advisory AI review, and the gate itself.
NOT_GATED = {"detect", "labeler", "claude-review", "gate"}


def load_jobs() -> dict:
    return yaml.safe_load(PR_YML.read_text(encoding="utf-8"))["jobs"]


def gate_script(jobs: dict) -> str:
    steps = [s for s in jobs["gate"]["steps"] if "run" in s]
    assert len(steps) == 1, "gate must have exactly one run step"
    return steps[0]["run"]


def run_gate(script: str, results_json: str) -> int:
    bash = shutil.which("bash")
    assert bash, "bash is required"
    env = dict(os.environ, RESULTS=results_json)
    return subprocess.run([bash, "-c", script], env=env, capture_output=True, text=True).returncode


class PrGateTests(unittest.TestCase):
    def test_req_ops_150_gate_is_named_pr_gate_and_always_runs(self) -> None:
        gate = load_jobs()["gate"]
        self.assertEqual(gate["name"], "PR gate")  # the required check-run name (REQ-OPS-151)
        self.assertEqual(gate["if"], "always()")

    def test_req_ops_150_gate_needs_every_check_job(self) -> None:
        jobs = load_jobs()
        missing = sorted(set(jobs) - NOT_GATED - set(jobs["gate"]["needs"]))
        self.assertEqual(missing, [], f"jobs not covered by the gate: {missing}")

    def test_req_ops_150_failed_or_cancelled_job_fails_the_gate(self) -> None:
        script = gate_script(load_jobs())
        for result in ("failure", "cancelled"):
            with self.subTest(result=result):
                rc = run_gate(script, '{"sdlc": {"result": "success"}, "backend": {"result": "%s"}}' % result)
                self.assertNotEqual(rc, 0)

    def test_req_ops_150_success_and_skipped_pass_the_gate(self) -> None:
        script = gate_script(load_jobs())
        rc = run_gate(script, '{"sdlc": {"result": "success"}, "android": {"result": "skipped"}}')
        self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
