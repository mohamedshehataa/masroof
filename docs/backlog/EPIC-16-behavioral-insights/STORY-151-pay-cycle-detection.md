---
id: STORY-151
epic: EPIC-16
title: "Pay-cycle detection from salary income"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-150]
reqs: [REQ-AN-106, REQ-AN-107]
implements: ["Owner request 2026-10-25 (payday effect)", "PROMPT.md §10 intro (month start day, e.g. salary day 25th)", "PROMPT.md §4.3 (credits/salary stored as income)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-151 — Pay-cycle detection from salary income

## Goal
Masroof knows when each member actually gets paid, from recurring INCOME entries, and falls back to the household
month start day when there is no regular salary, so the payday-effect card (STORY-153) measures spending from the
real payday (owner request 2026-10-25; PROMPT §10 intro, §4.3 "credits/salary → income").

## Requirements and acceptance criteria
### REQ-AN-106 — Salary stream detection
Inputs: INCOME rows (`type = INCOME`, status CONFIRMED or PENDING_REVIEW, not soft-deleted) whose Cairo date is in
the 180 days ending "today" (STORY-150 REQ-AN-102), valued at the stored EGP amount. The receiving member is
`paidByMemberId`.
1. **Group key** per row: `merchantId`; else `merchantRaw` lower-cased, trimmed, with runs of digits and whitespace
   collapsed; else `paymentAccountId`; else the row cannot join a stream.
2. **Counted entries**: per (member, group key) and per Cairo calendar month, keep the largest entry.
3. **Median amount M**: lower median of the counted amounts (sort ascending, take index ⌊(n−1)/2⌋).
4. **Payday D**: the day-of-month (31 counted as 30) among the counted entries that minimises the sum of circular
   distances to the others, where distance(a, b) = min(|a−b|, 30−|a−b|); ties → the smallest day.
5. **Qualifying entries**: counted entries with `8·M ≤ 10·amount ≤ 12·M` (±20 %) and distance(day, D) ≤ 4.
6. A **salary stream** exists when there are ≥ 3 qualifying entries, `M ≥ 1,000.00 EGP` (100,000 piasters), and the
   newest qualifying entry is at most 45 days before today.
7. **Primary stream** per member: the largest M; ties → more qualifying entries; then the smaller group key.
   **Household payday** = the primary stream of the member with the largest M (ties → `member_id` ascending).

**Given** INCOME `ACME PAYROLL` 25,000.00 EGP to Mohamed on 2026-05-26, 06-25, 07-27, 08-25, 09-24 (Cairo dates)
and today 2026-10-06 **When** detection runs **Then** a stream exists for Mohamed with `D = 25`,
`M = 2,500,000` piasters, 5 qualifying entries.
**Given** Mohamed's stream above and Member 2's INCOME `NILE SCHOOL` 20,000.00 EGP on the 1st of each of the last
4 months **Then** the household payday is Mohamed's (larger M) and Member 2's own payday is `D = 1`.
**Given** a salary on the 30th, 1st and 2nd of consecutive months **Then** `D = 1` (circular distance) and all three
qualify.
Unhappy paths:
- **Given** irregular freelance income of 8,000, 15,000, 4,000 and 22,000 EGP on random days **Then** no stream
  (fewer than 3 entries within ±20 % of M).
- **Given** only 2 months of salary history **Then** no stream (needs 3).
- **Given** two INCOME rows from the same payer in one month (salary 25,000 and a 3,000 bonus) **Then** only the
  25,000 is counted for that month.
- **Given** the newest salary is 60 days old (job change) **Then** no stream.
- **Given** a salary received in USD and stored as 1,500,000 piasters at the day's rate **Then** the stored EGP
  amount is used; later FX changes do not change M.
- **Given** a TRANSFER from Mohamed's own account or a REFUND **Then** it never forms a stream (only INCOME).
- **Given** a salary entry is deleted and fewer than 3 qualifying entries remain **Then** the next request falls
  back (REQ-AN-107).

### REQ-AN-107 — Pay cycles, missed salary, fallback and API
With a stream: for each month, the *actual payday* is the earliest INCOME row of the stream's group key with an
amount within ±20 % of M and a Cairo date within ±7 days of the expected date (day D of that month, clamped to the
month's length). Cycle *i* runs from payday *i* to the day before payday *i+1* (Cairo dates, inclusive).
If today is more than 7 days past an expected date with no actual payday, that month's cycle starts on the expected
date with `startSource = EXPECTED` and `salaryMissed = true`.
Without a stream: cycles are the household periods (month start day), `startSource = MONTH_START_DAY`.
`GET /api/analytics/habits/pay-cycle?scope=household|me` → `200 PayCycle {scope, source: SALARY | MONTH_START_DAY,
expectedDayOfMonth: int|null, medianSalary: Money|null, currentCycle: {start, end}, nextExpectedPayday,
cycles: [{start, end, startSource: SALARY | EXPECTED | MONTH_START_DAY, salaryMissed}]}`, `cycles` = the last 6
complete cycles, newest first. `scope=household` uses the household payday and always returns
`medianSalary = null`; `scope=me` uses the caller's primary stream and returns the caller's own M.
**Given** the Mohamed stream above and today 2026-10-26 with an actual payday 2026-10-25 **Then** `currentCycle =
{start: 2026-10-25, end: 2026-11-24}`, `nextExpectedPayday = 2026-11-25`, and the newest complete cycle is
`{start: 2026-09-24, end: 2026-10-24, startSource: SALARY}`.
**Given** the June salary arrived early for Eid on 2026-06-18 (7 days before D) **Then** the June cycle starts
2026-06-18 with `startSource = SALARY`.
Unhappy paths:
- **Given** no September salary and today 2026-10-03 **Then** the September cycle starts 2026-09-25 with
  `startSource = EXPECTED, salaryMissed = true`; when the salary arrives on 2026-10-05 it starts a new cycle that day.
- **Given** no stream and `monthStartDay = 25` **Then** `source = MONTH_START_DAY`, `expectedDayOfMonth = 25`,
  cycles equal the household periods.
- **Given** `monthStartDay = 1` and no stream **Then** cycles are calendar months, `expectedDayOfMonth = 1`.
- **Given** Member 2 asks `scope=household` **Then** the response gives Mohamed's payday dates and
  `medianSalary = null` (no salary amount of the other member is ever returned).
- **Given** `scope=me` for a member with no stream **Then** the household periods are used.
- **Given** `scope=other` **Then** `422`; no token → `401`.

## UX states (UI stories only)
N/A (no UI). The cycle is shown only inside the payday card (STORY-161/163).

## Contract impact
New `GET /api/analytics/habits/pay-cycle`; schemas `PayCycle`, `PayCycleRange`, `PayCycleSource`. Additive.

## Data / migration impact
None (computed on read). No setting is added: the month start day remains the manual override ("e.g. 25 if you get
paid on the 25th", STORY-031).

## Security / privacy / LLM-safety impact
Salary amounts are sensitive: the household scope never returns an amount; `me` returns only the caller's own.
INCOME `merchantRaw` (payer names) is never logged. No LLM.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-150 (shared rules, endpoint family). Uses INCOME rows that the parsing stories already create (§4.3).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 120 production + ≈ 250 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
