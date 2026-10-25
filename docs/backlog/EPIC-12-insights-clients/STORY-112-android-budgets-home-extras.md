---
id: STORY-112
epic: EPIC-12
title: "Android Budgets screen, Home extras and onboarding budget step"
phase: 7
status: IN_PROGRESS
size: M
modules: [android]
depends_on: [STORY-034, STORY-070, STORY-092, STORY-103, STORY-104, STORY-059]
reqs: [REQ-AND-073, REQ-AND-074, REQ-AND-038]
implements: [PROMPT.md §11 Navigation 1 and 5, PROMPT.md §11 Onboarding (set monthly budget)]
owner_agent: android-engineer
---

# STORY-112 — Android Budgets screen, Home extras and onboarding budget step

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Full §11 Home and budgets on Android.

## Requirements and acceptance criteria
### REQ-AND-073 — More → Budgets: list/progress, create/edit with thresholds and rollover
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-074 — Home: budget ring, top-4 bars, 'Needs review (n)' banner, upcoming bills, AI insight card; drill-down to filtered list
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-038 — Onboarding step 'set monthly budget (optional)'
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/budgets*`, `/api/analytics/*`.

## Data / migration impact
Room cache.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-034, STORY-070, STORY-092, STORY-103, STORY-104, STORY-059

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
