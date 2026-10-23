---
id: STORY-058
epic: EPIC-06
title: Web Review inbox card stack
phase: 3
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-057, STORY-029]
reqs: [REQ-UI-030]
implements: [PROMPT.md §11 Key screens (Review inbox), "PROMPT.md §13 (Playwright: review inbox)"]
owner_agent: web-engineer
---

# STORY-058 — Web Review inbox card stack

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 clears the review queue from the PWA in a few taps (PROMPT §11).

## Requirements and acceptance criteria
### REQ-UI-030 — Card stack with parsed result, confidence, original source; actions Confirm, Edit, Not an expense, Ignore sender; 'Needs review (n)' badge in More and Home
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty ('All caught up') · loading · error · offline (read-only) · RTL · dark

## Contract impact
Uses `/api/review*`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Raw text/images shown to household members only.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-057, STORY-029

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
