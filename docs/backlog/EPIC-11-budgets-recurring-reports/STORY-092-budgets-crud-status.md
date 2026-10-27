---
id: STORY-092
epic: EPIC-11
title: "Budgets CRUD and budget status (progress per category or total, period, rollover flag)"
phase: 7
status: DESCOPED
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
### REQ-AN-008 — CRUD /api/budgets (category or total, MONTHLY|WEEKLY, amount, rollover, alert thresholds) and GET /api/analytics/budgets progress
Implemented. CUSTOM periods, push/WhatsApp alerts (STORY-102) and the web page (STORY-108) are out of scope here.

- Given a signed-in household, When it POSTs a budget with a positive amount in the household currency (category optional), Then 201 with the budget; a second budget for the same category and period returns 409.
- Given a budget id of another household, When it is read, updated or deleted, Then 404; a category of another household on create returns 400.
- Given amount <= 0 or above 1,000,000,000,000 minor units, a foreign currency, thresholds that are duplicated or outside 1..200, or an unknown period, When saving, Then 400 with field errors (CUSTOM returns 422).
- Given spending in the period, When GET /api/analytics/budgets, Then spent, remaining, progressBps (half away from zero, capped at 100x), alertsFired (50/80/100 or the budget's own), onTrack and daysLeft are computed from the same eligible rows as the analytics (Cairo periods with month_start_day, refunds netted, TRANSFER and CASH_WITHDRAWAL excluded).
- Given a monthly household-total budget, When GET /api/analytics/summary for a month, Then remainingBudget is the total budget's remaining amount; otherwise null.

Rollover (the in-app part of the rollover requirement, whose heading lives in STORY-102):
- Given rollover is on and the budget existed in the previous period, When status is read, Then the previous period's leftover (or overspend) is added to the limit, one period deep.
- Given rollover leaves a limit of zero or less, When status is read, Then progress is full (10000 bps) or capped if anything is spent, the 100% alert fires, and onTrack is false.

## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/budgets*`, `Budget*`, `/api/analytics/budgets`, `BudgetStatus*` in v0.

## Data / migration impact
New table `budget` in `V9__budget.sql` (V4 holds no budget table; V8 is `raw_message`). Expand-only.

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
| 2026-10-26 | 5 implement | backend-engineer | IN_PROGRESS — backend CRUD + status (PR #59), Android screens; web page pending |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
