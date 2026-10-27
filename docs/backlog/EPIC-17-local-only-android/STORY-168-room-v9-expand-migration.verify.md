# Verification report — STORY-168

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-27  ·  **Base:** `f8392be4e55048a9eb78566f36338bb9fa22e9fb` (merge base; origin/main has since moved to 6d5bffc)  ·  **Head:** `a8331eb6cde052812176cf64289db40c21c9f4f2` (PR #77, branch story/STORY-168-room-v9)
- **Loop:** 1 of 3

## Environment
- `JAVA_HOME=".../jdk-21.0.12.101-hotspot" bash scripts/make/check-fast.sh android` → `OK android: ./gradlew checkFast` / `check-fast GREEN [android] in 102s`. Results: 430 unit tests, 0 failures, 0 skipped. `RoomV9MigrationTest` 22/22, `RoomMigrationTest` 5/5, `CapturedPairingTest` 12/12, `CapturedRaceTest` 10/10 (XML in `android/app/build/test-results/testDebugUnitTest`, timestamped by this run).
- After the build the working tree was clean (short status empty), so the KSP-exported `9.json` is byte-identical to the committed one.
- Emulator: AVD `qa35` (API 35, x86_64). I built main (f8392be) and the PR head with `./gradlew :app:assembleDebug`, installed main, then installed the PR build with `adb install -r`.

## Criteria
| REQ | Result | Evidence (command → observed) |
|---|---|---|
| REQ-AND-127 | **FAIL** | Happy path PASS: unit test `REQ-AND-127 migrating from every shipped version 2 to 8 ...` (`runMigrationsAndValidate(..., 9, true, *ALL_MIGRATIONS)`) passed. On the device, main's real v8 DB with 20,005 expenses plus one row in every v8 table was upgraded by `adb install -r pr-debug.apk` + `am start -W`. A `diff` of the pre- and post-upgrade dumps (all old columns of all 8 tables) shows 0 changed values; only category and account gain the new columns with defaults. `PRAGMA user_version`=9, `room_master_table` hash `57d41cb8...` equals the `9.json` identityHash, `PRAGMA quick_check`=ok. Second start: no new copy (mtime unchanged) and no migration. **Unhappy path FAILS:** with a corrupt `masroof.db` (first 1 MiB of a v8 file), the file is untouched (md5 `6e7c35bc...` before and after) but **the app crashes on every launch**: `FATAL EXCEPTION: main ... IllegalArgumentException: This driver is configured to open a database named 'blocked' but 'blocked-never-opened.db' was requested` (StandaloneCoroutine, Dispatchers.Main.immediate). The "could not finish" message is never usable (F1). |
| REQ-AND-128 | **FAIL** | Copy PASS. On the device, before the upgrade all data was only in the WAL (`masroof.db` 4096 B, `-wal` 2.3 MB). After the upgrade, `no_backup/pre-migration-v8.db` (2.1 MB) dumps identical to the pre-upgrade data (`cmp` → identical). So the WAL was checkpointed before the copy, and the copy is in `noBackupFilesDir`. Unit tests passed for: copy exists before the first migration statement (spy migration); only the newest copy is kept; IOException → `.part` deleted, DB stays at v8, the next start succeeds; free space < size → no copy, v8 kept. Fresh install on the device: no `pre-migration-*`, `user_version`=9. **The "Free up storage" message cannot be shown:** it uses the same blocked DB and `MainActivity.onStart` path that crashed on the device (F1). The low-space case itself was NOT RUN on the device. |
| REQ-AND-129 | PASS (at migration time) | Device: `serverId=id` for all 20,003 `pending=0` rows. `q-1` (pending) and `f-1` (pending+failed, USD) have `serverId` NULL and keep pending/failed. Count of rows with created/updated != occurred is 0; `deletedAtMillis` NULL, `timeKnown`=1, `paidByMemberId` NULL. `amountMinor` unchanged, including 9223372036854775807 and -1. No REAL column, both in `pragma_table_info` and in every entity of `9.json` (Python probe). Duplicate `serverId` is rejected (unit test, and Python probe: `UNIQUE constraint failed: expense.serverId`). But the existing refresh and edit paths null `serverId` again (F3). |
| REQ-EXP-041 | PASS | Fresh install on the device: 29 rows. Every row matches `R__seed_reference.sql`: I checked by eye after a static script compare (20+9 rows, 0 field diffs). Ids `cat-<reference_key>`, `nameEn`, `nameAr`, colours (subcategories take the parent's colour), `sortOrder`, `parentId` (`cat-car` / `cat-bills`), `archived=0` and `system=1` all match. Unit tests passed: seeding twice leaves 29; a v8 DB with categories gets no seed and keeps `c1` with new-column defaults; a v8 DB with an empty category table gets 29. Device upgrade with an existing `c1` → 1 category. |
| REQ-EXP-042 | PASS (deviation accepted) | Device: exactly one row `member-me|Me|0`, after both the upgrade and a fresh install. Unit test: seeding again leaves 1, and an existing member blocks it. Storing "Me" and rendering "أنا" by id is acceptable: ADR-0009 D3 gives `member` a single `displayName`. No string resource or render path exists yet (NIT). |
| REQ-DEDUP-062 | PASS | Unit tests passed: duplicate `localSourceId` rejected and the first row unchanged; deleting an expense cascades its 2 sources and leaves the receipt with `expenseId` NULL; duplicate `sha256` rejected; an orphan source is rejected (FK on through `AppDatabaseCallback.onOpen`). Python probe on the committed `9.json` DDL confirms the cascade and SET NULL semantics. |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| BLOCKER (F1) | `MainActivity.kt:88-91` (`onStart` → `foregroundSync.onForeground()` → `repository.hasRetryable()`), `DatabaseStartup.kt` `blocked()` | In a blocked start, `onStart` is not guarded, so it queries the blocked DB in `lifecycleScope`. Room 2.8 then throws `IllegalArgumentException` (the driver's name 'blocked' does not match 'blocked-never-opened.db'; it would throw `IllegalStateException` even if the names matched). The exception is uncaught on `Main.immediate`, so the process dies on every launch, and neither blocking message is ever shown. The unit tests only call `DatabaseStartup.open`; nothing launches `MainActivity` or uses the Hilt graph in a blocked state. | Install main, launch, force-stop. `run-as eg.masroof rm -f databases/masroof.db-wal databases/masroof.db-shm`. `run-as eg.masroof head -c 1048576 no_backup/pre-migration-v8.db > /data/local/tmp/trunc.db`, then `run-as eg.masroof cp /data/local/tmp/trunc.db databases/masroof.db`. Run `am start -W -n eg.masroof/.MainActivity`, then `adb logcat -d -b crash` → FATAL. It reproduces on the 2nd launch. Needed test: Robolectric `ActivityScenario<MainActivity>` with `DbStartup.CouldNotFinish` and `FreeUpStorage(n)` asserts the en and ar text and no crash. |
| MAJOR (F2) | `DefaultSeed.kt`; `ApiRemoteSource.kt:273-274` (`UUID::fromString` built outside the `try`); `ApiExpenseEditRemote.kt:61`; `ApiBudgetsRemote.kt:56`; `RoomDataWiper.kt:52-56` | The server-backed app is still active. A fresh install, or any logout or member switch (the wiper re-seeds), now shows the 29 seeded `cat-*` categories next to the server's UUID categories in the Add, Edit and Budgets pickers (duplicate "Groceries"). If the user picks a seeded one, the upload throws `IllegalArgumentException: Invalid UUID string: cat-groceries` out of `syncOutbox`. The entry is never bumped or rejected, so the outbox queue is blocked forever. `HomeViewModel.refresh()` (viewModelScope, no handler) calls `syncOutbox()` first, so a signed-in Home is expected to crash on every open. | Device, PR build: insert an outbox row plus a pending expense with `categoryId='cat-groceries'`, then `am start` → logcat `WM-WorkerWrapper ... IllegalArgumentException: Invalid UUID string: cat-groceries at ApiRemoteSource.upload(ApiRemoteSource.kt:273) at DefaultExpenseRepository.syncOutbox(ExpenseRepository.kt:169)`. The signed-in Home crash comes from reading the code: sign-in needs a backend, NOT RUN. |
| MAJOR (F3) | `Daos.kt:31-57` (`upsert` = REPLACE, `replaceSynced` = DELETE pending=0 + REPLACE); `ApiRemoteSource.kt:341` (`toEntity` sets no v9 fields); `ExpenseEdit.kt:109,150`; `Daos.kt:345` | Builder risk 6. **Today:** every signed-in refresh, edit (PR #70), 409 reload and `completeUpload` rewrites expense rows with `serverId=NULL` and `createdAtMillis=updatedAtMillis=0`. The REQ-AND-129 backfill is undone on the first refresh after the update, so STORY-182's idempotent import cannot rely on `serverId` of cached rows. No code writes `expense_source` or `receipt` yet, so there is no cascade loss today. **After STORY-169:** with FKs on, REPLACE deletes the old row, so the sources cascade away and receipts lose their link. `replaceSynced` deletes every `pending=0` row. A REPLACE that hits the unique `serverId` with a different id deletes the other expense and its sources. | Python `sqlite3` on the committed `9.json` DDL with `PRAGMA foreign_keys=ON`: (1) REPLACE same id → sources 0, receipt.expenseId NULL, serverId NULL; (2) replaceSynced → sources 0, serverId NULL; (3) REPLACE id `import-2` with an existing serverId → `local-1` and its source deleted. Fix: use `@Upsert`/`@Update` and drop `replaceSynced` before any `expense_source` write. |
| MINOR | Room `SupportSQLiteOpenHelper.Callback.onCorruption` (default) | The startup probe correctly uses a no-op `DatabaseErrorHandler`: on the device the corrupt file was kept. But the real Room connection keeps the default handler, which deletes `masroof.db` on SQLITE_CORRUPT. `quick_check` right before the open makes this unlikely during the migration. At runtime it is pre-existing behaviour, but under ADR-0009 Room holds the only copy. | Read the code, NOT RUN. Follow-up: an open-helper factory whose `onCorruption` does not delete. |
| MINOR | `PreMigrationBackup.run`, `MasroofApp.attachBaseContext` | `PRAGMA quick_check` and `wal_checkpoint(TRUNCATE)` run on the main thread at every cold start, including process starts for an SMS or notification, even with no migration. Emulator, 20,005 rows / 3.9 MB: cold `TotalTime` 1189 ms (upgrade), 1130 and 1636 ms (later starts) vs main 1126-1553 ms, so no measurable regression at this size. A `quick_check` failure on an up-to-date v9 DB blocks the app with "update" wording. | `am start -W` timings. |
| MINOR | `ingest/Captured.kt:353` (`CapturedActionReceiver`); SMS/notification workers | In a blocked start, an Undo tap on a notification posted before the update runs DB code in a `CoroutineScope` with no handler, so the process crashes. Intake workers fail (WorkManager failure, no retry), so captures received while blocked are dropped. | Read the code. |
| MINOR | `DatabaseStartup.run` (`current` cached) | The blocked result lasts for the life of the process. After freeing storage, reopening the activity does not retry until the process dies. | Read the code. |
| MINOR | `RoomDataWiper.wipe`, `SmsLifecycleTest.kt:319` | The re-seed after `clearAllTables` is idempotent and transactional, but no test asserts 29 categories and 1 member after `wipe()`. Combined with the server category sync, it causes F2. | Grep of the tests. |
| NIT | `RoomV9MigrationTest.kt:167` | The test name claims "the older schema files are the ones that shipped", but the test asserts nothing about them. The v2..v8 test checks values only for `e-123`/`c1` and only counts the rest; the device run covered every value. | Read the test. |
| NIT | `PreMigrationBackup.kt` | `pre-migration-v<M>.db.part` from another version is never cleaned up. The checkpoint's busy result is ignored. The free-space check ignores the space the migration itself needs (that case ends as "could not finish" rather than "free up storage"). | Read the code. |
| NIT | REQ-EXP-042 | "أنا" depends on a future UI. No string resource keyed on `member-me` exists; STORY-187/196 should own it with a test. | Read the code. |

## Exploratory
16 cases tried, 5 problems:
1. v8 DB with every row only in the WAL (`masroof.db` 4096 B, `-wal` 2.3 MB), 20,005 expenses → the copy is complete and identical. OK.
2. `amountMinor = 9223372036854775807`, merchant `'مطعم ١٬٢٥٠'`, notes `'ملاحظة'`, REFUND / PENDING_REVIEW / NOTIFICATION → unchanged, typeof integer. OK.
3. `amountMinor = -1`, `occurredAtMillis = 0` → unchanged; `createdAtMillis` 0. OK.
4. USD row with `pending=1, failed=1` → `serverId` NULL, flags kept. OK.
5. Seed rows with 1970 timestamps → `review_item`/`captured_tx` purged by the existing 30-day retention worker (SmsWork.kt:297-298), not by the migration. Rerun with current timestamps → every row survives. OK.
6. Truncated v8 file (first 1 MiB) → file untouched (md5 equal), but the app crashes. PROBLEM (F1).
7. Relaunch while blocked → crashes again. PROBLEM (F1).
8. Second start after a successful migration → no new copy, no crash. OK.
9. Fresh install → no copy, v9, 29 categories equal to the SQL, 1 member. OK.
10. Outbox row with `categoryId='cat-groceries'` → `Invalid UUID string`, outbox blocked. PROBLEM (F2).
11. SQL: `INSERT OR REPLACE` of an expense that has a source and a receipt → source deleted, receipt link NULL. PROBLEM (F3, latent).
12. SQL: replaceSynced semantics → `serverId` NULL. PROBLEM (F3).
13. SQL: REPLACE with a colliding `serverId` and a new id → the other expense and its sources deleted (F3, latent).
14. SQL: seeded plus server category with the same name → 2 "Groceries" rows (F2).
15. Two NULL `serverId` rows → allowed. OK.
16. Duplicate `serverId` → UNIQUE constraint failed. OK.

## Claim checklist
1. v2..v8 keep rows and match 9.json: PASS (unit test plus device diff). The name-status diff vs the merge base over `android/app/schemas` and `data/db` shows only `A 9.json` and new files. The `Daos.kt` hunk only touches the entity list and the version, so `MIGRATION_2_3..7_8` and `2..8.json` are untouched.
2. `MIGRATION_8_9` is additive: PASS. I read the SQL: only `ALTER TABLE ... ADD COLUMN`, one `UPDATE` that writes only new columns, and `CREATE TABLE/INDEX IF NOT EXISTS`. The only destructive fallback is `fallbackToDestructiveMigrationFrom(true, 1)`.
3. Safety copy: PASS on the data side (device and unit tests). The blocked message FAILS (F1). Low space on the device: NOT RUN.
4. Blocked messages: the en and ar strings exist and follow the in-app language (`MainActivity.attachBaseContext` → `AppLanguage.wrap`), but the screen crashes (F1). The startup probe never deletes the file (device md5). Room's own corruption handler still can (MINOR).
5. Server-era rows: PASS (F3 covers what happens later).
6. Default categories: PASS.
7. "Me": PASS; the deviation is acceptable.
8. Constraints: PASS.
9. REPLACE risk: MAJOR (F3).
10. attachBaseContext: cold start is fine at 20k rows; a blocked start crashes (F1, BLOCKER) and the action receiver can crash (MINOR).
11. Wiper re-seed: correct in isolation, untested, and feeds F2.
12. Edits to the three existing tests only append `MIGRATION_8_9`: PASS.
13. Emulator install-over: done. The upgrade keeps every row; the blocked path crashes.

## Test integrity
OK. `bash scripts/quality/test-integrity-check.sh origin/main` → CapturedPairingTest 36→36, CapturedRaceTest 39→39, SmsLifecycleTest 36→36 asserts, 0 skips; RoomV9MigrationTest added with 111; `TEST_INTEGRITY: OK`. The diffs of the three edited tests only add `MIGRATION_8_9` to `addMigrations(...)` and its import.

STATUS: FAIL

## Addendum: new branch head 8793ba48213501d40a96d9cb01cf2c592d65e981
- Between a8331eb and 8793ba4, only `DefaultSeed.kt` and `Migration9.kt` changed under `data/db` (SQL literals now written as raw strings). `MainActivity.kt`, `di/` and `schemas/` are unchanged, so F1 to F3 still apply.
- `./gradlew :app:testDebugUnitTest --tests eg.masroof.RoomV9MigrationTest --tests eg.masroof.RoomMigrationTest` on 8793ba4 → RoomV9MigrationTest 22/22, RoomMigrationTest 5/5, 0 failures, 0 skipped.
- NOT RUN on 8793ba4: full check-fast, test-integrity, the emulator upgrade. The device evidence above is from a8331eb.

STATUS: FAIL
