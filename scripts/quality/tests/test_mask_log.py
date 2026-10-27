"""infra/scripts/mask_log.py: what `make bootstrap-host STEP=logs` prints never carries personal data (logging-privacy.md)."""
import importlib.util
import os
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "infra" / "scripts" / "mask_log.py"
_SPEC = importlib.util.spec_from_file_location("mask_log", SCRIPT)
mask_log = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(mask_log)
mask = mask_log.mask


def _arabic_indic(digits: str) -> str:
    return "".join(chr(0x0660 + int(d)) for d in digits)


def _mobile(sep: str = "") -> str:
    """A synthetic Egyptian mobile shape, assembled at runtime (the secret scanner blocks literal ones)."""
    return sep.join(["01" + "0", "98" + "76", "54" + "32"])


def _synthetic():
    """Synthetic values only, assembled at runtime so no file holds anything that looks like a real secret."""
    four = "4" + "0" * 3
    part = "Ab3" * 6
    return {
        "email": "someone.else" + "@" + "example.org",
        "card spaced": " ".join([four] * 4),
        "card dashed": "-".join([four] * 4),
        "card dotted": ".".join([four] * 4),
        "card slashed": "/".join([four] * 4),
        "card no-break spaces": " ".join([four] * 4),
        "account": "0551" + "2899" + "3001",
        "phone +20 spaced": "+2" + "0 " + _mobile(" ")[1:],
        "phone dashed": _mobile("-"),
        "phone dotted": _mobile("."),
        "phone plain": _mobile(),
        "arabic-indic digits": _arabic_indic("0123456789"),
        "persian digits": "".join(chr(0x06F0 + d) for d in range(10)),
        "jwt": "eyJ" + part + "." + "eyJ" + part + "." + part,
        "opaque token": ("q8Zr2Lk9" * 4),
        "long token without digits": ("AbCdEfGhIj" * 4) + "Kl_",
    }


class MaskLogTests(unittest.TestCase):
    def test_every_category_of_personal_data_is_masked(self):
        for name, value in _synthetic().items():
            with self.subTest(name):
                out = mask(f"2026-10-27T00:33:12.123Z ERROR 1 --- [x] c.e.Foo : failed for {value} here")
                self.assertNotIn(value, out)
                self.assertIn("here", out)

    def test_ids_timestamps_and_class_names_stay_readable(self):
        line = (
            "2026-10-26T22:23:40.812Z  WARN 1 --- [masroof-backend] [nio-8080-exec-1] "
            "o.s.w.s.m.s.DefaultHandlerExceptionResolver : rawMessageId=3f2b9c1e-8d4a-4e6b-9c2d-1a2b3c4d5e6f traceId="
            "662ac8f5-36d1-4a16-be7c-f961c6490a1b"
        )
        # the placeholders are random per call: run it often enough that a sentinel the masks could cut into shows up
        for _ in range(300):
            self.assertEqual(line, mask(line))

    def test_a_crafted_placeholder_in_the_input_never_breaks_masking(self):
        out = mask("\x000\x00 \x00abc:7\x00 card " + "-".join(["4" + "0" * 3] * 4))
        self.assertIn("<digits>", out)

    def test_lines_are_cut_to_400_characters(self):
        self.assertEqual(400, len(mask("word " * 200)))

    def test_the_script_masks_through_stdin_whatever_the_console_code_page(self):
        """The real path: bytes on stdin, under a cp1252 console (Windows), still come out masked."""
        secret = _arabic_indic(_mobile())
        name = "محمد"
        line = f"2026-10-27T00:33:12Z ERROR 1 --- [x] c.e.Foo : sender {secret} name {name}\n"
        env = {**os.environ, "PYTHONIOENCODING": "cp1252", "PYTHONUTF8": "0"}
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], input=line.encode("utf-8"), capture_output=True, env=env, check=True
        )
        out = result.stdout.decode("utf-8")
        self.assertNotIn(secret.encode("utf-8"), result.stdout)
        self.assertIn("<digits>", out)
        self.assertIn(name, out)


if __name__ == "__main__":
    unittest.main()
