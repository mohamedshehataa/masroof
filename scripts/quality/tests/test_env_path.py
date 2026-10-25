"""scripts/env.sh PATH normalisation (Git Bash drive entries) -- SDLC maintenance, no REQ id."""
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASH = shutil.which("bash") or "bash"


def normalised(path_in: str, ostype: str = "msys") -> str:
    script = f'OSTYPE={ostype}; PATH="$1"; . scripts/env.sh >/dev/null 2>&1; printf "%s" "$PATH"'
    r = subprocess.run([BASH, "-c", script, "_", path_in], cwd=ROOT, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return r.stdout


@unittest.skipUnless(Path("/c").is_dir() or Path("C:/").is_dir(), "needs the Git Bash /c drive mount")
class EnvPathTests(unittest.TestCase):
    def test_drive_entries_are_rewritten(self):
        out = normalised("/usr/bin:C:/Tools/bin:d:/x")
        parts = out.split(":")
        self.assertIn("/c/Tools/bin", parts)
        self.assertNotIn("C", parts)

    def test_non_drive_single_letter_segment_is_untouched(self):
        # regression (STORY-016 review NIT): `/usr/bin:x:/y` must not become `/usr/bin:/x/y`
        parts = normalised("/usr/bin:x:/y").split(":")
        self.assertIn("x", parts)
        self.assertIn("/y", parts)
        self.assertNotIn("/x/y", parts)

    def test_posix_path_unchanged_when_not_msys(self):
        parts = normalised("/usr/bin:C:/Tools/bin", ostype="linux-gnu").split(":")
        self.assertIn("C", parts)


if __name__ == "__main__":
    unittest.main()
