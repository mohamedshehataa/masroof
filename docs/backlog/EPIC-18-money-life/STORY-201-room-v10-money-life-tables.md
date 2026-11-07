---
id: STORY-201
epic: EPIC-18
title: Updating the app adds the money-life tables and keeps every existing row
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: []
reqs: [REQ-AND-145, REQ-AND-146, REQ-AND-147]
implements: ["ADR-0009 D3 migration rules (additive version; the contract step moves to v11)", "EPIC-18 foundation for STORY-181 and STORY-202..205, 207"]
owner_agent: android-engineer
---

# STORY-201 - Updating the app adds the money-life tables and keeps every existing row

## Goal
One additive Room migration (v9 -> v10) creates every table EPIC-18 needs, plus the `budget` table STORY-181 was
waiting for, so the feature stories can run in parallel without fighting over schema versions. No UI.

## Requirements and acceptance criteria
### REQ-AND-145 - The v9 -> v10 migration is additive and keeps every row
**Given** a v9 database with expenses, sources, receipts, review items, learned templates and captured rows **When** the
app updates **Then** `MIGRATION_9_10` (hand-written SQL) runs after the existing pre-migration safety copy, every
existing row and column is unchanged, and these are created (money `INTEGER` minor units + `TEXT` ISO currency, times
UTC epoch millis, enums as `TEXT`, booleans `INTEGER 0/1`, foreign keys with indices):
- `category.emoji TEXT NULL` (new column, existing rows NULL).
- `expense.excludedFromTotals INTEGER NOT NULL DEFAULT 0` ("Don't count in totals"; analytics use it in STORY-206).
- `account` new columns, all nullable: `creditLimitMinor`, `statementDay` (1..31), `dueDay` (1..31), `emoji`.
- `account_balance` (id, accountId FK account CASCADE, balanceMinor, currency, atMillis, source `MANUAL`|`SMS`, createdAtMillis):
  a known balance at a moment; the current balance is the latest one plus entry flows after it (STORY-205).
- `budget` (id, categoryId NULL FK category SET NULL - NULL means all spending, period `MONTHLY`|`WEEKLY`, amountMinor,
  currency, rollover, alertThresholds TEXT default `50,80,100`, createdAtMillis, updatedAtMillis) - the shape STORY-181
  specifies.
- `installment_plan` (id, title, provider, categoryId NULL, accountId NULL, currency, purchasePriceMinor NULL,
  downPaymentMinor, monthlyAmountMinor, paymentCount, firstDueAtMillis, reminderDaysBefore, status
  `ACTIVE`|`COMPLETED`|`ARCHIVED`, notes NULL, createdAtMillis, completedAtMillis NULL, payoutAtMillis NULL,
  payoutAmountMinor NULL, payoutReceivedAtMillis NULL). Provider is free text from a fixed suggestion list (valU,
  Halan, Souhoola, Gam3eya, Money Fellows, Contact, Aman, Forsa, Sympl, bank card, store, none); the payout columns are
  for a gam3eya (rotating savings circle).
- `installment_payment` (id, planId FK CASCADE, seq, dueAtMillis, amountMinor, paidAtMillis NULL, expenseId NULL FK
  expense SET NULL; unique (planId, seq)).
- `subscription` (id, kind `SUBSCRIPTION`|`BILL`|`INCOME`, name, amountMinor, currency, cadence
  `WEEKLY`|`MONTHLY`|`QUARTERLY`|`YEARLY`, nextRenewalAtMillis, categoryId NULL, accountId NULL, merchantKey NULL,
  reminderDaysBefore, active, notes NULL, createdAtMillis). `INCOME` rows are recurring income (salary, a certificate
  payout) and drive "days to payday".
- `savings_goal` (id, name, emoji NULL, targetMinor, currency, targetAtMillis NULL, sortOrder, createdAtMillis,
  completedAtMillis NULL, archivedAtMillis NULL).
