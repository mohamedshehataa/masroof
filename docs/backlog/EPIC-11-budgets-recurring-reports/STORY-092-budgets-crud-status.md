---
id: STORY-092
epic: EPIC-11
title: "Budgets CRUD and budget status (progress per category or total, period, rollover flag)"
phase: 7
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-025, STORY-013]
reqs: [REQ-AN-008]
implements: [PROMPT.md §10 item 8, PROMPT.md §9 (/api/budgets), PROMPT.md §8 (budget)]
owner_agent: backend-engineer
---

# STORY-092 — Budgets CRUD and budget status (progress per category or total, period, rollover flag)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The household sets limits and sees progress (PROMPT §10 item 8).

## Requirements and acceptance criteria
### REQ-AN-008 — CRUD /api/budgets (category or total, MONTHLY|WEEKLY|CUSTOM, amount, rollover, alert thresholds) and GET /api/analytics/budgets progress
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/budgets*`, `Budget*`, `/api/analytics/budgets`, `BudgetStatus*` in v0.

## Data / migration impact
Uses `budget` (V4).

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025, STORY-013

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
