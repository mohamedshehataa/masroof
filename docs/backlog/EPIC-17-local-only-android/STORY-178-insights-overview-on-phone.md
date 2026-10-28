---
id: STORY-178
epic: EPIC-17
title: Home and the Insights overview and categories are computed on the phone
phase: 7
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-171, STORY-176, STORY-177]
reqs: [REQ-AN-145, REQ-AN-146, REQ-AN-147, REQ-AN-148, REQ-AN-149]
implements: ["ADR-0009 D2 (AnalyticsEngine: filter, summary, forecast, by-category; outputs feed InsightsSnapshot/InsightsUiModel)", "PROMPT.md §10 items 1 and 2, §11 Home and Insights (Android)"]
owner_agent: android-engineer
---

# STORY-178 - Home and the Insights overview and categories are computed on the phone

## Goal
"Spent this month", the vs-last-month chip, the forecast, and the Insights Overview and Categories tabs (shipped in
STORY-109/112 from `/api/analytics`, descoped) are computed from Room by a new `AnalyticsEngine`, so they include every
local entry, work offline and need no server. The month start day becomes a phone setting. The Compose screens keep
their look (ADR-0009 D2: outputs feed the existing `InsightsSnapshot`/`InsightsUiModel`).

## Requirements and acceptance criteria
### REQ-AN-145 - Summary and forecast equal the golden file
**Given** the seed rows of `fixtures/analytics/parity-v1.json` inserted into Room (unit test reading the file by relative
path) **When** the engine computes summary (spent, previous period, delta bps, income, savings rate, daily average,
days left, remaining budget = null when no total budget) and forecast (linear and weekday-aware month end) for each
`today` and month start day in the file **Then** every value equals the file.
The filter is the server's (ADR-0009 D2): EGP only, status `CONFIRMED` or `PENDING_REVIEW`, types `EXPENSE` and `REFUND`
with refunds netted, `CASH_WITHDRAWAL` only with `includeCashWithdrawals`, `TRANSFER` never, income summed separately,
soft-deleted rows never. `includeCashWithdrawals` comes from a Settings switch "Count ATM withdrawals as spending"
(default off; the one-time import copies the household's server value); the golden file covers both values.
Unhappy path: **Given** a period with no rows **Then** spent is 0, delta is "no value" when the previous period is 0
too, and the screen shows the empty state, not an error.
Unhappy path: **Given** only a USD expense in the period **Then** spent is 0 EGP and the Overview shows "1 entry in
another currency is not included".

### REQ-AN-146 - Categories roll up and match the golden file
**Given** the parity seed **When** the engine computes by-category for each case in the file **Then** the list (category,
amount, share bps, delta vs previous period, order by amount descending then name) equals the file, with children rolled
up to their parent and an "Uncategorised / غير مصنف" bucket for rows without a category.
Unhappy path: **Given** a row whose `categoryId` points to an archived or missing category **Then** it counts under
its parent if the parent exists, otherwise under "Uncategorised"; the total never loses the amount.

### REQ-AN-147 - Home and Insights read the engine, never the network
**Given** the user adds `200` for Fuel today **When** Home and Insights are visible **Then** spent-this-month increases by
200 EGP and the Car category grows by 200 EGP without a refresh (the screens observe Room). **Given** any Insights or
Home figure **Then** no `/api/analytics/*` request is made (recording fake HTTP client) and the `filesDir/insights`
cache is no longer written (old files are deleted on first start).
Unhappy path: **Given** airplane mode **Then** every figure shows exactly as online, with no "last updated" or offline
banner.
Unhappy path: **Given** the engine throws (test fake) **Then** Insights shows "Couldn't compute insights" with Retry and
Home shows the list without the summary card; the app does not crash.

### REQ-AN-148 - The month start day is a phone setting
**Given** Settings **When** the user sets "Month starts on" to 25 (allowed 1..28, default 1) **Then** Home, Insights and
the period selector use periods from the 25th to the 24th in `Africa/Cairo`, e.g. on 2026-10-27 the current period is
2026-10-25 .. 2026-11-24.
Unhappy path: **Given** a value outside 1..28 typed or restored **Then** it is refused (picker offers only 1..28; a
stored invalid value falls back to 1 and is logged as an error code, not a value).
Unhappy path: **Given** a Cairo-midnight edge: an expense at 2026-10-24T21:30:00Z (00:30 Cairo on the 25th) with month
start day 25 **Then** it belongs to the period that starts on the 25th, not the previous one.

### REQ-AN-149 - Figures appear fast with a year of data
**Given** 12 months x 600 expenses in Room on a mid-range test device profile (Robolectric benchmark in CI as a proxy,
Macrobenchmark when available) **When** Home or the Insights Overview opens **Then** the summary and by-category
computation completes with p95 < 200 ms (PROMPT S9 read budget) over 20 runs, using the indexed range query on
`occurredAtMillis`.
Unhappy path: **Given** 10x that volume (72,000 rows) **Then** it still completes without an out-of-memory error (rows of
one period only are loaded).

## Known limitations
- Home's budgets card (and the Budgets screen) still read GET `/api/analytics/budgets` until STORY-180; STORY-178 does not build budgets.
- Insights trend, calendar and weekday data, and Home's 6-month sparkline, are empty until STORY-179.

## UX states (UI stories only)
- empty: "No spending yet this month" on Home/Insights; Categories shows the empty donut placeholder.
- loading: figures appear from Room; a skeleton for at most the first frame.
- error: REQ-AN-147 message with Retry.
- offline: identical to online, no banner.
- RTL / dark: unchanged chart components (mirrored, TalkBack summaries); the month-start setting row and its picker in
  `values` + `values-ar`, digits follow the digit setting.

## Contract impact
None. `/api/analytics/*` are no longer called.

## Data / migration impact
No schema change. `filesDir/insights` cache files are deleted. Month start day stored in the app settings (DataStore).

## Security / privacy / LLM-safety impact
None beyond removing network reads (less exposure). Amounts are not logged.

## Eval impact
None for the LLM harness. Parity is gated by the Android unit test over `fixtures/analytics/parity-v1.json`.

## Design notes (architect)
ADR-0009 D2 (`AnalyticsEngine` in `eg.masroof.core.analytics`, grouping by Cairo day/hour in Kotlin, indexed instant
range query excluding deleted rows).

## Dependencies
STORY-171, STORY-176, STORY-177

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: engine summary/forecast/by-category ≈ 150, wiring + setting ≈ 60, tests ≈ 180)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D2), reuses STORY-109 Insights UI |
| 2026-10-28 | 5 implement | android-engineer | IN_PROGRESS - AnalyticsEngine, LocalInsightsRepository, Settings section, parity + benchmark tests |
| 2026-10-28 | 6+7 verify | qa-verifier | PASS loop 2 (round 1 fixed USD-only note, Home engine path, Cairo midnight, savings rate); merged |
