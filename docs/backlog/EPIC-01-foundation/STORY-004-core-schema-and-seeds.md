---
id: STORY-004
epic: EPIC-01
title: Core schema (household, members, auth, devices, accounts, categories, merchants) with seeds
phase: 1
status: READY
size: M
modules: [backend]
depends_on: [STORY-002]
reqs: [REQ-OPS-040, REQ-OPS-041, REQ-OPS-042]
implements: ["PROMPT.md §8 (data model, default categories, seeds)", "PROMPT.md §14 Phase 1 (Flyway schema + seeds)"]
owner_agent: backend-engineer
---

# STORY-004 — Core schema with seeds

## Goal
The master-data tables of PROMPT §8 exist as a Flyway migration with the bilingual default categories and Egyptian institutions seeded, so auth and CRUD stories have a schema to build on.

## Requirements and acceptance criteria
### REQ-OPS-040 — Core tables migration
**Given** a database at Flyway version 1 **When** the backend starts **Then** `V2__core_schema.sql` applies and creates `member`, `app_user`, `refresh_token`, `device`, `payment_account`, `category`, `merchant`, `merchant_rule` with: `uuid` primary keys, `created_at`/`updated_at timestamptz` default `now()`, `deleted_at timestamptz null` on `member`, `device`, `payment_account`, `category`; FKs to `household`; enums as `varchar` + `CHECK` (`member.role IN ('ADMIN','MEMBER')`, `device.platform IN ('ANDROID','IOS_SHORTCUT','WEB')`, `payment_account.type IN ('CARD','BANK','WALLET','CASH')`); `member.phone_e164` unique per household; `payment_account.last4 CHAR(4)` with `CHECK (last4 ~ '^[0-9]{4}$')`; `merchant.aliases text[]`, `merchant_rule.tags text[]`.
Unhappy path: **Given** an insert of `payment_account.last4 = '12A4'` **Then** the CHECK constraint rejects it.
Unhappy path: **Given** the migration is applied **When** `V2__core_schema.sql` is edited **Then** `protect-files.sh` blocks the edit and the migration test (`flyway validate`) fails on checksum mismatch.

### REQ-OPS-041 — Default categories and institutions seeded
**Given** a fresh database **When** migrations finish **Then** a seed migration `V3__seed_defaults.sql` inserts a reference row set: the 21 default categories with exactly these `name_en`/`name_ar` pairs — Groceries/بقالة, Dining & Delivery/أكل برا, Fuel/بنزين, Transport/مواصلات, Car/عربية, Bills & Utilities/فواتير, Rent/Housing/سكن, Home & Furniture/بيت وعفش, Health/صحة, Baby/بيبي, Personal Care/عناية شخصية, Clothing/لبس, Shopping/تسوق, Subscriptions/اشتراكات, Education/تعليم, Gifts & Family/هدايا وعيلة, Charity/صدقة, Entertainment/ترفيه, Travel/سفر, Fees & Bank Charges/رسوم بنكية, Other/أخرى — each with `icon`, `color`, `sort_order`, `is_essential`; subcategories for Car (maintenance, insurance, installment) and Bills (electricity, water, gas, internet, mobile); and an `institution` reference table with the senders of §4.1 (CIB, NBE, Banque Misr, QNB Alahli, HSBC Egypt, AAIB, Banque du Caire, Faisal Islamic, ADIB, Emirates NBD Egypt, Alex Bank, InstaPay/IPN, Vodafone Cash, Orange Cash, Etisalat Cash/e& money, WE Pay, Fawry, ValU, Sympl, Contact, Telda).
**Given** a household is created **Then** its categories are copied from the reference set (per-household, editable) and `Other` cannot be archived.
Unhappy path: **Given** the seed runs twice (migration re-check) **Then** no duplicate rows (idempotent via unique keys).

### REQ-OPS-042 — Dev sample-data generator
**Given** `make up` (profile `demo`, `DEMO_SEED=true` in `.env.example`) **When** the backend starts on an empty DB **Then** a demo household "Home Expenses" with two members (Mohamed ADMIN, Mai MEMBER, deterministic ids), two app users with a documented demo password, three payment accounts and merchants `Carrefour`, `Uber`, `Netflix` are inserted by application code (not a migration).
Unhappy path: **Given** `DEMO_SEED=false` or the household already exists **Then** nothing is inserted and startup logs `demo seed skipped`.

## UX states (UI stories only)
N/A.

## Contract impact
None (schema only; `Category`, `PaymentAccount`, `Member` schemas in v0 already match these columns).

## Data / migration impact
New migrations `V2__core_schema.sql`, `V3__seed_defaults.sql` (expand only). Demo data is application-level and idempotent.

## Security / privacy / LLM-safety impact
`app_user.password_hash` is BCrypt (cost ≥ 10); `refresh_token.token_hash` and `device.token_hash` store SHA-256 hashes only, never the token. Demo password lives in `.env.example` as a placeholder and is forbidden when `SPRING_PROFILES_ACTIVE=prod`.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-002.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (SQL + entities; seed rows are data)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
