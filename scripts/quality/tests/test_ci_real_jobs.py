"""STORY-016: CI runs the backend integration + ArchUnit tests and builds + scans the real images.

REQ-OPS-060  backend integration/ArchUnit job in pr.yml and main.yml (via a make target, >0 tests per suite)
REQ-OPS-061  `make build-images` builds masroof/backend:<tag> and masroof/web:<tag>, never silently skips;
             pr.yml `artifacts` and main.yml `images` run Trivy image scans that are not skippable
REQ-OPS-172  main.yml publishes the `web-bundle` and `android-debug-apk` artifacts

Two kinds of checks, in the style of test_pr_gate.py:
  * static checks on .github/workflows/{pr,main}.yml
  * behaviour of the make recipes, run in a throwaway copy of scripts/ + Makefile with a fake `gradlew`
    (writes JUnit XML with a chosen test count) and a fake `docker` on PATH (records its arguments).
Design constraint these tests pin down: `build-images` calls `docker build`/`docker buildx build` with the
tags `masroof/backend:<tag>` and `masroof/web:<tag>` as arguments (infra/README.md naming), and the
integration job's make target reads the JUnit XML under backend/build/test-results/<task>/.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
PR_YML = ROOT / ".github" / "workflows" / "pr.yml"
MAIN_YML = ROOT / ".github" / "workflows" / "main.yml"
BASH = shutil.which("bash") or "bash"


def jobs_of(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))["jobs"]


def run_lines(job: dict) -> list[str]:
    return [s["run"] for s in job.get("steps", []) if "run" in s]


def norm(expr: object) -> str:
    return re.sub(r"\s+", " ", str(expr or ""))


def integration_job(jobs: dict) -> tuple[str, str]:
    """(job id, make target) of the job that runs the backend integration + ArchUnit tests through make."""
    pat = re.compile(r"(?m)^\s*make\s+(\S*(?:integration|arch)\S*)")
    for jid, job in jobs.items():
        for line in run_lines(job):
            m = pat.search(line)
            if m:
                return jid, m.group(1)
    raise AssertionError("no job runs a `make <integration|arch target>` step")


def gated_on(job: dict, output: str) -> bool:
    return f"needs.detect.outputs.{output} == 'true'" in norm(job.get("if")) and "detect" in _as_list(job.get("needs"))


def _as_list(v: object) -> list:
    return v if isinstance(v, list) else ([v] if v else [])


def step_gated(job: dict, step: dict, output: str) -> bool:
    cond = f"needs.detect.outputs.{output} == 'true'"
    return cond in norm(step.get("if")) or cond in norm(job.get("if"))


def is_true(v: object) -> bool:
    return v is True or str(v).lower() == "true"


def run_gate(script: str, results: dict) -> int:
    env = dict(os.environ, RESULTS=json.dumps(results, indent=2))
    return subprocess.run([BASH, "-c", script], env=env, capture_output=True, text=True).returncode


def gate_script(jobs: dict) -> str:
    return [s for s in jobs["gate"]["steps"] if "run" in s][0]["run"]


def make_tree(dest: Path, *, with_web: bool, with_build_script: bool = True, with_backend: bool = True) -> None:
    """Throwaway project copy: Makefile, scripts/, infra/scripts/ and the Dockerfiles."""
    shutil.copy(ROOT / "Makefile", dest / "Makefile")
    shutil.copytree(ROOT / "scripts", dest / "scripts", ignore=shutil.ignore_patterns("__pycache__", "tests"))
    (dest / "infra").mkdir()
    shutil.copytree(ROOT / "infra" / "scripts", dest / "infra" / "scripts")
    if not with_build_script:
        (dest / "infra" / "scripts" / "build-images.sh").unlink(missing_ok=True)
    if with_backend:
        (dest / "backend").mkdir()
        shutil.copy(ROOT / "backend" / "Dockerfile", dest / "backend" / "Dockerfile")
    if with_web:
        (dest / "web").mkdir()
        shutil.copy(ROOT / "web" / "Dockerfile", dest / "web" / "Dockerfile")
        (dest / "web" / "package.json").write_text("{}", encoding="utf-8")


def make(dest: Path, target: str, extra_env: dict[str, str], bin_dir: Path | None = None) -> subprocess.CompletedProcess:
    pre = f'export PATH="{bin_dir.as_posix()}:$PATH"; ' if bin_dir else ""
    env = {k: v for k, v in os.environ.items() if k not in ("IMAGE_TAG", "VERSION", "IMAGE_REGISTRY")}
    env.update(extra_env)
    return subprocess.run([BASH, "-c", f". scripts/env.sh && {pre}make {target}"], cwd=dest, env=env,
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


FAKE_GRADLEW = r"""#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
for a in "$@"; do
  case "$a" in
    integrationTest|archTest)
      [ "${FAKE_GRADLE_FAIL:-}" = "1" ] && { echo "FAILED $a" >&2; exit 1; }
      if [ "$a" = integrationTest ]; then n="${FAKE_INTEGRATION_TESTS-2}"; else n="${FAKE_ARCH_TESTS-2}"; fi
      [ "$n" = "none" ] && continue
      mkdir -p "build/test-results/$a"
      printf '<testsuite name="fake.%s" tests="%s" failures="0" errors="0" skipped="0"/>\n' "$a" "$n" \
        > "build/test-results/$a/TEST-fake.xml"
      ;;
  esac
