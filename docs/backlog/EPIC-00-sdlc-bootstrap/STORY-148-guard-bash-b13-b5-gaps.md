---
id: STORY-148
epic: EPIC-00
title: "guard-bash blocks mixed-case interpreter writes to PROMPT files and backtick delete targets"
phase: 1
status: READY
size: S
modules: [sdlc]
depends_on: [STORY-001]
reqs: [REQ-OPS-170, REQ-OPS-171]
implements: ["PROMPT.md S7 (guard-bash)", "STORY-001 REQ-OPS-001 B5 and B13", "STORY-001 accepted residual risk F-03, F-04"]
risk_tier: low
owner_agent: devops-engineer
---

# STORY-148 — guard-bash closes the B13 case gap and the B5 backtick gap

## Goal
Close two in-class REQ-OPS-001 bypasses that STORY-001 was accepted with (PROMPT S7). They were split out of STORY-146 on 2026-10-25 to keep both stories under the size cap.
- **F-03** in STORY-001's residual-risk list (**F-01** in its verify loop-3 report): B13 interpreter/.NET writes match protected paths case-sensitively.
- **F-04** in the residual-risk list (**F-02** in the verify report): backtick delete targets are not analysed.

The unverified commit `f54c33a` (local branch `story-001-loop4`) contains a fix for both. The implementer may reuse it after checking it against the cases below.

## Requirements and acceptance criteria
### REQ-OPS-170 — B13 interpreter and .NET writes match protected paths case-insensitively
**Given** locked hooks and a temp repo on `main` **When** `guard-bash.sh` receives one of these commands (the verify loop-3 F-01 reproductions plus case variants) **Then** each exits 2 with one stderr line ending in a `Safe alternative:`:
- `node -e "require('fs').appendFileSync('docs/PROMPT.md','x')"`
- `node -e "require('fs').writeFileSync('docs/PROMPT.md','x')"`
- `[IO.File]::AppendAllText('docs/PROMPT.md','x')` and `[IO.File]::WriteAllText('docs/PROMPT.md','x')` (PowerShell tool)
- `python -c "open('docs/PROMPT.md','w').write('x')"`
- `python -c "open('docs/PROMPT_DELIVERY_GATES.md','a').write('x')"`
- `python -c "open('DOCS/Prompt.md','w')"`
- `node -e "require('fs').writeFileSync('.CLAUDE/Hooks/lib.sh','x')"`
- `python -c "open('EVAL/Baseline.json','w')"`

Every case already blocked (lower-case `docs/prompt.md`, `.claude/HOOKS/lib.sh`, `.Claude/settings.json`) stays blocked.
Unhappy path (no false positive, each exits 0 with no stderr):
- `python -c "print(open('docs/PROMPT.md').read())"` (read only);
- `node -e "require('fs').writeFileSync('docs/notes.md','x')"`;
- `python -c "open('docs/prompting-notes.md','w')"`. The protected names are exactly `PROMPT.md` and `PROMPT_DELIVERY_GATES.md`, with no prefix match.

Unhappy path: **Given** malformed JSON **Then** behaviour is unchanged: exit 0 and exactly one stderr line.

### REQ-OPS-171 — Command-substitution delete targets are blocked as unresolvable
**Given** B5 (recursive or forced deletes) **When** a delete target contains a backtick substitution:
- ``rm -rf `echo x` ``;
- ``rm -rf `pwd` ``;
- ``rm -rf build/`date +%s` ``;
- ``sudo rm -rf `pwd` ``;

**Then** exit 2, and the single stderr line says the target cannot be resolved and gives a `Safe alternative:`. The verify loop-3 F-02 reproduction returned exit 0 for the first two.
The `$()` forms stay blocked as today: `rm -rf $(echo x)`, `rm -rf "$(pwd)/x"`.
Unhappy path (no false positive, each exits 0):
- `rm -rf build/`;
- `rm -rf /tmp/masroof-x`;
- ``echo `date` ``;
- ``ls `pwd` ``;
- ``git commit -m 'docs: never rm -rf `pwd`' `` (single-quoted, so it is data and nothing runs).

Unhappy path: **Given** a backtick that is never closed (``rm -rf `pwd ``) **Then** exit 2. An unparsable delete target fails closed.

## UX states (UI stories only)
N/A

## Contract impact
None

## Data / migration impact
None

## Security / privacy / LLM-safety impact
This story narrows two known bypasses of the pre-execution guard. A false positive only blocks a command, which the agent can rephrase. The cases above bound false positives on everyday commands, and the REQ-OPS-001 allow-list tests must stay green. Risk tier is low (SDLC tooling): one combined verify+review agent.

## Eval impact
None

## Design notes (architect)
Not needed. The fix is confined to the B13 path matcher and the B5 segment splitter in `guard-bash.sh` (edits need `make hooks-unlock` … `make hooks-lock`).

## Dependencies
STORY-001 (DONE with accepted risk). Independent of STORY-146.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines. Estimate ≈ 90: matcher ~10, splitter ~20, about 25 test cases ~60.
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-25 | 1 spec (low tier) | product-analyst | READY — split out of STORY-146 (REQ-OPS-170/171 moved unchanged in number) to keep both under the 400-line cap |
