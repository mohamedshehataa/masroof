---
id: STORY-096
epic: EPIC-10
title: "By merchant: top merchants, visit frequency, average ticket"
phase: 7
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-025, STORY-051]
reqs: [REQ-AN-004]
implements: [PROMPT.md §10 item 4]
owner_agent: backend-engineer
---

# STORY-096 — By merchant: top merchants, visit frequency, average ticket

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Where the money goes, by shop (PROMPT §10 item 4).

## Requirements and acceptance criteria
### REQ-AN-004 — GET /api/analytics/by-merchant with total, count, average ticket, last visit
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/analytics/by-merchant`, `MerchantBreakdown` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025, STORY-051

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 7 start |
