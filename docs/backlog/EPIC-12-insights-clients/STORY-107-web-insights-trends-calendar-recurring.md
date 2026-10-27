---
id: STORY-107
epic: EPIC-12
title: "Web Insights: Trends, Calendar and Recurring tabs"
phase: 7
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-105, STORY-097, STORY-098, STORY-103]
reqs: [REQ-UI-052]
implements: [PROMPT.md §11 Navigation 4 (Insights), PROMPT.md §10]
owner_agent: web-engineer
---

# STORY-107 — Web Insights: Trends, Calendar and Recurring tabs

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Insights tabs Trends, Calendar, Recurring on the PWA (PROMPT §11 Insights).

## Requirements and acceptance criteria
### REQ-UI-052 — Insights tabs Trends, Calendar, Recurring: interactive Recharts (tap → filtered transaction list), empty/loading/error states
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline (cached) · RTL (charts mirrored, Arabic-Indic axis) · dark

## Contract impact
Uses analytics endpoints.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-105, STORY-097, STORY-098, STORY-103

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
