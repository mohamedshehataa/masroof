---
id: STORY-103
epic: EPIC-11
title: "Recurring & subscriptions: auto-detection, upcoming bills, amount-change and missed alerts"
phase: 7
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-096, STORY-102]
reqs: [REQ-AN-009, REQ-AN-060, REQ-AN-061]
implements: [PROMPT.md §10 item 9, PROMPT.md §9 (/api/recurring), PROMPT.md §8 (recurring_series)]
owner_agent: backend-engineer
---

# STORY-103 — Recurring & subscriptions: auto-detection, upcoming bills, amount-change and missed alerts

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Subscriptions and bills are tracked without manual setup (PROMPT §10 item 9).

## Requirements and acceptance criteria
### REQ-AN-009 — CRUD /api/recurring plus scheduled detection (same merchant, similar amount, regular cadence) creating series
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-060 — Upcoming bills list with next expected date and amount
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-061 — Alerts when a recurring charge changes amount or is missed
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/recurring*`, `RecurringSeries*` in v0.

## Data / migration impact
Uses `recurring_series` (V4).

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-096, STORY-102

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
