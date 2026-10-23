---
id: STORY-095
epic: EPIC-10
title: "By member and settlement: who paid what, running balance, settle up"
phase: 7
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-025, STORY-023]
reqs: [REQ-AN-003, REQ-AN-031]
implements: [PROMPT.md §10 item 3]
owner_agent: backend-engineer
---

# STORY-095 — By member and settlement: who paid what, running balance, settle up

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Both members know who owes whom (PROMPT §10 item 3).

## Requirements and acceptance criteria
### REQ-AN-003 — GET /api/analytics/by-member and /settlement: per-member spend and 'Mohamed owes X / is owed X' from shared/split expenses
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-031 — POST settle-up records a settlement entry (TRANSFER) and resets the balance
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/analytics/by-member`, `/settlement`, `MemberBreakdown`, `Settlement` in v0; add `POST /api/analytics/settlement/settle` (non-breaking).

## Data / migration impact
`settlement` table or TRANSFER expense.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025, STORY-023

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
