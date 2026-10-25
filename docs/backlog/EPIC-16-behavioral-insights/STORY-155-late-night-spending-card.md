---
id: STORY-155
epic: EPIC-16
title: "Late-night spending card"
phase: 7
status: DRAFT
size: M
modules: [backend, contracts]
depends_on: [STORY-149, STORY-150, STORY-098, STORY-012]
reqs: [REQ-AN-114, REQ-AN-115]
implements: ["Owner request 2026-10-25 (late-night impulse spending)", "PROMPT.md §10 item 7 (time patterns: hour, Cairo)", "PROMPT.md §10 intro"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-155 — Late-night spending card

## Goal
The Habits report shows how many small and medium discretionary purchases happen late at night (default 23:00–04:00
Cairo), what they add up to and what they are, counting only purchases whose time of day is known
(owner request 2026-10-25; PROMPT §10 item 7). The card says "late-night" and never calls a purchase "impulsive".

## Requirements and acceptance criteria
### REQ-AN-114 — Late-night window setting
`Settings.lateNightStartHour` (0..23, default 23) and `Settings.lateNightEndHour` (0..23, default 4) define the
window in Cairo wall-clock time. A local time with hour `h` is late-night when `start > end` and (`h ≥ start` or
`h < end`), or when `start < end` and `start ≤ h < end`. Admin-only `PATCH /api/settings`, audit-logged.
**Given** an ADMIN **When** `PATCH {"lateNightStartHour": 22, "lateNightEndHour": 5}` **Then** `200` and the next
report uses 22:00–05:00.
**Given** defaults **Then** 23:00:00 and 03:59 are late-night and 04:00:00 and 22:59 are not.
Unhappy paths:
- **Given** `start = end` (`23`, `23`), `24`, `-1` or `"11pm"` **Then** `422` naming the field.
- **Given** a MEMBER sends the PATCH **Then** `403`.

### REQ-AN-115 — Late-night card computation and text
Card `kind = LATE_NIGHT`, for the requested scope.
1. Window: the last 3 complete active periods (STORY-150 REQ-AN-104).
2. *Candidates*: discretionary eligible expenses (REQ-AN-103) with net amount ≤ `P90`, the nearest-rank 90th
   percentile of discretionary net amounts in the window (sort ascending, take index ⌈0.9·n⌉ − 1). This keeps
   planned big buys out.
3. *Timed* candidates have `timeKnown = true` (STORY-149); the others are *untimed* and are only counted.
4. *Late* = timed candidates whose Cairo local hour is in the window (REQ-AN-114). The expense stays on its own
   Cairo date (01:30 on Saturday is a Saturday purchase).
5. `lateCount`, `lateTotal`, `timedCount`, `timedTotal`, `untimedCount`;
   `lateShareBps = ⌊lateTotal × 10,000 / timedTotal⌋`; `lateAvgTicket = ⌊lateTotal / lateCount⌋`;
   `otherAvgTicket = ⌊(timedTotal − lateTotal) / (timedCount − lateCount)⌋`;
   `untimedShareBps = ⌊untimedCount × 10,000 / (timedCount + untimedCount)⌋`; `currentPeriod {lateCount, lateTotal}`
   for the reference period so far with the same P90.
6. `messageKey`: `habits.lateNight.none` when `lateCount = 0`; `habits.lateNight.adds` with `highlight = true` when
   `lateShareBps ≥ 1,000` and `lateCount ≥ 4`; otherwise `habits.lateNight.some`.
7. `topCategory` = top-level category with the largest late total (ties → more late purchases, then `sortOrder`).
`detail = {startHour, endHour, p90, lateAvgTicket, otherAvgTicket, untimedCount, untimedShareBps, currentPeriod, topMerchants: up to 3 × {merchantId, merchantName, lateTotal, lateCount}}`
(expenses without `merchantId` are left out of `topMerchants`).
`drillDown = {from, to, hourFrom: startHour, hourTo: endHour, timeKnown: true, maxAmountMinor: p90, categoryIds: discretionary top-level categories}`.
`params`: `lateCount`, `lateTotal`, `startHour`, `endHour` (integers; clients render a 12-hour local time:
"11 PM" / "١١ م" or "11 م" by digit setting), `periods` (3), optional `topCategory`, `untimedCount`.

| key | English | Arabic |
|---|---|---|
| `habits.lateNight.adds` | Late-night buys add up: {lateCount} purchases between {startHour} and {endHour} over the last {periods} months, {lateTotal} in total. | مشتريات آخر الليل بتتجمّع: {lateCount} عملية بين {startHour} و{endHour} في آخر {periods} شهور، بإجمالي {lateTotal}. |
| `habits.lateNight.some` | A few late-night buys: {lateCount} between {startHour} and {endHour} over the last {periods} months ({lateTotal}). | شوية مشتريات بالليل: {lateCount} بين {startHour} و{endHour} في آخر {periods} شهور ({lateTotal}). |
| `habits.lateNight.none` | No purchases between {startHour} and {endHour} in the last {periods} months. | مفيش مشتريات بين {startHour} و{endHour} في آخر {periods} شهور. |
| `habits.lateNight.topCategory` (appended when set) | Most of it is {topCategory}. | أغلبها {topCategory}. |
| `habits.lateNight.untimed` (appended when `untimedCount > 0`) | {untimedCount} purchases had no time, so they are not counted. | {untimedCount} عملية متسجلة من غير وقت، فمش محسوبة. |
| `habits.lateNight.insufficient` | We need the time of at least {need} purchases to look at late nights; {have} so far. | محتاجين وقت {need} عملية على الأقل عشان نشوف آخر الليل، لحد دلوقتي {have}. |
| `habits.lateNight.mostlyUntimed` | Most purchases were saved without a time, so late nights can't be read yet. | أغلب العمليات متسجلة من غير وقت، فلسه مش قادرين نقرا آخر الليل. |

**Given** a window with 50 timed candidates totalling 12,800.00 EGP, 2 untimed candidates, and 8 late purchases
totalling 1,280.00 EGP, mostly Dining out **When** the report is built **Then** `lateShareBps = 1,000`,
`highlight = true`, `messageKey = habits.lateNight.adds`, `lateAvgTicket = 16,000`, `otherAvgTicket = 27,428`,
`untimedCount = 2`, `untimedShareBps = 384`.
Unhappy paths:
- **Given** fewer than 3 complete active periods **Then** `INSUFFICIENT_DATA {reason: "NOT_ENOUGH_PERIODS"}`.
- **Given** fewer than 30 timed candidates **Then** `INSUFFICIENT_DATA {reason: "TOO_FEW_TIMED", have, need: 30}`,
  `messageKey = habits.lateNight.insufficient`.
- **Given** `untimedShareBps > 5,000` (mostly manual back-dated entries) **Then**
  `INSUFFICIENT_DATA {reason: "MOSTLY_UNTIMED", have: untimedShareBps, need: 5000}`,
  `messageKey = habits.lateNight.mostlyUntimed`.
- **Given** a bank SMS with only a posting date (`timeKnown = false`, stored at 00:00 Cairo) **Then** it is untimed,
  never a midnight purchase.
- **Given** the DST fall-back night 2026-10-29 **When** expenses exist at `2026-10-29T20:30:00Z` and
  `2026-10-29T21:30:00Z` (both 23:30 Cairo, before and after the clock change) **Then** both are late-night on
  Thursday 29 Oct.
- **Given** an expense at `2026-04-23T22:30:00Z` (01:30 on 24 Apr, the spring-forward night) **Then** it is
  late-night; local hours come from the zone rules, never a fixed +02:00.
- **Given** an essential expense (a Bills payment at 02:00) or a recurring subscription charged at 01:00 **Then** it
  is not a candidate.
- **Given** a 15,000 EGP Electronics purchase at 02:00 above `P90` **Then** it is not a candidate.

## UX states (UI stories only)
N/A (rendered by STORY-161/163).

## Contract impact
`Settings`/`SettingsPatch` gain `lateNightStartHour`, `lateNightEndHour` (additive, defaults 23 and 4);
`HabitCardKind` gains `LATE_NIGHT`; `LateNightDetail`; `HabitDrillDown.hourFrom/hourTo/timeKnown` are used. Non-breaking.

## Data / migration impact
New migration (expand): two `smallint` columns on `household` with defaults 23 and 4.

## Security / privacy / LLM-safety impact
Merchant names appear in `detail` for the caller's scope only (`me` excludes the other member's unsplit purchases).
No LLM. Merchant names are not logged.

## Eval impact
None (no AI). Correctness depends on STORY-149's time-of-day flag, which has its own eval cases.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-149 (`timeKnown`), STORY-150 (shared rules), STORY-098 (hour aggregation in Cairo), STORY-012 (settings).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 110 production + ≈ 220 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
