---
id: STORY-097
epic: EPIC-10
title: "Trends: 6/12-period stacked bars by category, total line, income vs expense vs savings rate"
phase: 7
status: IN_PROGRESS
size: M
modules: [backend]
depends_on: [STORY-025]
reqs: [REQ-AN-005]
implements: [PROMPT.md §10 item 5]
owner_agent: backend-engineer
---

# STORY-097 — Trends: 6/12-period stacked bars by category, total line, income vs expense vs savings rate

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Month-over-month picture (PROMPT §10 item 5).

## Requirements and acceptance criteria
### REQ-AN-005 — GET /api/analytics/trend?periods=6|12 with per-period category stacks, total, income, savings rate when income exists
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/analytics/trend`, `Trend`, `TrendPoint` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025

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
