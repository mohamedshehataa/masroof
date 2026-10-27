---
id: STORY-059
epic: EPIC-06
title: Android Review inbox card stack
phase: 3
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-057, STORY-037]
reqs: [REQ-AND-020]
implements: [PROMPT.md §11 Key screens (Review inbox), "PROMPT.md S9 (Maestro: review inbox)"]
owner_agent: android-engineer
---

# STORY-059 — Android Review inbox card stack

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Mohamed clears the review queue natively (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-020 — Card stack with parsed result, confidence, original source; swipe/buttons Confirm, Edit, Not an expense, Ignore sender; badge on More and Home
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/review*`.

## Data / migration impact
Room cache for review items.

## Security / privacy / LLM-safety impact
As STORY-058.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-057, STORY-037

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
