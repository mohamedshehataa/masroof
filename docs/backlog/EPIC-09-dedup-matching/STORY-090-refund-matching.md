---
id: STORY-090
epic: EPIC-09
title: Refund and reversal matching offsets the original expense
phase: 6
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-085]
reqs: [REQ-DEDUP-050]
implements: [PROMPT.md §4.3 (Refunds offset the original expense if matched), PROMPT.md §7 (refund matching scenario)]
owner_agent: backend-engineer
---

# STORY-090 — Refund and reversal matching offsets the original expense

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A refunded purchase does not count as spending (PROMPT §4.3).

## Requirements and acceptance criteria
### REQ-DEDUP-050 — REFUND/reversal matched to the original expense (amount, merchant, window) and linked; analytics offsets it; unmatched refunds stay as income-like entries
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
`expense.refund_of_expense_id`.

## Security / privacy / LLM-safety impact
None.

## Eval impact
Suite `dedup` refund scenario.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-085

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 6 start |
