---
id: STORY-035
epic: EPIC-04
title: Android Transactions tab — search, filter chips, day groups, swipe actions and multi-select
phase: 2
status: READY
size: M
modules: [android]
depends_on: [STORY-033, STORY-021, STORY-022]
reqs: [REQ-AND-011, REQ-AND-012]
implements: ["PROMPT.md §11 Navigation 2 (Transactions)", "PROMPT.md §9 (filters)"]
owner_agent: android-engineer
---

# STORY-035 — Android Transactions tab

## Goal
Same Transactions behaviour as the PWA (STORY-027) with native gestures: search, filters, day grouping, swipe to recategorize/delete with undo, multi-select.

## Requirements and acceptance criteria
### REQ-AND-011 — Search, filters, day groups, paging
**Given** the Transactions tab **Then** a search field (debounced 300 ms → `q`), filter chips (member, category tree, source, status, account, date presets/custom) in a horizontally scrolling row with active values and clear, a `LazyColumn` grouped by Cairo day with sticky headers "Wed 22 Oct · 1,450 EGP", Paging 3 over `GET /api/expenses` with `nextCursor`, rows showing merchant/category, member chip, source icon and amount; tapping opens Transaction detail (STORY-037); filters survive rotation and process death (`SavedStateHandle`).
Unhappy path: **Given** no results **Then** "Nothing matches these filters" with Clear all; **Given** a page load error **Then** an inline retry footer keeps loaded rows; **Given** offline **Then** cached rows (STORY-038) with a banner.

### REQ-AND-012 — Swipe actions and multi-select
**Given** a row **When** swiped in the reading direction **Then** a Recategorize action opens the category picker and `PATCH`es; the opposite swipe soft-deletes with a 6 s snackbar Undo (`POST .../restore`); haptic feedback on threshold; **Given** long-press **Then** multi-select mode with count, Recategorize and Delete (batch undo), Merge hidden behind feature flag `dedup.manualMerge` until Phase 6.
Unhappy path: **Given** `409` on PATCH **Then** the row reloads and a snackbar explains; **Given** TalkBack is on **Then** swipe actions are also exposed as custom accessibility actions on the row; **Given** 200 selected rows **Then** batch requests run with concurrency 4 and a progress indicator.

## UX states (UI stories only)
empty: filtered vs. truly empty · loading: 8 shimmer rows · error: list-level Retry; footer retry · offline: cached + banner; actions disabled · RTL: swipe directions and sticky headers mirrored, Arabic-Indic totals · dark: theme; Roborazzi matrix; Compose UI test for swipe-delete-undo.

## Contract impact
Uses `GET /api/expenses`, `PATCH/DELETE`, `/restore`. None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-033, STORY-021, STORY-022.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
