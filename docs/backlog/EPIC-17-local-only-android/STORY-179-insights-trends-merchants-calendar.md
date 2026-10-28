---
id: STORY-179
epic: EPIC-17
title: Insights merchants, calendar heatmap and trends are computed on the phone
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-178]
reqs: [REQ-AN-150, REQ-AN-151, REQ-AN-152]
implements: ["ADR-0009 D2 (AnalyticsEngine merchants, heatmap, trend)", "PROMPT.md §10 items 4, 5, 6, 7; §11 Insights (Android)"]
owner_agent: android-engineer
---

# STORY-179 - Insights merchants, calendar heatmap and trends are computed on the phone

## Goal
The remaining Insights tabs shipped from the server in STORY-109/111 (descoped) - top merchants, the calendar heatmap
with weekday and hour patterns, and the 6/12-period trend with income and savings rate - come from the on-device
`AnalyticsEngine` with the same numbers as the server (ADR-0009 D2). Screens keep their look.

## Requirements and acceptance criteria
### REQ-AN-150 - Merchants equal the golden file
**Given** the seed of `fixtures/analytics/parity-v1.json` **When** the engine computes merchants for each case **Then**
the list equals the file: grouped by `lower(trim(name))` (`" Carrefour "` and `carrefour` are one merchant, displayed
with the spelling the file expects), positive net only after refunds, ordered by total descending then name,
with visit count and average ticket in minor units (half away from zero).
Unhappy path: **Given** a merchant whose refunds equal or exceed its spend in the period **Then** it is not listed.
Unhappy path: **Given** rows without a merchant **Then** they are not listed as a merchant and the total of listed
merchants may be less than spent (no "unknown" merchant row, as on the server).

### REQ-AN-151 - Heatmap and time patterns equal the golden file
**Given** the parity seed **When** the engine computes the heatmap **Then** the per-Cairo-day totals, the weekday totals
(in the file's weekday order) and the hour-of-day totals equal the file, with days and hours taken in
`Africa/Cairo` (an expense at 2026-10-24T21:30:00Z counts on 2026-10-25 at hour 00).
Unhappy path: **Given** untimed rows (`timeKnown = 0`, stored at Cairo noon; the golden file contains such rows)
**Then** they count for their day, and their hour-of-day treatment equals the file (the server has no `timeKnown`
column, so the file records them at hour 12; any change to that is an EPIC-16-style follow-up, not this story).
Unhappy path: **Given** the October DST night (24:00 -> 23:00) **Then** the hour 23 bucket of that day holds both
23:xx instants exactly as the golden file says.

### REQ-AN-152 - Trends equal the golden file
**Given** the parity seed **When** the engine computes the trend for 6 and 12 periods **Then** each period's spend by
top-level category, total, income and savings rate bps equal the file, periods honour the month start day, and periods
with no rows are present with zeros (no gaps).
Unhappy path: **Given** 12 periods requested on a phone with 2 months of data **Then** 12 periods are returned, the older
ones at zero, and the chart shows them as empty bars, not as missing.
Unhappy path: **Given** income 0 in a period **Then** the savings rate is "no value" and the chart shows "-".

## Carried over from STORY-178
- Home's 6-month sparkline (the `HomeInsight.trend` chart) is empty after STORY-178 because the local snapshot has no trend data; this story must bring it back from the same trend computation.

## UX states (UI stories only)
- empty: per tab ("No merchants yet", empty heatmap grid, flat trend).
- loading: Room-backed, no spinner beyond the first frame. error: same "Couldn't compute insights" + Retry as STORY-178.
- offline: identical. RTL / dark: existing Compose charts (mirrored, TalkBack summaries); no new strings except empty
  states (`values` + `values-ar`).

## Contract impact
None. `/api/analytics/merchants|heatmap|trend` are no longer called.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (no network, merchant labels are digit-free capture labels or user text; not logged).

## Eval impact
None for the LLM harness. Golden-file Android unit test extended to merchants, heatmap and trend.

## Design notes (architect)
ADR-0009 D2. Exact server conventions (weekday start, untimed rows in hour totals, display spelling of a merchant) are
whatever `AnalyticsService` does; the golden file of STORY-177 is the referee, not this text.

## Dependencies
STORY-178

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: engine ≈ 130, wiring ≈ 40, tests ≈ 170)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D2), reuses STORY-111 Insights tabs |
