---
id: STORY-005
epic: EPIC-01
title: Ledger schema (raw messages, media, expenses, splits, budgets, jobs, audit) with migration tests
phase: 1
status: READY
size: M
modules: [backend]
depends_on: [STORY-004]
reqs: [REQ-OPS-043, REQ-OPS-044, REQ-OPS-045]
implements: ["PROMPT.md §8 (data model)", "PROMPT.md S9 (migration tests, money rule)"]
owner_agent: backend-engineer
---

# STORY-005 — Ledger schema with migration tests

## Goal
The transactional tables of PROMPT §8 exist with money stored as integer minor units and the indexes the §9 filters need, and migrations are tested from empty and from the previous snapshot.

## Requirements and acceptance criteria
### REQ-OPS-043 — Ledger tables migration
**Given** a database at version 3 **When** `V4__ledger_schema.sql` applies **Then** it creates `raw_message` (source CHECK in WHATSAPP|SMS|PUSH|IOS_SHORTCUT|UPLOAD|MANUAL, `external_id`, `sender`, `member_id`, `payload jsonb`, `text`, `media_ids uuid[]`, `received_at`, `processing_status`, `error`; unique `(source, external_id)` where `external_id` is not null), `media` (`storage_key`, `mime`, `size`, `sha256 char(64)` unique, `phash`, `ocr_text`), `expense` (`type`, `amount_egp_minor bigint`, `amount_original_minor bigint`, `currency_original char(3)`, `fx_rate numeric(18,8)`, `occurred_at timestamptz`, `merchant_id`, `merchant_raw`, `category_id`, `paid_by_member_id`, `payment_account_id`, `payment_method`, `status` CHECK in CONFIRMED|PENDING_REVIEW|POSSIBLE_DUPLICATE|IGNORED, `confidence numeric(4,3)`, `notes`, `tags text[]`, `is_recurring`, `recurring_series_id`, `split_mode`, `deleted_at`), `expense_item`, `expense_source` (role PRIMARY|SUPPORTING, unique `(expense_id, raw_message_id)`), `expense_split`, `budget`, `recurring_series`, `sms_template`, `fx_rate` (unique `(date, base, quote)`), `goal`, `audit_log`, `job` (`status`, `run_at`, `attempts`, `locked_by`, `locked_at`, index on `(status, run_at)`), `ai_call_log` (`model`, `prompt_version`, `input_tokens`, `output_tokens`, `cost_micros bigint`, `latency_ms`, `raw_message_id`).
**Then** indexes exist on `expense (household_id, occurred_at desc)`, `expense (household_id, category_id)`, `expense (household_id, paid_by_member_id)`, `expense (household_id, status)` and a GIN index on `expense.tags`.
Unhappy path: **Given** an `expense` insert with `amount_egp_minor = -1` for `type = 'EXPENSE'` **Then** a CHECK constraint rejects it (amounts are non-negative; direction is `type`).

### REQ-OPS-044 — Migration tests from empty and from snapshot
**Given** the Testcontainers migration test **When** it runs against an empty Postgres 16 **Then** all migrations apply and `flyway_schema_history` has no failed rows; **When** it runs against `backend/src/test/resources/db/snapshot-v3.sql` (previous schema with data rows) **Then** `V4` applies and the pre-existing rows are intact (row counts unchanged).
Unhappy path: **Given** a migration file whose checksum differs from the applied one **Then** the test fails with `Validate failed` naming the version.

### REQ-OPS-045 — No floating-point money in persistence
**Given** the ArchUnit + semgrep rules **When** `make check` runs **Then** JPA entities and DTOs under `adapters/out/persistence` have no `double`/`float`/`Double`/`Float` fields, and every money column is `bigint` minor units with a sibling `char(3)` currency (schema test reads `information_schema.columns`).
Unhappy path: **Given** a column `amount numeric(12,2)` added to `expense` **Then** the schema test fails naming the column.

## UX states (UI stories only)
N/A.

## Contract impact
None (`Money` schema in v0 already encodes `amountMinor` + `currency`).

## Data / migration impact
New migration `V4__ledger_schema.sql` (expand only). Snapshot file `snapshot-v3.sql` added for the migration test; a new snapshot is added at each release.

## Security / privacy / LLM-safety impact
`raw_message.text`, `raw_message.payload` and `media.ocr_text` hold untrusted, sensitive content — flagged here for the Phase 9 encryption-at-rest story (STORY-122); DB access only through the backend user with no superuser rights.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-004.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
