"""Shared helpers for the scripts/quality unit tests (loaded by unittest discovery from this directory)."""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

QUALITY_DIR = Path(__file__).resolve().parents[1]


def load_script(name: str) -> ModuleType:
    """Import scripts/quality/<name>.py (names may contain hyphens) as a module."""
    modname = "quality_" + name.replace("-", "_")
    if modname in sys.modules:
        return sys.modules[modname]
    spec = importlib.util.spec_from_file_location(modname, QUALITY_DIR / f"{name}.py")
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    sys.modules[modname] = mod
    spec.loader.exec_module(mod)
    return mod


def req(area: str, n: int) -> str:
    """Build a requirement id without a literal token in test source (traceability scans this directory)."""
    return f"REQ-{area}-{n:03d}"


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    return path


def story_md(sid: str, epic: str, status: str, reqs: list[str], phase: int = 1, log_rows: list[str] | None = None) -> str:
    fm = (
        f"---\nid: {sid}\nepic: {epic}\ntitle: t\nphase: {phase}\nstatus: {status}\nsize: S\nmodules: [backend]\n"
        f"depends_on: []\nreqs: [{', '.join(reqs)}]\n---\n\n# {sid} — t\n\n## Requirements and acceptance criteria\n"
    )
    body = "".join(f"### {r} — requirement {r[-3:]}\n**Given** a **When** b **Then** c\n\n" for r in reqs)
    log = "## Status log\n| date | stage | agent | result |\n|---|---|---|---|\n" + "".join(f"{row}\n" for row in (log_rows or []))
    return fm + body + log


def junit_xml(cases: list[tuple[str, str, str]]) -> str:
    """cases: (classname, name, status PASS|FAIL|SKIP)."""
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<testsuite name="s">']
    for cls, name, status in cases:
        if status == "PASS":
            out.append(f'  <testcase classname="{cls}" name="{name}"/>')
        elif status == "SKIP":
            out.append(f'  <testcase classname="{cls}" name="{name}"><skipped/></testcase>')
        else:
            out.append(f'  <testcase classname="{cls}" name="{name}"><failure message="boom">x</failure></testcase>')
    out.append("</testsuite>")
    return "\n".join(out) + "\n"
