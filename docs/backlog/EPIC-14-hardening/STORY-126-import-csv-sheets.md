---
id: STORY-126
epic: EPIC-14
title: "Import: CSV and the owner's existing Google Sheets tracker export"
phase: 9
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-020, STORY-051]
reqs: [REQ-EXP-008]
implements: [PROMPT.md §9 (POST /api/import), PROMPT.md §14 Phase 9 (import from existing Google Sheets tracker)]
owner_agent: backend-engineer
---

# STORY-126 — Import: CSV and the owner's existing Google Sheets tracker export

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
History from the old tracker lands in Masroof once (PROMPT §9).
Cross-epic note (EPIC-16, 2026-10-26): imported rows without a time column are stored with `timeKnown = false`
(STORY-149 REQ-AN-100 default rule), so they never count as late-night purchases.

## Requirements and acceptance criteria
### REQ-EXP-008 — POST /api/import (multipart CSV / Sheets export) with column mapping preview, dry-run, dedup against existing entries, ImportResult report
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/import`, `ImportForm`, `ImportResult` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
File size limits; CSV injection safe.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-020, STORY-051

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
