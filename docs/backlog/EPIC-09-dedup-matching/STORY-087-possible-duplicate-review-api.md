---
id: STORY-087
epic: EPIC-09
title: Possible duplicates in the review inbox with Merge / Keep both resolution
phase: 6
status: DESCOPED
size: S
modules: [backend]
depends_on: [STORY-086, STORY-057]
reqs: [REQ-DEDUP-002, REQ-DEDUP-040]
implements: ["PROMPT.md §7 (Middle score → POSSIBLE_DUPLICATE, Merge / Keep both)"]
owner_agent: backend-engineer
---

# STORY-087 — Possible duplicates in the review inbox with Merge / Keep both resolution

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Uncertain matches are decided by a human in one tap (PROMPT §7).

## Requirements and acceptance criteria
### REQ-DEDUP-002 — POST /api/review/{id}/resolve action MERGE_WITH {targetId} or KEEP_BOTH
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-040 — Middle score → entry created as POSSIBLE_DUPLICATE with candidate ids; listed in /api/review with both sides
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ResolveRequest` extended with MERGE_WITH/KEEP_BOTH (non-breaking).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
Suite `dedup` middle-score cases.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-086, STORY-057

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
