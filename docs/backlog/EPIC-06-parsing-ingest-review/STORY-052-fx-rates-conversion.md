---
id: STORY-052
epic: EPIC-06
title: Daily FX rates and conversion of foreign-currency entries to EGP
phase: 3
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-020, STORY-043]
reqs: [REQ-PARSE-070, REQ-PARSE-071, REQ-PARSE-072]
implements: [PROMPT.md §6 step 4 (FX), PROMPT.md §1 (USD/EUR/SAR with conversion), PROMPT.md S9 (jqwik FX)]
owner_agent: backend-engineer
---

# STORY-052 — Daily FX rates and conversion of foreign-currency entries to EGP

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
`$40 Netflix` and `Purchase of USD 9.99 at NETFLIX.COM` become EGP amounts with the rate used stored for audit (PROMPT §6).

## Requirements and acceptance criteria
### REQ-PARSE-070 — Scheduled job fetches daily rates (base EGP) from a configurable provider into fx_rate with source
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-071 — Foreign entries store amount_original/currency_original and amount_egp with the rate of the transaction date (lifts REQ-EXP-021 restriction)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-072 — Property: FX round-trip within tolerance; missing rate → previous available day, flagged
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`Expense.amountOriginal/currencyOriginal/fxRate` in v0; `POST /api/expenses` accepts non-EGP after this story.

## Data / migration impact
Uses `fx_rate` (V4).

## Security / privacy / LLM-safety impact
Outbound call to an FX provider (API key in .env); provider response validated.

## Eval impact
Suites `sms` foreign-currency cases; currency accuracy ≥ 99%.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-020, STORY-043

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
