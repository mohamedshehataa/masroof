---
id: STORY-094
epic: EPIC-10
title: "By category: donut data, ranked list, drill into subcategories and items, MoM change"
phase: 7
status: IN_PROGRESS
size: M
modules: [backend]
depends_on: [STORY-025]
reqs: [REQ-AN-002]
implements: [PROMPT.md §10 item 2]
owner_agent: backend-engineer
---

# STORY-094 — By category: donut data, ranked list, drill into subcategories and items, MoM change

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Spend by category with drill-down (PROMPT §10 item 2).

## Requirements and acceptance criteria
### REQ-AN-002 — GET /api/analytics/by-category with totals, share, MoM change, children and item drill-down
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/analytics/by-category`, `CategoryBreakdown`, `CategoryTotal` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025

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

## Implemented acceptance (speed mode, feat/insights-tab)
- Given categorised expenses, When GET /api/analytics/by-category is called, Then categories are ranked with share and month-over-month change, sub-categories roll up, and parentId drills down. Trimmed: item-level drill-down.
