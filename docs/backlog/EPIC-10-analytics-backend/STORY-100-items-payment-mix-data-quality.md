---
id: STORY-100
epic: EPIC-10
title: "Item-level insights, payment method mix and data quality stats"
phase: 7
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-094, STORY-053]
reqs: [REQ-AN-011, REQ-AN-013, REQ-AN-014]
implements: ["PROMPT.md §10 items 11, 13, 14"]
owner_agent: backend-engineer
---

# STORY-100 — Item-level insights, payment method mix and data quality stats

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Receipt detail and capture quality become insights (PROMPT §10).

## Requirements and acceptance criteria
### REQ-AN-011 — Item price history across stores and most-bought items from receipt line items
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-013 — Payment method mix: cash vs card vs wallet vs InstaPay
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-014 — Data quality: % auto-captured vs manual, pending review count, parsing accuracy (corrections / total)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
Extends `Insights`/`AnalyticsSummary` (non-breaking).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
Parsing accuracy stat feeds the eval feedback loop.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-094, STORY-053

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
