---
id: STORY-202
epic: EPIC-18
title: Bills - track installments (valU, Halan, ...) and a gam3eya, with reminders before each payment
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-201]
reqs: [REQ-EXP-053, REQ-EXP-054, REQ-EXP-055, REQ-AN-166, REQ-AND-148]
implements: ["Say teardown items 6, 18, 19 (Installments tab, provider list, gam3eya)", "ADR-0009 D1 (local, no network)"]
owner_agent: android-engineer
---

# STORY-202 - Bills - track installments (valU, Halan, ...) and a gam3eya, with reminders before each payment

## Goal
Mohamed sees every installment plan in one place: what he pays each month, what is left, what is due next, and when
the monthly total drops. He gets a reminder the day before a payment. A gam3eya (rotating savings circle) is tracked the
same way, including the month he collects. Tables come from STORY-201; this story adds the repository, screens and the
daily reminder worker that STORY-203 will extend.

## Requirements and acceptance criteria
### REQ-EXP-053 - Create an installment plan and its schedule
**Given** Bills -> Add installment **When** the user picks provider valU (list: valU, Halan, Souhoola, Gam3eya, Money
Fellows, Contact, Aman, Forsa, Sympl, Bank / credit card, Another store, No provider), types "iPhone 17 Pro" (or taps a
chip: Phone, Car, Laptop, Furniture, AC, Fridge, TV, Personal loan), monthly `3,250` EGP, 18 months, first payment
2026-03-05 **Then** one `installment_plan` and 18 `installment_payment` rows are stored (`amountMinor = 325000`,
due dates the 5th of each month at Cairo noon, seq 1..18), with optional purchase price, down payment, category
(default from the chip, else "Household items"), account and reminder (default 1 day before).
Duration can be given as an end date instead; the other value is computed (end date 2027-08-05 -> 18 payments).
A due day of 31 clamps to the month's last day without drifting (31 Jan, 28 Feb, 31 Mar).
**Given** the first payment is in the past **Then** the form asks "How many are already paid?" (default: those due
before today) and marks them paid without creating entries.
Unhappy path: amount empty/0/negative/over 1,000,000,000,000 minor units, 0 or more than 120 payments, end date before
the first payment -> field errors, nothing stored.

### REQ-EXP-054 - Record a payment, link it, complete a plan
**Given** a plan with 7 of 18 paid **When** the user taps "Record payment (8/18)" **Then** payment 8 is marked paid now
and the user chooses: link an existing entry (candidates: not-deleted EXPENSE entries with the exact amount within 5
days of the due date and not linked to another payment) or create a new EXPENSE entry (amount, plan category and
account, merchant = provider). Never both; one entry links to at most one payment. Undo restores the previous state.
**When** "Mark as complete" is confirmed **Then** status COMPLETED, remaining payments are closed without entries, and
the plan leaves the active totals. Delete asks first and removes the plan and its payments; linked entries stay.
Unhappy path: **Given** all payments paid **Then** "Record payment" is hidden and the plan shows "Completed".

### REQ-EXP-055 - Gam3eya: monthly share, members, collection month
**Given** provider Gam3eya **When** the user enters share `2,000` EGP, 10 members, collection turn 4, first payment
2026-11-01 **Then** 10 payments are scheduled and the collection is stored as `payoutAtMillis` = 2027-02-01 and
`payoutAmountMinor` = 2,000,000 (share x members, editable). Gam3eya payments recorded as new entries are type
TRANSFER (money set aside, never spending under the D2 rule) and the collection is recorded as an INCOME-side TRANSFER
entry, so neither inflates spending or income.
Unhappy path: turn outside 1..members -> field error.

### REQ-AN-166 - Bills overview numbers and calendar
**Given** active plans of 3,250 + 1,450 + 2,100 EGP with remaining payments **When** Bills opens **Then** it shows the
monthly total for the current Cairo month (sum of unpaid payments due this month plus those already paid this month),
active count, remaining total (sum of unpaid payments), "drops to X in N months" (the first future month whose total is
lower, with X and N), a "Coming up" list of payments due in the next 14 days (gam3eya collections shown as incoming),
and each plan with "k of N paid", a progress bar and the estimated completion month. A calendar view marks due days per
month. Non-EGP plans are listed but excluded from EGP totals with the standard hint. All maths integer.
Unhappy path: **Given** no plans **Then** an empty state with one example card and "Add installment".

### REQ-AND-148 - A reminder before each payment, from one daily worker
**Given** a payment due 2026-11-09 with "1 day before" **When** the daily WorkManager job runs on 2026-11-08 (once a day,
around 09:00 Cairo, no exact alarm) **Then** one local notification "valU - iPhone 17 Pro: 3,250 EGP due tomorrow" is
posted (Arabic in Arabic); tapping it opens the plan. Each payment is reminded once (remembered across runs and
reboots). The worker exposes a small `ReminderSource` interface so STORY-203 can add subscriptions without editing it.
Unhappy path: **Given** POST_NOTIFICATIONS is denied **Then** no crash; Bills shows a hint to allow notifications.
**Given** the payment is already paid **Then** no reminder.

## UX states (UI stories only)
Route `bills` (Bills tab in STORY-206; until then reachable from Settings -> "Your money" -> Bills). Empty, list,
plan detail (hero with amount and "payment due in N days", purchase price / monthly / remaining, progress, details,
actions), add form, calendar, record-payment sheet with link candidates. RTL, dark, Arabic-Indic digits. Strings in
`strings_bills.xml` (en + ar).

## Contract impact
None.

## Data / migration impact
None (tables from STORY-201).

## Security / privacy / LLM-safety impact
Notifications show provider, plan title and amount only; no account numbers. No network.

## Eval impact
None.

## Design notes (architect)
`data/bills/InstallmentRepository` (schedule generation and totals as pure functions, unit-tested), `ui/bills/*`,
`work/BillsReminderWorker` + `ReminderSource`. Linking an entry writes `installment_payment.expenseId` in the same Room
transaction as `paidAtMillis`.

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
- [x] Size: M+ (repository ~250, screens ~450, worker ~120, tests ~450)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 2, from the Say APK and the real app |
