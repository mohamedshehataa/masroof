# STORY-169 verify report

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-27
- Base: origin/main (after the merge of e3db4af)
- Head: 966567f953347c4a7883333119fcc2ad62e3ec17 (parent 35a85ac). Loop 3 judges only the diff babaeee..966567f; loops 1 and 2 verified f017fb9 and babaeee.
- Loop: 3 of 3

## Environment
- env JAVA_HOME=.../jdk-21.0.12.101-hotspot bash scripts/make/check-fast.sh android -> "check-fast GREEN [android] in 81s". The test-results XMLs show 496 tests: 0 skipped, 0 failed, 0 errors.
- bash scripts/quality/test-integrity-check.sh origin/main -> "TEST_INTEGRITY: OK" (624 -> 704 asserts, 0 in deleted files). The same ViewModelTest WARN (assertEquals 19 -> 16) was judged in loop 2 as removed because the spec changed (refresh states, scheduler, Retry/Delete).
- Emulator qa35: ./gradlew :app:assembleDebug, then adb install -r. Probes are in the gitignored android/app/build/qa-probe/.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-EXP-043 | PASS | Device (loops 1 to 3): saves of 1,450 / 200@past date / 300 / 75 give minor units, CONFIRMED, a MANUAL source, and Cairo noon with timeKnown=0 for a past date. The REQ-EXP-043 tests pass. A save now waits on EraseLock (ExpenseRepository.add). |
| REQ-AND-130 | PASS | Home is the first screen on a fresh install (device, loop 1). The recorder test on the production OkHttp client passes. |
| REQ-AND-131 | PASS | The REQ-AND-131 tests pass; the session manager has no wiper. |
| REQ-AND-132 | PASS | Device loops 1 and 2: dialog, Back, Erase, the interrupted erase finished, and the safety copies deleted. Loop 3: see below. |
| REQ-AND-133 | PASS (unit) | The REQ-AND-133 tests pass. A local row is not tappable and its row is kept (device, loop 2). |
| Carried: onCorruption guard | PASS (unit) | NeverDeleteDatabaseTest and the allowDataLossOnRecovery(false) test pass. |

## Loop-3 claims
| Claim | Result | Evidence |
|---|---|---|
| 1 Partial erase: CLEANUP never clears tables; later entries survive; ERASING still does the full wipe | PASS | RoomDataWiper.recover: CLEANUP -> finishCleanup() only, ERASING -> wipe(). Test "after a partial erase an entry added later survives the restart and only the clean-up is finished" passes; "an erase killed after closing the flag is finished at the next start" (ERASING) passes. On the device: saved 75 EGP (1 row), force-stop, run-as cp prefs with erase_stage=CLEANUP, am start -> "1\|7500" and erase_stage=NONE. |
| 1b A failed stage write never wipes unexpectedly | PASS with MINOR | Before the transaction: check(setStage(ERASING)) aborts before anything is deleted (test "a stage that cannot be saved stops the erase before anything is deleted" passes). After the commit: if both CLEANUP writes fail, PartialEraseException is thrown with the stage still ERASING. The UI says "data was erased, clean-up next start", and the next start reruns the FULL wipe over entries added since. Same if the transaction rolls back and the NONE write fails. That needs two failed SharedPreferences.commit() calls (disk full or I/O); see the MINOR finding. |
| 2 One lock over erase, recovery and save; recovery takes it before any save | PASS | EraseLock is a @Singleton injected into DataEraser and DefaultExpenseRepository. recoverInterrupted() calls tryLock() synchronously in MasroofApp.onCreate (before any Activity), and the launched job unlocks in finally. Test "a save made while the start-up recovery runs waits for it and is not wiped" passes: the save is active while recovery holds the lock, 0 rows during recovery, 1 row after. Erase holds the lock across logout and wipe. |
| 3 A delete that returns false is a failure; the stage stays CLEANUP | PASS (code), no test | deleteReceipts/deleteSafetyCopies use filterNot { delete() } then check(), which throws; finishCleanup catches it and sets failed, so PartialEraseException is thrown and the stage is not reset (setStage(NONE) only runs on success). No unit test exercises a delete that returns false (NIT). |
| 4 No regressions | PASS | check-fast GREEN 496/0 failures; TEST_INTEGRITY OK. VACUUM now has a second wal_checkpoint(TRUNCATE) (loop-2 NIT fixed). |

## Findings
| Severity | Where | What | Reproduce |
|---|---|---|---|
| MINOR | RoomDataWiper.wipe (CLEANUP write fails twice after the commit; or the NONE write fails after a rollback) | The stage stays ERASING, so the next start reruns the full table wipe over entries the user added in between, without confirmation. Needs two failed prefs commits; very unlikely. | Unit: a gate whose setStage(CLEANUP) returns false, then wipe(), then repo.add(...), then recover(ERASING) -> the entry is gone. |
| MINOR (carried) | InsightsViewModel / Settings members | A save can trigger /api/insights, and Settings calls /api/members (not /api/expenses*). Insights moves on-device in STORY-178. | Open Insights, then Add, then Save. |
| MINOR (carried) | SessionUploadCheck | A capture queued under member A uploads under member B. Acceptable under ADR-0009 D3, the release rule and STORY-171. | Code inspection. |
| NIT | RoomDataWiper.deleteReceipts / deleteSafetyCopies | No unit test covers a delete that returns false. A file that can never be deleted keeps the stage CLEANUP, so capture stays off at every start. | Code inspection. |
| NIT | PrefsCaptureGate | The key was renamed capture_closed -> erase_stage, so a closed flag from babaeee is ignored. Harmless: babaeee never shipped (release rule). | Code inspection. |
| NIT (carried) | AddExpenseViewModel | The date is a text field; a save after midnight stores yesterday at noon. | Code inspection. |

## Exploratory
Loops 1 and 2: 51 cases (34 amount inputs, device flows, code probes), recorded in those reports.
Loop 3:
- device: save 75 EGP, then erase_stage=CLEANUP, then restart (the entry survived, stage NONE)
- code: when tryLock runs relative to Activity start
- code: whether EraseLock is a shared singleton
- code: failed stage writes before and after the commit
- code: deletes returning false
- code: the old-key upgrade
5 code probes plus 1 device run; problems: 1 MINOR, 2 NIT.
NOT RUN: a real v8 APK install, the Arabic/RTL dialog on the device, a real server session, real SQLite corruption.

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main -> "TEST_INTEGRITY: OK". Loop 3 only adds tests (LocalSafetyRound1Test +3, SmsPipelineTest MemoryGate stage support). The earlier decreases were judged in loops 1 and 2 as removed because the spec changed (REQ-AND-130/131/133), not weakened.

## Status
PASS: every criterion was observed passing, the gates are green, test integrity is OK, and there is no open BLOCKER or MAJOR. 3 MINOR and 3 NIT remain open.
