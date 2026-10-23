"""Unit tests for scripts/quality/anonymize-fixtures.py (fixture anonymization + leak check)."""
from __future__ import annotations

import json
import re
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from helpers import QUALITY_DIR, load_script, write

anon_mod = load_script("anonymize-fixtures")

RAW_EN = "Dear Mohamed, card 5123 4567 8901 2345 debited EGP 1,250.75 at CARREFOUR. Ref 98765432. Call 01012345678 or https://cib.eg/x"
RAW_AR = "تم خصم مبلغ ٣٬٥٠٠٫٥٠ جنيه من حساب ****4321 السيد أحمد. رقم المرجع ١٢٣٤٥٦٧٨. للاستعلام ٠١٢٢٢٢٢٢٢٢٢"
RAW_OTP = "Your OTP is 4821. Mr. Khaled Hassan account 12345678901234 balance 15000 EGP from +201155566677"


class AnonymizeFixturesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.anon = anon_mod.Anonymizer(anon_mod.load_names(QUALITY_DIR / "anonymize-names.txt"))

    def test_anonymize_phone_replaced_into_stub_range_and_deterministic(self) -> None:
        out = self.anon.anonymize(RAW_EN)
        self.assertNotIn("01012345678", out)
        self.assertNotIn("1012345678", anon_mod.to_western(out).replace("+2010000000", ""))
        stubs = re.findall(r"\+2010000000\d{2}", out)
        self.assertEqual(len(stubs), 1)
        again = self.anon.anonymize(RAW_EN)
        self.assertEqual(out, again)
        # same national number written with another prefix maps to the same stub
        a = self.anon.anonymize("call 01012345678")
        b = self.anon.anonymize("call +201012345678")
        c = self.anon.anonymize("call 00201012345678")
        self.assertEqual(re.findall(r"\+2010000000\d{2}", a), re.findall(r"\+2010000000\d{2}", b))
        self.assertEqual(re.findall(r"\+2010000000\d{2}", a), re.findall(r"\+2010000000\d{2}", c))

    def test_anonymize_card_and_long_digit_runs_keep_last_four(self) -> None:
        out = self.anon.anonymize(RAW_EN)
        self.assertIn("**** **** **** 2345", out)
        self.assertNotIn("5123", out)
        out2 = self.anon.anonymize(RAW_OTP)
        self.assertIn("****1234", out2)
        self.assertNotIn("12345678901234", out2)
        self.assertIn("4821", out2)  # 4-digit OTP is not a long run

    def test_anonymize_urls_stripped_and_names_replaced(self) -> None:
        out = self.anon.anonymize(RAW_EN)
        self.assertNotIn("http", out)
        self.assertNotIn("Mohamed", out)
        self.assertIn("Fulan", out)
        out2 = self.anon.anonymize(RAW_OTP)
        self.assertIn("Mr. Fulan", out2)
        self.assertNotIn("Khaled", out2)
        self.assertNotIn("Hassan", out2)
        out3 = self.anon.anonymize(RAW_AR)
        self.assertIn("السيد فلان", out3)
        self.assertNotIn("أحمد", out3)

    def test_anonymize_reference_numbers_randomized_same_length(self) -> None:
        out = self.anon.anonymize(RAW_EN)
        m = re.search(r"Ref (\d+)", out)
        self.assertIsNotNone(m)
        assert m is not None
        self.assertEqual(len(m.group(1)), 8)
        self.assertNotEqual(m.group(1), "98765432")
        out_ar = self.anon.anonymize(RAW_AR)
        m2 = re.search(r"رقم المرجع ([٠-٩]+)", out_ar)
        self.assertIsNotNone(m2)
        assert m2 is not None
        self.assertEqual(len(m2.group(1)), 8)
        self.assertNotEqual(m2.group(1), "١٢٣٤٥٦٧٨")

    def test_anonymize_amount_shifted_keeping_format(self) -> None:
        out = self.anon.anonymize(RAW_EN)
        m = re.search(r"EGP (\d{1,3}(?:,\d{3})*\.\d{2})", out)
        self.assertIsNotNone(m, out)
        assert m is not None
        value = Decimal(m.group(1).replace(",", ""))
        self.assertNotEqual(value, Decimal("1250.75"))
        self.assertTrue(Decimal("1187") <= value <= Decimal("1314"), value)  # within +/-5 %
        self.assertIn(",", m.group(1))
        out2 = self.anon.anonymize(RAW_OTP)
        m2 = re.search(r"balance (\d+) EGP", out2)
        assert m2 is not None
        self.assertTrue(14250 <= int(m2.group(1)) <= 15750)

    def test_anonymize_arabic_indic_digits_preserved(self) -> None:
        out = self.anon.anonymize(RAW_AR)
        m = re.search(r"مبلغ ([٠-٩]{1,3}(?:٬[٠-٩]{3})*٫[٠-٩]{2}) جنيه", out)
        self.assertIsNotNone(m, out)
        assert m is not None
        value = Decimal(anon_mod.to_western(m.group(1)).replace("٬", "").replace("٫", "."))
        self.assertTrue(Decimal("3325") <= value <= Decimal("3676"), value)
        self.assertNotEqual(value, Decimal("3500.50"))
        self.assertNotIn("٠١٢٢٢٢٢٢٢٢٢", out)
        self.assertRegex(out, r"\+2010000000\d{2}")
        self.assertIn("****4321", out)
        # no Western digits leaked into the Arabic amount (Python's \d also matches Arabic-Indic, so use [0-9])
        self.assertFalse(re.search(r"مبلغ [0-9]", out))

    def test_anonymize_output_never_contains_original_phone_or_card(self) -> None:
        for raw in (RAW_EN, RAW_AR, RAW_OTP):
            out = anon_mod.to_western(self.anon.anonymize(raw))
            for secret in ("01012345678", "5123456789012345", "01222222222", "201155566677", "12345678901234"):
                self.assertNotIn(secret, out.replace(" ", ""))
            self.assertEqual(anon_mod.find_leaks(out), [], out)

    def test_anonymize_cli_writes_case_stubs_with_deterministic_split(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw = write(root / "raw.txt", RAW_EN + "\n\n" + RAW_AR + "\n" + RAW_OTP + "\n")
            out = root / "cases.jsonl"
            rc = anon_mod.main(["--in", str(raw), "--out", str(out), "--suite", "sms", "--sender", "CIB"])
            self.assertEqual(rc, 0)
            cases = [json.loads(l) for l in out.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(cases), 3)
            for c in cases:
                self.assertEqual(set(c), {"id", "suite", "split", "input", "expected", "tags", "source"})
                self.assertTrue(c["id"].startswith("sms-"))
                self.assertIn(c["split"], ("train", "test"))
                self.assertEqual(c["split"], anon_mod.split_for(c["id"]))
                self.assertEqual(c["input"]["sender"], "CIB")
                self.assertEqual(c["input"]["source"], "SMS")
                self.assertEqual(c["expected"], {})
                self.assertEqual(c["tags"], ["needs-labeling"])
                self.assertEqual(c["source"], "anonymized-real")
            first = out.read_bytes()
            anon_mod.main(["--in", str(raw), "--out", str(out), "--suite", "sms", "--sender", "CIB"])
            self.assertEqual(first, out.read_bytes())
            self.assertEqual(anon_mod.main(["--check", str(out)]), 0)

    def test_anonymize_split_is_deterministic_hash_of_id(self) -> None:
        self.assertEqual(anon_mod.split_for("sms-abc"), anon_mod.split_for("sms-abc"))
        splits = {anon_mod.split_for(f"sms-{i}") for i in range(200)}
        self.assertEqual(splits, {"train", "test"})
        train = sum(1 for i in range(1000) if anon_mod.split_for(f"sms-{i}") == "train")
        self.assertTrue(600 < train < 800, train)

    def test_anonymize_jsonl_input_uses_sender_and_received_at(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw = write(root / "raw.jsonl", json.dumps({"text": RAW_EN, "sender": "NBE", "received_at": "2026-02-02T10:00:00+02:00"}) + "\n")
            out = root / "cases.jsonl"
            self.assertEqual(anon_mod.main(["--in", str(raw), "--out", str(out), "--suite", "notifications", "--sender", "X"]), 0)
            case = json.loads(out.read_text(encoding="utf-8").splitlines()[0])
            self.assertEqual(case["input"]["sender"], "NBE")
            self.assertEqual(case["input"]["received_at"], "2026-02-02T10:00:00+02:00")
            self.assertEqual(case["input"]["source"], "NOTIFICATION")

    def test_anonymize_check_catches_leaks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            leaky = write(root / "leaky.jsonl",
                          json.dumps({"id": "a", "input": {"text": "call 01012345678 now"}}) + "\n"
                          + json.dumps({"id": "b", "input": {"text": "acct 123456789"}}) + "\n"
                          + json.dumps({"id": "c", "input": {"text": "card 5123 4567 8901 2345"}}) + "\n"
                          + json.dumps({"id": "d", "input": {"text": "see https://x.y/z"}}) + "\n"
                          + json.dumps({"id": "e", "input": {"text": "رقم ٠١٠١٢٣٤٥٦٧٨"}}) + "\n")
            self.assertEqual(anon_mod.main(["--check", str(leaky)]), 1)
            clean = write(root / "clean.jsonl",
                          json.dumps({"id": "a", "input": {"text": "call +201000000042, balance 15450 EGP, Ref 12345678, card ****1234, OTP 4821"}}) + "\n")
            self.assertEqual(anon_mod.main(["--check", str(clean)]), 0)
            self.assertEqual(anon_mod.main(["--check", str(root / "missing.jsonl")]), 2)

    def test_anonymize_usage_error_exit_2(self) -> None:
        self.assertEqual(anon_mod.main(["--in", "x.txt"]), 2)


if __name__ == "__main__":
    unittest.main()
