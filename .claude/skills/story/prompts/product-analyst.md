# Prompt template — `product-analyst` (stage 1-2)

Agent tool: `subagent_type: product-analyst`. Fill every `<...>`; delete the "Fix loop" block on the first run.

```
You are specifying one story for Masroof. Work only in docs/.

Story file: <docs/backlog/EPIC-NN-slug/STORY-NNN-slug.md>
Epic: <docs/backlog/EPIC-NN-slug/> (read the sibling stories so IDs, REQ numbering and dependencies stay consistent)
Template: docs/templates/story.md (keep every section; keep the YAML frontmatter keys exactly)
Source of truth: docs/PROMPT.md sections <e.g. "§4 Android SMS", "§6 Parsing pipeline", "S9 gates">
Requirement ID area: REQ-<AREA>-NNN with AREA from S11 (WA, SMS, NTF, IOS, RCPT, PARSE, DEDUP, EXP, AN, UI, AND, PWA, SEC, OPS).
Next free REQ number in this area: run `grep -rhoE 'REQ-<AREA>-[0-9]{3}' docs/backlog | sort -u | tail -1`.

Write the specification so that it meets the Definition of Ready (PROMPT.md S2):
- Goal in one sentence with the Part I links.
- Numbered REQs, each with Given/When/Then AND at least one unhappy path (invalid input, offline, duplicate
  delivery, Arabic-Indic digits, RTL, Cairo midnight, month-start-day boundary, zero/negative/huge amounts,
  foreign currency, empty state) where it applies.
- UX states for UI stories: empty, loading, error, offline, RTL, dark.
- Contract impact (paths/schemas in contracts/openapi.yaml), data/migration impact, security/privacy/LLM-safety
  impact (or "None" with a reason), eval impact (suites and cases) for anything that touches parsing.
- Size S (<150 changed lines) or M (<400). If it would be larger, split: create the extra STORY-NNN files
  (next free global id: `ls docs/backlog/*/STORY-*.md | sed -E 's/.*STORY-([0-9]+).*/\1/' | sort -n | tail -1`)
  and set depends_on.
- Tick the Definition of Ready checklist items you satisfied; leave unticked ones unticked and explain why.
- Set frontmatter status to READY only if every checklist item is ticked; otherwise leave DRAFT.

<Fix loop only:>
The spec-reviewer requested changes. Address every item below; do not argue with them in the file.
FINDINGS:
<paste the reviewer's FINDINGS lines verbatim>

Do not design the solution, do not write code or tests, do not touch files outside docs/.
End your final message with exactly this contract (STATUS must be the last line):
STORIES:
- <STORY-NNN> <title> (<status>)
REQS:
- <REQ-AREA-NNN>: <one line>
STATUS: SPEC_READY | BLOCKED
```

Parse: `STATUS: SPEC_READY` -> run the spec-reviewer. `BLOCKED` -> read why; if owner input is needed,
escalate (OWNER-NNN), else fix the inputs and relaunch once.
