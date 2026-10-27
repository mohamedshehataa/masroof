---
id: STORY-034
epic: EPIC-04
title: Android Home tab — period selector, spent-this-month, vs-last-month chip, recent transactions
phase: 2
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-033, STORY-021, STORY-025]
reqs: [REQ-AND-010]
implements: ["PROMPT.md §11 Navigation 1 (Home)", "PROMPT.md §10 item 1"]
owner_agent: android-engineer
---

# STORY-034 — Android Home tab

## Goal
Same Home as the PWA (STORY-026) on Android: period total, comparison chip, by-member row and the 10 latest entries; Phase 7 adds budget ring/bars, review banner, upcoming bills and the insight card (STORY-112).

## Requirements and acceptance criteria
### REQ-AND-010 — Home tab v1
**Given** a signed-in user on Home **Then** a period selector (label from `AnalyticsSummary.period.label`, previous/next, "This month"), the big `totalSpend` with tabular numerals via `MoneyFormatter`, a "vs last month" chip (red up / green down / neutral when `deltaPct` is null), a by-member row with colour chips, and a "Recent" list of 10 expenses (merchant/category, member chip, amount, relative time, source icon) with "See all" navigating to the Transactions tab; pull-to-refresh refetches both; data flows through a `HomeViewModel` with `UiState { Loading | Content | Error }` unit-tested with Turbine.
Unhappy path: **Given** the summary call fails **Then** an error card with Retry in the number area while Recent still loads; **Given** no expenses **Then** empty state with a button opening the Add sheet; **Given** the device is offline **Then** the last cached content (STORY-038) with an "Updated 5 min ago" label, or the offline banner if nothing is cached.

## UX states (UI stories only)
empty: illustration + CTA · loading: shimmer skeletons · error: per-section cards with Retry · offline: banner + cached data · RTL: arrows mirrored, `١٬٢٥٠ ج.م` · dark: theme; Roborazzi matrix including font scale 1.5.

## Contract impact
Uses `GET /api/analytics/summary`, `GET /api/expenses`. None.

## Data / migration impact
None (Room cache belongs to STORY-038).

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-033, STORY-021, STORY-025.

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
