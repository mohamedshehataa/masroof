---
id: STORY-206
epic: EPIC-18
title: Wallet home and new tabs - spent, balance and what you owe at a glance, quick actions, insight tiles
phase: 7
status: READY
risk_tier: low
size: M
modules: [android]
depends_on: [STORY-199, STORY-200, STORY-202, STORY-204, STORY-205]
reqs: [REQ-UI-081, REQ-UI-082, REQ-AN-175]
implements: ["Say teardown items 1, 14, 15, 16 (Wallet layout, quick actions, insight tiles, don't count in totals)"]
owner_agent: android-engineer
---

# STORY-206 - Wallet home and new tabs - spent, balance and what you owe at a glance, quick actions, insight tiles

## Goal
Bring every EPIC-18 screen into one navigation that matches how Say is used, and make Home ("Wallet") answer the three
daily questions at a glance: how much did we spend this month, how much do we have, and what do we owe or have coming
up. Insights stays one tap away. An entry can be marked "Don't count in totals".

## Requirements and acceptance criteria
### REQ-UI-081 - Bottom tabs and where everything lives
**Given** the app **When** it opens **Then** the bottom bar has Wallet, Bills, Plan (budgets and goals), Wealth and Ask;
Insights opens from the Wallet top bar ("Analytics") and from the insight tiles; Transactions from "View all"; Settings
from the profile button at the top start; the add action stays one tap from Wallet. Every route added by STORY-199..205
and 207 is reachable, the temporary Settings -> "Your money" rows are removed, back navigation returns to the tab, and
each tab keeps its scroll position. Deep links used by notifications (bills reminders, capture Undo) still land on the
right screen.
Unhappy path: rotating or process death restores the selected tab.

### REQ-UI-082 - Wallet: hero, quick actions, accounts, coming up, recent, tiles
**Given** data for November 2026 (month start day 1) **When** Wallet opens **Then** it shows: a period pill ("Nov 1 -
Nov 30", tap to pick another period); Spent this period and "X today" using the D2 rule (same numbers as Insights); an
account picker (All accounts or one account, which filters Spent and the balance); Balance = sum of counted account
balances from STORY-205 with "N accounts without a balance" when some are unset, or "Set your balances" when none are;
"You owe X" when debts exist; quick actions Add, Say it (opens Add with the mic, STORY-200), Receipt (camera, STORY-175),
Ask; an accounts carousel (balance or "Set balance"); the Needs-review banner; "Coming up" (installments, subscriptions,
bills and payday in the next 7 days); recent entries with category emoji; and a grid of insight tiles (top spending
donut, bills due, budget, money in vs out this period, income kept %, top goal) each opening its screen.
Unhappy path: a first run with no data shows the "Get started" cards (turn on capture, set balances, add a bill) instead
of empty tiles.

### REQ-AN-175 - "Don't count in totals"
**Given** an entry **When** the user switches on "Don't count in totals" in Edit **Then** `expense.excludedFromTotals = 1`
and the entry is left out of spending and income everywhere (Wallet, Insights, Ask, budgets) while still shown in lists
with a marker and still moving its account balance (STORY-205). Switching it off restores it. The analytics parity file
keeps passing (no fixture entry has the flag); a new unit test covers the flag.

## UX states (UI stories only)
Tabs, Wallet with data / first run / one account selected, tiles loading and empty, RTL, dark, Arabic-Indic digits,
font scale 1.5 (tiles wrap, no clipped amounts). Strings in `strings_wallet.xml`.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (no new permissions; FLAG_SECURE rules unchanged).

## Eval impact
None.

## Design notes (architect)
`MainActivity` NavHost reorganised into tab graphs; `ui/wallet/*` replaces `ui/home/*` (keep tests by moving them, never
deleting assertions). The D2 filter gains `excludedFromTotals = 0` in `AnalyticsCalculator` and the DAO total queries.

## Dependencies
STORY-199, STORY-200, STORY-202, STORY-204, STORY-205

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M+ (navigation ~200, wallet ~450, tests ~350)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 4 |
