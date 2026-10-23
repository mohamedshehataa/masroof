---
id: STORY-108
epic: EPIC-12
title: "Web Budgets screen and Home extras (budget ring/bars, needs-review banner, upcoming bills, AI insight card), drill-down"
phase: 7
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-026, STORY-092, STORY-103, STORY-104, STORY-058]
reqs: [REQ-UI-053, REQ-UI-054, REQ-UI-055]
implements: [PROMPT.md §11 Navigation 1 (Home) and 5 (Budgets), PROMPT.md §11 Navigation 4 (drill down)]
owner_agent: web-engineer
---

# STORY-108 — Web Budgets screen and Home extras (budget ring/bars, needs-review banner, upcoming bills, AI insight card), drill-down

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Home becomes the full §11 Home and budgets are manageable (PROMPT §11).

## Requirements and acceptance criteria
### REQ-UI-053 — More → Budgets: list/progress bars, create/edit budgets with thresholds and rollover
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-UI-054 — Home: budget ring, top-4 budget bars, 'Needs review (n)' banner, upcoming recurring bills, one AI insight card
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-UI-055 — Every chart/segment drills down to the filtered Transactions list (URL filters)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/budgets*`, `/api/analytics/*`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-026, STORY-092, STORY-103, STORY-104, STORY-058

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 7 start |
