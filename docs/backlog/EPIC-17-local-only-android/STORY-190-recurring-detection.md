---
id: STORY-190
epic: EPIC-17
title: Recurring bills and subscriptions are detected on the phone
phase: 7
status: DRAFT
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-179]
reqs: [REQ-AN-157]
implements: ["PROMPT.md §10 item 9 (recurring, computed on the device per ADR-0009 D1 §10)"]
owner_agent: android-engineer
---

# STORY-190 - Recurring bills and subscriptions are detected on the phone

> **DRAFT stub** - reuses STORY-103 (descoped). REQ id reserved; full Given/When/Then at /spec.

## Goal
The household sees upcoming bills and subscriptions, and when one changes amount or is missed.

## Requirements and acceptance criteria
### REQ-AN-157 - Same merchant, similar amount, regular interval becomes a recurring item with the next due date
Given at least 3 entries of the same normalised merchant at a regular interval (weekly or monthly within a tolerance)
and amounts within a tolerance, when detection runs, then a recurring item with the expected next date and amount is
listed under Insights -> Recurring; an amount change or a missed occurrence is flagged; the user can dismiss an item.
Integer money only; dates in Africa/Cairo.

## UX states (UI stories only)
Recurring list, empty, dismissed; RTL/dark.

## Contract impact
None.

## Data / migration impact
Possibly a dismissed-items store (architect).

## Security / privacy / LLM-safety impact
None (local rules, no LLM).

## Eval impact
None for the LLM harness; rule tests with fixture series.

## Design notes (architect)
To be filled.

## Dependencies
STORY-179

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [ ] Data / migration impact (open)
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-103 |
