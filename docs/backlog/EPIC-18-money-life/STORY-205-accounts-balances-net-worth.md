---
id: STORY-205
epic: EPIC-18
title: Wealth - accounts with honest balances, credit cards, debts, things you own and a net worth chart
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-201]
reqs: [REQ-EXP-057, REQ-AN-168, REQ-EXP-058, REQ-EXP-059, REQ-AN-169]
implements: ["Say teardown items 5, 17, 20 (Wealth tab, account types, holdings) and its defects list (fake negative balances, -480% change, debt shown as 0)", "Accounts half of STORY-196 REQ-EXP-051 intent (members stay in STORY-196)"]
owner_agent: android-engineer
---

# STORY-205 - Wealth - accounts with honest balances, credit cards, debts, things you own and a net worth chart

## Goal
Mohamed sees one honest number for what the household is worth: bank and wallet balances he has confirmed, cash, what
he owns (an apartment, the car, a certificate, a fund), minus what he owes (credit cards, loans, money owed to people,
remaining installments). Unlike Say, an account whose balance was never set is not shown as a negative number, and the
yearly change never shows a meaningless percent. Gold and silver by the gram come in STORY-209.

## Requirements and acceptance criteria
### REQ-EXP-057 - Accounts screen: add, edit, archive, set balance
**Given** Wealth -> Accounts **When** the user adds "CIB debit" (kind: Debit card / bank, Credit card, Cash, E-wallet),
institution, last four digits (exactly 4, optional for cash), currency, emoji **Then** an `account` row is stored and SMS
capture keeps matching by last four as today. **When** "Set balance" `12,500.50` at now (or a chosen Cairo date/time)
**Then** an `account_balance` row (MANUAL) is stored. Archive hides an account from pickers and totals but keeps its
history. Edit changes label, institution, last four, emoji and credit-card fields; currency is fixed after creation.
Unhappy path: last four not 4 digits, empty label, a second active account with the same kind and last four -> field
errors, nothing stored. A full card or account number is never accepted (more than 4 digits is rejected).

### REQ-AN-168 - Balance = last confirmed balance plus the money that moved after it
**Given** CIB debit set to 10,000 EGP at 2026-11-01 10:00 Cairo, then entries on that account after it: EXPENSE 1,200,
REFUND 200, INCOME 5,000, CASH_WITHDRAWAL 1,000, TRANSFER (sent) 500, a soft-deleted EXPENSE 900, an entry before the
check 700 **Then** the balance is 10,000 - 1,200 + 200 + 5,000 - 1,000 - 500 = 12,500 EGP (soft-deleted and pre-check
entries ignored; CONFIRMED and PENDING_REVIEW count; "don't count in totals" does not change balances because money
still moved; only entries in the account's currency). For a credit card the same flows give the outstanding amount
(spending raises it, refunds and payments lower it).
**Given** an account with no balance check **Then** it shows "Set balance" instead of a number and is left out of net
worth with the hint "N accounts without a balance are not counted". Never a computed negative from zero.

### REQ-EXP-058 - Credit card: limit, statement day, due day
**Given** a credit card with limit 50,000, statement day 20, due day 5 and outstanding 18,300 **Then** the card shows
outstanding 18,300, available 31,700, spent since the last statement (entries after the last statement day 20, Cairo),
and "next payment due 5 Dec" (the first due day after the latest statement day). The outstanding amount counts as
debt in net worth. Through STORY-202's `ReminderSource` (if merged; otherwise noted as a limitation) the due date gets a
reminder 3 days before.
Unhappy path: statement or due day outside 1..31, limit <= 0 -> field error. Days beyond the month length clamp to
the last day.

### REQ-EXP-059 - Debts and things you own (without gold)
**Given** Wealth -> Add **When** the user adds a debt ("Loan from Ahmed", I owe, 20,000, due 2027-01-01) or money owed to
them, or an asset of kind Property, Vehicle, Business, Other (manual value with "last updated"), Certificate (principal,
annual rate in basis points, start, maturity; value = principal plus simple interest accrued so far only when interest
is paid at maturity, integer maths, half away from zero), or Fund (principal and an expected yearly rate; value grows
pro rata by days, simple, integer) **Then** it is stored (`debt` / `asset`) and listed under Debt / Assets with its
value. Settling a debt sets `settledAtMillis` and removes it from totals. "Hide from totals" keeps an asset listed but
out of net worth. Unpaid `installment_payment` rows (STORY-201 table) count as debt "Installments left" automatically.
Unhappy path: empty name, value or principal <= 0, rate outside 0..100,000 bp, maturity before start -> field error.

### REQ-AN-169 - Net worth, its breakdown and a daily history chart
**Given** the data above **Then** Wealth shows Net worth / Assets / Debt tabs: net worth = counted account balances
(a negative bank balance counts as debt) + asset values + money owed to me - (open debts + credit-card outstanding +
installments left); the Assets tab shows a stacked bar by kind (spendable accounts, certificates, funds, property,
vehicles, other) with integer-basis-point shares; Debt lists each debt. A `net_worth_snapshot` row is upserted once per
Cairo day (on app open and by a daily WorkManager job) and the chart shows 1M / 3M / 1Y / All from those rows; tapping a
point shows its date and value. "Change this year" shows the absolute change from the first snapshot of the Cairo year;
a percent is shown only when that first value is greater than 0 and the current value is not negative (no "-480%").
All amounts EGP; other-currency items are listed in their currency and excluded with the standard hint.
Unhappy path: **Given** no snapshot yet **Then** the chart shows "History starts today" with a single point.
**Given** nothing added **Then** an empty state "Add an account balance or something you own".

## UX states (UI stories only)
Route `wealth` (Wealth tab in STORY-206; until then Settings -> "Your money" -> Wealth) and `accounts`. Empty states,
lists, add/edit forms, set-balance sheet, credit-card detail, chart with range chips, tooltips. RTL, dark, Arabic-Indic
digits. Strings in `strings_wealth.xml` (en + ar).

## Contract impact
None.

## Data / migration impact
None (tables and columns from STORY-201).

## Security / privacy / LLM-safety impact
Only a last four is stored for cards. No network. Balances are never logged above DEBUG.

## Eval impact
None.

## Design notes (architect)
Pure `BalanceMath`, `NetWorthMath`, `CardCycle` and `AssetValuation` (certificate interest, fund growth) with
table-driven tests incl. leap years and month ends; `data/wealth/*` repositories; `work/NetWorthSnapshotWorker`;
`ui/wealth/*`, `ui/accounts/*`. Reuse `ui/insights/Charts.kt` for the line chart if it fits, else a Canvas line chart.

## Dependencies
STORY-201 (STORY-202 only for the card due-date reminder; optional)

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M+ (maths ~250, repositories ~200, screens ~550, worker ~80, tests ~500)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 2, from the Say APK and the real app |
