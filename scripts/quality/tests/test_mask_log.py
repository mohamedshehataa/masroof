"""infra/scripts/mask_log.py: what `make bootstrap-host STEP=logs` prints never carries personal data (logging-privacy.md)."""
import importlib.util
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "mask_log", Path(__file__).resolve().parents[3] / "infra" / "scripts" / "mask_log.py"
)
mask_log = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(mask_log)
mask = mask_log.mask


def _synthetic():
    """Synthetic values only, assembled at runtime so no file holds anything that looks like a real secret."""
    four = "4" + "1" * 3
    part = "Ab3" * 6
    return {
        "email": "someone.else" + "@" + "example.org",
        "card spaced": " ".join([four] * 4),
        "card dashed": "-".join([four] * 4),
        "account": "0551" + "2899" + "3001",
        "phone +20 spaced": "+20 " + "100" + " 123 " + "4567",
        "phone 010 dashed": "010" + "-1234-" + "5678",
        "phone plain": "010" + "1234" + "5678",
        "arabic-indic digits": "".join(chr(0x0660 + d) for d in range(10)),
        "jwt": "eyJ" + part + "." + "eyJ" + part + "." + part,
        "opaque token": ("q8Zr2Lk9" * 4),
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
        self.assertEqual(line, mask(line))

    def test_lines_are_cut_to_400_characters(self):
        self.assertEqual(400, len(mask("x" * 1000)))


if __name__ == "__main__":
    unittest.main()