- `goal_movement` (id, goalId FK CASCADE, amountMinor signed - positive adds, negative takes out, atMillis, note NULL).
- `asset` (id, kind `GOLD`|`SILVER`|`CERTIFICATE`|`FUND`|`INVESTMENT`|`PROPERTY`|`VEHICLE`|`BUSINESS`|`OTHER`, name,
  currency, valueMinor NULL - manual value, karat NULL - gold/silver purity, principalMinor NULL, annualRateBp NULL -
  certificate or fund rate, startAtMillis NULL, maturityAtMillis NULL, goalId NULL FK savings_goal SET NULL,
  hiddenFromTotals, archived, createdAtMillis, updatedAtMillis). Gold/silver quantity comes from `asset_lot`.
- `asset_lot` (id, assetId FK asset CASCADE, side `BUY`|`SELL`, quantityMilli - grams or units x 1000,
  totalMinor, currency, atMillis, expenseId NULL FK expense SET NULL, note NULL): gold bought in pieces, sold later.
- `metal_price` (id, metal `GOLD`|`SILVER`, karat, pricePerGramMinor, currency, atMillis): prices the user types.
- `merchant_rule` (id, merchantKey unique - normalised, digit-free, categoryId FK category CASCADE, createdAtMillis):
  "always file this merchant under this category".
- `debt` (id, name, kind `LOAN`|`CREDIT_CARD`|`PERSON`|`OTHER`, direction `I_OWE`|`OWED_TO_ME`, amountMinor, currency,
  dueAtMillis NULL, settledAtMillis NULL, notes NULL, createdAtMillis).
- `net_worth_snapshot` (id, cairoDate TEXT `yyyy-MM-dd` unique, assetsMinor, debtsMinor, netMinor, currency,
  breakdown TEXT - `KIND=minor;KIND=minor`, createdAtMillis).
Each table gets an `@Entity` and a DAO with insert/update/delete/observe-all/observe-by-id; no repository or UI.
Unhappy path: **Given** the migration throws (simulated) **Then** the existing STORY-168 startup path shows the blocked
screen and the safety copy is intact; no destructive fallback is added.

### REQ-AND-146 - Migration tests from every shipped version and the exported schema
**Given** `MigrationTestHelper` **When** databases are created at every shipped version still supported (2..9) and
migrated to 10 **Then** validation passes against the exported `10.json`, seeded rows survive, and inserting one row per
new table and reading it back works (FKs enforced: deleting a plan deletes its payments; deleting an expense nulls
`installment_payment.expenseId`). `schemas/.../10.json` is committed; 1..9 JSONs are unchanged (byte-identical).
Unhappy path: **Given** an insert of a second `installment_payment` with the same (planId, seq) **Then** it fails.

### REQ-AND-147 - Erase all data and the backup list cover the new tables
**Given** rows in every new table **When** Settings -> "Erase all data" completes **Then** every new table is empty (and
the existing erase guarantees hold). The STORY-183 spec's file list names the new tables (doc change in that story).
Unhappy path: none beyond REQ-AND-145.

## UX states (UI stories only)
None.

## Contract impact
None.

## Data / migration impact
Room v9 -> v10 additive (above). ADR-0009 D3's contract step (STORY-198) becomes v10 -> v11; record in DECISIONS.

## Security / privacy / LLM-safety impact
No message text in any new table. Account numbers are never stored (only `account.last4`, already there).

## Eval impact
None.

## Design notes (architect)
Follow `MIGRATION_8_9` / `Migration9.kt` style: one Kotlin file `Migration10.kt`, SQL matching Room's generated schema
exactly (column order, affinity, NOT NULL, defaults, index names) so validation passes.

## Dependencies
None.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M+ (14 tables and 3 altered ones, mechanical: entities/DAOs ~400, migration ~160, tests ~300)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 (Say teardown), speed mode |
