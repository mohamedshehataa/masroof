---
id: STORY-192
epic: EPIC-17
title: A home-screen widget and quick-add tile show the month and add an entry fast
phase: 9
status: DRAFT
risk_tier: low
size: M
modules: [android]
depends_on: [STORY-178, STORY-181]
reqs: [REQ-AND-142, REQ-AND-143]
implements: ["PROMPT.md §11 Android-specific (Glance widget, Quick Settings tile, app shortcuts)"]
owner_agent: android-engineer
---

# STORY-192 - A home-screen widget and quick-add tile show the month and add an entry fast

> **DRAFT stub** - reuses STORY-127 (widget) and the tile/shortcut part of STORY-128 (both descoped). REQ ids reserved.

## Goal
One glance shows this month's spending and budget; one tap opens Quick add.

## Requirements and acceptance criteria
### REQ-AND-142 - Glance widget: spent this month, budget ring, quick-add button
Given the widget on the home screen, when an entry is saved or captured, then within one update the widget shows the new
spent-this-month and total-budget progress from the on-device engine; tapping "+" opens the Add sheet; amounts hidden
while the app lock (STORY-191) is on, if enabled.

### REQ-AND-143 - Quick Settings tile and app shortcut open Quick add
Given the tile or the long-press shortcut "Add expense", when tapped, then the Add sheet opens directly.

## UX states (UI stories only)
Widget sizes, empty month, RTL/dark, Arabic digits.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Widget shows totals on the home screen (privacy choice in Settings).

## Eval impact
None.

## Design notes (architect)
To be filled.

## Dependencies
STORY-178, STORY-181

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate M; may split widget / tile at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-127/128 |
