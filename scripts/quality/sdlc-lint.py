#!/usr/bin/env python3
"""sdlc-lint: validate the SDLC artefacts so broken agent/skill/hook/story definitions never ship.

Checks (each failure is one line; exit 1 if any):
  agents    .claude/agents/*.md     frontmatter (name == filename, description, tools, model alias),
                                    required body sections in order, Agent in disallowedTools,
                                    reviewers/verifier cannot Write/Edit, output contract matches
                                    .claude/hooks/output-contracts.json (STATUS values, sections)
  skills    .claude/skills/*/SKILL.md  frontmatter present, name == folder, description,
                                    owner-triggered workflow skills have disable-model-invocation: true
  rules     .claude/rules/*.md      valid frontmatter; `paths` is a list/str when present
  settings  .claude/settings.json   valid JSON, known hook events, every hook command script exists
  stories   docs/backlog/**/STORY-*.md  frontmatter fields, id == filename prefix, status enum,
                                    REQ ids well formed with known areas, each listed REQ has a heading,
                                    no duplicate REQ ids across stories
  workflows .github/workflows/*.yml parse
  todos     backend|web|android|scripts|eval|infra source: TODO must be TODO(owner) or carry STORY-NNN
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

try:
    import yaml  # type: ignore
except ImportError:  # pragma: no cover
    print("sdlc-lint: PyYAML missing (pip install pyyaml)")
    sys.exit(2)

ROOT = Path(__file__).resolve().parents[2]
ERRORS: list[str] = []
WARNINGS: list[str] = []

AGENT_SECTIONS = [
    "## Role",
    "## Inputs you will receive",
    "## Procedure",
    "## Quality bar",
    "## Never do",
    "## Output contract",
]
MODEL_ALIASES = {"opus", "sonnet", "haiku", "inherit", "fable"}
NO_WRITE_AGENTS = {
    "qa-verifier", "code-reviewer", "security-reviewer", "spec-reviewer",
    "ux-a11y-reviewer", "i18n-rtl-reviewer", "performance-engineer", "sre-observer",
}
WORKFLOW_SKILLS = {
    "sdlc-status", "next", "story", "spec", "design", "tdd", "implement", "verify", "review",
    "security-review", "eval", "bug", "adr", "phase-gate", "release", "retro", "sdlc-selftest",
}
HOOK_EVENTS = {
    "SessionStart", "Setup", "UserPromptSubmit", "UserPromptExpansion", "PreToolUse", "PermissionRequest",
    "PermissionDenied", "PostToolUse", "PostToolUseFailure", "PostToolBatch", "Notification", "MessageDisplay",
    "SubagentStart", "SubagentStop", "TaskCreated", "TaskCompleted", "Stop", "StopFailure", "TeammateIdle",
    "InstructionsLoaded", "ConfigChange", "CwdChanged", "DirectoryAdded", "FileChanged", "WorktreeCreate",
    "WorktreeRemove", "PreCompact", "PostCompact", "PreModelSwitch", "PostModelSwitch", "Elicitation",
    "ElicitationResult", "SessionEnd",
}
STORY_STATUSES = {"DRAFT", "READY", "IN_PROGRESS", "BLOCKED", "DONE", "DESCOPED"}
REQ_AREAS = {"WA", "SMS", "NTF", "IOS", "RCPT", "PARSE", "DEDUP", "EXP", "AN", "UI", "AND", "PWA", "SEC", "OPS"}
REQ_RE = re.compile(r"REQ-([A-Z]+)-(\d{3})")
FM_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n(.*)\Z", re.S)


def err(msg: str) -> None:
    ERRORS.append(msg)


def warn(msg: str) -> None:
    WARNINGS.append(msg)


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def frontmatter(p: Path) -> tuple[dict, str] | None:
    m = FM_RE.match(read(p))
    if not m:
        return None
    try:
        data = yaml.safe_load(m.group(1)) or {}
    except yaml.YAMLError as e:  # pragma: no cover
        err(f"{p}: invalid YAML frontmatter: {e}")
        return None
    if not isinstance(data, dict):
        err(f"{p}: frontmatter is not a mapping")
        return None
    return data, m.group(2)


def as_list(v) -> list[str]:
    if v is None:
        return []
    if isinstance(v, str):
        return [x.strip() for x in v.split(",") if x.strip()]
    return [str(x).strip() for x in v]


def lint_agents() -> None:
    contracts_path = ROOT / ".claude/hooks/output-contracts.json"
    contracts = json.loads(read(contracts_path))["agents"] if contracts_path.exists() else {}
    agents_dir = ROOT / ".claude/agents"
    if not agents_dir.exists():
        return
    seen = set()
    for p in sorted(agents_dir.glob("*.md")):
        fm = frontmatter(p)
        if fm is None:
            err(f"{p}: missing frontmatter")
            continue
        data, body = fm
        name = str(data.get("name", ""))
        seen.add(name)
        if name != p.stem:
            err(f"{p}: name '{name}' != filename '{p.stem}'")
        if not data.get("description"):
            err(f"{p}: missing description")
        model = str(data.get("model", "inherit"))
        if model not in MODEL_ALIASES and not model.startswith("claude-"):
            err(f"{p}: model '{model}' is not an alias (opus|sonnet|haiku|inherit) or a model id")
        tools = as_list(data.get("tools"))
        dis = as_list(data.get("disallowedTools"))
        if "Agent" in tools or "Task" in tools:
            err(f"{p}: subagents must not spawn subagents (Agent/Task in tools)")
        if "Agent" not in dis:
            err(f"{p}: disallowedTools must include Agent (nesting is on by default)")
        if name in NO_WRITE_AGENTS:
            for t in ("Write", "Edit"):
                if t in tools or t not in dis:
                    err(f"{p}: {name} must have {t} in disallowedTools and not in tools")
        # body sections in order
        pos = -1
        for sec in AGENT_SECTIONS:
            idx = body.find(sec)
            if idx < 0:
                err(f"{p}: missing section '{sec}'")
            elif idx < pos:
                err(f"{p}: section '{sec}' out of order")
            else:
                pos = idx
        # output contract vs contracts file
        c = contracts.get(name)
        if c is None:
            err(f"{p}: agent '{name}' has no entry in output-contracts.json")
        else:
            oc = body[body.find("## Output contract"):] if "## Output contract" in body else ""
            for s in c["required_sections"]:
                if not re.search(rf"^\s*(\*\*)?{re.escape(s)}(\*\*)?\s*:", oc, re.M):
                    err(f"{p}: output contract lacks required section '{s}:'")
            m = re.search(r"^\s*(\*\*)?STATUS(\*\*)?\s*:\s*(.+)$", oc, re.M)
            if not m:
                err(f"{p}: output contract lacks a STATUS: line")
            else:
                vals = {v.strip().strip("`*") for v in re.split(r"\|", m.group(3))}
                expected = set(c["status"])
                if vals != expected:
                    err(f"{p}: STATUS values {sorted(vals)} != contract {sorted(expected)}")
    for name in contracts:
        if name not in seen:
            err(f".claude/agents/{name}.md: agent in output-contracts.json but no definition file")


def lint_skills() -> None:
    skills_dir = ROOT / ".claude/skills"
    if not skills_dir.exists():
        return
    found = set()
    for sk in sorted(skills_dir.glob("*/SKILL.md")):
        fm = frontmatter(sk)
        if fm is None:
            err(f"{sk}: missing frontmatter")
            continue
        data, body = fm
        name = str(data.get("name", sk.parent.name))
        found.add(name)
        if name != sk.parent.name:
            err(f"{sk}: name '{name}' != folder '{sk.parent.name}'")
        if not data.get("description"):
            err(f"{sk}: missing description")
        if name in WORKFLOW_SKILLS and not data.get("disable-model-invocation", False):
            err(f"{sk}: workflow skill must set disable-model-invocation: true")
        if len(body.splitlines()) > 500:
            warn(f"{sk}: body over 500 lines; move reference material to supporting files")
        for link in re.findall(r"\]\(([^)#:]+\.md)\)", body):
            if not (sk.parent / link).exists() and not (ROOT / link).exists():
                err(f"{sk}: broken link to '{link}'")
    for w in sorted(WORKFLOW_SKILLS - found):
        warn(f".claude/skills/{w}/SKILL.md: expected workflow skill not present yet")


def lint_rules() -> None:
    for r in sorted((ROOT / ".claude/rules").glob("*.md")) if (ROOT / ".claude/rules").exists() else []:
        fm = frontmatter(r)
        if fm is None:
            continue  # rules without frontmatter are always-on
        data, _ = fm
        paths = data.get("paths")
        if paths is not None and not isinstance(paths, (list, str)):
            err(f"{r}: 'paths' must be a list or comma-separated string")


def lint_settings() -> None:
    p = ROOT / ".claude/settings.json"
    if not p.exists():
        warn(".claude/settings.json: not present yet")
        return
    try:
        data = json.loads(read(p))
    except json.JSONDecodeError as e:
        err(f"{p}: invalid JSON: {e}")
        return
    hooks = data.get("hooks", {})
    for event, groups in hooks.items():
        if event not in HOOK_EVENTS:
            err(f"{p}: unknown hook event '{event}'")
        for g in groups:
            for h in g.get("hooks", []):
                cmd = h.get("command", "")
                for m in re.finditer(r"\.claude/hooks/([A-Za-z0-9_.-]+\.sh)", cmd):
                    script = ROOT / ".claude/hooks" / m.group(1)
                    if not script.exists():
                        err(f"{p}: hook {event} references missing script {script.name}")
    for rule in data.get("permissions", {}).get("deny", []):
        if rule.startswith("Edit(.env.*") or rule.startswith("Read(.env.*"):
            err(f"{p}: deny rule '{rule}' would also block .env.example")


def lint_stories() -> None:
    backlog = ROOT / "docs/backlog"
    if not backlog.exists():
        return
    req_owner: dict[str, str] = {}
    for s in sorted(backlog.rglob("STORY-*.md")):
        if s.name.endswith((".verify.md", ".review.md", ".bug.md")):
            continue
        fm = frontmatter(s)
        if fm is None:
            err(f"{s}: missing frontmatter")
            continue
        data, body = fm
        sid = str(data.get("id", ""))
        if not re.fullmatch(r"STORY-\d{3}", sid):
            err(f"{s}: id '{sid}' must be STORY-NNN")
        if not s.name.startswith(sid):
            err(f"{s}: filename must start with its id '{sid}'")
        for f in ("epic", "title", "phase", "status", "modules", "reqs"):
            if f not in data:
                err(f"{s}: frontmatter missing '{f}'")
        if str(data.get("status")) not in STORY_STATUSES:
            err(f"{s}: status '{data.get('status')}' not in {sorted(STORY_STATUSES)}")
        epic = str(data.get("epic", ""))
        if not re.fullmatch(r"EPIC-\d{2}", epic):
            err(f"{s}: epic '{epic}' must be EPIC-NN")
        if not s.parent.name.startswith(epic):
            err(f"{s}: folder '{s.parent.name}' must start with epic '{epic}'")
        for req in as_list(data.get("reqs")):
            m = REQ_RE.fullmatch(req)
            if not m:
                err(f"{s}: malformed REQ id '{req}'")
                continue
            if m.group(1) not in REQ_AREAS:
                err(f"{s}: REQ area '{m.group(1)}' not in {sorted(REQ_AREAS)}")
            if req in req_owner and req_owner[req] != sid:
                err(f"{s}: REQ '{req}' already defined in {req_owner[req]}")
            req_owner[req] = sid
            if not re.search(rf"^###\s+{re.escape(req)}\b", body, re.M):
                err(f"{s}: no '### {req}' heading for listed requirement")
        for heading in re.findall(r"^###\s+(REQ-[A-Z]+-\d{3})", body, re.M):
            if heading not in as_list(data.get("reqs")):
                err(f"{s}: heading '{heading}' not listed in frontmatter reqs")
        if str(data.get("status")) in {"READY", "IN_PROGRESS", "DONE"}:
            if not re.search(r"\*\*Given\*\*|Given ", body):
                err(f"{s}: {data.get('status')} story has no Given/When/Then criteria")


def lint_workflows() -> None:
    for w in sorted((ROOT / ".github/workflows").glob("*.y*ml")) if (ROOT / ".github/workflows").exists() else []:
        try:
            yaml.safe_load(read(w))
        except yaml.YAMLError as e:
            err(f"{w}: invalid YAML: {e}")


def lint_todos() -> None:
    exts = {".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".py", ".sh", ".yml", ".yaml", ".sql"}
    skip_parts = {"node_modules", "build", "dist", ".gradle", "generated", "__pycache__"}
    for d in ("backend", "web", "android", "scripts", "eval", "infra"):
        base = ROOT / d
        if not base.exists():
            continue
        for f in base.rglob("*"):
            if not f.is_file() or f.suffix not in exts or skip_parts & set(f.parts):
                continue
            try:
                text = read(f)
            except UnicodeDecodeError:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                if re.search(r"(#|//|/\*|^\s*\*|--|<!--)\s*(TODO|FIXME|XXX)\b", line) and not re.search(r"TODO\(owner\)|STORY-\d{3}", line):
                    err(f"{f}:{i}: TODO without TODO(owner) or a STORY-NNN link")


def main() -> int:
    lint_agents()
    lint_skills()
    lint_rules()
    lint_settings()
    lint_stories()
    lint_workflows()
    lint_todos()
    for w in WARNINGS:
        print(f"warn: {w}")
    for e in ERRORS:
        print(f"ERROR: {e}")
    print(f"sdlc-lint: {len(ERRORS)} error(s), {len(WARNINGS)} warning(s)")
    return 1 if ERRORS else 0


if __name__ == "__main__":
    sys.exit(main())
