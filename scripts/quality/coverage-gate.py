#!/usr/bin/env python3
"""Coverage / mutation / flakiness gate (PROMPT.md S9). Stdlib + PyYAML only.

  coverage-gate.py [--thresholds scripts/quality/quality-thresholds.yaml] [--root DIR]

Reads
  * JaCoCo XML      backend line/branch overall and per package token (money, parsing, dedup, analytics)
  * PIT mutations.xml   mutation score per package token and for the rest of the domain
  * Kover XML       Android line coverage per package token (ingestion, filtering, outbox, viewmodel)
  * Vitest coverage-summary.json   web line coverage per path token (hooks, utils, components)
  * docs/FLAKY_TESTS.md    number of quarantined tests
and compares them with quality-thresholds.yaml.

A module whose marker (backend/gradlew, android/gradlew, web/package.json) is absent is SKIPPED with a note;
a module that exists but has no report is a FAIL. A package/path token with no matching entry in the report
is reported as SKIP (nothing to measure yet). Prints a table; exit 1 on any FAIL, 2 on usage error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import yaml


@dataclass
class Row:
    area: str
    metric: str
    actual: str
    threshold: str
    status: str  # PASS | FAIL | SKIP
    note: str = ""


def pct(covered: int, total: int) -> float | None:
    return None if total == 0 else round(100.0 * covered / total, 2)


def fmt(v: float | None) -> str:
    return "–" if v is None else f"{v:.1f}%"


def gate(area: str, metric: str, actual: float | None, minimum: float, rows: list[Row], missing_note: str = "no data for token") -> None:
    if actual is None:
        rows.append(Row(area, metric, "–", f">= {minimum:g}%", "SKIP", missing_note))
    else:
        rows.append(Row(area, metric, fmt(actual), f">= {minimum:g}%", "PASS" if actual >= minimum else "FAIL"))


# -------------------------------------------------------------- JaCoCo / Kover (same XML schema)
def jacoco_counters(elem: ET.Element) -> dict[str, tuple[int, int]]:
    out: dict[str, tuple[int, int]] = {}
    for c in elem.findall("counter"):
        out[c.get("type", "")] = (int(c.get("covered", 0)), int(c.get("missed", 0)))
    return out


def jacoco_overall(root: ET.Element, kind: str) -> float | None:
    cov, miss = jacoco_counters(root).get(kind, (0, 0))
    return pct(cov, cov + miss)


def jacoco_package(root: ET.Element, token: str, kind: str) -> float | None:
    cov = miss = 0
    found = False
    for pkg in root.iter("package"):
        name = pkg.get("name", "").replace("/", ".")
        if token.lower() in name.lower():
            found = True
            c, m = jacoco_counters(pkg).get(kind, (0, 0))
            cov += c
            miss += m
    return pct(cov, cov + miss) if found else None


def load_xml(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return None


# -------------------------------------------------------------- PIT
def pit_scores(path: Path) -> list[tuple[str, bool]]:
    root = load_xml(path)
    if root is None:
        return []
    out: list[tuple[str, bool]] = []
    for m in root.iter("mutation"):
        cls = (m.findtext("mutatedClass") or "").strip()
        out.append((cls, str(m.get("detected", "")).lower() == "true"))
    return out


def pit_score_for(muts: list[tuple[str, bool]], pred) -> float | None:
    sel = [killed for cls, killed in muts if pred(cls)]
    return pct(sum(sel), len(sel)) if sel else None


# -------------------------------------------------------------- Vitest
def vitest_path_pct(data: dict, token: str) -> float | None:
    cov = total = 0
    found = False
    for key, val in data.items():
        if key == "total" or not isinstance(val, dict):
            continue
        norm = key.replace("\\", "/").lower()
        if f"/{token.lower()}/" in norm or norm.startswith(f"{token.lower()}/"):
            found = True
            lines = val.get("lines", {})
            cov += int(lines.get("covered", 0))
            total += int(lines.get("total", 0))
    return pct(cov, total) if found else None


# -------------------------------------------------------------- flaky
def flaky_count(path: Path) -> int:
    if not path.is_file():
        return 0
    rows = 0
    bullets = 0
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        s = line.strip()
        if s.startswith("|"):
            cells = [c.strip() for c in s.strip("|").split("|")]
            if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
                continue
            if cells and cells[0].lower() in ("test", "name", "test name", "id"):
                continue
            rows += 1
        elif re.match(r"^[-*]\s+\S", s):
            bullets += 1
    return rows or bullets


# -------------------------------------------------------------- evaluation
def evaluate(root: Path, th: dict) -> list[Row]:
    rows: list[Row] = []

    # ---- backend
    b = th.get("backend") or {}
    if b:
        if not (root / b.get("module_marker", "backend/gradlew")).exists():
            rows.append(Row("backend", "coverage/mutation", "–", "–", "SKIP", f"module not present ({b.get('module_marker')})"))
        else:
            jac = root / b.get("jacoco", "")
            xml = load_xml(jac) if jac.is_file() else None
            if xml is None:
                rows.append(Row("backend", "JaCoCo report", "missing", "required", "FAIL", f"{b.get('jacoco')} not found or unreadable"))
            else:
                gate("backend", "line coverage (overall)", jacoco_overall(xml, "LINE"), float(b.get("line", 85)), rows)
                gate("backend", "branch coverage (overall)", jacoco_overall(xml, "BRANCH"), float(b.get("branch", 75)), rows)
                for token, limits in sorted((b.get("packages") or {}).items()):
                    gate("backend", f"line coverage ({token})", jacoco_package(xml, token, "LINE"), float(limits.get("line", 95)), rows,
                         f"no package containing '{token}'")
                    gate("backend", f"branch coverage ({token})", jacoco_package(xml, token, "BRANCH"), float(limits.get("branch", 90)), rows,
                         f"no package containing '{token}'")
            pit = root / b.get("pitest", "")
            muts = pit_scores(pit) if pit.is_file() else []
            if not pit.is_file():
                rows.append(Row("backend", "PIT report", "missing", "required", "FAIL", f"{b.get('pitest')} not found"))
            elif not muts:
                rows.append(Row("backend", "mutation score", "–", "–", "SKIP", "report has no mutations"))
            else:
                tokens = sorted((b.get("packages") or {}).items())
                for token, limits in tokens:
                    if "mutation" in limits:
                        gate("backend", f"mutation score ({token})", pit_score_for(muts, lambda c, t=token: t.lower() in c.lower()),
                             float(limits["mutation"]), rows, f"no mutated class containing '{token}'")
                dom = str(b.get("domain_token", "domain"))
                named = [t for t, _ in tokens]
                gate("backend", f"mutation score ({dom}, other packages)",
                     pit_score_for(muts, lambda c: dom.lower() in c.lower() and not any(t.lower() in c.lower() for t in named)),
                     float(b.get("domain_mutation", 65)), rows, f"no other mutated class containing '{dom}'")
                rows.append(Row("backend", "mutation score (overall)", fmt(pit_score_for(muts, lambda c: True)), "info", "PASS"))

    # ---- android
    a = th.get("android") or {}
    if a:
        if not (root / a.get("module_marker", "android/gradlew")).exists():
            rows.append(Row("android", "coverage (Kover)", "–", "–", "SKIP", f"module not present ({a.get('module_marker')})"))
        else:
            reports = sorted(p for p in root.glob(a.get("kover", "android/**/build/reports/kover/*.xml")) if p.is_file())
            xml = load_xml(reports[-1]) if reports else None
            if xml is None:
                rows.append(Row("android", "Kover report", "missing", "required", "FAIL", f"{a.get('kover')} not found or unreadable"))
            else:
                rows.append(Row("android", "line coverage (overall)", fmt(jacoco_overall(xml, "LINE")), "info", "PASS"))
                for token, limits in sorted((a.get("packages") or {}).items()):
                    gate("android", f"line coverage ({token})", jacoco_package(xml, token, "LINE"), float(limits.get("line", 80)), rows,
                         f"no package containing '{token}'")

    # ---- web
    w = th.get("web") or {}
    if w:
        if not (root / w.get("module_marker", "web/package.json")).exists():
            rows.append(Row("web", "coverage (Vitest)", "–", "–", "SKIP", f"module not present ({w.get('module_marker')})"))
        else:
            summary = root / w.get("vitest", "web/coverage/coverage-summary.json")
            data: dict | None = None
            if summary.is_file():
                try:
                    data = json.loads(summary.read_text(encoding="utf-8-sig"))
                except json.JSONDecodeError:
                    data = None
            if not isinstance(data, dict):
                rows.append(Row("web", "Vitest coverage summary", "missing", "required", "FAIL", f"{w.get('vitest')} not found or unreadable"))
            else:
                total = data.get("total", {}).get("lines", {})
                rows.append(Row("web", "line coverage (overall)", fmt(total.get("pct")), "info", "PASS"))
                for token, limits in sorted((w.get("paths") or {}).items()):
                    gate("web", f"line coverage ({token})", vitest_path_pct(data, token), float(limits.get("line", 85)), rows,
                         f"no file under '{token}/'")

    # ---- flaky
    f = th.get("flaky") or {}
    if f:
        n = flaky_count(root / f.get("file", "docs/FLAKY_TESTS.md"))
        mx = int(f.get("max_quarantined", 5))
        rows.append(Row("tests", "quarantined flaky tests", str(n), f"<= {mx}", "PASS" if n <= mx else "FAIL"))
    return rows


def render(rows: list[Row]) -> str:
    widths = [max(len(x) for x in col) for col in zip(*([("area", "metric", "actual", "threshold", "status", "note")] +
                                                        [(r.area, r.metric, r.actual, r.threshold, r.status, r.note) for r in rows]))]
    def line(vals: Iterable[str]) -> str:
        return "  ".join(v.ljust(wd) for v, wd in zip(vals, widths)).rstrip()
    out = [line(("area", "metric", "actual", "threshold", "status", "note")), line("-" * wd for wd in widths)]
    out.extend(line((r.area, r.metric, r.actual, r.threshold, r.status, r.note)) for r in rows)
    return "\n".join(out)


def default_root() -> Path:
    return Path(__file__).resolve().parents[2]


def main(argv: Iterable[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--thresholds", default=None, help="thresholds YAML (default: quality-thresholds.yaml beside this script)")
    ap.add_argument("--root", default=None, help="repository root (default: two levels above this script)")
    try:
        args = ap.parse_args(list(argv) if argv is not None else None)
    except SystemExit as e:
        return int(e.code or 0)
    root = Path(args.root).resolve() if args.root else default_root()
    th_path = Path(args.thresholds) if args.thresholds else Path(__file__).resolve().parent / "quality-thresholds.yaml"
    if not th_path.is_absolute() and not th_path.is_file():
        th_path = root / th_path
    if not th_path.is_file():
        print(f"coverage-gate: thresholds file not found: {th_path}", file=sys.stderr)
        return 2
    try:
        th = yaml.safe_load(th_path.read_text(encoding="utf-8-sig")) or {}
    except yaml.YAMLError as e:
        print(f"coverage-gate: cannot parse {th_path}: {e}", file=sys.stderr)
        return 2
    rows = evaluate(root, th)
    print(render(rows))
    fails = [r for r in rows if r.status == "FAIL"]
    skips = sum(1 for r in rows if r.status == "SKIP")
    print()
    print(f"COVERAGE_GATE: {'FAIL' if fails else 'OK'} ({len(rows) - len(fails) - skips} pass, {len(fails)} fail, {skips} skipped)")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