done
exit 0
"""

FAKE_DOCKER = r"""#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_LOG"
if [ "${FAKE_DOCKER_DOWN:-}" = "1" ]; then
  echo "Cannot connect to the Docker daemon" >&2
  exit 1
fi
case "$1" in
  build|buildx|compose)
    if [ -n "${FAKE_BUILD_FAIL_MATCH:-}" ] && [[ "$*" == *"$FAKE_BUILD_FAIL_MATCH"* ]]; then
      echo "ERROR: failed to solve: dockerfile parse error" >&2
      exit 1
    fi ;;
esac
exit 0
"""


class BackendIntegrationJobTests(unittest.TestCase):
    """REQ-OPS-060"""

    def test_req_ops_060_pr_job_runs_integration_and_arch_tests_through_make_in_time(self) -> None:
        jobs = jobs_of(PR_YML)
        jid, target = integration_job(jobs)
        job = jobs[jid]
        self.assertTrue(gated_on(job, "backend"), "job must need detect and run only when backend/gradlew exists")
        direct = [ln for ln in run_lines(job) if re.search(r"\./gradlew|\bgradlew\b|\bnpm\b|\bpytest\b", ln)]
        self.assertEqual(direct, [], "S8: workflows call make targets only")
        self.assertLessEqual(job["timeout-minutes"], 15)
        self.assertTrue(any("setup-java" in s.get("uses", "") for s in job["steps"]), "needs a JDK")
        self.assertRegex((ROOT / "Makefile").read_text(encoding="utf-8"), rf"(?m)^{re.escape(target)}:")

    def test_req_ops_060_pr_job_uploads_test_results_even_when_tests_fail(self) -> None:
        jobs = jobs_of(PR_YML)
        job = jobs[integration_job(jobs)[0]]
        uploads = [s for s in job["steps"] if "upload-artifact" in s.get("uses", "")]
        self.assertTrue(uploads, "job must upload the test results")
        up = uploads[0]
        self.assertIn("backend/build/test-results", str(up["with"]["path"]))
        self.assertIn("always()", norm(up.get("if")), "a failing run must still publish the report that names the failing class")

    def test_req_ops_060_pr_gate_needs_the_job_and_fails_when_it_fails_or_skips_ok(self) -> None:
        jobs = jobs_of(PR_YML)
        jid, _ = integration_job(jobs)
        self.assertIn(jid, jobs["gate"]["needs"])
        script = gate_script(jobs)
        base = {"detect": {"result": "success", "outputs": {}}, "sdlc": {"result": "success", "outputs": {}}}
        for result in ("failure", "cancelled"):
            with self.subTest(result=result):
                self.assertNotEqual(run_gate(script, {**base, jid: {"result": result, "outputs": {}}}), 0)
        self.assertEqual(run_gate(script, {**base, jid: {"result": "skipped", "outputs": {}}}), 0)

    def test_req_ops_060_main_runs_the_same_job_and_main_gate_needs_it(self) -> None:
        pr_jid, pr_target = integration_job(jobs_of(PR_YML))
        jobs = jobs_of(MAIN_YML)
        jid, target = integration_job(jobs)
        self.assertEqual(target, pr_target, "same make target in both workflows")
        self.assertTrue(gated_on(jobs[jid], "backend"))
        self.assertIn(jid, jobs["gate"]["needs"])
        self.assertLessEqual(jobs[jid]["timeout-minutes"], 15)

    def _run_target(self, **env: str) -> subprocess.CompletedProcess:
        _, target = integration_job(jobs_of(PR_YML))
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d)
            make_tree(dest, with_web=False)
            (dest / "backend").mkdir(exist_ok=True)
            gw = dest / "backend" / "gradlew"
            gw.write_text(FAKE_GRADLEW, encoding="utf-8", newline="\n")
            gw.chmod(0o755)
            return make(dest, target, env)

    def test_req_ops_060_target_passes_when_both_suites_executed_tests(self) -> None:
        r = self._run_target()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_req_ops_060_target_fails_when_integration_suite_ran_zero_tests(self) -> None:
        # Docker/Testcontainers unusable or tag filter empty: never success with 0 integration tests.
        r = self._run_target(FAKE_INTEGRATION_TESTS="0")
        self.assertNotEqual(r.returncode, 0, "0 integration tests must fail the job")

    def test_req_ops_060_target_fails_when_arch_suite_ran_zero_tests(self) -> None:
        r = self._run_target(FAKE_ARCH_TESTS="0")
        self.assertNotEqual(r.returncode, 0, "0 ArchUnit tests must fail the job")

    def test_req_ops_060_target_fails_when_a_suite_wrote_no_results(self) -> None:
        r = self._run_target(FAKE_ARCH_TESTS="none")
        self.assertNotEqual(r.returncode, 0, "missing archTest results must fail the job")

    def test_req_ops_060_target_fails_when_gradle_fails(self) -> None:
        r = self._run_target(FAKE_GRADLE_FAIL="1")
        self.assertNotEqual(r.returncode, 0)


class BuildImagesTests(unittest.TestCase):
    """REQ-OPS-061 (script behaviour with a fake docker on PATH)"""

    def _build(self, *, web: bool = True, build_script: bool = True, env: dict[str, str] | None = None):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        dest = Path(d.name)
        make_tree(dest, with_web=web, with_build_script=build_script)
        bin_dir = dest / "fakebin"
        bin_dir.mkdir()
        dk = bin_dir / "docker"
        dk.write_text(FAKE_DOCKER, encoding="utf-8", newline="\n")
        dk.chmod(0o755)
        log = dest / "docker.log"
        log.write_text("", encoding="utf-8")
        r = make(dest, "build-images", {"FAKE_DOCKER_LOG": log.as_posix(), **(env or {})}, bin_dir)
        return r, log.read_text(encoding="utf-8")

    def test_req_ops_061_build_images_script_exists(self) -> None:
        self.assertTrue((ROOT / "infra" / "scripts" / "build-images.sh").is_file(),
                        "infra/scripts/build-images.sh is missing: make build-images skips silently")

    def test_req_ops_061_tags_both_images_with_image_tag_and_does_not_skip(self) -> None:
        r, calls = self._build(env={"IMAGE_TAG": "pr-12"})
        out = r.stdout + r.stderr
        self.assertEqual(r.returncode, 0, out)
        self.assertNotIn("skip:", out)
        self.assertIn("masroof/backend:pr-12", calls)
        self.assertIn("masroof/web:pr-12", calls)

    def test_req_ops_061_default_tag_is_dev(self) -> None:
        r, calls = self._build()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("masroof/backend:dev", calls)
        self.assertIn("masroof/web:dev", calls)

    def test_req_ops_061_missing_build_script_never_exits_0_with_skip(self) -> None:
        r, _ = self._build(build_script=False, env={"IMAGE_TAG": "pr-12"})
        out = r.stdout + r.stderr
        self.assertFalse(r.returncode == 0 and "skip:" in out, "silent skip: " + out)

    def test_req_ops_061_dockerfile_error_exits_nonzero_and_names_the_image(self) -> None:
        r, _ = self._build(env={"IMAGE_TAG": "pr-12", "FAKE_BUILD_FAIL_MATCH": "web"})
        out = r.stdout + r.stderr
        self.assertNotEqual(r.returncode, 0, out)
        self.assertRegex(out, r"(?is)(fail|error)[^\n]*\bweb\b|\bweb\b[^\n]*(fail|error)")

    def test_req_ops_061_docker_daemon_down_exits_nonzero_with_one_stderr_line(self) -> None:
        r, _ = self._build(env={"IMAGE_TAG": "pr-12", "FAKE_DOCKER_DOWN": "1"})
        self.assertNotEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn("skip:", r.stdout + r.stderr)
        lines = [ln for ln in r.stderr.splitlines() if re.search(r"(?i)docker", ln)
                 and re.search(r"(?i)unavailable|not (running|reachable)|cannot connect|daemon", ln)
                 and not ln.startswith("make")]
        self.assertTrue(lines, "stderr must say Docker is unavailable: " + r.stderr)

    def test_req_ops_061_without_web_only_backend_is_built_and_web_skip_is_named(self) -> None:
        r, calls = self._build(web=False, env={"IMAGE_TAG": "pr-12"})
        out = r.stdout + r.stderr
        self.assertEqual(r.returncode, 0, out)
        self.assertIn("masroof/backend:pr-12", calls)
        self.assertNotIn("masroof/web", calls)
        self.assertRegex(out, r"(?i)skip[^\n]*\bweb\b", "a line must name the skipped web image")


class TrivyImageScanWorkflowTests(unittest.TestCase):
    """REQ-OPS-061 (workflow structure)"""

    @staticmethod
    def _trivy_image_steps(job: dict) -> list[dict]:
        return [s for s in job["steps"]
                if "trivy-action" in s.get("uses", "") and s.get("with", {}).get("scan-type") == "image"]

    def test_req_ops_061_pr_artifacts_job_scans_both_images_and_gate_needs_it(self) -> None:
        jobs = jobs_of(PR_YML)
        steps = self._trivy_image_steps(jobs["artifacts"])
        refs = " ".join(str(s["with"]["image-ref"]) for s in steps)
        self.assertIn("masroof/backend:", refs)
        self.assertIn("masroof/web:", refs)
        for s in steps:
            w = s["with"]
            self.assertEqual(w["severity"], "HIGH,CRITICAL")
            self.assertTrue(is_true(w["ignore-unfixed"]))
            self.assertEqual(str(w["exit-code"]), "1")
            self.assertFalse(s.get("continue-on-error"), "scan must be able to fail the job")
        self.assertIn("artifacts", jobs["gate"]["needs"])
        self.assertFalse([ln for ln in run_lines(jobs["artifacts"]) if "make build-images" not in ln and "docker build" in ln])

    def test_req_ops_061_main_trivy_image_scans_are_not_skippable_by_missing_images(self) -> None:
        job = jobs_of(MAIN_YML)["images"]
        steps = self._trivy_image_steps(job)
        self.assertEqual(len(steps), 2)
        for s in steps:
            self.assertNotIn("steps.img", norm(s.get("if")), "scan is skipped when the image was not built")
        text = "\n".join(run_lines(job))
        self.assertNotIn("::warning::No masroof", text, "a missing image must fail the job, not warn")


class MainArtifactsWorkflowTests(unittest.TestCase):
    """REQ-OPS-172"""

    @staticmethod
    def _job_with_run(jobs: dict, cmd: str) -> str:
        for jid, job in jobs.items():
            if any(re.search(rf"(?m)^\s*{re.escape(cmd)}\s*$", ln) for ln in run_lines(job)):
                return jid
        raise AssertionError(f"main.yml has no job running `{cmd}`")

    @staticmethod
    def _upload(job: dict, name: str) -> dict:
        for s in job["steps"]:
            if "upload-artifact" in s.get("uses", "") and s.get("with", {}).get("name") == name:
                return s
        raise AssertionError(f"no upload-artifact step named {name}")

    def test_req_ops_172_main_builds_and_uploads_web_bundle(self) -> None:
        jobs = jobs_of(MAIN_YML)
        jid = self._job_with_run(jobs, "make build-web")
        job = jobs[jid]
        self.assertIn("detect", _as_list(job.get("needs")))
        build = [s for s in job["steps"] if re.search(r"(?m)^\s*make build-web\s*$", s.get("run", ""))][0]
        self.assertTrue(step_gated(job, build, "web"), "build-web must be gated on detect.outputs.web")
        up = self._upload(job, "web-bundle")
        self.assertEqual(str(up["with"]["path"]).strip(), "web/dist/")
        self.assertGreaterEqual(int(up["with"]["retention-days"]), 7)
        checks_index = "web/dist/index.html" in "\n".join(run_lines(job))
        self.assertTrue(checks_index or up["with"].get("if-no-files-found") == "error",
                        "an empty bundle must never be published")
        self.assertIn(jid, jobs["gate"]["needs"], "Main gate must need the web-bundle job")

    def test_req_ops_172_main_builds_and_uploads_debug_apk_only_when_android_exists(self) -> None:
        jobs = jobs_of(MAIN_YML)
        jid = self._job_with_run(jobs, "make build-apk")
        job = jobs[jid]
        self.assertIn("detect", _as_list(job.get("needs")))
        build = [s for s in job["steps"] if re.search(r"(?m)^\s*make build-apk\s*$", s.get("run", ""))][0]
        self.assertTrue(step_gated(job, build, "android"), "APK build must be gated on detect.outputs.android")
        up = self._upload(job, "android-debug-apk")
        self.assertTrue(step_gated(job, up, "android"))
        self.assertEqual(up["with"]["path"], "android/**/build/outputs/apk/debug/*.apk")
        self.assertEqual(up["with"]["if-no-files-found"], "error")
        self.assertGreaterEqual(int(up["with"]["retention-days"]), 7)
        self.assertIn(jid, jobs["gate"]["needs"], "Main gate must need the APK job")


if __name__ == "__main__":
    unittest.main()
