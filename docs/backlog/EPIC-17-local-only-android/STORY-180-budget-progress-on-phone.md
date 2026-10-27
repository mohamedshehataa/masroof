---
id: STORY-180
epic: EPIC-17
title: Budget progress, rollover and on-track status are computed on the phone
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-178]
reqs: [REQ-AN-153, REQ-AN-154, REQ-AN-155]
implements: ["PROMPT.md §10 item 8 (budgets, progress, rollover) as shipped in STORY-092", "ADR-0009 D2 (device computes; Kotlin port)"]
owner_agent: android-engineer
---

# STORY-180 - Budget progress, rollover and on-track status are computed on the phone

## Goal
Budgets shipped in STORY-092 (server) and STORY-112 (Android screens), both descoped, compute spend and progress on the
server, which will no longer see local entries. This slice ports the budget status rules (`domain/budget/BudgetProgress`
and the status part of `application/budget/BudgetService`) to `eg.masroof.core.analytics` with the server's own test
cases, so a budget on the phone behaves exactly as today. Storage and screens move in STORY-181.

## Requirements and acceptance criteria
### REQ-AN-153 - Progress equals the server's rules
**Given** every case of `backend/src/test/java/eg/masroof/domain/budget/BudgetProgressTest.java` and the status cases of
`backend/src/test/java/eg/masroof/application/budget/BudgetServiceTest.java` **When** run through the Kotlin port
**Then** every output equals the Java expectation: `progressBps` = spent / limit in basis points, half away from zero,
capped at 1,000,000 (100x); spent <= 0 gives 0; `alertsFired` = the thresholds (percent, default 50/80/100) reached,
ascending.
**Given** a monthly Groceries budget of 4,000 EGP and 3,000 EGP of eligible Groceries spend (incl. its subcategories,
refunds netted, no transfers, cash withdrawals only with the setting, EGP only, `CONFIRMED` + `PENDING_REVIEW`)
**When** status is computed **Then** spent 300000, remaining 100000, `progressBps` 7500, alerts [50].
Unhappy path: **Given** spent above 100x the limit (e.g. limit 1 piaster, spent 1,000,000 EGP) **Then** `progressBps` is
exactly 1,000,000 and no overflow happens.
Unhappy path: **Given** a weekly budget **Then** the period is the ISO week (Monday 00:00 to the next Monday 00:00) in
Cairo time: spend at Sunday 23:30 Cairo counts in that week, spend at Monday 00:10 Cairo (Sunday 21:10/22:10 UTC) in
the next.

### REQ-AN-154 - Rollover is one period deep and only for budgets that existed
**Given** rollover on, a budget that existed in the previous period with limit 4,000 EGP and previous spend 3,500 EGP
**When** the current status is computed **Then** the limit is 4,500 EGP (leftover 500 added); **given** previous spend
4,600 EGP **Then** the limit is 3,400 EGP (overspend subtracted). Only one period back counts.
Unhappy path: **Given** the budget was created during the current period **Then** no rollover is applied.
Unhappy path: **Given** rollover makes the limit 0 or less **Then** progress is 10,000 bps with nothing spent, 1,000,000
bps with anything spent, the 100% alert fires and `onTrack` is false.

### REQ-AN-155 - On track, days left and remaining budget for Home
**Given** a monthly total budget (no category) of 20,000 EGP, month start day 25, today 2026-11-05 Cairo, spent 9,000 EGP
**When** status is computed **Then** `daysLeft` and `onTrack` equal the Java rules (spend vs the linear share of the
elapsed period), and the summary's `remainingBudget` (REQ-AN-145) is the total budget's remaining amount; with no total
budget it stays null.
Unhappy path: **Given** two total budgets cannot exist (STORY-181 refuses the second) **Then** if a restored backup holds
two anyway, the oldest one is used for `remainingBudget` and the other is shown with a "Duplicate budget" marker.

## UX states (UI stories only)
N/A (no UI in this slice).

## Contract impact
None.

## Data / migration impact
None (pure computation over expense rows and a budget value type).

## Security / privacy / LLM-safety impact
None (pure integer arithmetic; nothing logged).

## Eval impact
None for the LLM harness. Ported Java cases plus a seeded `BigInteger` property test for `progressBps` (fixed seed,
at least 1,000 cases).

## Design notes (architect)
Port of `domain/budget/BudgetProgress` and the status computation of `BudgetService` (ADR-0009 D2 approach; budgets are
listed as "later" in the ADR text but the owner's MVP includes them, see the EPIC-17 README).

## Dependencies
STORY-178 (engine filter and period helpers)

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: port ≈ 110, tests ≈ 200)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot, ports STORY-092 status rules |
