---
id: STORY-000
epic: EPIC-00
title: <one-line user-facing goal>
phase: 1
status: DRAFT            # DRAFT | READY | IN_PROGRESS | BLOCKED | DONE | DESCOPED
size: S                  # S (<150 changed lines) | M (<400) | split if larger
modules: [backend]       # backend | web | android | infra | sdlc | contracts | eval
depends_on: []
reqs: [REQ-XXX-001]
implements: ["PROMPT.md §9"]
owner_agent: backend-engineer
---

# STORY-000 — <title>

## Goal
One sentence. Links to the Part I section(s) this implements.

## Requirements and acceptance criteria
### REQ-XXX-001 — <short name>
**Given** … **When** … **Then** …
Unhappy path: **Given** … **When** … **Then** …

## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
None | paths/schemas touched in `contracts/openapi.yaml` (breaking? ADR link)

## Data / migration impact
None | new migration `V<N>__<name>.sql` (expand → migrate → contract plan)

## Security / privacy / LLM-safety impact
None (reason) | describe + trust boundary

## Eval impact
None | suites and cases affected

## Design notes (architect)
Filled at stage 3. Module boundaries, key types, sequence, ADR links.

## Dependencies
STORY-xxx, …

## Definition of Ready checklist
- [ ] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths
- [ ] UX states (UI stories)
- [ ] Contract impact
- [ ] Data / migration impact
- [ ] Security / privacy / LLM-safety impact
- [ ] Eval impact
- [ ] Size ≤ 400 changed lines
- [ ] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
