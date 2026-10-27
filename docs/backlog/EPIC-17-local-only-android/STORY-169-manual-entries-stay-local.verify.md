# STORY-169 verify report

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-27
- Base: origin/main d7a26672ca32ffdcada4f87a19e5b200294ba23a
- Head: babaeee4c610cc3b27c388ef3e96dafa47bcc6d8 (loop 1 verified f017fb9)
- Loop: 2 of 3

## Environment
- env JAVA_HOME=.../jdk-21.0.12.101-hotspot bash scripts/make/check-fast.sh android -> "check-fast GREEN [android] in 94s". The test-results XMLs show 494 tests: 0 skipped, 0 failed, 0 errors. LocalSafetyRound1Test ran 15 tests, all passing.
- bash scripts/quality/test-integrity-check.sh origin/main -> "TEST_INTEGRITY: OK" (assertions 520 -> 591, 0 in deleted files, 1 WARN on ViewModelTest, judged below).
- Emulator qa35 (API 35): ./gradlew :app:assembleDebug, then adb install -r over the loop-1 install. DB and files were read with adb shell "run-as eg.masroof ...". Screenshots are in the gitignored android/app/build/qa-probe/.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-EXP-043 | PASS | Loop 1 on the device: 1,450 -> 145000 CONFIRMED, timeKnown=1, MANUAL source; a past date -> Cairo noon, timeKnown=0. Loop 2 on the device: Add 300 -> 1 row with serverId NULL. The REQ-EXP-043 unit tests pass, including the new "the Arabic comma is a thousands separator". |
| REQ-AND-130 | PASS | Fresh install opens on Home (loop 1, device). New recorder test "REQ-AND-130 no request on start, foreground, save, list views or an upload pass without a session": an OkHttp interceptor on the real ApiRemoteSource client records 0 paths. "...with a session the capture upload talks to ingest only and never to api expenses": every recorded path is /api/ingest/. Both pass. |
| REQ-AND-131 | PASS | REQ-AND-131 tests pass. "REQ-AND-131 Undo of a captured expense without a session makes no request and is remembered" passes. The SessionManager constructor still has no wiper (DataEraser depends on SessionManager, not the reverse). |
| REQ-AND-132 | PASS | On the device, loop 1: dialog with counts, Back deletes nothing, Erase leaves 0 rows with defaults re-seeded. Loop 2 interrupted erase: 1 expense present, force-stop, run-as cp a prefs file with capture_closed=true, am start -> expense 0, source 0, category 29, member 1, capture_closed=false. pre-migration-v8.db, .part, -wal and -shm were deleted; keep.txt and androidx.work.workdb* were kept. Closed flag with no data: restart -> 0/29/1, flag false, app alive (pid present). Sign-out first: "REQ-AND-132 the erase signs out of the server first and wipes" passes. In-transaction check: "...flag closes between the last check and the write is dropped by the check inside the transaction" passes. |
| REQ-AND-133 | PASS (unit) | REQ-AND-133 tests pass (now in OutboxTest.kt). "tapping a local row does not open the editor and a server row does" and "saving or deleting a local entry never calls the server and a 404 never deletes it" pass. On the device: tapping the local 300 EGP row on Home opens nothing (no clickable node, UI unchanged) and the row stays. Upgrading a v9 DB with outbox rows on the device: NOT RUN. |
| Carried: onCorruption guard | PASS (unit) | NeverDeleteDatabaseTest passes, and so does "REQ-AND-130 recovery that may lose data is never allowed by the open helper" (allowDataLossOnRecovery(false)). Real page corruption on the device: NOT RUN. |

## Loop-1 findings re-checked
| Loop-1 finding | Now | Evidence |
|---|---|---|
| MAJOR edit screen deletes local rows on 404 | FIXED | Components.kt:210 opens the editor only when serverId != null. ExpenseEdit.attempt() returns Failed for a row without serverId before any HTTP call, and addresses the server by serverId. On the device the local row is not clickable. Unit tests listed above. Server rows stay editable (unit test; no server on the device). |
| MAJOR capture flag stuck closed after a mid-erase crash | FIXED | MasroofApp.onCreate -> DataEraser.recoverInterrupted() reruns the idempotent wipe when the flag is closed. Device repro above: the flag reopened and the erase finished. |
| MINOR wrong error after a post-commit failure | FIXED (new risk below) | Post-commit steps now each run, then PartialEraseException -> EraseState.Incomplete ("The data was erased. A last clean-up will finish the next time the app starts.", en + ar). |
| MINOR in-transaction check untested | FIXED | New test for the check inside the transaction (SmsWork.kt:136). |
| MINOR zero-HTTP recorder | PARTLY | The recorder is real (interceptor on the production OkHttp client in ApiRemoteSource) and covers start (ForegroundSync), the list view models, save and the upload pass. "Refresh" no longer exists. It does not wire the Insights, Budgets or Members remotes, and a save still fires LocalDataSignal, so InsightsViewModel refetches from the server when it is alive. That is not /api/expenses*. |
| NIT Arabic comma | FIXED | MoneyFormat maps U+060C to "," plus a unit test. |
| NIT FAB overlaps Log out | FIXED | android/app/build/qa-probe/05-settings-bottom.png: 96 dp bottom padding, Log out fully clear of the FAB. |

