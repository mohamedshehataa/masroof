---
id: STORY-093
epic: EPIC-10
title: "Overview: daily average, projected month-end (linear + weekday-aware), remaining budget, days left"
phase: 7
status: IN_PROGRESS
size: M
modules: [backend]
depends_on: [STORY-025, STORY-092]
reqs: [REQ-AN-030]
implements: [PROMPT.md §10 item 1]
owner_agent: backend-engineer
---

# STORY-093 — Overview: daily average, projected month-end (linear + weekday-aware), remaining budget, days left

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The Home number tells where the month is heading (PROMPT §10 item 1).

## Requirements and acceptance criteria
### REQ-AN-030 — AnalyticsSummary gains dailyAverage, projectedTotal (linear and weekday-aware), remainingBudget, daysLeft; GET /api/analytics/forecast
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`AnalyticsSummary` nullable fields filled; `GET /api/analytics/forecast`, `Forecast` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025, STORY-092

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
