---
id: STORY-168
epic: EPIC-17
title: Updating the app to the local-only schema keeps every row and a safety copy
phase: 2
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: []
reqs: [REQ-AND-127, REQ-AND-128, REQ-AND-129, REQ-EXP-041, REQ-EXP-042, REQ-DEDUP-062]
implements: ["ADR-0009 D3 (expand v8 -> v9, migration rules)", "PROMPT.md §8 (default categories, superseded by Room per ADR-0009 D1)"]
owner_agent: android-engineer
---

# STORY-168 - Updating the app to the local-only schema keeps every row and a safety copy

## Goal
Room becomes the only copy of the household's data (ADR-0009). This first slice upgrades the database from v8 to v9
without losing a single row, keeps a copy of the old database in case anything goes wrong, and seeds the defaults
(categories, a "Me" member) that a phone without a server needs. There is no visible change for the user; every later
EPIC-17 story builds on these tables. Implements ADR-0009 D3 "Expand" and the binding migration rules.

## Requirements and acceptance criteria
### REQ-AND-127 - The update from any shipped version keeps every row
**Given** a database at any shipped version from v2 to v8, seeded with at least one row in every table that version
has (for v8: `expense` with `pending` 0 and 1 and `failed` 1, `category`, `account`, `outbox`, `parsed_outbox`,
`review_item`, `learned_template`, `captured_tx`) **When** the app opens it after the update **Then** the database is at
v9, every seeded row is still present with the same values in its old columns (asserted per row with
`MigrationTestHelper` under Robolectric), and the v9 schema JSON exported under `android/app/schemas/` matches the
database the migration produced.
Unhappy path: **Given** a database the migration cannot open or migrate (a corrupt file in the test, or a migration that
throws) **When** the app starts **Then** nothing is deleted (no destructive fallback exists for v2..v8; the only
`fallbackToDestructiveMigrationFrom` entry is still v1), the app shows "The update could not finish. Your data is safe;
please contact support" (Arabic string in `values-ar`), and the original file and the safety copy (REQ-AND-128) are both
still on disk.
Unhappy path: **Given** the update has already run **When** the app starts again **Then** no migration runs a second time
and the row counts are unchanged.

### REQ-AND-128 - A safety copy is taken before a migration runs
**Given** an existing database at a version lower than the app's **When** the app opens it **Then** before the first
migration statement runs, the WAL is checkpointed and `masroof.db` is copied to `noBackupFilesDir/pre-migration-v<N>.db`
(N = the version on disk), the copy opens as a valid SQLite database with the same row counts, and only the newest
`pre-migration-*.db` is kept (an older one is deleted after the new copy is complete).
Unhappy path: **Given** the copy cannot be completed (the test simulates an `IOException` or less free space than the
database size) **When** the app opens the database **Then** the migration does not run, the database stays at its old
version unchanged, a half-written copy is deleted, and the app shows "Free up storage to finish the update" with the
needed size in MB; the next start retries.
Unhappy path: **Given** a fresh install (no database file) **When** the app starts **Then** no copy is attempted and the
database is created directly at v9.

### REQ-AND-129 - Rows from the server era keep their identity and get safe defaults
**Given** a v8 `expense` row with `pending = 0` (a row cached from the server, id `e-123`) and one with `pending = 1`
(a manual entry still in the outbox) **When** the database is migrated to v9 **Then** the cached row has
`serverId = "e-123"`, the queued row has `serverId = null`; every existing row has `createdAtMillis` and
`updatedAtMillis` equal to its `occurredAtMillis`, `deletedAtMillis = null`, `timeKnown = 1`, `paidByMemberId = null`,
and `amountMinor`/`currency` unchanged (money stays `INTEGER` minor units plus an ISO-4217 code; no `REAL` column is
added anywhere, checked against the exported schema JSON).
Unhappy path: **Given** a v8 row with `pending = 1` and `failed = 1` (a manual entry the server refused) **When**
migrated **Then** it gets `serverId = null`, keeps `pending`/`failed` as they were and is still listed. **Given** a
migrated database **When** a row is inserted with a `serverId` that another row already has **Then** the insert is
rejected with a constraint error (the import in STORY-182 relies on it).

