---
id: STORY-025
epic: EPIC-02
title: Analytics summary v1 — period totals honoring month start day and Cairo time
phase: 2
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-012, STORY-021]
reqs: [REQ-AN-001, REQ-AN-020, REQ-AN-021]
implements: ["PROMPT.md §10 (intro: month start day, Cairo TZ, exclusions; item 1 Overview)", "PROMPT.md §11 (Home: spent this month, vs-last-month chip)", "PROMPT.md S9 (jqwik month-boundary math)"]
owner_agent: backend-engineer
---

# STORY-025 — Analytics summary v1

## Goal
The Home tab's "spent this month" number and "vs last month" chip come from the backend, computed with the household's month start day in `Africa/Cairo` (PROMPT §10). Daily average, projection and budget fields are added by STORY-093 in Phase 7.

## Requirements and acceptance criteria
### REQ-AN-001 — Period summary
**Given** an authenticated member **When** `GET /api/analytics/summary?period=current` (also `previous`, or explicit `from`/`to` Cairo dates) **Then** `200 AnalyticsSummary {period: PeriodRange{from, to, label}, totalSpend: Money, previousTotalSpend: Money, deltaPct: number|null, deltaAmount: Money, transactionCount, incomeTotal: Money, byMemberSpend[{memberId, amount}]}` where `totalSpend` sums `amountEgp` of non-deleted expenses with `type EXPENSE` and `status IN (CONFIRMED, PENDING_REVIEW)` in the period; `deltaPct` is `null` when the previous period total is 0.
Unhappy path: **Given** `period=lastyear` or `from > to` **Then** `422`; **Given** a household with no expenses **Then** `200` with zero amounts (never `null` Money).

### REQ-AN-020 — Period boundaries from month start day in Cairo
**Given** `monthStartDay = 25` and now is `2026-10-23 01:00 Africa/Cairo` **When** the current period is computed **Then** it is `2026-09-25 00:00` to `2026-10-24 23:59:59.999` Cairo (`from=2026-09-25`, `to=2026-10-24`), stored/queried as UTC `timestamptz` bounds; **Given** `monthStartDay = 1` and an expense at `2026-10-31T22:30:00Z` (= Nov 1 00:30 Cairo) **Then** it belongs to November.
**Given** jqwik generates any `monthStartDay` 1..28 and any instant 2020–2040 **Then** periods are contiguous and non-overlapping, every instant belongs to exactly one period, and `previous(next(p)) == p` (property tests).
Unhappy path: **Given** a DST-like offset change (Egypt re-introduced DST in 2023) **Then** boundaries still fall at local midnight (zone rules from tzdata, not fixed offsets).

### REQ-AN-021 — Exclusions
**Given** expenses of type `TRANSFER` and `CASH_WITHDRAWAL` in the period **When** the summary is computed **Then** they are excluded from `totalSpend`; **Given** settings `countAtmWithdrawalsAsSpending = true` (or query `includeCashWithdrawals=true`) **Then** `CASH_WITHDRAWAL` is included; `INCOME` and `REFUND` go to `incomeTotal`/offset, never to spend; `IGNORED` and `POSSIBLE_DUPLICATE` statuses are excluded.
Unhappy path: **Given** a soft-deleted expense **Then** it is excluded; restoring it (REQ-EXP-004) brings it back into the total.

## UX states (UI stories only)
N/A.

## Contract impact
`GET /api/analytics/summary`, `AnalyticsSummary`, `PeriodRange`, `Period`, `IncludeCashWithdrawals` in v0; fields not yet computed (`dailyAverage`, `projectedTotal`, `budget*`, `daysLeft`) are returned `null`/omitted until STORY-093 — architect marks them nullable (non-breaking).

## Data / migration impact
None (reads V4 indexes).

## Security / privacy / LLM-safety impact
None beyond household scoping (aggregates only).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-012, STORY-021.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
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
