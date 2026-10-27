#!/usr/bin/env python3
"""Masks personal data in backend log lines before an operator (or an agent transcript) sees them.

Reads lines on stdin, writes masked lines on stdout, each cut to 400 characters. UUIDs (raw message, trace and
expense ids) and ISO timestamps are kept readable; everything that could identify a person or unlock an account is
replaced: e-mail addresses, Egyptian phone numbers, digit runs of 6+ with or without spaces/dashes (card and account
numbers, Arabic-Indic digits too), JWTs and long opaque tokens that mix letters and digits.
"""
import re
import sys

_KEEP = [
    re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
    re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?(?:Z|[+-]\d{2}:?\d{2})?"),
]
_DIGIT = "0-9٠-٩۰-۹"
_MASKS = [
    (re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"), "<jwt>"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "<email>"),
    (re.compile(r"(?:\+|00)?20[ -]?1[0125](?:[ -]?\d){8}"), "<phone>"),
    (re.compile(rf"[{_DIGIT}](?:[ -]?[{_DIGIT}]){{5,}}"), "<digits>"),
    (re.compile(r"(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}"), "<token>"),
]
MAX_LINE = 400


def mask(line: str) -> str:
    kept: list[str] = []

    def hold(m: re.Match) -> str:
        kept.append(m.group(0))
        return f"\x00{len(kept) - 1}\x00"

    out = line
    for pattern in _KEEP:
        out = pattern.sub(hold, out)
    for pattern, replacement in _MASKS:
        out = pattern.sub(replacement, out)
    out = re.sub(r"\x00(\d+)\x00", lambda m: kept[int(m.group(1))], out)
    return out[:MAX_LINE]


def main() -> None:
    for raw in sys.stdin:
        print(mask(raw.rstrip("\n")))


if __name__ == "__main__":
    main()