### REQ-EXP-041 - A phone that never had a server starts with the default categories
**Given** a fresh install, or a v8 database whose `category` table is empty **When** the v9 database is ready **Then** the
`category` table holds the 20 top-level categories and 9 subcategories of `backend/.../db/migration/R__seed_reference.sql`
with their English and Arabic names, colours and sort order (for example `Groceries / بقالة` and `Fuel / بنزين` under
`Car / السيارة`), each with a stable id that is identical on every install (so a backup restored on another phone
matches), `system = 1` and `archived = 0`.
Unhappy path: **Given** a v8 database that already has categories (a signed-in user's server categories) **When**
migrated **Then** no default category is added and the existing rows keep their ids; the new columns get
`parentId = null`, `archived = 0`, `system = 0`.
Unhappy path: **Given** the seed has already run **When** the seeding code runs again (second start, or after the
seeding step was interrupted) **Then** no duplicate category exists (count stays 29).

### REQ-EXP-042 - A household member exists from the first start
**Given** a fresh install or a v8 database **When** the v9 database is ready **Then** the `member` table holds exactly one
row "Me" (Arabic "أنا"), `sortOrder = 0`.
Unhappy path: **Given** the `member` table already has a row **When** the seeding code runs again **Then** no second
"Me" row is created.

### REQ-DEDUP-062 - The same message can be linked to an expense only once
**Given** an expense with an `expense_source` row whose `localSourceId` is `L1` **When** another `expense_source` row with
`localSourceId = L1` is inserted (for any expense) **Then** the insert is rejected (unique constraint) and the first row
is unchanged. **Given** an expense with two sources and one `receipt` row attached **When** the expense row is deleted
(purge, not soft delete) **Then** its sources are deleted with it and the receipt row remains with `expenseId = null`.
**Given** the same file hash is inserted twice into `receipt` **Then** the second insert is rejected.
Unhappy path: **Given** an `expense_source` row whose `expenseId` points to no expense **When** inserted **Then** it is
rejected by the foreign key (foreign keys are enforced on the connection).

## UX states (UI stories only)
No new screen. Two blocking messages only (REQ-AND-127 "could not finish", REQ-AND-128 "free up storage"): shown in
Arabic and English, RTL mirrored, readable in dark theme, no retry loop while the app is open.

## Contract impact
None. No API change; the server contract is frozen (ADR-0009 D7).

## Data / migration impact
Room v8 -> v9, one hand-written `MIGRATION_8_9`, additive only (ADR-0009 D3 "Expand"): new columns on `expense`,
`category`, `account`; new tables `member`, `receipt`, `expense_source`; indexes `(occurredAtMillis)`, `(categoryId)`,
unique `serverId`, unique `expense_source.localSourceId`, unique `receipt.sha256`, `receipt(expenseId)`. Exported
`9.json` committed; `2.json`..`8.json` untouched. New test-only dependency `androidx.room:room-testing` (same version as
Room). No new `fallbackToDestructiveMigration*`. Rollback: the pre-migration copy (REQ-AND-128); a downgrade is not
supported. The contract step (drop `outbox`, `parsed_outbox`, `pending`, `failed`) is STORY-198, not here.

## Security / privacy / LLM-safety impact
The safety copy holds the same data as the database (incl. `review_item` text) and lives in `noBackupFilesDir`
(app-private, excluded from any backup; `allowBackup="false"` unchanged). It is never logged or exported. No new
permission, no exported component. No LLM.

## Eval impact
None (no parsing change). Kotlin parser tests over `fixtures/sms/*.jsonl` must stay green.

## Design notes (architect)
ADR-0009 D3 is the design (columns, tables, constraints, migration rules, pre-migration copy). Budgets are not in the
D3 v9 list; STORY-181 needs a local `budget` table (see the EPIC-17 README "Open design points").

## Dependencies
None inside EPIC-17. Builds on Room v8 from STORY-065/STORY-067 (merged PR #44, #63).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: migration + entities + seed ≈ 150, migration tests ≈ 220)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D3) |
