---
id: STORY-150
epic: EPIC-16
title: "Habits endpoint, eligible spend and member scope"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-013, STORY-023, STORY-025, STORY-090]
reqs: [REQ-AN-102, REQ-AN-103, REQ-AN-104, REQ-AN-105]
implements: ["Owner request 2026-10-25 (behavioral insights)", "PROMPT.md §10 (intro: month start day, Cairo, exclusions)", "PROMPT.md §9 (GET /api/analytics/*)", "PROMPT.md S9 (reads p95 < 200 ms, no seq scans, no N+1)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-150 — Habits endpoint, eligible spend and member scope

## Goal
One endpoint, `GET /api/analytics/habits`, returns the household's behavioral insight cards, and every card in
EPIC-16 is computed from the same spend set, the same scope rules and the same history rules (owner request
2026-10-25; PROMPT §10 intro). This is the walking skeleton: it ships with an empty card list and the shared
rules; STORY-153..158 each add one card kind.

## Requirements and acceptance criteria
### REQ-AN-102 — Habits report envelope
**Given** an authenticated member **When** `GET /api/analytics/habits?scope=household&period=current` **Then**
`200 HabitsReport {period: PeriodRange, scope: "HOUSEHOLD", generatedAt, history: {activePeriods, firstEligibleDate}, cards: HabitCard[]}`.
`scope` defaults to `household`; `period` is `current` (default) or `previous` and sets the *reference period*.
"Complete periods" always means periods that ended before the reference period starts; "today" means
`min(today in Cairo, last day of the reference period)`.
`HabitCard` has: `kind` (enum, grows per story: `PAYDAY_EFFECT`, `WEEKEND_WEEKDAY`, `LATE_NIGHT`,
`SMALL_PURCHASES`, `CATEGORY_CREEP`), `state` (`READY` | `INSUFFICIENT_DATA` | `NOT_APPLICABLE`),
`highlight: boolean`, `messageKey: string`, `params: object` (values are `Money`, integer, ISO date, string, or
`CategoryRef {id, nameEn, nameAr}`), `insufficient: {reason, have, need} | null` (set only when `state` is
`INSUFFICIENT_DATA`), `drillDown: {from, to, categoryIds?, merchantIds?, maxAmountMinor?, weekdays?, hourFrom?, hourTo?, timeKnown?} | null`
(filters the Transactions list understands, dates in Cairo) and `detail: object` (kind-specific, defined per story).
Cards are ordered highlighted first, then by the kind order above. In this story `cards` is `[]`.
**Given** `period=previous` **Then** `period` is the previous PeriodRange and every later card is computed as if
today were that period's last day.
Unhappy paths:
- **Given** no token **Then** `401`.
- **Given** `scope=partner`, `scope=<a member uuid>` or `period=lastyear` **Then** `422` with
  `errors[].field` naming the parameter; there is no parameter that selects another member.
- **Given** a household with no expenses **Then** `200` with `history.activePeriods = 0`,
  `firstEligibleDate = null` and `cards = []` (never `500`, never `null` lists).
- **Given** the database is unavailable **Then** `503` Problem+JSON, no partial body.

### REQ-AN-103 — Eligible spend, discretionary flag and scope
The *eligible spend* of an expense is computed once and reused by every Habits computation:
1. Included rows: `type = EXPENSE`, plus `CASH_WITHDRAWAL` only when `countAtmWithdrawalsAsSpending = true`;
   `status IN (CONFIRMED, PENDING_REVIEW)`; not soft-deleted. TRANSFER, INCOME, REFUND rows, IGNORED and
   POSSIBLE_DUPLICATE never count (same set as STORY-025 REQ-AN-021).
2. Value: the stored base-currency amount `amount.amountMinor` (EGP piasters). The original currency and
   today's FX rate are never used.
3. Net of refunds: REFUND rows linked to the expense by STORY-090 are subtracted from it; the result is floored at
   0. Unlinked refunds are ignored. The net stays on the original expense's date and category.
4. Local date and hour come from `occurredAt` in `Africa/Cairo` (zone rules, never a fixed offset).
5. *Discretionary* = the expense's top-level category (a subcategory rolls up to its parent) has
   `isEssential = false` **and** `isRecurring = false`. `categoryId = null` counts in totals but is never
   discretionary. With the seeded categories, Dining out, Clothing, Entertainment, Electronics etc. are
   discretionary; Groceries, Bills, Rent, Car, Transport, Health, Education are not.
6. *Scope*: `household` = the net amount. `me` = the caller's part: the full net amount when `splitMode = NONE`
   and `paidByMemberId` = caller; 0 when another member paid an unsplit expense; for split expenses the net amount
   allocated with `Money.allocate` using the `expense_split` shares as weights, taking the caller's part. Expenses
   with `paidByMemberId = null` and no splits count only in `household`.

**Given** these expenses in one period: Dining out 120.00 EGP CONFIRMED; Dining out 80.00 PENDING_REVIEW;
TRANSFER 5,000.00; CASH_WITHDRAWAL 2,000.00; a USD 9.99 Subscriptions charge with `isRecurring = true` stored as
49,950 piasters at `fxRate 50.0` (today's rate is 51.2); Groceries 1,000.00 with a linked REFUND of 300.00; an
unlinked REFUND of 50.00; a soft-deleted Dining out 400.00 **When** eligible spend is summed (household scope, ATM
rule off) **Then** total = 12,000 + 8,000 + 49,950 + 70,000 = 139,950 piasters and discretionary total = 20,000
(the Subscriptions charge is recurring, Groceries is essential).
**Given** `countAtmWithdrawalsAsSpending = true` **Then** total = 339,950.
**Given** a 350.00 EGP Dining out expense split EQUAL between Mohamed and Member 2 **When** Mohamed asks with
`scope=me` **Then** his part is 17,500; **Given** the same expense has a linked refund of 100.01 EGP **Then** the
net 24,999 is allocated by `Money.allocate`: 12,500 to the member with the lower `member_id`, 12,499 to the other.
Unhappy paths:
- **Given** a refund larger than its linked expense **Then** the expense counts 0, never negative.
- **Given** an expense is recategorised, edited, deleted or restored **When** the next request arrives **Then** the
  result reflects the change (any cache is invalidated by the household's EXPENSE_CREATED/UPDATED/DELETED events).
- **Given** a category is archived **Then** its past expenses still count under it.
- **Given** an expense of another household **Then** it never appears in any total (IDOR test with two households).

### REQ-AN-104 — History and cold-start rules
An *active period* is a household period (month start day, Cairo) with at least 5 eligible expenses in
household scope. Card stories ask for "the last N complete active periods": complete periods before the reference
period, inactive ones skipped, looking back at most 12 periods. `history.activePeriods` = the number of complete
active periods in that 12-period look-back; `history.firstEligibleDate` = Cairo date of the oldest eligible expense.
**Given** `monthStartDay = 25`, today 2026-11-10, and eligible expenses only from 2026-08-01 onward with 3, 12, 15
and 9 expenses in the periods starting 2026-07-25, 08-25, 09-25 and 10-25 **When** the report is built **Then**
`activePeriods = 2` (periods 08-25 and 09-25; 07-25 has fewer than 5, 10-25 is the current period).
Unhappy paths:
- **Given** a new household with 4 expenses in its first week **Then** `activePeriods = 0`; later card stories
  return `INSUFFICIENT_DATA` with `insufficient {reason: "NOT_ENOUGH_PERIODS", have: 0, need: <card minimum>}`.
- **Given** a 2-month gap with no expenses (travel) between active periods **Then** the gap periods are skipped, not
  counted as zero-spend periods.
- **Given** the admin changes `monthStartDay` from 1 to 25 **Then** the next request re-tiles all history with day 25
  (no stored periods).
- **Given** a household with one active member **Then** `scope=me` and `scope=household` both return `200`.

### REQ-AN-105 — Performance budget
**Given** a seeded household with 12 months × 400 expenses (4,800 rows) next to another household with 10,000
rows **When** k6 runs `GET /api/analytics/habits` (both scopes) at 20 rps **Then** p95 < 200 ms (S9 reads budget);
the EXPLAIN check finds no sequential scan on `expense` (S9 DB budget) and the Hibernate statistics assertion finds
no N+1. Every later EPIC-16 card story re-runs this check with its card added.
Unhappy path: **Given** the budget is missed after a card story **Then** that story is not DONE (the check is part of
its `make check` run, as in STORY-101).

## UX states (UI stories only)
N/A (no UI).

## Contract impact
New `GET /api/analytics/habits` with `scope` and `period` parameters; schemas `HabitsReport`, `HabitCard`,
`HabitCardKind`, `HabitCardState`, `HabitDrillDown`, `CategoryRef`. Additive, non-breaking. `HabitCardKind` grows in
later stories (additive enum values; clients must render unknown kinds as nothing, see STORY-161/163).

## Data / migration impact
None required (computed on read from `expense`, `expense_split`, `category`, refund links). The architect may add a
per-household cache or an expression index on `(household_id, (occurred_at AT TIME ZONE 'Africa/Cairo')::date)`;
any index is created `CONCURRENTLY` in its own migration.

## Security / privacy / LLM-safety impact
Household scoping on every query (IDOR test). The `me` scope is the only member filter; no endpoint returns another
member's habits (README "Privacy between members"). Aggregates only; no merchant text logged. No LLM.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-025 (spend set, periods), STORY-023 (split shares), STORY-090 (refund links), STORY-013 (`isEssential`,
parent categories).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 130 production + ≈ 260 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-25 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
