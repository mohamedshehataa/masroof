"""REQ-OPS-054: a Gradle module whose wrapper exists but is not executable must FAIL the gate, never be skipped.

Regression: android/gradlew was committed as mode 100644, so on Linux `[ -x android/gradlew ]` was false, the CI Android
job printed "skip" and went green without building anything.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GUARD = ROOT / "scripts" / "make" / "gradlew-guard.sh"
BASH = shutil.which("bash")


def state(module_dir: Path, mode: int | None) -> tuple[int, str]:
    """Run gradlew_state in a temp project; mode None = no wrapper at all."""
    gw = module_dir / "gradlew"
    if mode is not None:
        gw.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8", newline="\n")
        os.chmod(gw, mode)
    script = f'. "{GUARD.as_posix()}"; cd "{module_dir.parent.as_posix()}"; gradlew_state {module_dir.name}'
    proc = subprocess.run([BASH, "-c", script], capture_output=True, text=True)
    return proc.returncode, proc.stderr


@unittest.skipUnless(BASH, "bash required")
class GradlewGuardTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.mod = Path(self._tmp.name) / "android"
        self.mod.mkdir()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_absent_wrapper_is_a_legitimate_skip(self) -> None:
        self.assertEqual(state(self.mod, None)[0], 2)

    def test_executable_wrapper_is_present(self) -> None:
        self.assertEqual(state(self.mod, 0o755)[0], 0)

    @unittest.skipIf(os.name == "nt", "POSIX exec bit is not enforced on Windows filesystems")
    def test_non_executable_wrapper_fails_with_a_clear_message(self) -> None:
        code, err = state(self.mod, 0o644)
        self.assertEqual(code, 1)
        self.assertIn("not executable", err)
        self.assertIn("android/gradlew", err)

    def test_check_scripts_use_the_guard_instead_of_a_bare_test(self) -> None:
        for name in ("check-fast.sh", "build.sh", "module.sh"):
            text = (ROOT / "scripts" / "make" / name).read_text(encoding="utf-8")
            self.assertIn("gradlew_state", text, name)
            self.assertNotIn("[ -x android/gradlew ]", text, name)
            self.assertNotIn("[ -x backend/gradlew ]", text, name)

    def test_committed_gradle_wrappers_have_the_executable_bit(self) -> None:
        git = shutil.which("git")
        self.assertIsNotNone(git)
        out = subprocess.run([git, "ls-files", "-s", "--", "android/gradlew", "backend/gradlew"], cwd=ROOT,
                             capture_output=True, text=True).stdout.strip().splitlines()
        self.assertTrue(out, "no gradle wrapper tracked")
        for line in out:
            self.assertTrue(line.startswith("100755"), f"gradlew must be committed as 100755: {line}")


if __name__ == "__main__":
    unittest.main()
