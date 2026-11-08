---
id: STORY-204
epic: EPIC-18
title: Savings goals - a target with an emoji, add or take out money, and what is needed each month
phase: 7
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-201]
reqs: [REQ-AN-167, REQ-EXP-056]
implements: ["Say teardown items 8 and 22 (Goals, goal funding)", "Supersedes the STORY-195 stub (owner idea 'saving ahead'); its open questions are answered the way Say does it: money is recorded against the goal, nothing moves in a bank"]
owner_agent: android-engineer
---

# STORY-204 - Savings goals - a target with an emoji, add or take out money, and what is needed each month

## Goal
The household saves towards known things (a new car, Sahel, school fees, an emergency fund). Each goal shows how much
is saved, how much is left, how many days remain and how much is needed each month to make it. Recording money for a
goal never moves money in a bank and never counts as spending.

## Requirements and acceptance criteria
### REQ-AN-167 - Goal progress, monthly need and on-track status
**Given** goal "New car" 🚗 target 850,000 EGP due 2028-04-01 Cairo with 135,000 EGP added, today 2026-11-08, month
start day 1 **When** the goal opens **Then** it shows 135,000 of 850,000 (16%, integer basis points, half away from zero),
"510 days to go", "Needs 42,059 EGP a month to make it" = ceil((850,000 - 135,000) / remaining periods) where remaining
periods = the current period plus every later period that starts before the target date (here Nov 2026 .. Mar 2028 =
17; at least 1), a ring, and "On track" when saved >=
target x elapsed / total duration since creation (else "Behind by X"). Goals list: each goal's ring and saved of target,
plus total saved of total target across active goals with a bar. Goals in other currencies show their own currency and
are excluded from the EGP totals with the standard hint.
Unhappy path: **Given** no target date **Then** no monthly need and no on-track line. **Given** a target date in the
past and not reached **Then** "Past the date - X left", no monthly need. **Given** saved >= target **Then** "Reached"
with a one-time celebration, `completedAtMillis` set.

### REQ-EXP-056 - Create, fund, take out, edit, archive and delete
**Given** Goals -> New goal **When** the user enters a name, picks an emoji (a grid of at least 40 money-life emoji), a
target and an optional date **Then** a `savings_goal` row is stored. **When** "Add money" `5,000` or "Take out" `2,000`
with an optional note and date **Then** one `goal_movement` (+500000 / -200000) is stored, progress updates at once, and
a step chart of the running total ("Money you've added") shows the history. Edit changes name, emoji, target and date;
archive hides it from the list and totals; delete asks first and removes the goal and its movements.
Unhappy path: taking out more than is saved -> "You only have X in this goal", nothing stored; amount empty/0/negative/
over the cap -> field error. Goal movements never create or change `expense` rows and never count in spending or income.

## UX states (UI stories only)
Route `goals` (Buckets/Plan area in STORY-206; until then Settings -> "Your money" -> Goals). Empty state with
examples, list, detail (ring, figures, Add money / Take out, chart, history), create/edit form, emoji grid. RTL, dark,
Arabic-Indic digits. Strings in `strings_goals.xml` (en + ar).

## Contract impact
None.

## Data / migration impact
None (tables from STORY-201). STORY-205/209 later let a gold asset back a goal (`asset.goalId`).

## Security / privacy / LLM-safety impact
None (local data).

## Eval impact
None.

## Design notes (architect)
`data/goals/GoalRepository`, pure `GoalMath` (monthly need, on-track) unit-tested with Cairo dates and month start days
1, 15, 28; `ui/goals/*`.

## Dependencies
STORY-201

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M (maths ~120, repository ~100, screens ~350, tests ~300)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 2, supersedes STORY-195 |
| 2026-11-08 | 5 implement | android-engineer | READY_FOR_VERIFY 3d4bf78; MINOR fixes 2f8da11 |
| 2026-11-08 | 6 verify | qa-verifier | PASS loop 1 (691 tests; 20,000 random monthly-need cases; expense triggers 0 rows) |
| 2026-11-08 | 8 integrate | orchestrator | DONE |
