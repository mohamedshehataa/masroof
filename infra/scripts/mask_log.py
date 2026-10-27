#!/usr/bin/env python3
"""Masks personal data in backend log lines before an operator (or an agent transcript) sees them.

Reads lines on stdin, writes masked lines on stdout, each cut to 400 characters. Input and output are always UTF-8
(never the console code page: on Windows that is cp1252, which would hide Arabic-Indic digits from the masks).
UUIDs (raw message, trace and expense ids) and ISO timestamps stay readable; everything that could identify a person
or unlock an account is replaced: e-mail addresses, Egyptian phone numbers, digit runs of 6+ with or without
separators (space, dash, dot, slash, no-break spaces; card and account numbers, Arabic-Indic and Persian digits too),
JWTs, and long opaque tokens.
"""
import re
import secrets
import sys

_KEEP = [
    re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
    re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?(?:Z|[+-]\d{2}:?\d{2})?"),
]
_DIGIT = "0-9٠-٩۰-۹"
_SEP = " .\\-/  "
_MASKS = [
    (re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"), "<jwt>"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "<email>"),
    (re.compile(rf"(?:\+|00)?20[{_SEP}]?1[0125](?:[{_SEP}]?\d){{8}}"), "<phone>"),
    (re.compile(rf"[{_DIGIT}](?:[{_SEP}]?[{_DIGIT}]){{5,}}"), "<digits>"),
    # 40+ characters: a token whatever it holds; 24-39: only when letters and digits are mixed (class names stay)
    (re.compile(r"[A-Za-z0-9_-]{40,}"), "<token>"),
    (re.compile(r"(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}"), "<token>"),
]
MAX_LINE = 400


def mask(line: str) -> str:
    # placeholders use a per-call random sentinel, so a crafted log line can never collide with them
    sentinel = secrets.token_hex(8)
    kept: list[str] = []

    def hold(m: re.Match) -> str:
        kept.append(m.group(0))
        return f"\x00{sentinel}:{len(kept) - 1}\x00"

    out = line
    for pattern in _KEEP:
        out = pattern.sub(hold, out)
    for pattern, replacement in _MASKS:
        out = pattern.sub(replacement, out)

    def restore(m: re.Match) -> str:
        index = int(m.group(1))
        return kept[index] if index < len(kept) else "<masked>"

    out = re.sub(rf"\x00{sentinel}:(\d+)\x00", restore, out)
    return out[:MAX_LINE]


def main() -> None:
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    for raw in sys.stdin:
        print(mask(raw.rstrip("\n")))


if __name__ == "__main__":
    main()
