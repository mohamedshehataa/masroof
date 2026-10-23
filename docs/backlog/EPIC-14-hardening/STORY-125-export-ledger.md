---
id: STORY-125
epic: EPIC-14
title: "Export: CSV/XLSX/PDF for a date range and full household data export"
phase: 9
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-021]
reqs: [REQ-EXP-007, REQ-EXP-040]
implements: [PROMPT.md §9 (GET /api/export), PROMPT.md §12 (Data export of everything)]
owner_agent: backend-engineer
---

# STORY-125 — Export: CSV/XLSX/PDF for a date range and full household data export

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The household owns its data (PROMPT §12).

## Requirements and acceptance criteria
### REQ-EXP-007 — GET /api/export?format=csv|xlsx|pdf&from&to streams the ledger with items and sources summary
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-EXP-040 — Full household export (all tables + media manifest) as a zip for data portability
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/export` in v0; full export path addition.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Authenticated; rate-limited; no raw secrets in exports.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-021

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
