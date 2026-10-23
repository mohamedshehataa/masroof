#!/usr/bin/env python3
"""Build docs/TRACEABILITY.md: REQ -> story -> tests (with last result) -> implementing files.

Used by `make trace` (PROMPT.md S11). Stdlib + PyYAML only.

Sources
  * Stories:  docs/backlog/EPIC-*/STORY-*.md (YAML frontmatter `reqs:` and `### REQ-XXX-NNN — name` headings)
  * Tests:    any test file (same patterns as .claude/hooks/test-integrity.sh) containing a literal REQ token,
              plus eval cases `eval/datasets/<suite>/cases.jsonl` (field `tags`)
  * Results:  JUnit XML from backend/android/web/hook/quality-test runs and eval/reports/latest.json
  * Files:    non-test source files mentioning a REQ token (heuristic, informational)

Gate (exit 1)
  * a test references a REQ id that no story defines ("unknown REQ")
  * a REQ of a DONE story has no test, or has a failing test
  * the same REQ id is defined by two stories
  * --strict: a REQ of a DONE story whose tests all have no result (UNKNOWN)
REQs of stories that are not DONE and have no tests are "pending", not failures.

Exit codes: 0 gate passed, 1 gate failed, 2 usage error. Output is sorted so re-runs produce no diff.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

import yaml

AREAS = ("WA", "SMS", "NTF", "IOS", "RCPT", "PARSE", "DEDUP", "EXP", "AN", "UI", "AND", "PWA", "SEC", "OPS")
REQ_RE = re.compile(r"\bREQ-(" + "|".join(AREAS) + r")-(\d{3})\b")
REQ_HEADING_RE = re.compile(r"^###\s+(REQ-(?:" + "|".join(AREAS) + r")-\d{3})\s*(?:[—\-–:]\s*(.*))?$")
STORY_FILE_RE = re.compile(r"^STORY-\d{3,}(?:-[^.]*)?\.md$")

# Keep in sync with .claude/hooks/test-integrity.sh (test-file detection).
TEST_DIR_RE = re.compile(r"(^|/)(test|tests|androidTest|e2e|__tests__|spec)/")
TEST_FILE_RES = (
    re.compile(r"\.(test|spec)\.(ts|tsx|js|jsx|mjs|kt|py)$"),
    re.compile(r"(Test|Tests|IT|Spec|Specs)\.(java|kt)$"),
    re.compile(r"(^|/)test_[^/]+\.py$"),
)
CODE_EXTS = {".java", ".kt", ".kts", ".py", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".sh", ".feature", ".yaml", ".yml", ".sql"}
EXCLUDED_DIRS = {
    ".git", "node_modules", "build", "dist", "coverage", ".gradle", "__pycache__", ".venv", ".vite",
    "test-results", "playwright-report", "state", "worktrees", "agent-memory-local", ".idea", "generated",
}
# IDE output dirs that are excluded only directly under a module root (backend/out), never deep
# package dirs such as adapters/out (STORY-002 verify finding).
MODULE_ROOT_ONLY_DIRS = {"out"}
RESULT_GLOBS = (
    "backend/build/test-results/**/*.xml",
    "android/**/build/test-results/**/*.xml",
    "android/**/build/outputs/androidTest-results/**/*.xml",
    "web/test-results/junit.xml",
    "web/playwright-report/junit.xml",
    ".claude/state/hook-tests.xml",
    "scripts/quality/tests/results.xml",
)
EVAL_RESULTS = "eval/reports/latest.json"
STATUSES = ("DRAFT", "READY", "IN_PROGRESS", "BLOCKED", "DONE", "DESCOPED")


@dataclass
class Story:
    id: str
    path: str
    status: str
    phase: str
    reqs: dict[str, str] = field(default_factory=dict)  # REQ id -> short name


@dataclass
class TestRef:
    file: str
    name: str
    kind: str  # "test" | "eval"
    result: str = "UNKNOWN"  # PASS | FAIL | UNKNOWN


@dataclass
class ReqRow:
    req: str
    name: str = ""
    story: Story | None = None
    tests: list[TestRef] = field(default_factory=list)
    files: list[str] = field(default_factory=list)

    @property
    def area(self) -> str:
        return self.req.split("-")[1]

    @property
    def result(self) -> str:
        if not self.tests:
            return "—"
        results = {t.result for t in self.tests}
        if "FAIL" in results:
            return "FAIL"
        if "PASS" in results:
            return "PASS"
        return "UNKNOWN"


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8-sig", errors="replace")
    except OSError:
        return ""


def rel(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def is_excluded(path: Path, root: Path) -> bool:
    parts = path.resolve().relative_to(root.resolve()).parts[:-1]
    if any(p in EXCLUDED_DIRS for p in parts):
        return True
    return len(parts) >= 2 and parts[1] in MODULE_ROOT_ONLY_DIRS


def is_test_file(relpath: str) -> bool:
    if TEST_DIR_RE.search(relpath):
        return True
    return any(r.search(relpath) for r in TEST_FILE_RES)


def walk_files(root: Path) -> Iterator[Path]:
    stack = [root]
    while stack:
        d = stack.pop()
        try:
            entries = sorted(d.iterdir(), key=lambda p: p.name)
        except OSError:
            continue
        for p in entries:
            if p.is_dir():
                depth = len(p.resolve().relative_to(root.resolve()).parts)
                if p.name in EXCLUDED_DIRS or (p.name in MODULE_ROOT_ONLY_DIRS and depth <= 2):
                    continue
                stack.append(p)
            else:
                yield p


# ---------------------------------------------------------------- stories
def parse_frontmatter(text: str) -> dict:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end < 0:
        return {}
    try:
        data = yaml.safe_load(text[3:end])
    except yaml.YAMLError:
        return {}
    return data if isinstance(data, dict) else {}


def load_stories(root: Path) -> tuple[list[Story], list[str]]:
    """Return stories and a list of parse problems."""
    stories: list[Story] = []
    problems: list[str] = []
    backlog = root / "docs" / "backlog"
    if not backlog.is_dir():
        return stories, problems
    for path in sorted(backlog.rglob("STORY-*.md")):
        if not STORY_FILE_RE.match(path.name):
            continue  # .verify.md / .review.md / .bug.md reports
        text = read_text(path)
        fm = parse_frontmatter(text)
        sid = str(fm.get("id") or path.name.split("-")[0] + "-" + path.name.split("-")[1]).strip()
        status = str(fm.get("status") or "DRAFT").strip().upper()
        if status not in STATUSES:
            problems.append(f"{rel(path, root)}: unknown status '{status}'")
        story = Story(id=sid, path=rel(path, root), status=status, phase=str(fm.get("phase", "")).strip())
        for r in fm.get("reqs") or []:
            m = REQ_RE.search(str(r))
            if m:
                story.reqs.setdefault(m.group(0), "")
        for line in text.splitlines():
            m = REQ_HEADING_RE.match(line.strip())
            if m:
                story.reqs[m.group(1)] = (m.group(2) or "").strip()
        stories.append(story)
    return stories, problems


# ---------------------------------------------------------------- tests
def scan_tests(root: Path) -> tuple[dict[str, list[TestRef]], dict[str, list[str]]]:
    """Return REQ -> test refs and REQ -> implementing files."""
    tests: dict[str, list[TestRef]] = {}
    files: dict[str, list[str]] = {}
    for path in walk_files(root):
        if path.suffix not in CODE_EXTS:
            continue
        if is_excluded(path, root):
            continue
        relpath = rel(path, root)
        text = read_text(path)
        if "REQ-" not in text:
            continue
        if is_test_file(relpath):
            for line in text.splitlines():
                for m in REQ_RE.finditer(line):
                    name = re.sub(r"\s+", " ", line.strip())[:120]
                    tests.setdefault(m.group(0), []).append(TestRef(relpath, name, "test"))
        else:
            for m in REQ_RE.finditer(text):
                lst = files.setdefault(m.group(0), [])
                if relpath not in lst:
                    lst.append(relpath)
    # eval cases: tags carry REQ ids
    for cases in sorted((root / "eval" / "datasets").glob("*/cases.jsonl")):
        relpath = rel(cases, root)
        for line in read_text(cases).splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            tags = obj.get("tags") or []
            cid = str(obj.get("id", ""))
            for tag in tags:
                m = REQ_RE.search(str(tag))
                if m:
                    tests.setdefault(m.group(0), []).append(TestRef(relpath, cid, "eval"))
    for lst in tests.values():
        lst.sort(key=lambda t: (t.file, t.name))
    return tests, files


# ---------------------------------------------------------------- results
@dataclass
class CaseResult:
    classname: str
    name: str
    file: str
    status: str  # PASS | FAIL | SKIP


def load_junit(root: Path) -> list[CaseResult]:
    out: list[CaseResult] = []
    seen: set[Path] = set()
    for pattern in RESULT_GLOBS:
        for xml_path in sorted(root.glob(pattern)):
            if xml_path in seen or not xml_path.is_file():
                continue
            seen.add(xml_path)
            try:
                tree = ET.parse(xml_path)
            except (ET.ParseError, OSError):
                continue
            for tc in tree.iter("testcase"):
                status = "PASS"
                for child in tc:
                    tag = child.tag.split("}")[-1]
                    if tag in ("failure", "error"):
                        status = "FAIL"
                        break
                    if tag == "skipped":
                        status = "SKIP"
                out.append(CaseResult(tc.get("classname", ""), tc.get("name", ""), tc.get("file", ""), status))
    return out


def load_eval_results(root: Path) -> dict[str, str]:
    """case id -> PASS|FAIL from eval/reports/latest.json (optional)."""
    path = root / EVAL_RESULTS
    if not path.is_file():
        return {}
    try:
        data = json.loads(read_text(path))
    except json.JSONDecodeError:
        return {}
    cases = data if isinstance(data, list) else (data.get("cases") or data.get("results") or [])
    out: dict[str, str] = {}
    for c in cases:
        if not isinstance(c, dict):
            continue
        cid = str(c.get("id", ""))
        if "pass" in c or "passed" in c:
            ok = bool(c.get("pass", c.get("passed")))
        else:
            ok = str(c.get("status", "")).upper() in ("PASS", "PASSED", "OK")
        out[cid] = "PASS" if ok else "FAIL"
    return out


def attach_results(tests: dict[str, list[TestRef]], junit: list[CaseResult], evals: dict[str, str]) -> None:
    for req, refs in tests.items():
        for ref in refs:
            if ref.kind == "eval":
                ref.result = evals.get(ref.name, "UNKNOWN")
                continue
            stem = Path(ref.file).name
            stem = stem.split(".")[0]
            by_name = [c for c in junit if req in c.name or req in c.classname]
            if by_name:
                matched = by_name
            else:
                matched = [
                    c for c in junit
                    if c.classname.split(".")[-1].split("/")[-1] == stem
                    or c.classname.replace("\\", "/").endswith(ref.file)
                    or (c.file and c.file.replace("\\", "/").endswith(Path(ref.file).name))
                ]
            statuses = {c.status for c in matched}
            if "FAIL" in statuses:
                ref.result = "FAIL"
            elif "PASS" in statuses:
                ref.result = "PASS"
            else:
                ref.result = "UNKNOWN"


# ---------------------------------------------------------------- matrix
@dataclass
class Matrix:
    rows: list[ReqRow]
    unknown: dict[str, list[TestRef]]
    duplicates: dict[str, list[str]]
    problems: list[str]
    strict: bool = False

    def gate_findings(self) -> list[str]:
        findings: list[str] = []
        for req in sorted(self.duplicates):
            findings.append(f"duplicate REQ {req} defined in: {', '.join(sorted(self.duplicates[req]))}")
        for req in sorted(self.unknown):
            where = sorted({t.file for t in self.unknown[req]})
            findings.append(f"unknown REQ {req} referenced by tests but defined in no story: {', '.join(where)}")
        for row in self.rows:
            if row.story is None or row.story.status != "DONE":
                continue
            if not row.tests:
                findings.append(f"{row.req} ({row.story.id} is DONE) has no test")
            elif row.result == "FAIL":
                findings.append(f"{row.req} ({row.story.id} is DONE) has a failing test")
            elif self.strict and row.result == "UNKNOWN":
                findings.append(f"{row.req} ({row.story.id} is DONE) has tests but no result was found (--strict)")
        findings.extend(f"story parse problem: {p}" for p in self.problems)
        return findings

    def summary(self) -> dict[str, int | str]:
        total = len(self.rows)
        covered = sum(1 for r in self.rows if r.tests)
        passing = sum(1 for r in self.rows if r.result == "PASS")
        pending = sum(1 for r in self.rows if not r.tests and r.story is not None and r.story.status not in ("DONE", "DESCOPED"))
        orphans = sum(1 for r in self.rows if not r.tests and r.story is not None and r.story.status == "DONE")
        return {
            "total REQs": total,
            "covered (at least one test)": covered,
            "passing": passing,
            "failing": sum(1 for r in self.rows if r.result == "FAIL"),
            "pending (story not DONE, no test yet)": pending,
            "orphans (DONE story, no test)": orphans,
            "unknown REQ references": len(self.unknown),
            "duplicate REQ ids": len(self.duplicates),
            "gate": "FAIL" if self.gate_findings() else "PASS",
        }


def build_matrix(root: Path, strict: bool) -> Matrix:
    stories, problems = load_stories(root)
    tests, files = scan_tests(root)
    attach_results(tests, load_junit(root), load_eval_results(root))

    owners: dict[str, list[str]] = {}
    rows: dict[str, ReqRow] = {}
    for story in sorted(stories, key=lambda s: s.id):
        for req, name in story.reqs.items():
            owners.setdefault(req, []).append(story.id)
            if req not in rows:
                rows[req] = ReqRow(req=req, name=name, story=story)
    duplicates = {req: ids for req, ids in owners.items() if len(ids) > 1}
    unknown = {req: refs for req, refs in tests.items() if req not in rows}
    for req, row in rows.items():
        row.tests = tests.get(req, [])
        row.files = sorted(files.get(req, []))
    ordered = sorted(rows.values(), key=lambda r: (AREAS.index(r.area), r.req))
    return Matrix(ordered, unknown, duplicates, problems, strict)


# ---------------------------------------------------------------- rendering
def md_escape(s: str) -> str:
    return s.replace("|", "\\|").replace("\n", " ")


def render_markdown(m: Matrix) -> str:
    out: list[str] = []
    out.append("# Traceability matrix")
    out.append("")
    out.append("Generated by `scripts/quality/traceability.py` (`make trace`). Do not edit by hand.")
    out.append("REQ → story (status, phase) → tests (file · name, last result) → implementing files.")
    out.append("")
    out.append("## Summary")
    out.append("| metric | value |")
    out.append("|---|---|")
    for k, v in m.summary().items():
        out.append(f"| {k} | {v} |")
    out.append("")
    findings = m.gate_findings()
    out.append("## Gate findings")
    if findings:
        out.extend(f"- {md_escape(f)}" for f in findings)
    else:
        out.append("None.")
    out.append("")
    if m.unknown:
        out.append("## Unknown REQ references")
        out.append("| REQ | tests |")
        out.append("|---|---|")
        for req in sorted(m.unknown):
            refs = sorted({f"`{t.file}`" for t in m.unknown[req]})
            out.append(f"| {req} | {', '.join(refs)} |")
        out.append("")
    for area in AREAS:
        rows = [r for r in m.rows if r.area == area]
        if not rows:
            continue
        out.append(f"## Area {area}")
        out.append("| REQ | name | story | status | phase | tests | result | files |")
        out.append("|---|---|---|---|---|---|---|---|")
        for r in rows:
            story = r.story
            tests = "<br>".join(f"`{t.file}` · {md_escape(t.name)} ({t.result})" for t in r.tests) or "—"
            files = "<br>".join(f"`{f}`" for f in r.files) or "—"
            out.append(
                f"| {r.req} | {md_escape(r.name) or '—'} | {story.id if story else '—'} | "
                f"{story.status if story else '—'} | {story.phase if story else '—'} | {tests} | {r.result} | {files} |"
            )
        out.append("")
    return "\n".join(out).rstrip("\n") + "\n"


def to_json(m: Matrix) -> dict:
    return {
        "summary": m.summary(),
        "gate_findings": m.gate_findings(),
        "reqs": [
            {
                "req": r.req,
                "name": r.name,
                "story": r.story.id if r.story else None,
                "status": r.story.status if r.story else None,
                "phase": r.story.phase if r.story else None,
                "result": r.result,
                "tests": [{"file": t.file, "name": t.name, "kind": t.kind, "result": t.result} for t in r.tests],
                "files": r.files,
            }
            for r in m.rows
        ],
        "unknown": {req: sorted({t.file for t in refs}) for req, refs in sorted(m.unknown.items())},
        "duplicates": {req: sorted(ids) for req, ids in sorted(m.duplicates.items())},
    }


def default_root() -> Path:
    return Path(__file__).resolve().parents[2]


def resolve_out(s: str, root: Path) -> Path:
    """Relative paths are under root; '/c/x' (Git Bash style) becomes 'C:/x' on Windows."""
    m = re.fullmatch(r"/([A-Za-z])/(.*)", s.replace("\\", "/"))
    if m and sys.platform == "win32":
        return Path(f"{m.group(1).upper()}:/{m.group(2)}")
    p = Path(s)
    if p.is_absolute() or s.startswith("/"):
        return p
    return root / p


def main(argv: Iterable[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="docs/TRACEABILITY.md", help="markdown output (relative to --root unless absolute)")
    ap.add_argument("--strict", action="store_true", help="also fail when a DONE story's REQ has only UNKNOWN test results")
    ap.add_argument("--json", action="store_true", help="print the matrix as JSON on stdout")
    ap.add_argument("--root", default=None, help="repository root (default: two levels above this script)")
    try:
        args = ap.parse_args(list(argv) if argv is not None else None)
    except SystemExit as e:  # argparse exits 2 on usage error, 0 on -h
        return int(e.code or 0)
    root = Path(args.root).resolve() if args.root else default_root()
    if not root.is_dir():
        print(f"traceability: root not found: {root}", file=sys.stderr)
        return 2
    matrix = build_matrix(root, args.strict)
    out_path = resolve_out(args.out, root)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(render_markdown(matrix), encoding="utf-8", newline="\n")
    if args.json:
        print(json.dumps(to_json(matrix), indent=2, ensure_ascii=False, sort_keys=False))
    else:
        summary = matrix.summary()
        print(f"traceability: {summary['total REQs']} REQs, {summary['covered (at least one test)']} covered, "
              f"{summary['passing']} passing, {summary['unknown REQ references']} unknown, gate {summary['gate']} -> {out_path}")
        for f in matrix.gate_findings():
            print(f"  - {f}")
    return 1 if matrix.gate_findings() else 0


if __name__ == "__main__":
    sys.exit(main())
