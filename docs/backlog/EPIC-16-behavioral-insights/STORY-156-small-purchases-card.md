---
id: STORY-156
epic: EPIC-16
title: "Small-purchases-add-up card"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-150, STORY-096]
reqs: [REQ-AN-116, REQ-AN-117]
implements: ["Owner request 2026-10-25 (small purchases that quietly add up)", "PROMPT.md §10 item 4 (merchants, average ticket)", "PROMPT.md §10 intro"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-156 — Small-purchases-add-up card

## Goal
The Habits report shows what the household's small purchases (coffee, snacks, rides, delivery fees) add up to per
month and per year, with the categories and merchants behind them. "Small" is relative to the household's own
typical ticket, not a fixed number (owner request 2026-10-25; PROMPT §10 item 4).

## Requirements and acceptance criteria
### REQ-AN-116 — Small-purchase threshold
Window: the last 3 complete active periods (STORY-150 REQ-AN-104). *Candidates*: eligible expenses
(REQ-AN-103, any category, essential or not, uncategorised included) with `isRecurring = false` and net amount > 0,
in the requested scope.
`median` = lower median of candidate net amounts (sort ascending, index ⌊(n−1)/2⌋).
`threshold` = `median` clamped to [2,000; 30,000] piasters (20–300 EGP), then rounded down to a multiple of 500
(5 EGP). A *small purchase* is a candidate with net amount ≤ `threshold`.
**Given** 120 candidates whose lower median is 87.40 EGP **Then** `threshold = 8,500` (85 EGP).
**Given** a lower median of 12.00 EGP **Then** `threshold = 2,000`; **given** 450.00 EGP **then** `30,000`.
Unhappy paths:
- **Given** fewer than 30 candidates **Then** `INSUFFICIENT_DATA {reason: "TOO_FEW_EXPENSES", have, need: 30}`.
- **Given** fewer than 3 complete active periods **Then** `INSUFFICIENT_DATA {reason: "NOT_ENOUGH_PERIODS"}`.
- **Given** a 75.00 EGP subscription with `isRecurring = true` **Then** it is not a candidate.
- **Given** a 60.00 EGP purchase fully refunded (linked refund, net 0) **Then** it is not a candidate.
- **Given** a USD 1.50 app purchase stored as 7,500 piasters **Then** the stored amount is used.

### REQ-AN-117 — Small-purchases card computation and text
Card `kind = SMALL_PURCHASES`, for the requested scope.
- `windowSmallCount`, `windowSmallTotal`; `windowTotal` = all eligible spend in the window;
  `windowSmallShareBps = ⌊windowSmallTotal × 10,000 / windowTotal⌋`; `avgPerPeriod = ⌊windowSmallTotal / 3⌋`;
  `annualized = windowSmallTotal × 4` (3 periods × 4 = 12, exact); `annualizedRounded` = `annualized` rounded
  HALF_EVEN to a multiple of 10,000 piasters (100 EGP), used for "about … a year".
- `currentPeriod {smallCount, smallTotal}` = small purchases in the reference period so far (same threshold).
- `topCategories`: up to 3 top-level categories by small total (uncategorised appears as `category: null`);
  `topMerchants`: up to 3 by small total among expenses with a `merchantId`; ties → higher count, then name.
- `highlight = true` when `windowSmallShareBps ≥ 1,500` and `avgPerPeriod ≥ 50,000` (500 EGP);
  `messageKey = habits.small.addsUp` when highlighted, else `habits.small.modest`.
`detail = {threshold, median, windowSmallCount, windowSmallShareBps, avgPerPeriod, annualized, currentPeriod, topCategories, topMerchants}`;
`drillDown = {from: window start, to: window end, maxAmountMinor: threshold}`.
`params`: `threshold`, `windowSmallTotal`, `annualizedRounded`, `currentTotal` (Money), `windowSmallCount`,
`currentCount`, `periods` (3), `sharePct` (= ⌊windowSmallShareBps / 100⌋), optional `topCategory` (the first of
`topCategories` when it is not `null`).

| key | English | Arabic |
|---|---|---|
| `habits.small.addsUp` | Small buys of {threshold} or less add up: {windowSmallCount} of them over the last {periods} months came to {windowSmallTotal}, about {annualizedRounded} a year. | الحاجات الصغيرة ({threshold} أو أقل) بتتجمّع: {windowSmallCount} عملية في آخر {periods} شهور بإجمالي {windowSmallTotal}، يعني حوالي {annualizedRounded} في السنة. |
| `habits.small.modest` | Small buys of {threshold} or less are {sharePct}% of your spending ({windowSmallTotal} over the last {periods} months). | الحاجات الصغيرة ({threshold} أو أقل) {sharePct}٪ من الصرف ({windowSmallTotal} في آخر {periods} شهور). |
| `habits.small.current` (appended) | This month so far: {currentCount} for {currentTotal}. | الشهر ده لحد دلوقتي: {currentCount} عملية بـ {currentTotal}. |
| `habits.small.topCategory` (appended when set) | Mostly {topCategory}. | أغلبها {topCategory}. |
| `habits.small.insufficient` | We need at least {need} purchases over {periods} full months to size up small buys; {have} so far. | محتاجين {need} عملية على الأقل في {periods} شهور كاملة عشان نحسب الحاجات الصغيرة، لحد دلوقتي {have}. |

**Given** `threshold = 8,500`, 64 small purchases totalling 3,162.40 EGP in the window, window total 18,000.00 EGP
**When** the report is built **Then** `windowSmallShareBps = 1,756`, `avgPerPeriod = 105,413`,
`annualized = 1,264,960`, `annualizedRounded = 1,260,000`, `highlight = true`, and English renders "Small buys of
85 EGP or less add up: 64 of them over the last 3 months came to 3,162 EGP, about 12,600 EGP a year."
**Given** an Arabic UI with the Arabic-Indic digit setting **Then** the same card renders
"الحاجات الصغيرة (٨٥ ج.م أو أقل) بتتجمّع: ٦٤ عملية في آخر ٣ شهور بإجمالي ٣٬١٦٢ ج.م، يعني حوالي ١٢٬٦٠٠ ج.م في السنة."
Unhappy paths:
- **Given** `windowTotal = 0` after deletions **Then** `state = NOT_APPLICABLE`, no division by zero.
- **Given** an expense is edited from 80.00 to 800.00 EGP **When** the next request arrives **Then** it leaves the
  small set and the median is recomputed.
- **Given** `scope=me` **Then** the threshold is computed from the caller's own candidates.

## UX states (UI stories only)
N/A (rendered by STORY-161/163).

## Contract impact
`HabitCardKind` gains `SMALL_PURCHASES`; `SmallPurchasesDetail`. Additive.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Merchant names in `detail` respect the scope. No LLM. Merchant names are not logged.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-150 (shared rules), STORY-096 (merchant aggregation and naming).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 90 production + ≈ 180 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
