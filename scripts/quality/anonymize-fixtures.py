#!/usr/bin/env python3
"""Anonymize real bank/wallet messages into eval case stubs (PROMPT.md S10.1). Stdlib only.

  anonymize-fixtures.py --in raw.txt|raw.jsonl --out cases.jsonl --suite sms --sender CIB [--names FILE] [--source SMS]
  anonymize-fixtures.py --check cases.jsonl

Input: a .txt with one message per line (blank lines ignored) or a .jsonl whose objects carry the message in
`text` / `body` / `message` / `input.text` (optional `sender`, `received_at`).

Per message, in this order (each step's output is protected from the later ones):
  1. URLs are stripped
  2. Egyptian phone numbers `(+20|0020|0)1[0125]NNNNNNNN` (Western or Arabic-Indic digits) become
     `+2010000000NN`, NN derived from a hash of the number so the same number always maps to the same stub
  3. card numbers `dddd dddd dddd dddd` keep only the last 4 digits (`**** **** **** 1234`)
  4. reference numbers (`Ref`, `Reference no.`, `#`, `رقم المرجع`, `رقم العملية` ... followed by 5+ digits)
     become random digits of the same length
  5. amounts (numbers next to a currency or `amount`/`مبلغ`/`قيمة` keyword) are shifted by a random 1–5 %,
     keeping thousands separators, decimal places and the digit script (Arabic-Indic stays Arabic-Indic)
  6. every remaining digit run longer than 4 keeps only its last 4 digits (`****1234`)
  7. names from the names file (and any `Mr./Mrs./Dr./السيد/السيدة <Name>`) become `Fulan` / `فلان`
Randomness is seeded from a hash of the message, so re-running produces identical output.
Before a case is written the anonymized text is re-scanned with the --check rules; a leak aborts with exit 1.

Each case: {id, suite, split, input:{text, sender, source, received_at}, expected:{}, tags:["needs-labeling"],
source:"anonymized-real"}; `split` is train/test 70/30 from a hash of the id.

--check scans an existing JSONL for leftover real-looking phone numbers, card numbers, URLs or digit runs
longer than 4 (outside amounts and the anonymized phone range) and exits 1 when any is found.
Exit codes: 0 ok, 1 leak found, 2 usage error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sys
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from pathlib import Path
from typing import Callable, Iterable

ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
TO_WESTERN = str.maketrans(ARABIC_DIGITS, "0123456789")
TO_ARABIC = str.maketrans("0123456789", ARABIC_DIGITS)
ANON_PHONE_PREFIX = "+2010000000"
ANON_PHONE_RE = re.compile(r"\+2010000000\d{2}")
PHONE_RE = re.compile(r"(?<!\d)(?:\+20|0020|0)(1[0125]\d{8})(?!\d)")
CARD_RE = re.compile(r"(?<!\d)\d{4}([ -]?)\d{4}\1\d{4}\1\d{4}(?!\d)")
URL_RE = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
REF_RE = re.compile(
    r"(?i)(\b(?:ref(?:erence)?\.?|trx|txn|transaction|trans|auth(?:orization)?\s*code|code)\s*(?:no\.?|num(?:ber)?|id)?\s*[:#.]?\s*)(\d{5,})"
    r"|((?:رقم\s*)?(?:المرجع|مرجع|مرجعي|العملية|العمليه|الحركة|المعاملة|التفويض)\s*(?:رقم)?\s*[:#]?\s*)(\d{5,})"
    r"|(#\s?)(\d{5,})"
)
CURRENCY = r"(?:EGP|LE|L\.E\.?|E£|£|ج\.م\.?|جم|جنيهاً|جنيها|جنيه|USD|US\$|\$|SAR|AED|EUR|€|KWD|GBP|ريال|درهم|دولار|يورو)"
KEYWORD = r"(?:amount|total|balance|مبلغ|بمبلغ|قيمة|بقيمة|رصيد|رصيدك|الرصيد|المتاح)"
NUM = r"(?<![\d.,٬٫])(\d{1,3}(?:[,٬]\d{3})+(?:[.٫]\d{1,2})?|\d+(?:[.٫]\d{1,2})?)(?![\d])"
AMOUNT_RE = re.compile(
    rf"(?i){NUM}\s*{CURRENCY}(?![A-Za-z؀-ۿ])"
    rf"|(?<![A-Za-z؀-ۿ]){CURRENCY}\s*{NUM}"
    rf"|{KEYWORD}\s*(?:of|is|:|هو|=)?\s*{NUM}"
)
DIGIT_RUN_RE = re.compile(r"\d{5,}")
LATIN_TITLE_RE = re.compile(r"\b(Mr|Mrs|Ms|Miss|Dr|Eng)\.?\s+([A-Z][A-Za-z'\-]+)")
ARABIC_TITLE_RE = re.compile(r"(السيد|السيدة|الأستاذ|الاستاذ|الأستاذة|الاستاذة|الدكتور|الدكتورة|المهندس|المهندسة|أ/|م/|د/)\s*([؀-ۿ]{2,})")
PLACEHOLDER_RE = re.compile("(\\d+)")
DEFAULT_RECEIVED_AT = "2026-01-01T12:00:00+02:00"
SOURCE_BY_SUITE = {"sms": "SMS", "notifications": "NOTIFICATION", "whatsapp_text": "WHATSAPP", "commands": "WHATSAPP"}


def to_western(s: str) -> str:
    return s.translate(TO_WESTERN)


def has_arabic_digits(s: str) -> bool:
    return any(c in ARABIC_DIGITS for c in s)


def sha(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


# ------------------------------------------------------------------ core
class Anonymizer:
    def __init__(self, names: list[str]) -> None:
        self.names = sorted({n.strip() for n in names if n.strip()}, key=len, reverse=True)
        self.name_res = [
            (re.compile(r"(?<!\w)" + re.escape(n) + r"(?!\w)", 0 if _is_arabic(n) else re.IGNORECASE), _is_arabic(n))
            for n in self.names
        ]

    def anonymize(self, text: str) -> str:
        rnd = random.Random(sha(text))
        protected: list[str] = []

        def protect(s: str) -> str:
            protected.append(s)
            return f"{len(protected) - 1}"

        text = URL_RE.sub("", text)
        text = _sub_normalized(text, PHONE_RE, lambda m, orig: protect(_phone_stub(m.group(1))))
        text = _sub_normalized(text, CARD_RE, lambda m, orig: protect(_mask_card(orig)))
        text = _sub_normalized(text, REF_RE, lambda m, orig: _replace_ref(m, orig, rnd, protect))
        text = _sub_normalized(text, AMOUNT_RE, lambda m, orig: _shift_amount(m, orig, rnd, protect))
        text = _sub_normalized(text, DIGIT_RUN_RE, lambda m, orig: protect("****" + orig[-4:]))
        text = LATIN_TITLE_RE.sub(lambda m: f"{m.group(1)}. Fulan", text)
        text = ARABIC_TITLE_RE.sub(lambda m: f"{m.group(1)} فلان", text)
        for rx, arabic in self.name_res:
            text = rx.sub("فلان" if arabic else "Fulan", text)
        text = PLACEHOLDER_RE.sub(lambda m: protected[int(m.group(1))], text)
        return re.sub(r"[ \t]{2,}", " ", text).strip()


def _is_arabic(s: str) -> bool:
    return any("؀" <= c <= "ۿ" for c in s)


def _sub_normalized(text: str, rx: re.Pattern[str], repl: Callable[[re.Match[str], str], str]) -> str:
    """Run `rx` on a Western-digit copy (1:1 char mapping keeps offsets) and substitute spans in the original."""
    w = to_western(text)
    out: list[str] = []
    pos = 0
    for m in rx.finditer(w):
        out.append(text[pos:m.start()])
        out.append(repl(m, text[m.start():m.end()]))
        pos = m.end()
    out.append(text[pos:])
    return "".join(out)


def _phone_stub(national: str) -> str:
    nn = int(sha(national)[:8], 16) % 100
    return f"{ANON_PHONE_PREFIX}{nn:02d}"


def _mask_card(orig: str) -> str:
    digits_seen = sum(c.isdigit() for c in orig)
    out: list[str] = []
    seen = 0
    for c in orig:
        if c.isdigit():
            seen += 1
            out.append(c if seen > digits_seen - 4 else "*")
        else:
            out.append(c)
    return "".join(out)


def _replace_ref(m: re.Match[str], orig: str, rnd: random.Random, protect: Callable[[str], str]) -> str:
    groups = [(m.group(i), m.group(i + 1)) for i in (1, 3, 5) if m.group(i) is not None]
    label, digits = groups[0]
    orig_digits = orig[len(label):]
    fresh = "".join(str(rnd.randrange(10)) for _ in digits)
    if has_arabic_digits(orig_digits):
        fresh = fresh.translate(TO_ARABIC)
    return orig[: len(label)] + protect(fresh)


def _shift_amount(m: re.Match[str], orig: str, rnd: random.Random, protect: Callable[[str], str]) -> str:
    num_group = next(i for i in range(1, (m.re.groups or 0) + 1) if m.group(i) is not None)
    start, end = m.start(num_group) - m.start(), m.end(num_group) - m.start()
    orig_num = orig[start:end]
    normalized = to_western(orig_num).replace("٬", ",").replace("٫", ".")
    thousands = "," in normalized
    decimals = len(normalized.split(".")[1]) if "." in normalized else 0
    try:
        value = Decimal(normalized.replace(",", ""))
    except InvalidOperation:
        return orig
    pct = Decimal(str(round(rnd.uniform(0.01, 0.05), 4)))
    sign = rnd.choice((1, -1))
    shifted = (value * (Decimal(1) + sign * pct)).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP)
    text = f"{shifted:,.{decimals}f}" if thousands else f"{shifted:.{decimals}f}"
    if has_arabic_digits(orig_num):
        text = text.translate(TO_ARABIC)
        if "٬" in orig_num:
            text = text.replace(",", "٬")
        if "٫" in orig_num:
            text = text.replace(".", "٫")
    return orig[:start] + protect(text) + orig[end:]


# ------------------------------------------------------------------ leak check
def find_leaks(text: str) -> list[str]:
    """Human-readable leak descriptions for one anonymized text ('' when clean)."""
    w = to_western(text)
    issues: list[str] = []
    if URL_RE.search(w):
        issues.append("url")
    stripped = ANON_PHONE_RE.sub(" ", w)
    for m in PHONE_RE.finditer(stripped):
        issues.append(f"phone-like number {m.group(0)[:4]}...")
    if CARD_RE.search(stripped):
        issues.append("card-like number")
    # labelled reference numbers are randomized by this tool and amounts are shifted, so neither counts as a leak
    stripped = REF_RE.sub(lambda m: " " * len(m.group(0)), stripped)
    stripped = AMOUNT_RE.sub(lambda m: " " * len(m.group(0)), stripped)
    for m in DIGIT_RUN_RE.finditer(stripped):
        issues.append(f"digit run of {len(m.group(0))}")
    return issues


def check_file(path: Path) -> int:
    bad = 0
    total = 0
    for n, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        total += 1
        try:
            obj = json.loads(line)
            text = str((obj.get("input") or {}).get("text", obj.get("text", "")))
        except (json.JSONDecodeError, AttributeError):
            text = line
        issues = find_leaks(text)
        if issues:
            bad += 1
            print(f"{path.name}:{n}: {', '.join(issues)}")
    print(f"anonymize-fixtures --check: {total} cases, {bad} with possible leaks -> {'FAIL' if bad else 'OK'}")
    return 1 if bad else 0


# ------------------------------------------------------------------ IO
@dataclass
class RawMessage:
    text: str
    sender: str
    received_at: str


def read_raw(path: Path, default_sender: str) -> list[RawMessage]:
    out: list[RawMessage] = []
    text = path.read_text(encoding="utf-8-sig")
    if path.suffix.lower() == ".jsonl":
        for line in text.splitlines():
            if not line.strip():
                continue
            obj = json.loads(line)
            inp = obj.get("input") if isinstance(obj.get("input"), dict) else {}
            body = obj.get("text") or obj.get("body") or obj.get("message") or inp.get("text") or ""
            if not str(body).strip():
                continue
            out.append(RawMessage(str(body), str(obj.get("sender") or inp.get("sender") or default_sender),
                                  str(obj.get("received_at") or inp.get("received_at") or obj.get("date") or DEFAULT_RECEIVED_AT)))
    else:
        for line in text.splitlines():
            if line.strip():
                out.append(RawMessage(line.rstrip("\r"), default_sender, DEFAULT_RECEIVED_AT))
    return out


def load_names(path: Path) -> list[str]:
    if not path.is_file():
        return []
    return [l.strip() for l in path.read_text(encoding="utf-8-sig").splitlines() if l.strip() and not l.lstrip().startswith("#")]


def split_for(case_id: str) -> str:
    return "train" if int(sha(case_id)[:8], 16) % 100 < 70 else "test"


def build_case(msg: RawMessage, anon_text: str, suite: str, source: str) -> dict:
    case_id = f"{suite}-{sha(msg.sender + '|' + anon_text)[:10]}"
    return {
        "id": case_id,
        "suite": suite,
        "split": split_for(case_id),
        "input": {"text": anon_text, "sender": msg.sender, "source": source, "received_at": msg.received_at},
        "expected": {},
        "tags": ["needs-labeling"],
        "source": "anonymized-real",
    }


def default_names_file() -> Path:
    return Path(__file__).resolve().parent / "anonymize-names.txt"


def main(argv: Iterable[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="inp", help="raw .txt (one message per line) or .jsonl")
    ap.add_argument("--out", help="output cases.jsonl")
    ap.add_argument("--suite", help="eval suite name (sms, notifications, whatsapp_text, ...)")
    ap.add_argument("--sender", help="sender id for every message (e.g. CIB, VodafoneCash)")
    ap.add_argument("--source", help="input.source value (default from suite: SMS / NOTIFICATION / WHATSAPP)")
    ap.add_argument("--names", default=None, help="names file (default: anonymize-names.txt beside this script)")
    ap.add_argument("--check", metavar="CASES_JSONL", help="scan an existing JSONL for leaks and exit 1 if any")
    try:
        args = ap.parse_args(list(argv) if argv is not None else None)
    except SystemExit as e:
        return int(e.code or 0)

    if args.check:
        p = Path(args.check)
        if not p.is_file():
            print(f"--check: file not found: {p}", file=sys.stderr)
            return 2
        return check_file(p)
    if not (args.inp and args.out and args.suite and args.sender):
        ap.print_usage(sys.stderr)
        print("anonymize: --in, --out, --suite and --sender are required (or use --check)", file=sys.stderr)
        return 2
    inp = Path(args.inp)
    if not inp.is_file():
        print(f"anonymize: input not found: {inp}", file=sys.stderr)
        return 2
    names = load_names(Path(args.names) if args.names else default_names_file())
    anon = Anonymizer(names)
    source = args.source or SOURCE_BY_SUITE.get(args.suite, args.suite.upper())
    cases: dict[str, dict] = {}
    for msg in read_raw(inp, args.sender):
        text = anon.anonymize(msg.text)
        leaks = find_leaks(text)
        if leaks:
            print(f"anonymize: refusing to emit a case that still looks identifying ({', '.join(leaks)}); "
                  f"fix the input or extend the rules", file=sys.stderr)
            return 1
        case = build_case(msg, text, args.suite, source)
        cases.setdefault(case["id"], case)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as f:
        for cid in sorted(cases):
            f.write(json.dumps(cases[cid], ensure_ascii=False) + "\n")
    n_train = sum(1 for c in cases.values() if c["split"] == "train")
    print(f"anonymize: {len(cases)} cases -> {out} (train {n_train} / test {len(cases) - n_train}); "
          f"all tagged needs-labeling")
    return 0


if __name__ == "__main__":
    sys.exit(main())
