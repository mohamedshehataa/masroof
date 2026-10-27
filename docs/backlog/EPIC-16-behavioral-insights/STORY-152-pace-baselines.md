---
id: STORY-152
epic: EPIC-16
title: "Personal pace baselines by day of period"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-150]
reqs: [REQ-AN-108, REQ-AN-109]
implements: ["Owner request 2026-10-25 (gentle nudges: 'you usually spend X on delivery by now')", "PROMPT.md §10 intro (month start day, Cairo)", "PROMPT.md §10 item 1 (days left)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-152 — Personal pace baselines by day of period

## Goal
For today's day of the period, Masroof knows how much the household (or the caller) usually has spent by now, in
total and per category, from its own history. This powers the "usual by now" list (STORY-162/164) and the nudge
engine (STORY-159): "By the 12th you usually spend 800 EGP on Dining out; you're at 1,150" (owner request 2026-10-25).

## Requirements and acceptance criteria
### REQ-AN-108 — Pace curve and status per category
Let R be the reference period (STORY-150 REQ-AN-102) starting on `S`, and `t` "today". `d = days(S, t) + 1` (day 1
is the first day). Baseline periods = the last up to 6 complete active periods (STORY-150 REQ-AN-104); `K` = how
many there are.
Items: one TOTAL item (all eligible spend) plus one per top-level category that has eligible spend in R or in any
baseline period (subcategories roll up). For each item:
- `C_i(d)` = eligible spend of baseline period *i* on its first `d` Cairo dates; when `d` exceeds the period's
  length, the whole period total.
- `typicalByNow` = median of the `C_i(d)`; `typicalFullPeriod` = median of the baseline period totals. Median: odd
  `K` → middle value; even `K` → mean of the two middle values rounded HALF_EVEN to the piaster.
- `actualSoFar` = eligible spend in R on Cairo dates `S..t`.
- `paceBps` = ⌊actualSoFar × 10,000 / typicalByNow⌋, or `null` when `typicalByNow = 0`.
- `status`, first match wins: `INSUFFICIENT_HISTORY` when `K < 3`; `LOW_SPEND` when
  `typicalFullPeriod < 20,000` (200 EGP); `AHEAD` when `typicalByNow > 0`,
  `100·actualSoFar ≥ 125·typicalByNow` and `actualSoFar − typicalByNow ≥ 10,000` (100 EGP); `BEHIND` when
  `100·actualSoFar ≤ 75·typicalByNow`; otherwise `ON_PACE`.
`GET /api/analytics/habits/pace?scope=household|me&period=current|previous` → `200 Pace {period, scope, today,
dayOfPeriod, daysInPeriod, daysLeft, baselinePeriods, items: [{category: CategoryRef|null, discretionary,
typicalByNow, actualSoFar, typicalFullPeriod, paceBps, status}]}`. `category = null` is the TOTAL item and comes
first; the rest are sorted by `actualSoFar − typicalByNow` descending, ties by category `sortOrder`.
`daysLeft` follows the skill rule `days(t, end) − 1`.
**Given** `monthStartDay = 1`, today 2026-10-12 (`d = 12`), Dining out spent by the 12th in July, August and
September = 700.00, 800.00 and 950.00 EGP (full months 1,900, 2,100, 2,000) and 1,150.00 EGP in October 1–12
**When** `GET /api/analytics/habits/pace` **Then** the Dining out item has `typicalByNow = 80,000`,
`actualSoFar = 115,000`, `typicalFullPeriod = 200,000`, `paceBps = 14,375`, `status = AHEAD`.
**Given** four baseline periods with 800.01, 950.00, 700.00 and 1,000.00 EGP by day 12 **Then**
`typicalByNow = 87,500` (mean of 80,001 and 95,000 = 87,500.5, HALF_EVEN).
**Given** today 2026-10-31 (`d = 31`) and a 30-day September **Then** September contributes its full total.
Unhappy paths:
- **Given** only 2 complete active periods **Then** every item has `status = INSUFFICIENT_HISTORY` and
  `baselinePeriods = 2`; the response is still `200`.
- **Given** a category created this period **Then** its `typicalByNow = 0`, `paceBps = null`, `status = LOW_SPEND`.
- **Given** a category with typical 150 EGP per period **Then** `LOW_SPEND` even when today is 3× typical (no nudge
  for small categories).
- **Given** `scope=me` **Then** every amount is the caller's part (STORY-150 REQ-AN-103) and the other member's
  unsplit spending is absent.
- **Given** `scope=me&memberId=<other>` or `scope=partner` **Then** `422`.

### REQ-AN-109 — Period edges, DST and edits
**Given** `monthStartDay = 25` and the 2026 DST end (Thursday 2026-10-29 has 25 hours in Cairo per the JDK zone
rules) **When** today is 2026-10-30 **Then** `d = 6`, `daysInPeriod = 31` (2026-10-25..2026-11-24) and
`daysLeft = 25`.
**Given** an expense at `2026-10-24T21:30:00Z` (00:30 on 25 Oct, UTC+3) and one at `2026-10-24T20:30:00Z` (23:30
on 24 Oct) **Then** the first counts on day 1 of the period starting 2026-10-25 and the second on the last day of the
period that ended 2026-10-24.
**Given** today is the first day of the period **Then** `d = 1` and `typicalByNow` uses only each baseline period's
first day.
Unhappy paths:
- **Given** an October Dining out expense is recategorised to Groceries **When** the next request arrives **Then**
  Dining out `actualSoFar` drops and Groceries rises by the same amount.
- **Given** a Fuel expense (subcategory of Car) **Then** it counts under Car.
- **Given** a skipped inactive period in the last 6 (travel month) **Then** the 6 baseline periods are taken from
  further back (up to the 12-period look-back) and `baselinePeriods` reports how many were found.
- **Given** `period=previous` **Then** `d` = that period's length, `actualSoFar` = its full total, and the baseline
  periods are the ones before it.
- **Given** the admin changes `monthStartDay` mid-period **Then** the next response uses the new tiling (no stored
  curves).

## UX states (UI stories only)
N/A (no UI).

## Contract impact
New `GET /api/analytics/habits/pace`; schemas `Pace`, `PaceItem`, `PaceStatus`. Additive.

## Data / migration impact
None (computed on read). REQ-AN-105's performance check covers this endpoint.

## Security / privacy / LLM-safety impact
Household scoping; `me` scope only. No LLM. Aggregates only.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3. Period tiling must come from the shared month-start-day helper, not ad hoc date math.

## Dependencies
STORY-150 (eligible spend, scope, active periods).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 110 production + ≈ 240 tests incl. property tests for the median and day capping)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
