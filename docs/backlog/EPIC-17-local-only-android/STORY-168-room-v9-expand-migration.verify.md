# Verification report — STORY-168

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-27  ·  **Base:** origin/main `908c7abe79c06abee7d822b12ab7a331d61b2081` (merge base `6d5bffcd4552669afcc35738bf96191ed30bb95b`)  ·  **Head:** `211757cd70bf8ca84405c1b3b94be97dfdefc9b0` (PR #77, branch story/STORY-168-room-v9)
- **Loop:** 2 of 3. Loop 1 (head a8331eb) was FAIL: 1 BLOCKER (blocked start crashed), 2 MAJOR (seeded categories reaching uploads; REPLACE resetting v9 columns). This loop re-verifies at 211757c, starting from 8793ba4.

## Environment
- `JAVA_HOME=".../jdk-21.0.12.101-hotspot" bash scripts/make/check-fast.sh android` at 211757c → `OK android: ./gradlew checkFast` / `check-fast GREEN [android] in 90s`. Results: 445 unit tests, 0 failures, 0 skipped. RoomV9MigrationTest 22/22, ExpenseWritesV9Test 8/8, StartupBlockedTest 7/7, BudgetsLogicTest 22/22 (XML from this run, 21:50). Working tree clean after the build, so the exported `9.json` is unchanged.
- Emulator `qa35` (API 35, userdebug, `adb shell` runs as root). APKs: main (f8392be, Room v8; the v8 schema has not changed on main since) and `:app:assembleDebug` at 211757c.
- No backend: the debug app talks to an HTTPS/pinned or `http://10.0.2.2/` server that is not running, so signed-in flows (refresh, edit, budgets) could not run on the device; they are covered by unit tests only.

## Diff reviewed (8793ba4 → 211757c)
`MainActivity.onStart`, `ForegroundSync.onForeground`, `OutboxSyncWorker`, `SmsIntakeWorker`, `SmsUploadWorker` and `CapturedActionReceiver` return early (workers `Result.retry()`) when `DatabaseStartup.isBlocked()`. `BlockedHelper.databaseName` is fixed. `ExpenseDao`: REPLACE removed; `upsert` = insert (ABORT) for a new id, `@Update` with `keepLocalFrom(old)` for an existing one. `replaceSynced` deletes only `pending = 0` ids the server no longer lists (chunks of 500), then upserts. `completeUpload` writes the server row first, re-points `expense_source` and `receipt`, then deletes the pending row. `LookupDao.observeCategories` filters `system = 0`, and `observeAllCategories` is added for local-only screens. `serverUuidOrNull` replaces `UUID.fromString` for category ids in upload and PATCH. `toEntity` sets `serverId`, `createdAtMillis` and `updatedAtMillis`.

## Criteria
| REQ | Result | Evidence (command → observed) |
|---|---|---|
| REQ-AND-127 | PASS | Device: main's v8 DB (20,005 expenses; every v8 table seeded; data only in the WAL, `masroof.db` 4096 B) → `adb install -r` 211757c APK + `am start -W` (2091 ms). The `diff` of old-column dumps before and after shows 0 changed values (category and account only gain the new columns with defaults). `user_version` 9, identity hash `57d41cb8...` = `9.json`, `quick_check` ok, crash buffer empty. Corrupt file (first 1 MiB of a v8 file): the English screen "The update could not finish. Your data is safe; please contact support." (screenshot), and the Arabic screen "التحديث ماخلصش. بياناتك في أمان، من فضلك تواصل مع الدعم." right-to-left (in-app language pref `ar`). Three cold launches: crash buffer empty, process alive, md5 `8516b56b...` unchanged. Unit tests: `StartupBlockedTest` 7/7, `RoomV9MigrationTest` 22/22. |
| REQ-AND-128 | PASS | Device: the safety copy dumps identical to the pre-upgrade data (WAL checkpointed before the copy). Low space: v8 DB grown to 603,791,360 B, /data filled to ~300 MB free → Arabic "أفرغ مساحة تخزين لإكمال التحديث. المطلوب حوالي ٥٧٦ ميجابايت." (576 MB = size rounded up), no crash, no `.part`, DB untouched at v8. After freeing space, the next cold start (4339 ms, no ANR) took a 603,791,360 B `pre-migration-v8.db`, replaced the older 2 MB copy (only the newest kept), and migrated: v9, 20,005 expenses, 601 review items. Fresh install (loop 1): no copy, v9. With a 100 % full disk, WorkManager's own initialisation crashes (`ForceStopRunnable` SQLITE_FULL on `workdb-shm`). Main does the same and the PR does not change it; `masroof.db` stayed v8 with all rows. |
| REQ-AND-129 | PASS | Device after the upgrade: `serverId = id` for all 20,003 `pending = 0` rows; NULL for `q-1`/`f-1`; created = updated = occurred; `timeKnown` 1; money unchanged incl. 9223372036854775807 and -1; 0 REAL columns. Unit tests `ExpenseWritesV9Test` (refresh keeps serverId, timestamps and local-only columns; refresh removes only rows the server dropped and never pending rows; edit keeps serverId and timestamps; completeUpload keeps the created time) passed. Refresh and edit on the device: NOT RUN (no backend). |
| REQ-EXP-041 | PASS | Loop 1 runtime compare: 29 rows equal to `R__seed_reference.sql` field by field. New unit test: the server pickers (`observeCategories`) show only server categories (no `cat-*`, no duplicate "Groceries"), and `observeAllCategories` still has the defaults; the wiper re-seeds after a wipe. All pickers (Add `ViewModels.kt:132/159/201`, Edit `EditExpenseViewModel.kt:107`, Budgets `BudgetsViewModel.kt:73/175`) read `observeCategories()`. Device repro: an outbox entry with `categoryId = 'cat-groceries'` → OutboxSyncWorker SUCCESS (retry scheduled), no `Invalid UUID`, attempts 0→3 from normal retry handling (IOException, no server). The queue is not blocked by the id. |
| REQ-EXP-042 | PASS | Device: one `member-me|Me|0`. Unit tests pass. Storing "Me" and rendering "أنا" by id is accepted (ADR-0009 D3 has a single `displayName`). |
| REQ-DEDUP-062 | PASS | Unit tests in RoomV9MigrationTest (unique `localSourceId`/`sha256`, cascade, SET NULL, enforced FK). New: a source row and a receipt link survive `replaceSynced` and `upsert`; `completeUpload` re-points both to the server row (ExpenseWritesV9Test). |

## Findings
Loop-1 BLOCKER F1 (blocked start crash), MAJOR F2 (seeded category ids) and MAJOR F3 (REPLACE resets v9 columns / cascades) are **closed** by 211757c (evidence in the criteria table and claims below).

| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | Room `SupportSQLiteOpenHelper.Callback.onCorruption` (default) | The author deferred this guard. Deferring is acceptable for this story: the behaviour is the same as main, and the startup `quick_check` with a no-op handler keeps a corrupt file on disk (device md5 unchanged). But it is not tracked anywhere: a grep for "onCorruption" finds only this report. Under ADR-0009 Room holds the only copy, so it must be fixed before STORY-169 writes local-only data. Add a story or an ESCALATIONS line. | Read the code, NOT RUN. |
| MINOR | `ApiRemoteSource.fetchExpenses` (200 per type, no paging) + `ExpenseDao.replaceSynced` | A signed-in refresh still deletes every synced row outside the newest 200 per type, same as main's `DELETE pending = 0`. The 20,003 server-era rows the migration kept will shrink to ≤ 600 on the first refresh. They are still on the server, so STORY-182 must import from the server and not trust the local cache. | Read the code (refresh NOT RUN on device). |
| MINOR | `DatabaseStartup.run` (`current` cached) | A blocked result lasts for the life of the process. After freeing space, reopening the activity does not retry until the process dies; on the device, a force-stop retried correctly. | Device: force-stop → relaunch migrated. |
| NIT | `Daos.kt` `keepLocalFrom` + `ApiRemoteSource.toEntity` | Server rows now always carry `updatedAtMillis = occurredAt`, so after an edit `updatedAtMillis` is set to `occurredAt` rather than the edit time. The unit test "an edit keeps serverId and timestamps" passes 0/0, which the real mapper never sends. It is harmless today (no consumer). | Read the code. |
| NIT | `ExpenseDao.deleteAll` | Batching (500 ids per `IN`, under SQLite's 999-variable limit on API 26) reads correctly, but no test deletes more than 500 rows. | Read the code. |
| NIT | `CapturedActionReceiver` | While blocked, Undo silently does nothing and the notification stays. There is no crash: device cold-start broadcast as root, process alive, crash buffer empty. | `am broadcast -n eg.masroof/.ingest.CapturedActionReceiver -a eg.masroof.action.UNDO_CAPTURED --es localId l1` |
| NIT | `PreMigrationBackup` | `quick_check` runs at every cold start. Measured: 1606 ms later cold start on a 603 MB DB, 4339 ms for the 603 MB copy plus migration (39 skipped frames, no ANR). Acceptable. | `am start -W` timings. |

## Claims (loop 2)
1. Blocked state: PASS on the device. English and Arabic messages, 3 launches without a crash, file untouched. Undo receiver cold-started while blocked: no crash. "Free up storage" was simulated (603 MB DB, ~300 MB free): Arabic message with ٥٧٦ MB, no crash, and the next start after freeing space succeeded. Outbox, intake and upload workers return `Result.retry()` (StartupBlockedTest, unit only). SMS intake on the device: NOT RUN (needs a signed-in capture gate).
2. Seeded categories: PASS. Pickers read only `system = 0` (unit test + all picker call sites). `serverUuidOrNull` keeps `cat-*` out of the upload and the PATCH. Device `cat-groceries` outbox repro: no `Invalid UUID`, normal retry, queue not blocked. `paymentAccountId` and budgets still use `UUID.fromString`, but they only receive server ids (accounts are not seeded; the budgets picker is filtered).
3. Expense writes: PASS by reading the code and running the unit tests. No `INSERT OR REPLACE` remains on `expense`. The REPLACE strategies left are on outbox, category, account and learned_template. The remaining expense DELETEs are deliberate: user delete or Undo (`ExpenseEdit.kt:117,134`, `Captured.kt:314`, `SmsWork.kt:435`), discard of a failed entry (`Daos.kt:440`), the emptied pending row after re-pointing (`Daos.kt:419`), and rows the server dropped (`replaceSynced`, `pending = 0` only). Refresh, edit and completeUpload keep `serverId`/`createdAt`/`updatedAt`, sources and receipts (ExpenseWritesV9Test 8/8). Device refresh/edit: NOT RUN (no backend).
4. v8 → v9 on the device at 211757c: PASS (20,005 rows, 0 changed values, copy identical; also a 603 MB DB after a low-space block).
5. Server-era flows: login screen renders after the migration (Arabic screenshot, no crash). List/add/edit/confirm/delete/budgets: unit tests green in check-fast (445/445). Signed-in device runs: NOT RUN (no backend).
6. `check-fast` GREEN (445 tests) and test-integrity OK (below).

## Exploratory
24 cases tried in total (16 in loop 1, 8 new), 0 new problems at 211757c. New inputs:
1. Corrupt v8 file (first 1 MiB) at 211757c → English blocked message, no crash.
2. Same, in-app language `ar` → Arabic, right to left.
3. Three cold launches while blocked → no crash.
4. Undo broadcast to a cold process while blocked → no crash.
5. 100 % full /data → WorkManager init crash (pre-existing, same on main); `masroof.db` stays v8 with all rows, no `.part`.
6. 603 MB v8 DB with ~300 MB free → "free up storage" (٥٧٦ MB), DB untouched.
7. Free space, relaunch → 603 MB copy, older copy replaced, migrated v9, 601 review items and 20,005 expenses intact.
8. Outbox entry with `categoryId = 'cat-groceries'` at 211757c → no `Invalid UUID`, normal retry.

## Test integrity
OK. `bash scripts/quality/test-integrity-check.sh origin/main` → CapturedPairingTest 36→36, CapturedRaceTest 39→39, SmsLifecycleTest 36→36, added RoomV9MigrationTest 111, ExpenseWritesV9Test 32, StartupBlockedTest 12 assertions; 0 skips; total 111→266; `TEST_INTEGRITY: OK`. One test in RoomV9MigrationTest was renamed (REQ-AND-127 → REQ-AND-129, "no REAL column ... at version 9"). The name now matches what the test asserts; the assertions are unchanged.

STATUS: PASS
