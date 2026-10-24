---
id: STORY-004
epic: EPIC-01
title: Core schema (household, members, auth, devices, accounts, categories, merchants) with seeds
phase: 1
status: IN_PROGRESS
size: M
modules: [backend]
depends_on: [STORY-002, STORY-142]
reqs: [REQ-OPS-040, REQ-OPS-041, REQ-OPS-042, REQ-OPS-046, REQ-OPS-047]
implements: ["PROMPT.md §8 (data model, default categories, seeds)", "PROMPT.md §14 Phase 1 (Flyway schema + seeds)", "CLAUDE.md money invariant (ArchUnit + semgrep; STORY-002 review #9, verify X17)", "STORY-002 review #14 (household DDL hardening)"]
risk_tier: high
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

### REQ-OPS-046 — Money rule: camelCase-boundary names and no floating point in any domain code unit
Source: STORY-002 review finding 9 and verify finding/X17. Today a `float` constructor parameter in domain passes the build (`eg.masroof.domain.Expense` with `public Expense(float egp)` -> `checkFast` + `archTest` rc 0). Name matching is unanchored, so `tip` matches in `multiplier`, `vat` in `activate` and `fee` in `coffee`. This rule must be closed in this story, before the first money-bearing domain type lands (STORY-004/005).

**(a) Shared name vocabulary on token boundaries.**
- **Given** the money-name vocabulary `amount`, `price`, `total`, `balance`, `fee`, `vat`, `tip`, `share`, `rateEgp`/`rate_egp` (the union of ArchUnit rule 3 and the semgrep `masroof-money-not-floating-{java,kotlin,ts}` rules today) **When** a member name is classified **Then** it is money-named only when a vocabulary word is a whole camelCase, snake_case or UPPER_SNAKE token (case-insensitive per token).
  - Must match: `amount`, `amountMinor`, `totalAmount`, `vatEgp`, `tip`, `serviceFee`, `getBalance`, `shareOf`, `rateEgp`, `rate_egp`, `TOTAL_FEE`.
  - Must not match: `multiplier`, `activate`, `coffee`, `feedback`, `stipend`, `shareholder`, `totality`.
  - Known limitation: single-token compounds such as `subtotal` are not matched. In domain classes they are still caught by (b).
- **And** ArchUnit rule 3 and all three semgrep money rules in `.semgrep/masroof.yml` use the same vocabulary and boundary semantics. A check that runs in `checkFast` or `make check` fails when the two diverge. The architect chooses the mechanism, for example one source both read or a test that compares them.
- **And** each of the three semgrep money rules has a `semgrep --test` fixture with at least one `ruleid:` case (`double serviceFee`) and one `ok:` case (`double multiplier`). The fixtures run wherever `make security` runs semgrep.

**(b) Floating-point ban on all constructors and methods in domain.**
- **Given** `archTest` runs **Then** `float`, `double`, `Float` and `Double` are rejected as constructor parameter types, method parameter types and method return types, whatever the member's name, in the scope the architect picks at stage 3:
  - (i) every class in `..domain..` that declares a money-named field, or
  - (ii) every class in `..domain..`.
- The chosen scope is recorded in Design notes and in the rule's `.as(...)` description.
- `..application..` stays name-scoped. ArchUnit does not see parameter names, so parameter-name coverage remains semgrep's job.

**Probes.** Each probe asserts that `MONEY_RULE` fails with a message that names the fixture member, or, for the guards, that it passes:
- P1: a `..domain..` fixture with `private final long amountMinor;` and constructor `(float egp)` -> violation naming the constructor.
- P2: the same fixture with method `double ratio()` -> violation naming `ratio()`.
- P3: the same fixture with method `void scale(double factor)` -> violation.
- P4 (scope (ii) only): the X17 plant (only `public Expense(float egp)`, no fields) -> violation. Under scope (i) this plant passes, and Design notes record why that is accepted.
- P5, false-positive guards in `..application..`: field `double multiplier`, method `double activate()`, field `double coffee` -> no `MONEY_RULE` violation.
- P6, true positives in `..application..`: field `double vatAmount`, method `Double serviceFee()`, field `double TOTAL_FEE` -> violation.
- The existing `BadMoney`, `BadParam` and `BadReturn` probes keep failing as before.

Unhappy path: **Given** the constructor sub-rule is mis-wired or dropped **Then** P1 fails `test` (checkFast). **Given** the boundary regex regresses to substring matching **Then** P5 fails.
Unhappy path: **Given** a domain class now declares a field (for example `Money.amountMinor`) **Then** `NO_FIELD_INJECTION` no longer uses `.allowEmptyShould(true)` and `archTest` still passes. This is the verify NIT on `ArchitectureRulesTest.java:26`.

