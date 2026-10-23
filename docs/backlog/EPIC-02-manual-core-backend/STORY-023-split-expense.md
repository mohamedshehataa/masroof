---
id: STORY-023
epic: EPIC-02
title: Split an expense between members (equal or custom shares)
phase: 2
status: READY
size: S
modules: [backend]
depends_on: [STORY-020]
reqs: [REQ-EXP-005]
implements: ["PROMPT.md §9 (POST /api/expenses/{id}/split)", "PROMPT.md §8 (expense_split, split_mode)", "PROMPT.md S9 (property: split shares always sum to the total)"]
owner_agent: backend-engineer
---

# STORY-023 — Split an expense between members

## Goal
A shared expense (`350 dinner with Ahmed, split`) is divided between the two members so settlement in Phase 7 knows who owes whom (PROMPT §9, §10 item 3).

## Requirements and acceptance criteria
### REQ-EXP-005 — Split expense
**Given** an expense of `35000` piasters **When** `POST /api/expenses/{id}/split` with `SplitRequest {mode: "EQUAL"}` **Then** `200 Expense` with `splitMode: EQUAL` and `splits: [{memberId: A, shareAmount: 17500}, {memberId: B, shareAmount: 17500}]`; for an odd total (`35001`) the extra piaster goes to the payer; **When** `{mode: "CUSTOM", shares: [{memberId: A, shareAmount: 20000}, {memberId: B, shareAmount: 15000}]}` **Then** `200` with those shares; **When** `{mode: "NONE"}` **Then** splits are removed.
**Given** jqwik generates any total 1..10^9 and any 2-member share vector **Then** the stored shares sum exactly to `amountEgp` (property test; no rounding leakage).
Unhappy path: **Given** CUSTOM shares summing to `34999` **Then** `422 title: "Shares must sum to the total"`; **Given** a negative share or a memberId outside the household **Then** `422`/`404`; **Given** `type` is `TRANSFER` or `INCOME` **Then** `422` (only spending is split); **Given** the expense is deleted **Then** `404`.

## UX states (UI stories only)
N/A (split editor UI in STORY-029/037).

## Contract impact
`POST /api/expenses/{expenseId}/split`, `SplitRequest`, `ExpenseSplit`, `SplitMode` in v0 (unchanged).

## Data / migration impact
Uses `expense_split` (V4); add `CHECK (share_amount >= 0)` if absent.

## Security / privacy / LLM-safety impact
None beyond household scoping (reason: pure domain arithmetic).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-020.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
