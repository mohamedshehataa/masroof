# STORY-201 verify report (qa-verifier, fresh context)

Loop 1 of 3 · base origin/main `ee4e824` · head `97a338c` · 2026-11-08

Gate: `./gradlew --console=plain -q checkFast </dev/null` (the `make check-fast MODULE=android` recipe; `make` could not
start inside the isolated worktree) -> exit 0; forced `testDebugUnitTest --rerun` -> 653 tests, 0 failures, 0 skipped.
Committed `10.json` equals the generated file byte for byte.

STORY: STORY-201
CRITERIA:
- Claim1-additive: PASS -- `Migration10.kt:15-161` only ADD COLUMN / CREATE IF NOT EXISTS; replaying it on a 9.json DB vs a fresh 10.json DB -> 24 tables, 0 pragma diffs
- Claim2-schema/no REAL: PASS -- every story column present with stated nullability/defaults; REAL scan of 10.json `[]`
- Claim3-1..9 and old migrations unchanged: PASS -- identical blobs for 1..9.json; Migration9.kt diff is only `ALL_MIGRATIONS`
- Claim4-2..9 validate + values: PASS -- REQ-AND-146 2..9 `runMigrationsAndValidate(.., 10, true, ..)`; values (incl. Long.MAX amount) survive
- Claim5-FK/unique: PASS -- tests plus a migrated-DB sqlite probe: cascades, SET NULLs and unique keys as specified
- Claim6-erase: PASS -- `REQ-AND-147 erase all data empties every new table` (real `RoomDataWiper`, FKs on)
- Claim7-no fallback/throwing migration: PASS -- only the existing v1 fallback; throwing 9->10 keeps original and `pre-migration-v9.db`
- Claim8-test integrity: PASS -- `bash scripts/quality/test-integrity-check.sh origin/main` -> `TEST_INTEGRITY: OK` (224 -> 337 assertions, no skips)
- Claim9-REQ ids: PASS -- RoomV10MigrationTest 16/16 named REQ-AND-145 (5), -146 (10), -147 (1)
- Claim10-gate: PASS -- checkFast exit 0, 653/653
- REQ-AND-145: PASS
- REQ-AND-146: PASS
- REQ-AND-147: PASS (STORY-183 doc lists the new tables)
FINDINGS:
- [MINOR] `data/db/Daos.kt:329-333` -- `upsertCategories`/`upsertAccounts` use REPLACE (delete + insert); with v10 FKs a re-upsert deletes `merchant_rule`/`account_balance` children, nulls `budget.categoryId` and resets the new columns. No production caller today; a trap for STORY-202..207. -> sent back (fix round 1)
- [MINOR] `data/db/Migration10.kt:26,38,59,90,103,114,135` -- missing commas between table constraints; SQLite accepts it but `sqlite_master.sql` differs between migrated and fresh installs. -> sent back (fix round 1)
- [NIT] `RoomV9MigrationTest.kt:321` -- test name says "at v9" but asserts `DB_VERSION`. -> sent back
- [NIT] DoR line said 14 tables (13 specified and built); no DB CHECK on enum TEXT or day 1..31 -- repositories in later stories validate.
EXPLORATORY: 16 cases tried (migrated-DB replay, Long.MAX amount, duplicate and orphan inserts, every cascade, defaults, REPLACE on parents, createSql comparison, scope, DAO ordering), 2 problems (the two MINORs)
TEST_INTEGRITY: OK
STATUS: PASS