### REQ-OPS-047 — Household DDL hardening (V1 follow-up, fix-forward)
Source: STORY-002 review finding 14. V1 accepts `egp`, `Mars/Phobos` and an empty `name`, and nothing maintains `updated_at`. `V1__baseline.sql` is immutable, so these fixes ship in a migration with version ≥ 2. The architect decides whether that is part of `V2__core_schema.sql` or a separate version.
- **Given** migrations have run **When** a `household` row is inserted or updated with `base_currency` `'egp'`, `'EG'` or `'E1P'` **Then** a CHECK constraint enforcing `^[A-Z]{3}$` rejects it. `'EGP'` and `'USD'` are accepted.
- **Given** the same **When** `household.name` is `''` or `'   '` (whitespace only) **Then** a CHECK constraint rejects it. `'Home Expenses'` is accepted.
- **Given** a `household` row, or a row in any table this story creates with `updated_at`, **When** it is updated in a later transaction **Then** `updated_at` is strictly greater than its previous value and `created_at` is unchanged. Example: `household.name` changes from `Home Expenses` to `Our Home`. The architect chooses the mechanism:
  - DB trigger: any `UPDATE` qualifies.
  - Application: the persistence adapter sets it from the injected `Clock`, and the test uses a fixed clock.
  - Design notes name the chosen path, so test-designer knows which path to exercise.
- **Given** the domain creates a household (REQ-OPS-041 creation path, demo seed of REQ-OPS-042) **When** `timezone` is `Mars/Phobos` or `''` **Then** the domain rejects it with a validation error naming the field `timezone`, and no `household` row is written. `Africa/Cairo` is accepted. Validation uses `java.time.ZoneId`, not a DB CHECK, because Postgres cannot validate zone names in an immutable CHECK.

Unhappy path: **Given** a database at version 1 that already holds a household row violating a new constraint (`base_currency = 'egp'`) **When** the hardening migration runs **Then** it fails, `flyway_schema_history` stays at the prior version (transactional DDL) and the row is not rewritten. No release has shipped, so no backfill is specified.
Unhappy path: **Given** an update rejected by a CHECK (name set to `''`) **Then** the row, including `updated_at`, is unchanged.
Migration test: migrate from empty, and from a V1 snapshot holding the valid row `('Home Expenses','EGP','Africa/Cairo')`. Both succeed and the row is unchanged.

## UX states (UI stories only)
N/A.

## Contract impact
None (schema only; `Category`, `PaymentAccount`, `Member` schemas in v0 already match these columns).

## Data / migration impact
New migrations `V2__core_schema.sql`, `V3__seed_defaults.sql` (expand only). Demo data is application-level and idempotent.
REQ-OPS-047 adds `household` CHECK constraints (`base_currency ~ '^[A-Z]{3}$'`, non-blank `name`). If the architect picks the trigger path for `updated_at`, it also adds the trigger. All of this ships in a version ≥ 2 migration and never as an edit to V1. These constraints tighten an existing table. That is acceptable without expand/contract because no release has shipped and no user data exists. A V1 database holding a violating row fails the migration loudly instead of being rewritten.

## Security / privacy / LLM-safety impact
`app_user.password_hash` is BCrypt (cost ≥ 10); `refresh_token.token_hash` and `device.token_hash` store SHA-256 hashes only, never the token. Demo password lives in `.env.example` as a placeholder and is forbidden when `SPRING_PROFILES_ACTIVE=prod`.
Money integrity (REQ-OPS-046) closes a known gap in the money invariant: floating point could reach domain constructors and methods unflagged. It touches `.semgrep/masroof.yml` (REQ-SEC-019 rules) in addition to backend tests. No LLM surface.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3. Open decisions this story hands to the architect (requirements only, no design here):
- REQ-OPS-046: the domain floating-point scope, (i) classes with a money-named field or (ii) all of `..domain..`, and the mechanism that keeps the ArchUnit and semgrep vocabularies in sync.
- REQ-OPS-047: whether the household hardening joins `V2__core_schema.sql` or gets its own version, and whether `updated_at` is maintained by trigger or by the application from `Clock`.

## Dependencies
- STORY-002 (DONE, f2053b2): backend module, V1 `household`, ArchUnit rule 3 and `MoneyRuleProbeTest`, which REQ-OPS-046 extends.
- STORY-142: the `make up` stack for REQ-OPS-042.
- Platform baseline: the backend runs on **Spring Boot 3.5.16** (past OSS EOL, CVE fixes overridden above the BOM) until STORY-143 moves it to **4.1.x** per ADR-0003 (`docs/adr/0003-spring-boot-3-5-after-oss-eol.md`). STORY-004 does not depend on STORY-143. If it is implemented first, it targets 3.5.16 and must not add Boot-3-only APIs that the 4.1 move would have to rewrite, for example Jackson 2 `com.fasterxml` imports in domain or application (ArchUnit already bans framework imports in domain).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (SQL + entities; seed rows are data). REQ-OPS-046 adds about 130 lines (rule, fixtures, probes; tests budgeted at about 2x per LESSONS) and REQ-OPS-047 adds about 80. Together they put the story near the limit. Split trigger: if the stage-3 estimate exceeds 400 hand-written lines, REQ-OPS-046 moves unchanged to a new story that STORY-005 depends on. It shares no files with REQ-OPS-040..042 or REQ-OPS-047.
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — STORY-142 added to depends_on (REQ-OPS-042 runs on make up) after the STORY-002 split |
| 2026-10-24 | 1-2 spec amend | product-analyst | READY — STORY-002 review #9/#14 and verify X17 follow-ups added as REQ-OPS-046 (money rule) and REQ-OPS-047 (household DDL); Boot 3.5.16 → 4.1.x (ADR-0003) noted; size near limit with a pre-cut split boundary |