## Findings
| Severity | Where | What | Reproduce |
|---|---|---|---|
| MINOR | data/auth/RoomDataWiper.kt (PartialEraseException path) + DataEraser.recoverInterrupted | After a partial erase the capture flag stays closed until the next process start, so captures are dropped meanwhile. The next start then runs a FULL wipe, which deletes any manual entries the user added after seeing "The data was erased", with no confirmation. Likelihood is low: every post-commit step is a SharedPreferences or File operation that practically never throws. A phase marker ("tables cleared") would let recovery redo only the clean-up. | Unit: make capture.clear() throw (Probe(failClear=true)), then repo.add(...), then recoverInterrupted() -> the entry is gone. |
| MINOR | DataEraser | Recovery runs asynchronously in AppScope while the UI is already usable. An entry saved in the first seconds (awaitIdle up to 5 s) is wiped. There is no mutex between a recovery and a user-started erase; two wipe() calls can overlap, and the first to finish reopens the flag while the other is still mid-way. Low likelihood. | Code inspection. |
| MINOR | LocalDataSignal -> InsightsViewModel; Settings members | Carried from loop 1: a save can trigger a server call to /api/insights, and Settings calls /api/members. Neither is /api/expenses*, so REQ-AND-130 is met. The strict "zero HTTP" wording of REQ-EXP-043 is met only while Insights is not in the back stack. Insights moves on-device in STORY-178. | Open Insights, then Add, then Save. |
| MINOR | SessionUploadCheck / SmsUploader | A capture queued under member A uploads under member B after B signs in. This is acceptable: ADR-0009 D3 says data does not follow the session, it is a two-person single household, the EPIC-17 release rule keeps APKs from main off the owner phone, and STORY-171 stops uploads. Uploads and Undo with no session send nothing (unit tests). | Code inspection. |
| NIT | RoomDataWiper.compact | It checkpoints, then runs VACUUM, but does not checkpoint after VACUUM. The rewritten pages sit in masroof.db-wal (144 KB on the device right after the erase) until the next checkpoint, so old pages remain in the main file until then. | adb run-as ls -la databases right after an erase. |
| NIT | AddExpenseViewModel | Carried: the date is a text field, not a picker. A save just after Cairo midnight in a sheet opened before it stores yesterday at noon with timeKnown=0. | Code inspection. |

## Exploratory
Loop 1 ran 34 MoneyFormat inputs (listed in the loop-1 report; all sane except U+060C, now fixed) plus 6 device flows.
Loop 2 device runs:
- install -r over existing data
- Add 300
- tap the local row on Home (nothing opens, row kept)
- force-stop, then capture_closed=true with 1 expense, then restart (erase finished, flag reopened)
- seeded pre-migration-v8.db, .part, -wal, -shm and keep.txt in no_backup, then erase (only the four pre-migration files went; keep.txt and the WorkManager DB stayed)
- capture_closed=true with no data, then restart (defaults intact, flag reopened, no crash)
- Settings bottom screenshot (no FAB overlap)
Loop 2 code probes:
- SessionManager.memberId() restores the session before answering (so uploads still work when signed in)
- DatabaseStartup runs in attachBaseContext before recovery
- which post-commit steps can throw
- the recovery and erase race
- the partial-erase window
Problems found in loop 2: 2 MINOR (partial-erase window, recovery race), 1 NIT (VACUUM in WAL).
NOT RUN:
- a real v8 APK install producing a genuine pre-migration-v8.db (the safety-copy files were seeded by hand with run-as cp instead)
- the Arabic/RTL erase dialog on the device
- a signed-in session against a real server
- a v9 outbox upgrade on the device
- real SQLite corruption

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main -> "TEST_INTEGRITY: OK". OutboxConversionTest.kt is now OutboxTest.kt (37 -> 35 asserts; the old upload tests were replaced by conversion tests, per REQ-AND-133).
WARN ViewModelTest (assertEquals 19 -> 16; total 26 -> 34): the removed assertEquals belong to these removed tests: REQ-AND-010 offline refresh; REQ-AND-011 error/retry and 401 SIGN_IN status; REQ-AND-013 "sync is scheduled" (scheduler.calls) and the 12_550 minor-units check; REQ-AND-015 Retry/Delete on a failed row. All are features removed by REQ-AND-130 (no refresh from the server) and REQ-AND-133 (no outbox, no Retry). Minor units and the Cairo date are now asserted by the REQ-EXP-043 tests. Verdict: removed because the spec changed, not weakened.
The other decreases (SessionTest, LoginViewModelTest, LoginScreenTest, SmsLifecycleTest, UploadConflictTest, WorkSchedulingTest, CapturedRefreshTest, CapturedPairingTest, SessionHardeningTest) were judged in loop 1: each traces to REQ-AND-130/131/133 reversing wipe-on-logout, refresh or upload. None was weakened.

## Status
PASS: every criterion was observed passing, the gate is green, test integrity is OK, and both loop-1 MAJOR findings are fixed and re-verified on the device. 4 MINOR and 2 NIT remain open.
