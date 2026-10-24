#!/usr/bin/env python3
"""Regenerate docs/SDLC_METRICS.md (`make metrics`, PROMPT.md S14). Stdlib + PyYAML only.

Inputs (all optional; missing inputs yield zeros / "–"):
  * docs/backlog/EPIC-*/STORY-*.md         frontmatter (status, phase) and the "## Status log" table
                                            rows `| date | stage | agent | result |`
  * STORY-*.verify.md / STORY-*.review.md  findings: table rows or bracket tokens with a severity
                                            BLOCKER|MAJOR|MINOR|NIT; the reviewer agent is the cell that looks
                                            like an agent name (e.g. code-reviewer); verify reports default to
                                            qa-verifier
  * docs/LESSONS.md, *.bug.md              escaped defects (convention below)
  * docs/PROGRESS.md (`RED` lines), .claude/state/hooks.log (`BLOCK` lines)   gate failures
  * docs/FLAKY_TESTS.md                     quarantined flaky tests (one table row or bullet per test)
  * backend/build/reports/jacoco/test/jacocoTestReport.xml, backend/build/reports/pitest/mutations.xml,
    android/**/build/reports/kover/*.xml, web/coverage/coverage-summary.json, newest eval/reports/*.md
                                            coverage / mutation / eval trend snapshot

Escaped-defect convention
  A defect found after its story was Done is recorded EITHER as a file
  `docs/backlog/EPIC-xx/STORY-NNN[-slug][.k].bug.md` beside the story (one file per defect) OR as an entry
  line in docs/LESSONS.md (a line starting with `- `) that mentions the STORY-NNN id. Each such file or
  line counts as one escaped defect; a story may have several.

Cycle time
  For each story the Status log rows are sorted by date; the time spent in a stage is the gap between the
  first row of that stage and the first row of the next different stage. Story cycle time is last row minus
  first row. Loops per story = verify rows whose result contains FAIL before the first verify PASS.

The `## AI spend ledger` section is preserved verbatim (it is hand-appended by the orchestrator) and
`AI spend (USD)` in the summary is the sum of its USD column. Everything else is rewritten deterministically.
Exit codes: 0 ok, 2 usage error.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable

import yaml

STATUSES = ("DRAFT", "READY", "IN_PROGRESS", "BLOCKED", "DONE", "DESCOPED")
SEVERITIES = ("BLOCKER", "MAJOR", "MINOR", "NIT")
STORY_FILE_RE = re.compile(r"^STORY-\d{3,}(?:-[^.]*)?\.md$")
STORY_ID_RE = re.compile(r"\bSTORY-\d{3,}\b")
SEV_RE = re.compile(r"\b(BLOCKER|MAJOR|MINOR|NIT)\b")
AGENT_RE = re.compile(r"^[a-z]+(?:-[a-z0-9]+)+$")
LEDGER_HEADING = "## AI spend ledger"
BUDGETS_HEADING = "## Phase budgets"  # hand-maintained caps per phase (owner decision 2026-10-24), preserved verbatim
DEFAULT_LEDGER = (
    f"{LEDGER_HEADING}\n"
    "| date | story/phase | model mix | USD | note |\n"
    "|---|---|---|---|---|\n"
)
# A gate failure line in PROGRESS.md carries RED as its own field; `<GREEN|RED|INFO>` in the format legend does not count.
RED_RE = r"(?<![|\w])RED(?![|\w])"
STAGE_ORDER = ("intake", "spec", "design", "test-design", "implement", "verify", "review", "integrate", "release", "operate")


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8-sig", errors="replace")
    except OSError:
        return ""


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


def parse_date(s: str) -> datetime | None:
    s = s.strip().replace("T", " ").rstrip("Z")
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s[: len(datetime(2000, 1, 1).strftime(fmt))], fmt)
        except ValueError:
            continue
    return None


def table_rows(text: str) -> list[list[str]]:
    """All markdown table body rows (header + separator rows removed) as lists of cells."""
    rows: list[list[str]] = []
    for line in text.splitlines():
        s = line.strip()
        if not s.startswith("|"):
            continue
        cells = [c.strip() for c in s.strip("|").split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c) and any(cells):
            continue  # separator
        rows.append(cells)
    return rows


def section(text: str, heading: str) -> str:
    """Body of a `## heading` section (without the heading line) up to the next `## `."""
    lines = text.splitlines()
    out: list[str] = []
    inside = False
    for line in lines:
        if line.strip().lower() == heading.lower():
            inside = True
            continue
        if inside and line.startswith("## "):
            break
        if inside:
            out.append(line)
    return "\n".join(out)


# ----------------------------------------------------------------- stories
@dataclass
class LogRow:
    date: datetime
    stage: str
    agent: str
    result: str


@dataclass
class Story:
    id: str
    status: str
    phase: str
    path: Path
    log: list[LogRow] = field(default_factory=list)

    @property
    def cycle_days(self) -> float | None:
        if len(self.log) < 2:
            return None
        return (self.log[-1].date - self.log[0].date).total_seconds() / 86400

    @property
    def loops(self) -> int | None:
        verify = [r for r in self.log if "verif" in r.stage.lower()]
        if not verify:
            return None
        n = 0
        for r in verify:
            if "PASS" in r.result.upper():
                break
            if "FAIL" in r.result.upper():
                n += 1
        return n


def load_stories(root: Path) -> list[Story]:
    stories: list[Story] = []
    backlog = root / "docs" / "backlog"
    if not backlog.is_dir():
        return stories
    for path in sorted(backlog.rglob("STORY-*.md")):
        if not STORY_FILE_RE.match(path.name):
            continue
        text = read_text(path)
        fm = parse_frontmatter(text)
        sid = str(fm.get("id") or "-".join(path.name.split("-")[:2])).strip()
        status = str(fm.get("status") or "DRAFT").strip().upper()
        if status not in STATUSES:
            status = "DRAFT"
        story = Story(sid, status, str(fm.get("phase", "")).strip() or "–", path)
        header_seen = False
        for cells in table_rows(section(text, "## Status log")):
            if not header_seen:
                header_seen = True
                if cells and cells[0].lower() == "date":
                    continue
            if len(cells) < 4:
                continue
            d = parse_date(cells[0])
            if d is None:
                continue
            story.log.append(LogRow(d, cells[1], cells[2], cells[3]))
        story.log.sort(key=lambda r: r.date)
        stories.append(story)
    return stories


def stage_durations(stories: list[Story]) -> dict[str, list[float]]:
    out: dict[str, list[float]] = {}
    for s in stories:
        rows = s.log
        i = 0
        while i < len(rows):
            stage = rows[i].stage.strip().lower()
            j = i + 1
            while j < len(rows) and rows[j].stage.strip().lower() == stage:
                j += 1
            if j < len(rows):
                out.setdefault(stage, []).append((rows[j].date - rows[i].date).total_seconds() / 86400)
            i = j
    return out


# ----------------------------------------------------------------- findings
@dataclass
class Finding:
    severity: str
    agent: str
    report: str


def load_findings(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    backlog = root / "docs" / "backlog"
    if not backlog.is_dir():
        return findings
    for path in sorted(list(backlog.rglob("*.review.md")) + list(backlog.rglob("*.verify.md"))):
        default_agent = "qa-verifier" if path.name.endswith(".verify.md") else "code-reviewer"
        text = read_text(path)
        for cells in table_rows(text):
            sev_cells = [c for c in cells if SEV_RE.fullmatch(c.strip("*` "))]
            if not sev_cells:
                continue
            if cells and cells[0].lower().startswith(("#", "severity")):
                continue
            agent = next((c for c in cells if AGENT_RE.match(c.strip("*` "))), default_agent)
            findings.append(Finding(sev_cells[0].strip("*` "), agent.strip("*` "), path.name))
        for line in text.splitlines():
            m = re.match(r"^\s*[-*]\s*\[(BLOCKER|MAJOR|MINOR|NIT)\]", line)
            if m:
                am = re.search(r"\b([a-z]+(?:-[a-z0-9]+)+)\b", line[m.end():])
                findings.append(Finding(m.group(1), am.group(1) if am else default_agent, path.name))
    return findings


# ----------------------------------------------------------------- misc counters
def escaped_defects(root: Path, stories: list[Story]) -> list[tuple[str, str]]:
    """(story id, source) per escaped defect, per the convention in the module docstring."""
    out: list[tuple[str, str]] = []
    backlog = root / "docs" / "backlog"
    if backlog.is_dir():
        for bug in sorted(backlog.rglob("*.bug.md")):
            m = STORY_ID_RE.search(bug.name)
            if m:
                out.append((m.group(0), bug.relative_to(root).as_posix()))
    lessons = root / "docs" / "LESSONS.md"
    if lessons.is_file():
        for n, line in enumerate(read_text(lessons).splitlines(), 1):
            if line.lstrip().startswith("- "):
                for sid in sorted(set(STORY_ID_RE.findall(line))):
                    out.append((sid, f"docs/LESSONS.md:{n}"))
    return sorted(set(out))


def count_lines(path: Path, pattern: str) -> int:
    if not path.is_file():
        return 0
    rx = re.compile(pattern)
    return sum(1 for line in read_text(path).splitlines() if rx.search(line))


def flaky_count(root: Path) -> int:
    path = root / "docs" / "FLAKY_TESTS.md"
    if not path.is_file():
        return 0
    text = read_text(path)
    rows = [r for r in table_rows(text) if r and not r[0].lower() in ("test", "name", "test name", "id")]
    bullets = [line for line in text.splitlines() if re.match(r"^\s*[-*]\s+\S", line)]
    return len(rows) if rows else len(bullets)


# ----------------------------------------------------------------- coverage trends
def jacoco_pct(xml_path: Path) -> dict[str, float | None]:
    out: dict[str, float | None] = {"line": None, "branch": None}
    try:
        tree = ET.parse(xml_path)
    except (ET.ParseError, OSError):
        return out
    for counter in tree.getroot().findall("counter"):
        kind = counter.get("type", "")
        missed, covered = int(counter.get("missed", 0)), int(counter.get("covered", 0))
        if missed + covered and kind in ("LINE", "BRANCH"):
            out[kind.lower()] = round(100.0 * covered / (missed + covered), 1)
    return out


def pit_score(xml_path: Path) -> float | None:
    try:
        tree = ET.parse(xml_path)
    except (ET.ParseError, OSError):
        return None
    muts = list(tree.iter("mutation"))
    if not muts:
        return None
    killed = sum(1 for m in muts if str(m.get("detected", "")).lower() == "true")
    return round(100.0 * killed / len(muts), 1)


def vitest_pct(json_path: Path) -> dict[str, float | None]:
    try:
        data = json.loads(read_text(json_path))
        total = data.get("total", {})
        return {"line": total.get("lines", {}).get("pct"), "branch": total.get("branches", {}).get("pct")}
    except (json.JSONDecodeError, AttributeError):
        return {"line": None, "branch": None}


def eval_summary(root: Path) -> tuple[str, list[str]]:
    reports = sorted((root / "eval" / "reports").glob("*.md"), key=lambda p: p.name)
    reports = [p for p in reports if p.name != "README.md"]
    if not reports:
        return "", []
    latest = reports[-1]
    lines = read_text(latest).splitlines()
    table: list[str] = []
    for line in lines:
        if line.strip().startswith("|"):
            table.append(line.rstrip())
        elif table:
            break
    return latest.name, table[:40]


def trends(root: Path) -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    jac = root / "backend" / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    if jac.is_file():
        p = jacoco_pct(jac)
        rows.append(("backend line coverage (JaCoCo)", fmt_pct(p["line"]), "backend/build/reports/jacoco/test/jacocoTestReport.xml"))
        rows.append(("backend branch coverage (JaCoCo)", fmt_pct(p["branch"]), "backend/build/reports/jacoco/test/jacocoTestReport.xml"))
    else:
        rows.append(("backend line/branch coverage (JaCoCo)", "–", "no report"))
    pit = root / "backend" / "build" / "reports" / "pitest" / "mutations.xml"
    rows.append(("backend mutation score (PIT)", fmt_pct(pit_score(pit)) if pit.is_file() else "–", "backend/build/reports/pitest/mutations.xml" if pit.is_file() else "no report"))
    kover = sorted(p for p in root.glob("android/**/build/reports/kover/*.xml") if p.is_file())
    if kover:
        p = jacoco_pct(kover[-1])
        rows.append(("android line coverage (Kover)", fmt_pct(p["line"]), kover[-1].relative_to(root).as_posix()))
    else:
        rows.append(("android line coverage (Kover)", "–", "no report"))
    web = root / "web" / "coverage" / "coverage-summary.json"
    if web.is_file():
        p = vitest_pct(web)
        rows.append(("web line coverage (Vitest)", fmt_pct(p["line"]), "web/coverage/coverage-summary.json"))
        rows.append(("web branch coverage (Vitest)", fmt_pct(p["branch"]), "web/coverage/coverage-summary.json"))
    else:
        rows.append(("web line/branch coverage (Vitest)", "–", "no report"))
    return rows


def fmt_pct(v: float | None) -> str:
    return "–" if v is None else f"{v:.1f}%"


def fmt_days(v: float | None) -> str:
    return "–" if v is None else f"{v:.1f} d"


# ----------------------------------------------------------------- preserved sections
def extract_section(old_text: str, heading: str) -> str:
    """A `## heading` section verbatim (heading included, to the next `## ` or EOF); '' if absent."""
    lines = (old_text or "").splitlines()
    start = next((i for i, l in enumerate(lines) if l.strip() == heading), None)
    if start is None:
        return ""
    end = next((j for j in range(start + 1, len(lines)) if lines[j].startswith("## ")), len(lines))
    return "\n".join(lines[start:end]).rstrip("\n") + "\n"


# ----------------------------------------------------------------- ledger
def extract_ledger(old_text: str) -> str:
    """The `## AI spend ledger` section verbatim (heading included, to the next `## ` or EOF)."""
    if not old_text:
        return DEFAULT_LEDGER
    lines = old_text.splitlines()
    start = next((i for i, l in enumerate(lines) if l.strip() == LEDGER_HEADING), None)
    if start is None:
        return DEFAULT_LEDGER
    end = len(lines)
    for j in range(start + 1, len(lines)):
        if lines[j].startswith("## "):
            end = j
            break
    body = "\n".join(lines[start:end]).rstrip("\n") + "\n"
    return body


def ledger_rows(ledger: str) -> tuple[list[dict[str, str]], float]:
    rows = table_rows(ledger)
    if not rows:
        return [], 0.0
    header = [c.lower() for c in rows[0]]
    try:
        usd_idx = header.index("usd")
    except ValueError:
        usd_idx = 3
    key_idx = next((i for i, c in enumerate(header) if "story" in c or "phase" in c), 1)
    out: list[dict[str, str]] = []
    total = 0.0
    for cells in rows[1:]:
        if len(cells) <= usd_idx:
            continue
        raw = cells[usd_idx].replace("$", "").replace(",", "").strip()
        try:
            usd = float(raw)
        except ValueError:
            continue
        total += usd
        out.append({"key": cells[key_idx] if len(cells) > key_idx else "", "usd": f"{usd:.2f}"})
    return out, total


# ----------------------------------------------------------------- render
def render(root: Path, old_text: str) -> str:
    stories = load_stories(root)
    findings = load_findings(root)
    escaped = escaped_defects(root, stories)
    ledger = extract_ledger(old_text)
    spend_rows, spend_total = ledger_rows(ledger)
    gate_failures = count_lines(root / "docs" / "PROGRESS.md", RED_RE) + count_lines(root / ".claude" / "state" / "hooks.log", r"\bBLOCK")
    flaky = flaky_count(root)

    done = [s for s in stories if s.status == "DONE"]
    cycle = [s.cycle_days for s in done if s.cycle_days is not None]
    loops = [s.loops for s in stories if s.loops is not None]
    sev_counts = {sev: sum(1 for f in findings if f.severity == sev) for sev in SEVERITIES}

    o: list[str] = []
    o.append("# SDLC metrics")
    o.append("")
    o.append("Regenerated by `make metrics` (`scripts/quality/sdlc-metrics.py`) from the backlog, verify/review reports, "
             "`.claude/state/` logs and `eval/reports/`. Hand edits are overwritten except the **AI spend ledger**, "
             "which the orchestrator appends to after each story.")
    o.append("")
    o.append("Escaped-defect convention: one `STORY-NNN*.bug.md` file beside the story per defect, or one "
             "`docs/LESSONS.md` entry line mentioning the story id.")
    o.append("")
    o.append("## Summary")
    o.append("| metric | value |")
    o.append("|---|---|")
    o.append(f"| stories done | {len(done)} |")
    o.append(f"| stories blocked | {sum(1 for s in stories if s.status == 'BLOCKED')} |")
    o.append(f"| median cycle time | {fmt_days(statistics.median(cycle)) if cycle else '–'} |")
    o.append(f"| mean loops per story | {f'{statistics.mean(loops):.2f}' if loops else '–'} |")
    o.append(f"| review findings (BLOCKER/MAJOR/MINOR) | {sev_counts['BLOCKER']}/{sev_counts['MAJOR']}/{sev_counts['MINOR']} |")
    o.append(f"| escaped defects | {len(escaped)} |")
    o.append(f"| gate failures | {gate_failures} |")
    o.append(f"| flaky tests quarantined | {flaky} |")
    o.append(f"| AI spend (USD) | {spend_total:.2f} |")
    o.append("")

    o.append("## Stories by status")
    o.append("| status | count |")
    o.append("|---|---|")
    for st in STATUSES:
        o.append(f"| {st} | {sum(1 for s in stories if s.status == st)} |")
    o.append(f"| total | {len(stories)} |")
    o.append("")

    o.append("## Stories by phase")
    o.append("| phase | " + " | ".join(STATUSES) + " | total |")
    o.append("|---|" + "---|" * (len(STATUSES) + 1))
    phases = sorted({s.phase for s in stories}, key=lambda p: (not p.isdigit(), int(p) if p.isdigit() else 0, p))
    for ph in phases:
        ps = [s for s in stories if s.phase == ph]
        o.append(f"| {ph} | " + " | ".join(str(sum(1 for s in ps if s.status == st)) for st in STATUSES) + f" | {len(ps)} |")
    if not phases:
        o.append("| – | " + " | ".join("0" for _ in STATUSES) + " | 0 |")
    o.append("")

    o.append("## Cycle time per stage")
    o.append("| stage | samples | median | mean |")
    o.append("|---|---|---|---|")
    durations = stage_durations(stories)
    for stage in sorted(durations, key=lambda s: (STAGE_ORDER.index(s) if s in STAGE_ORDER else len(STAGE_ORDER), s)):
        vals = durations[stage]
        o.append(f"| {stage} | {len(vals)} | {fmt_days(statistics.median(vals))} | {fmt_days(statistics.mean(vals))} |")
    if not durations:
        o.append("| – | 0 | – | – |")
    o.append("")

    o.append("## Loops per story")
    o.append("| story | status | verify FAIL loops | cycle time |")
    o.append("|---|---|---|---|")
    for s in sorted(stories, key=lambda s: s.id):
        if s.loops is None and s.cycle_days is None:
            continue
        o.append(f"| {s.id} | {s.status} | {s.loops if s.loops is not None else '–'} | {fmt_days(s.cycle_days)} |")
    if not any(s.loops is not None or s.cycle_days is not None for s in stories):
        o.append("| – | – | – | – |")
    o.append("")

    o.append("## Review findings by severity")
    o.append("| severity | count |")
    o.append("|---|---|")
    for sev in SEVERITIES:
        o.append(f"| {sev} | {sev_counts[sev]} |")
    o.append("")
    o.append("## Review findings by reviewer agent")
    o.append("| agent | " + " | ".join(SEVERITIES) + " | total |")
    o.append("|---|" + "---|" * (len(SEVERITIES) + 1))
    agents = sorted({f.agent for f in findings})
    for ag in agents:
        fs = [f for f in findings if f.agent == ag]
        o.append(f"| {ag} | " + " | ".join(str(sum(1 for f in fs if f.severity == sev)) for sev in SEVERITIES) + f" | {len(fs)} |")
    if not agents:
        o.append("| – | " + " | ".join("0" for _ in SEVERITIES) + " | 0 |")
    o.append("")

    o.append("## Escaped defects")
    o.append("| story | source |")
    o.append("|---|---|")
    for sid, src in escaped:
        o.append(f"| {sid} | `{src}` |")
    if not escaped:
        o.append("| – | – |")
    o.append("")

    o.append("## Gate failures and flaky tests")
    o.append("| source | count |")
    o.append("|---|---|")
    o.append(f"| `RED` lines in docs/PROGRESS.md | {count_lines(root / 'docs' / 'PROGRESS.md', RED_RE)} |")
    o.append(f"| `BLOCK` lines in .claude/state/hooks.log | {count_lines(root / '.claude' / 'state' / 'hooks.log', r'\bBLOCK')} |")
    o.append(f"| quarantined flaky tests (docs/FLAKY_TESTS.md) | {flaky} |")
    o.append("")

    o.append("## Coverage / mutation / eval snapshot")
    o.append("| metric | value | source |")
    o.append("|---|---|---|")
    for name, val, src in trends(root):
        o.append(f"| {name} | {val} | `{src}` |")
    report_name, table = eval_summary(root)
    o.append(f"| latest eval report | {('`eval/reports/' + report_name + '`') if report_name else '–'} | eval/reports/*.md |")
    o.append("")
    if table:
        o.append(f"### Latest eval summary (`eval/reports/{report_name}`)")
        o.extend(table)
        o.append("")

    o.append("## AI spend by story/phase")
    o.append("| story/phase | USD |")
    o.append("|---|---|")
    by_key: dict[str, float] = {}
    for r in spend_rows:
        by_key[r["key"]] = by_key.get(r["key"], 0.0) + float(r["usd"])
    for key in sorted(by_key):
        o.append(f"| {key or '–'} | {by_key[key]:.2f} |")
    if not by_key:
        o.append("| – | 0.00 |")
    o.append("")

    budgets = extract_section(old_text, BUDGETS_HEADING)
    if budgets:
        o.append(budgets.rstrip("\n"))
        o.append("")
    o.append(ledger.rstrip("\n"))
    return "\n".join(o) + "\n"


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
    ap.add_argument("--out", default="docs/SDLC_METRICS.md", help="output file (relative to --root unless absolute)")
    ap.add_argument("--root", default=None, help="repository root (default: two levels above this script)")
    try:
        args = ap.parse_args(list(argv) if argv is not None else None)
    except SystemExit as e:
        return int(e.code or 0)
    root = Path(args.root).resolve() if args.root else default_root()
    if not root.is_dir():
        print(f"sdlc-metrics: root not found: {root}", file=sys.stderr)
        return 2
    out = resolve_out(args.out, root)
    old = read_text(out) if out.is_file() else ""
    text = render(root, old)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8", newline="\n")
    print(f"sdlc-metrics: wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
