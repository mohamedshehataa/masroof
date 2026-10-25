---
id: STORY-153
epic: EPIC-16
title: "Payday-effect card"
phase: 7
status: DRAFT
size: M
modules: [backend, contracts]
depends_on: [STORY-151]
reqs: [REQ-AN-110, REQ-AN-111]
implements: ["Owner request 2026-10-25 (payday effect: spending spikes right after salary)", "PROMPT.md §10 intro", "PROMPT.md §11 (bilingual, digits setting)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-153 — Payday-effect card

## Goal
The Habits report shows whether discretionary spending jumps in the week after payday, using the real payday from
STORY-151 or the month start day as fallback (owner request 2026-10-25).

## Requirements and acceptance criteria
### REQ-AN-110 — Payday-effect computation
Card `kind = PAYDAY_EFFECT` in `GET /api/analytics/habits` (STORY-150), for the requested scope.
1. Cycles: the complete cycles of `GET /api/analytics/habits/pay-cycle` for the same scope (STORY-151), newest
   first; cycles shorter than 14 days are skipped; take the newest 3 that remain (`n = 3`).
2. Spend: discretionary eligible spend only (STORY-150 REQ-AN-103).
3. Per cycle the *early window* is its first 7 Cairo dates and the *rest* is the remaining dates.
   `earlyTotal`, `restTotal` are summed over the 3 cycles; `earlyDays = 21`; `restDays = Σ(length − 7)`.
4. `earlyDaily = ⌊earlyTotal / earlyDays⌋`, `restDaily = ⌊restTotal / restDays⌋` (piasters);
   `ratioBps = ⌊earlyDaily × 10,000 / restDaily⌋` (`null` when `restDaily = 0`);
   `earlyShareBps = ⌊earlyTotal × 10,000 / (earlyTotal + restTotal)⌋`.
5. `topCategory` = the top-level discretionary category with the largest positive
   `earlyDaily_c − restDaily_c` (same formulas per category); ties → larger `earlyTotal_c`, then `sortOrder`;
   none positive → absent.
6. `highlight = true` when `ratioBps ≥ 15,000` (1.5×) or when `restDaily = 0` and `earlyTotal > 0`.
`detail = {paydaySource, cycles: [{start, end, earlyTotal, restTotal}], categories: [up to 3 × {category, earlyDaily, restDaily}]}`;
`drillDown = {from: newest cycle start, to: start + 6 days, categoryIds: all discretionary top-level categories}`.
**Given** three complete 30-day cycles with early discretionary totals 2,100.00 / 2,800.00 / 2,450.00 EGP and rest
totals 4,600.00 / 4,140.00 / 4,600.00 EGP **When** the report is built **Then** `earlyDaily = 35,000`,
`restDaily = 19,333`, `ratioBps = 18,103`, `earlyShareBps = 3,552`, `highlight = true`,
`messageKey = habits.payday.spike`.
**Given** early and rest daily averages of 300.00 and 280.00 EGP **Then** `ratioBps = 10,714`, `highlight = false`,
`messageKey = habits.payday.steady`.
Unhappy paths:
- **Given** only 2 complete cycles **Then** `state = INSUFFICIENT_DATA`, `insufficient = {reason: "NOT_ENOUGH_CYCLES", have: 2, need: 3}`.
- **Given** one of the last 3 cycles is 9 days long (salary paid early twice) **Then** it is skipped and the next
  older complete cycle is used; with fewer than 3 valid cycles → `INSUFFICIENT_DATA`.
- **Given** `earlyTotal + restTotal < 50,000` (500 EGP) **Then** `state = NOT_APPLICABLE`,
  `messageKey = habits.payday.lowSpend`, `highlight = false`.
- **Given** all discretionary spend fell in early windows (`restDaily = 0`) **Then** `ratioBps = null`,
  `messageKey = habits.payday.allEarly`, `highlight = true`.
- **Given** the pay cycle source is `MONTH_START_DAY` **Then** the card is still computed on household periods and
  `params.assumedPaydayDay` is set (`monthStartDay`).
- **Given** a cycle with `salaryMissed = true` **Then** it is used like any other cycle (its start is the expected date).
- **Given** a big essential expense (rent) on payday **Then** it does not count (discretionary only).

### REQ-AN-111 — Payday card text
`params`: `earlyDaily`, `restDaily` (Money), `ratioBps` (integer, the `{ratio}` placeholder; clients show it with
one decimal, floored: 18,103 → "1.8"), `earlyTotal` (Money), `cycles` (integer), optional `topCategory` (CategoryRef), optional
`assumedPaydayDay` (integer). Templates (Arabic has a household form and a `me` form; English is the same for both;
numbers come only from params, never written into the template):
| key | English | Arabic (household / me) |
|---|---|---|
| `habits.payday.spike` | In the week after payday you spend about {ratio}× your usual daily amount: {earlyDaily} a day, against {restDaily} for the rest of the month. | في الأسبوع اللي بعد القبض بتصرفوا حوالي {ratio} أضعاف المعتاد: {earlyDaily} في اليوم، مقابل {restDaily} باقي الشهر. / في الأسبوع اللي بعد القبض بتصرف حوالي {ratio} أضعاف المعتاد: {earlyDaily} في اليوم، مقابل {restDaily} باقي الشهر. |
| `habits.payday.steady` | Your spending stays steady after payday: {earlyDaily} a day in the first week, {restDaily} later. | صرفكم ثابت بعد القبض: {earlyDaily} في اليوم أول أسبوع، و{restDaily} بعد كده. / صرفك ثابت بعد القبض: {earlyDaily} في اليوم أول أسبوع، و{restDaily} بعد كده. |
| `habits.payday.allEarly` | Almost all of your discretionary spending happens in the week after payday ({earlyTotal} over the last {cycles} months). | تقريبًا كل الصرف الاختياري بيحصل في الأسبوع اللي بعد القبض ({earlyTotal} في آخر {cycles} شهور). |
| `habits.payday.topCategory` (appended when `topCategory` is set) | Most of the difference is {topCategory}. | أغلب الفرق في {topCategory}. |
| `habits.payday.assumedPayday` (appended when `assumedPaydayDay` is set) | Based on your month start day (day {assumedPaydayDay}), because no regular salary was found. | محسوب من أول الشهر عندكم (يوم {assumedPaydayDay})، لأننا مالقيناش مرتب منتظم. |
| `habits.payday.lowSpend` | Not enough discretionary spending yet to compare. | الصرف الاختياري لسه قليل على المقارنة. |
| `habits.payday.insufficient` | We need {need} full pay cycles to spot a payday pattern; {have} so far. | محتاجين {need} دورات قبض كاملة عشان نشوف النمط، لحد دلوقتي {have}. |
**Given** the spike example above with `topCategory = Dining out` **When** an English client renders it **Then** the
text is "In the week after payday you spend about 1.8× your usual daily amount: 350 EGP a day, against 193 EGP for
the rest of the month. Most of the difference is Dining out." (193.33 → 193 HALF_EVEN at display).
Unhappy paths:
- **Given** a template string in the bundle contains a literal digit or one of the banned words
  (overspent, too much, waste, مبالغة, تبذير, غلط) **Then** the server-side template test fails (the server keeps the
  canonical table for STORY-160 rendering; clients copy it).
- **Given** the category name is Arabic and the UI is English **Then** `topCategory` renders `nameEn` (CategoryRef
  carries both).

## UX states (UI stories only)
N/A (rendered by STORY-161/163).

## Contract impact
`HabitCardKind` gains `PAYDAY_EFFECT`; `detail` shape documented as `PaydayEffectDetail`. Additive.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
No salary amount appears in the card (only spending). `me` scope only shows the caller's part. No LLM.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-151 (pay cycles), via STORY-150 (shared rules, endpoint, REQ-AN-105 performance re-run).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 60 production + ≈ 120 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
