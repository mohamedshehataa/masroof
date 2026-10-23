---
id: STORY-111
epic: EPIC-12
title: "Android Insights: Trends, Calendar and Recurring tabs"
phase: 7
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-109, STORY-097, STORY-098, STORY-103]
reqs: [REQ-AND-072]
implements: [PROMPT.md §11 Navigation 4 (Insights), PROMPT.md §10]
owner_agent: android-engineer
---

# STORY-111 — Android Insights: Trends, Calendar and Recurring tabs

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Insights tabs Trends, Calendar, Recurring on Android (PROMPT §11 Insights).

## Requirements and acceptance criteria
### REQ-AND-072 — Insights tabs Trends, Calendar, Recurring: interactive Vico charts (tap → filtered list), states, Roborazzi matrix
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline (cached) · RTL · dark

## Contract impact
Uses analytics endpoints.

## Data / migration impact
Room cache of analytics payloads.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-109, STORY-097, STORY-098, STORY-103

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
