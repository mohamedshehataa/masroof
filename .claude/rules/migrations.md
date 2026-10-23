---
paths:
  - "**/db/migration/**"
---
# Flyway migrations rule

- **Applied migrations are immutable.** Any `V<N>__*.sql` that exists on `main` is never edited, renamed or
  deleted (the `protect-files` hook blocks it). Fix forward with a new version.
- Naming: `V<N>__<snake_case_description>.sql`, `N` = highest existing + 1, no gaps reused, no timestamps.
  Repeatable migrations `R__<name>.sql` are allowed for views, functions and seed lookups only (idempotent
  `CREATE OR REPLACE`, `ON CONFLICT DO NOTHING`); never for data that users own.
- Schema changes follow **expand -> migrate -> contract** across releases: add the nullable column/table/index
  (expand, release N), backfill in batches and dual-write (migrate), then drop/rename/add NOT NULL (contract,
  release N+1 at the earliest). A single migration never both adds and drops the same concept.
- No data-destroying statements (`DROP TABLE`, `DROP COLUMN`, `TRUNCATE`, `DELETE` without a `WHERE` on a
  user-data table, type narrowing) without a story that names the migration in its "Data / migration impact"
  section and a rollback note; `guard-bash` additionally blocks DROP/TRUNCATE against non-local hosts.
- Every migration: `timestamptz` (never `timestamp`), `uuid` PKs, `created_at/updated_at`, `deleted_at` where
  user data is involved, money as `BIGINT` minor units + `CHAR(3)`, indexes created `CONCURRENTLY` in their own
  migration when the table may be large, explicit `SET lock_timeout` for ALTERs on hot tables.
- Tests: migrate from empty and from the previous release's schema snapshot with data
  (`backend/src/test/resources/schema-snapshots/`); the migration test class is tagged with the story's REQ id.
- Seeds for dev/demo live in `R__seed_*.sql` guarded by a flag or in the sample-data generator, never in `V*`.
