---
id: STORY-089
epic: EPIC-09
title: "Android Review inbox: side-by-side possible-duplicate card"
phase: 6
status: DESCOPED
size: S
modules: [android]
depends_on: [STORY-059, STORY-087]
reqs: [REQ-AND-060]
implements: [PROMPT.md §7, PROMPT.md §11 Key screens]
owner_agent: android-engineer
---

# STORY-089 — Android Review inbox: side-by-side possible-duplicate card

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Same as STORY-088 natively.

## Requirements and acceptance criteria
### REQ-AND-060 — Possible-duplicate cards side by side with Merge / Keep both; multi-select Merge enabled by flag
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
STORY-059, STORY-087

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
