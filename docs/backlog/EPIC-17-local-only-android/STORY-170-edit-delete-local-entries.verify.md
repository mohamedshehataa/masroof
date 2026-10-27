# Verify report - STORY-170 (re-verification, loop 2 of 3)

verifier: qa-verifier (fresh context) | date: 2026-10-28 | base: 99bad7b824f10e79bcdc2bd950035bff3db9807b (origin/main) | head: ff77e5e3c4584e5804b80fbb9509344f1aab64f2

## Environment
- Own detached worktree at origin/story/STORY-170-edit-delete-local (ff77e5e), JDK 21.0.12 Adoptium.
- bash scripts/make/check-fast.sh android -- "check-fast GREEN [android] in 121s", EXIT=0 (fresh build, result XML timestamps 2026-10-27T23:08-23:09Z).
- gh pr checks 80 -- head ff77e5e, MERGEABLE; all checks pass except "Android (check-fast)" and "Build artifacts" still pending at time of run.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-EXP-044 | PASS | EditExpenseViewModelTest + EditExpenseRepositoryTest: 18 + 15 tests, 0 fail/skip (confirm writes CONFIRMED only; deleted-meanwhile -> gone) |
| REQ-EXP-045 | PASS | "REQ-EXP-045 zero, empty, negative or too large amounts..." (10000000000.01 rejected, 10000000000 -> 1_000_000_000_000 written); "...notes over 500 show the error, keep the text, write nothing" pass |
| REQ-EXP-046 | PASS | PurgeSchedulerTest 3/3: start + daily periodic unique work (KEEP), worker purges 31-day-old, keeps 1s-old; three quick deletes reach Undo; MasroofApp.kt:42 calls purgeScheduler.schedule() |
| REQ-EXP-047 | PASS | REQ-EXP-047 tests in EditExpense*Test pass (29 distinct REQ-EXP-044..047/AND-134 test cases in results, 0 failures) |
| REQ-AND-134 | PASS | "REQ-AND-134 a double tap after a fast successful write does not write again" + "a double tap writes once and the buttons are disabled meanwhile" + failed-write test pass; locked = busy or done gates all buttons/fields |

## Previous findings
1. Amount ceiling: FIXED. EditExpenseViewModel.kt:169 uses MoneyFormat.MAX_ENTRY_MINOR (1_000_000_000_000). jshell on compiled classes: 10,000,000,000.00 -> 1000000000000 accepted, 10,000,000,000.01 -> 1000000000001 rejected; Edit and Add (parseEntry) agree at the boundary.
2. Double tap after fast success: FIXED. save/confirm/delete return early when done; buttons enabled = !locked.
3. Notes over 500: FIXED. onNotes no longer truncates; save sets notesInvalid, keeps text, writes nothing; error string in en + ar.
4. Undo buffer + purge scheduling: FIXED. deletions buffer 16 (ExpenseEdit.kt:94,106); PurgeScheduler start (ExistingWorkPolicy.KEEP) + 1-day periodic (KEEP).

## Findings
| Severity | Where | What | Repro |
|---|---|---|---|
| NIT | EditExpenseViewModel.kt:165-172 | When notes and amount are both invalid, only the notes error is shown; the amount error appears only on the next Save | onNotes(600 x), onAmount("0"), save() |
| NIT | EditExpenseScreen.kt:193 | Over-limit amount shows the generic add_amount_error, not a "too large" message as Add does | enter 10000000000.01, Save |
| NIT | PurgeSchedulerTest.kt (three quick deletes) | Collector is Dispatchers.Unconfined and never suspends, so the test does not simulate a slow snackbar; the buffer 16 fix is correct by inspection (tryEmit into a 16-slot buffer) | read test |

## Exploratory (inputs, via jshell against compiled MoneyFormat + code reading of the VM)
1. Arabic-Indic 1 followed by ten 0 (U+0661 U+0660x10) -> 1000000000000 accepted
2. same + U+066B U+0660 U+0661 -> 1000000000001 rejected (edit) / TooLarge (add)
3. "10,000,000,000.00" -> accepted
4. "10,000,000,000.01" -> rejected
5. "99999999999999999999999" -> null (no overflow crash), rejected
6. "5k" -> rejected
7. "2.5 الف" -> rejected
8. " 1,250 " -> 125000
9. "١٬٢٥٠" -> 125000
10. "0.001" -> rejected
11. "12,5" -> 1250
12. "-0", "1e3", "92233720368547758.08" -> rejected
13. 500 emoji notes (1000 UTF-16 units) -> 500 code points, accepted
14. 500 x + 300 trailing spaces -> 500 after trim, accepted
15. No HTTP: data/edit/ExpenseEdit.kt, DeletedEntryPurge.kt, EditExpenseViewModel, UndoDeleteViewModel import no retrofit/okhttp/remote; DI binds ExpenseEditor to DefaultExpenseEditor(db, signal, clock, eraseLock) only (ApiExpenseEditRemote is pre-existing frozen code from STORY-168, unbound).
Problems: 0 (2 UX nits above).

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main -- EditExpenseTest 83 -> 124 asserts, LocalSafetyRound1Test 54 -> 54, PurgeSchedulerTest added 0 -> 7; skips 0 -> 0; TEST_INTEGRITY: OK. The fix commit changed the REQ-EXP-045 boundary value (100000000000 -> 10000000000) and replaced the notes-truncation assertion with notes-error assertions: both align the tests with the spec text, not loosened.
