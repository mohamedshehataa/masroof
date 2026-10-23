---
id: STORY-088
epic: EPIC-09
title: "Web Review inbox: side-by-side possible-duplicate card"
phase: 6
status: DRAFT
size: S
modules: [web]
depends_on: [STORY-058, STORY-087]
reqs: [REQ-UI-040]
implements: [PROMPT.md §7, "PROMPT.md §11 Key screens (Review inbox: possible-duplicate cards)"]
owner_agent: web-engineer
---

# STORY-088 — Web Review inbox: side-by-side possible-duplicate card

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 resolves duplicates visually (PROMPT §11).

## Requirements and acceptance criteria
### REQ-UI-040 — Possible-duplicate cards show both entries side by side with Merge / Keep both; Transactions multi-select Merge enabled by flag
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/review*`, `/merge`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-058, STORY-087

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
