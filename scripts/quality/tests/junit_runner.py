#!/usr/bin/env python3
"""Run the unittest suites in this directory and write a JUnit XML report (merged with shell test results).

  junit_runner.py --tests-dir DIR --out results.xml [--shell-tsv FILE]

The optional TSV (written by run.sh) has one line per shell test case: suite<TAB>ok|fail<TAB>name<TAB>detail.
Exit 1 when any Python or shell case failed, else 0.
"""
from __future__ import annotations

import argparse
import sys
import time
import traceback
import unittest
from dataclasses import dataclass
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr


@dataclass
class Case:
    suite: str
    name: str
    status: str  # ok | fail | error | skip
    detail: str = ""
    seconds: float = 0.0


class Collector(unittest.TestResult):
    def __init__(self) -> None:
        super().__init__()
        self.cases: list[Case] = []
        self._start: float = 0.0

    @staticmethod
    def _ids(test: unittest.TestCase) -> tuple[str, str]:
        tid = test.id()  # module.Class.method
        parts = tid.rsplit(".", 1)
        return (parts[0], parts[1]) if len(parts) == 2 else ("tests", tid)

    def startTest(self, test: unittest.TestCase) -> None:
        super().startTest(test)
        self._start = time.perf_counter()

    def _add(self, test: unittest.TestCase, status: str, detail: str = "") -> None:
        suite, name = self._ids(test)
        self.cases.append(Case(suite, name, status, detail, time.perf_counter() - self._start))
        sys.stdout.write(f"  {status:<5} {suite}.{name}\n")

    def addSuccess(self, test: unittest.TestCase) -> None:
        super().addSuccess(test)
        self._add(test, "ok")

    def addFailure(self, test: unittest.TestCase, err) -> None:  # type: ignore[override]
        super().addFailure(test, err)
        self._add(test, "fail", "".join(traceback.format_exception(*err)))

    def addError(self, test: unittest.TestCase, err) -> None:  # type: ignore[override]
        super().addError(test, err)
        self._add(test, "error", "".join(traceback.format_exception(*err)))

    def addSkip(self, test: unittest.TestCase, reason: str) -> None:
        super().addSkip(test, reason)
        self._add(test, "skip", reason)


def read_shell_tsv(path: Path) -> list[Case]:
    out: list[Case] = []
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        suite, status, name = parts[0], parts[1], parts[2]
        detail = parts[3] if len(parts) > 3 else ""
        out.append(Case(suite, name, "ok" if status == "ok" else "fail", detail))
    return out


def write_xml(cases: list[Case], out: Path) -> None:
    suites: dict[str, list[Case]] = {}
    for c in cases:
        suites.setdefault(c.suite, []).append(c)
    bad = sum(1 for c in cases if c.status in ("fail", "error"))
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             f'<testsuites name="quality-tests" tests="{len(cases)}" failures="{bad}">']
    for suite in sorted(suites):
        cs = suites[suite]
        sbad = sum(1 for c in cs if c.status in ("fail", "error"))
        sskip = sum(1 for c in cs if c.status == "skip")
        lines.append(f'  <testsuite name={quoteattr(suite)} tests="{len(cs)}" failures="{sbad}" skipped="{sskip}">')
        for c in cs:
            head = f'    <testcase classname={quoteattr(suite)} name={quoteattr(c.name)} time="{c.seconds:.3f}"'
            if c.status == "ok":
                lines.append(head + "/>")
            elif c.status == "skip":
                lines.append(head + f"><skipped message={quoteattr(c.detail[:200])}/></testcase>")
            else:
                tag = "error" if c.status == "error" else "failure"
                lines.append(head + f'><{tag} message="failed">{escape(c.detail)}</{tag}></testcase>')
        lines.append("  </testsuite>")
    lines.append("</testsuites>")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tests-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--shell-tsv", default=None)
    args = ap.parse_args()
    tests_dir = Path(args.tests_dir).resolve()
    suite = unittest.defaultTestLoader.discover(str(tests_dir), pattern="test_*.py", top_level_dir=str(tests_dir))
    result = Collector()
    print("== python unit tests")
    suite.run(result)
    cases = list(result.cases)
    if args.shell_tsv:
        cases.extend(read_shell_tsv(Path(args.shell_tsv)))
    write_xml(cases, Path(args.out))
    bad = [c for c in cases if c.status in ("fail", "error")]
    for c in bad:
        print(f"FAILED {c.suite}.{c.name}\n{c.detail}")
    print(f"quality tests: {len(cases) - len(bad)}/{len(cases)} passed; report: {args.out}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
