---
id: STORY-203
epic: EPIC-18
title: Bills - subscriptions and recurring income with totals, renewals, reminders and days to payday
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-202]
reqs: [REQ-EXP-062, REQ-AN-170, REQ-AN-171, REQ-AN-172]
implements: ["Say teardown items 7 and 18 (Subscriptions, recurring income, certificates paying interest)", "A first, simple slice of STORY-190 (recurring detection); STORY-190 keeps missed-occurrence and anomaly flags"]
owner_agent: android-engineer
---

# STORY-203 - Bills - subscriptions and recurring income with totals, renewals, reminders and days to payday

## Goal
Next to installments (STORY-202), the Bills screen lists subscriptions and regular bills (Netflix, Shahid, WE internet,
the gym) with what they cost per month and per year, the next renewal and a reminder the day before; and recurring
income (salary, a certificate's monthly interest) so the app knows how many days are left to payday.

## Requirements and acceptance criteria
### REQ-EXP-062 - Add, edit, pause and delete a subscription, bill or recurring income
**Given** Bills -> Add -> Subscription **When** the user picks a service from a built-in list (Netflix, Shahid VIP,
Watch iT, OSN+, Spotify, Anghami, YouTube Premium, iCloud, Google One, ChatGPT, WE, Vodafone, Orange, e&, gym, other -
names only, a generic icon or emoji per kind, no brand logos) or types a name, an amount and currency, a cadence (weekly,
monthly, quarterly, yearly) and the next renewal date **Then** a `subscription` row (kind SUBSCRIPTION or BILL) is
stored with category (Subscriptions, or Bills for telecom/internet), optional account and reminder (default 1 day).
**When** Add -> Recurring income "Salary" 20,000 monthly on the 25th, deposit account CIB **Then** a kind INCOME row.
Edit changes every field; Pause sets `active = 0` (out of totals and reminders); Delete asks first.
Unhappy path: empty name, amount empty/0/negative/over the cap, renewal date missing -> field errors.

### REQ-AN-170 - Per month / per year totals, list, calendar
**Given** Netflix 199 monthly, iCloud 49 monthly, a gym 6,000 yearly and a 75 weekly bill, all active **Then** the
subscriptions card shows per year = 199x12 + 49x12 + 6,000 + 75x52 = 12,876 EGP and per month = per year / 12 rounded half
away from zero = 1,073 EGP, the active count, and a list sorted by next renewal ("Oct 2", "in 3 days"); a toggle switches
Mo / Yr. A calendar shows renewals per day for the chosen month. Income rows are listed separately under "Coming in".
Non-EGP rows are excluded from EGP totals with the standard hint.

### REQ-AN-171 - Renewals roll forward and match the real payment
**Given** Netflix next renewal 2026-10-31 monthly **When** that day passes **Then** the next renewal becomes 2026-11-30 and
then 2026-12-31 (the anchor day is kept; short months clamp). **Given** a captured or manual EXPENSE whose merchant key
equals the subscription's `merchantKey` (set from the first matched entry or typed) and whose amount is within 10% and
date within 3 days of the renewal **Then** that renewal is marked paid ("Paid Oct 2"), the date advances, and if the
amount differs it shows "Netflix went up from 199 to 229" once and updates the amount. Recurring income matches INCOME
entries the same way. Reminders reuse STORY-202's daily worker through a new `ReminderSource` (no edits to the worker
beyond registering the source).
Unhappy path: two candidate entries -> the closer date wins; an entry is never matched to two renewals.

### REQ-AN-172 - Days to payday, and suggestions from history
**Given** an active INCOME row "Salary" next on 2026-11-25 and today 2026-11-08 Cairo **Then** "17 days to payday" is
available to Home, Ask (STORY-199's payday answer uses it when present, else the month start day) and the Bills screen.
**Given** the last 120 days contain at least 3 EXPENSE entries of one merchant key spaced 25-35 days apart with amounts
within 10% and no subscription for that key **Then** a suggestion "Looks like a monthly payment: Netflix about 229 -
Add / Not a subscription" appears; Add pre-fills the form; dismissing remembers the key (DataStore) so it never returns.
Unhappy path: fewer than 3 occurrences or irregular spacing -> no suggestion.

## UX states (UI stories only)
Bills screen gains Subscriptions and Coming in sections next to Installments (one screen, sections or chips), detail,
add/edit forms, calendar, suggestion cards. RTL, dark, Arabic-Indic digits. Strings in `strings_subscriptions.xml`.

## Contract impact
None.

## Data / migration impact
None (`subscription` from STORY-201; dismissed suggestion keys in DataStore).

## Security / privacy / LLM-safety impact
No network; no brand logos bundled; notifications show name and amount only.

## Eval impact
None; rule tests with fixture series for matching and suggestions.

## Design notes (architect)
Pure `RenewalMath` (anchor-day roll forward, totals) and `SubscriptionMatcher` with table-driven tests; extend
`data/bills`, `ui/bills`, register a `ReminderSource`.

## Dependencies
STORY-202

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M (maths/matching ~250, screens ~350, tests ~400)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 3 |
