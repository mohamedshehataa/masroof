---
id: STORY-154
epic: EPIC-16
title: "Weekend-vs-weekday card"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-150, STORY-098, STORY-012]
reqs: [REQ-AN-112, REQ-AN-113]
implements: ["Owner request 2026-10-25 (weekend vs weekday habits)", "PROMPT.md §10 item 7 (time patterns: weekday)", "PROMPT.md §10 intro"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-154 — Weekend-vs-weekday card

## Goal
The Habits report compares discretionary spending per weekend day with spending per other day, names the category
behind the gap, and lets the household say which days are its weekend (owner request 2026-10-25; PROMPT §10 item 7).
STORY-098 shows spend by weekday as a chart; this card turns it into a per-day comparison with a sentence.

## Requirements and acceptance criteria
### REQ-AN-112 — Household weekend days setting
`Settings.weekendDays` (array of `SAT|SUN|MON|TUE|WED|THU|FRI`, returned Saturday-first, default `[FRI, SAT]`, the
Egyptian weekend) is readable by every member and changed by the admin with `PATCH /api/settings {weekendDays}`.
**Given** an ADMIN **When** `PATCH /api/settings {"weekendDays": ["SAT", "FRI"]}` **Then** `200` with
`weekendDays = ["SAT", "FRI"]` and an `audit_log` row `SETTINGS_CHANGED` with before/after.
**Given** an ADMIN **When** `PATCH {"weekendDays": ["FRI"]}` (one-day weekend) **Then** `200`.
Unhappy paths:
- **Given** `[]`, four days, a duplicate (`["FRI", "FRI"]`) or `"FRIDAY"` **Then** `422` with
  `errors[].field == "weekendDays"`.
- **Given** a MEMBER sends the PATCH **Then** `403` and nothing changes.

### REQ-AN-113 — Weekend card computation and text
Card `kind = WEEKEND_WEEKDAY`, for the requested scope.
1. Window: the Cairo dates of the last 3 complete active periods (STORY-150 REQ-AN-104).
2. Spend: discretionary eligible spend (REQ-AN-103). Weekend dates = window dates whose Cairo weekday is in
   `weekendDays`; weekday dates = the others.
3. `weekendDaily = ⌊weekend spend / number of weekend dates⌋`, `weekdayDaily` likewise (piasters).
   `ratioBps = ⌊weekendDaily × 10,000 / weekdayDaily⌋` (`null` when `weekdayDaily = 0`);
   `inverseBps = ⌊weekdayDaily × 10,000 / weekendDaily⌋` (`null` when `weekendDaily = 0`).
4. `messageKey`: `habits.weekend.higher` when `ratioBps ≥ 13,000` or (`weekdayDaily = 0` and `weekendDaily > 0`);
   `habits.weekend.lower` when `inverseBps ≥ 13,000` or (`weekendDaily = 0` and `weekdayDaily > 0`);
   otherwise `habits.weekend.balanced`. `highlight = true` for higher and lower.
5. `topCategory`: for higher, the top-level discretionary category with the largest positive
   `weekendDaily_c − weekdayDaily_c`; for lower, the largest positive `weekdayDaily_c − weekendDaily_c`; ties →
   larger total, then `sortOrder`; absent for balanced.
`detail = {weekendDays, byWeekday: 7 × {weekday, dailyAverage, dates} Saturday-first (STORY-098 convention), allSpend: {weekendDaily, weekdayDaily}}`
where `allSpend` uses all eligible spend (essential included) for context.
`drillDown = {from: window start, to: window end, weekdays: weekendDays, categoryIds: discretionary top-level categories}`.
`params`: `weekendDaily`, `weekdayDaily` (Money), `ratioBps`, `weekendDays` (array; clients render localized day
names joined with "and"/"و"), optional `topCategory`.

| key | English | Arabic |
|---|---|---|
| `habits.weekend.higher` | Weekends cost more: {weekendDaily} a day on {weekendDays}, against {weekdayDaily} on other days. | الويك إند بيكلّف أكتر: {weekendDaily} في اليوم أيام {weekendDays}، مقابل {weekdayDaily} باقي الأيام. |
| `habits.weekend.lower` | You spend less on weekends: {weekendDaily} a day on {weekendDays}, against {weekdayDaily} on workdays. | صرف الويك إند أقل: {weekendDaily} في اليوم أيام {weekendDays}، مقابل {weekdayDaily} أيام الشغل. |
| `habits.weekend.balanced` | Weekends and weekdays look about the same: {weekendDaily} and {weekdayDaily} a day. | الويك إند وباقي الأيام قريبين من بعض: {weekendDaily} و{weekdayDaily} في اليوم. |
| `habits.weekend.topCategory` (appended when set) | The biggest gap is {topCategory}. | أكبر فرق في {topCategory}. |
| `habits.weekend.insufficient` | Not enough history yet to compare weekends and weekdays ({have} of {need}). | لسه مفيش بيانات كفاية نقارن الويك إند بباقي الأيام ({have} من {need}). |

**Given** `monthStartDay = 1`, today 2026-10-12, window 2026-07-01..2026-09-30 (92 dates, of which 26 are Fridays or
Saturdays), discretionary spend 15,600.00 EGP on weekend dates and 26,400.00 EGP on the other 66 dates **When** the
report is built **Then** `weekendDaily = 60,000`, `weekdayDaily = 40,000`, `ratioBps = 15,000`,
`messageKey = habits.weekend.higher`, `highlight = true`, and English renders "Weekends cost more: 600 EGP a day on
Fri and Sat, against 400 EGP on other days."
Unhappy paths:
- **Given** fewer than 3 complete active periods **Then** `INSUFFICIENT_DATA {reason: "NOT_ENOUGH_PERIODS", have, need: 3}`.
- **Given** fewer than 20 discretionary expenses in the window **Then**
  `INSUFFICIENT_DATA {reason: "TOO_FEW_EXPENSES", have, need: 20}`.
- **Given** an expense at `2026-08-27T21:30:00Z` (00:30 Friday 28 Aug in Cairo, UTC+3) **Then** it counts as a
  Friday (weekend), not Thursday.
- **Given** a date-only expense (`timeKnown = false`) **Then** it still counts on its date (weekday logic needs
  only the date).
- **Given** `weekendDays` changes from `[FRI, SAT]` to `[SAT, SUN]` **Then** the next request reclassifies the window.
- **Given** a skipped inactive period **Then** the window is the 3 active periods only (dates in between are not
  counted as zero-spend days).
- Public holidays and Ramadan are not modelled (non-goal; recorded in the epic README).

## UX states (UI stories only)
N/A (rendered by STORY-161/163). The weekend-days and late-night-hours pickers are part of the nudge settings
screens (STORY-162/164).

## Contract impact
`Settings.weekendDays` and `SettingsPatch.weekendDays` (additive, default `[FRI, SAT]`); `HabitCardKind` gains
`WEEKEND_WEEKDAY`; `WeekendWeekdayDetail`. Non-breaking.

## Data / migration impact
New migration (expand): `household.weekend_days` (array or bitmask, architect decides) with default Friday+Saturday
for existing rows.

## Security / privacy / LLM-safety impact
Admin-only write, audit-logged. Aggregates only. No LLM.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-150 (shared rules), STORY-098 (weekday aggregation and Saturday-first convention), STORY-012 (settings).

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
