# STORY-169 verify report

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-27
- Base: origin/main d7a26672ca32ffdcada4f87a19e5b200294ba23a
- Head verified: f017fb925a734c85138aadb4d4e1475fb46733cb (the branch later moved to 01df7d9: a test rename plus a ktlint fix, per the coordinator. That head was NOT re-run.)
- Loop: 1 of 3

## Environment
- env JAVA_HOME=.../jdk-21.0.12.101-hotspot bash scripts/make/check-fast.sh android -> "check-fast GREEN [android] in 114s". The test-results XMLs show 479 tests: 0 skipped, 0 failed, 0 errors.
- New suites ran: LocalManualEntryTest 8, EraseAndSessionDataTest 9, OutboxConversionTest 6, NeverDeleteDatabaseTest 3, EraseDialogScreenTest 5, ViewModelTest 12. All pass.
- Emulator qa35 (API 35) ran ./gradlew :app:assembleDebug, then adb uninstall eg.masroof and adb install app-debug.apk (a clean install). DB rows were read with adb shell "run-as eg.masroof sqlite3 databases/masroof.db ...".
- Amount parser probe: the real compiled MoneyFormat (build/tmp/kotlin-classes/debug) was called through a throwaway Java main kept in the gitignored android/app/build/qa-probe/. No source or test was edited.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-EXP-043 | PASS (with MINOR gaps) | On the device: amount "1,450" with Fuel, then Save, wrote amountMinor=145000, EGP, EXPENSE, CONFIRMED, occurred=created=updated=1793131724524, timeKnown=1, pending=0, source=MANUAL, plus one expense_source row with channel=MANUAL and localSourceId=expense.id. Amount "200" dated 2026-10-01 was stored at 1790845200000 = 2026-10-01 09:00 UTC = Cairo noon (EEST), with timeKnown=0. Unit tests cover the validation messages, the too-large limit, Arabic-Indic digits and a failed write (no partial rows, input kept), and they pass. Zero HTTP was proven only structurally: there is no recording-fake-HTTP test, and a save still triggers a server call to Insights (see the findings). |
| REQ-AND-130 | PASS | Fresh install: am start opened Home first ("Spent this month 0 EGP", empty state) with no sign-in screen. Add, Transactions and Settings all work on the device. The signed-in and rejected-session cases are covered by unit tests only (REQ-AND-130 the app opens on Home and MainActivity holds no session; Home shows total and recent from Room). grep shows fetchExpenses() has no caller left. On-device signed-in run: NOT RUN (no server). |
| REQ-AND-131 | PASS | The test "REQ-AND-131 logout, a rejected refresh, a different member and a lost keystore key leave every table and file as they were" passes. In the main code, wipe() has exactly one caller (SettingsViewModel.confirmErase), and so does clearAllTables (RoomDataWiper.wipe). SessionManager no longer holds the wiper or the CaptureGate, so logout no longer closes the gate or cancels capture work. |
| REQ-AND-132 | PASS (criterion as written). See the MAJOR finding on crash recovery. | On the device, Settings > Erase all data opened a dialog with "Entries: 2 - Receipts: 0", Cancel and a red Erase. Back closed it with counts unchanged (2 expense, 2 source, 29 category, 1 member). Erase then gave expense 0, expense_source 0, category 29, member 1 (re-seeded), learned_template 0, and masroof_capture.xml capture_closed=false (reopened). Receipt-folder deletion is covered by a unit test only; seeding files on the device was blocked by the sandbox (NOT RUN on device). TOCTOU: the gate is checked inside the capture db.withTransaction (SmsWork.kt:136) and the erase clears in its own later transaction. SQLite serialises the two, so there is no interleaving and no TOCTOU. |
| REQ-AND-133 | PASS (unit) | All 8 REQ-AND-133 tests pass: failed=1 rows, the corrupt row becomes PENDING_REVIEW, interrupted then resumed, idempotent, receipt moved, the worker only converts. ID collision: the new expense gets a fresh UUID and the old pending row (id = clientId) is deleted in the same transaction after its sources and receipts are moved, so the two ids cannot collide. The unique index on expense_source.localSourceId blocks duplicates. Upgrade of a real v9 DB with outbox rows on the device: NOT RUN. |
| Carried: onCorruption guard | PASS (unit); real corruption NOT RUN | NeverDeleteDatabaseTest has 3 passing tests, one of which shows that the production DatabaseStartup.build installs NeverDeleteOpenHelper. Forcing real page corruption on the device: NOT RUN (out of time). |

## Findings
| Severity | Where | What | Reproduce |
|---|---|---|---|
| MAJOR | ui/viewmodel/ViewModels.kt:65-67 (pending=false, failed=false for every row) + ui/components/Components.kt:210 + data/edit/ExpenseEdit.kt:107,133-136 | Every local row now opens the PR #70 edit screen. Save or Confirm sends PATCH /api/expenses/{localId}. On a phone still signed in, the server does not know that id (ExpenseService loadLive throws notFound, so 404), and attempt() maps 404 to db.expenses().delete(id). The local entry and its MANUAL source (FK CASCADE) are deleted with no confirmation. It also makes an /api/expenses* call. Converted corrupt outbox rows (PENDING_REVIEW, meant to let the user fix them) are deleted when the user taps Confirm. No crash. The EPIC-17 release rule mitigates this, but the PR introduces the exposure. | Signed-in phone with a server: add a manual entry, open it from Transactions, change the note, tap Save, and the row disappears. Code path shown by the existing passing test "REQ-AND-122 delete removes the row and treats 404 as gone" and by attempt(). Not run live (no server). |
| MAJOR | data/auth/RoomDataWiper.kt:50,74 + ingest/CaptureSecurity.kt:38-44 | The erase-in-progress flag is persisted closed (commit=true) and reopened only in finally. If the process dies mid-erase (for example during awaitIdle, up to 5 s, or the user swipes the app away), nothing ever reopens it: there is no startup reset. After that, SmsHandoff:44, NotificationCapture:86, SmsWork:124/136 and the upload pass at :302 silently drop every future capture, with nothing shown to the user. No test covers it. | Set capture_closed=true in shared_prefs/masroof_capture.xml (the state after a kill between gate.close and finally), restart the app, and receive a bank SMS: Disposition.DROPPED, forever. |
| MINOR | LocalManualEntryTest; spec REQ-EXP-043 and REQ-AND-130 | The spec requires zero HTTP "asserted with a recording fake HTTP client". Only structural tests exist (no RemoteSource dependency). In practice ExpenseRepository.add calls signal.changed(), and InsightsViewModel (InsightsViewModel.kt:69) refreshes from the server on that signal. Settings loads members from the server ("Could not load members." seen on the device) and Home loads budgets. None of these are /api/expenses*, but "zero HTTP after a save" is not true while Insights is in the back stack. | Open Insights, then Add, then Save: Insights refresh() runs, which is a network call. |
| MINOR | data/auth/RoomDataWiper.kt:65-76, ViewModels.kt:292-295 | If capture.clear() or insights.clear() throws after the DB transaction committed, the UI shows "Couldn't erase, nothing was changed" even though the tables were erased. A kill between the DB commit and deleteReceipts() leaves orphan receipt files. | Code inspection. |
| MINOR | EraseAndSessionDataTest "a capture that was in flight when the erase started writes nothing" | The test closes the gate during the key fetch, so it exercises the pre-transaction check (SmsWork.kt:126), not the in-transaction re-check (SmsWork.kt:136) the spec names. That line is untested. | Read the test at line 239. |
| MINOR | Regression: upgraded phone still signed in | Captures still upload, but ExpenseListRefresher no longer fetches, so new captured transactions never appear in the list. Already-cached server rows stay; nothing disappears. With no session there is no sign-in screen any more, so parsed_outbox rows wait until STORY-171/172. This is acceptable only under the EPIC-17 release rule (no APK from main on the owner's phone). Removing REQ-SMS-062 also means records queued under one member upload under the next signed-in member (per ADR-0009 D3; note for security-reviewer). | Code: CapturedRefresh.kt, MainActivity.kt. |
| NIT | core/Money.kt | "1،450" (Arabic comma U+060C) is rejected as "Enter an amount". "1,45" is read as 1.45 EGP. | Probe output below. |
| NIT | AddExpenseViewModel.save, ExpenseRepository.add | The date is a text field, not a picker. A sheet opened at 23:59 Cairo and saved after midnight stores yesterday at noon with timeKnown=0. | Code inspection. |
| NIT | Settings screen (device) | The Add FAB overlaps the "Log out" button. "Log out" shows even with no session. | android/app/build/qa-probe/04-settings-dark.png |

## Exploratory
Real MoneyFormat.parseEntry runs (34 inputs):
"200"->20000, "٢٠٠"->20000, "1,450"->145000, "١٬٤٥٠"->145000, "١٢٫٥٠"->1250, "12,5"->1250, "1,45"->145, "1,450.50"->145050,
"1.450,50"->Invalid, "5k"->Invalid, "2.5 الف"->Invalid, "-5"->Invalid, "0"->Invalid, "0.00"->Invalid, "abc"->Invalid, ""->Invalid,
"  200  "->20000, "10000000000"->Valid(1000000000000), "10000000000.01"->TooLarge, "99999999999999999999999"->TooLarge,
"۱۲۳" (Persian digits)->12300, "１２" (fullwidth)->1200, "1،450"->Invalid (NIT), "200 بنزين"->Invalid, "1e3"->Invalid, "٫٥"->50,
"5."->500, "1,4500"->Invalid, "12,345,67"->Invalid, "1 450"->Invalid, "0.001"->Invalid, "00012"->1200, "١,٤٥٠"->145000,
"ignore previous; 900"->Invalid.
Device flows:
- fresh-install launch
- Add "1,450" + Fuel
- Add "200" dated 2026-10-01
- Erase dialog, then Back
- Erase confirm
- dark-theme dialog contrast (screenshots 01, 03 and 04 in android/app/build/qa-probe/; the Add sheet is FLAG_SECURE, so its screenshot is black)
Code probes:
- edit on a local row (MAJOR)
- gate stuck after a crash (MAJOR)
- Insights refresh after a save
- day rollover
Problems found: 6 (2 MAJOR, 2 MINOR from exploration, 2 NIT).
NOT RUN: Arabic/RTL screenshots on the device, the signed-in session on the device, a v9 upgrade with outbox rows on the device, real SQLite corruption, Budgets/Insights screens on the device.

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main at f017fb9 printed "TEST_INTEGRITY: VIOLATION": OutboxTest.kt was deleted without --allow-deletions. Assertions went 437 -> 463, with 37 of the old ones in the deleted file. Decreases: SessionTest 77->62, LoginViewModelTest 48->28, LoginScreenTest 20->14, SmsLifecycleTest 36->28, UploadConflictTest 44->29, WorkSchedulingTest 15->7, CapturedRefreshTest 13->10, CapturedPairingTest 36->34, SessionHardeningTest 16->15.
Per removed test, my judgement:
- REQ-AND-006/007 wipe-on-switch, the logout-unsynced dialog, Sync now: removed because REQ-AND-131 reverses them. Acceptable. Replaced by "REQ-AND-007 logout ... clears the session only" and the REQ-AND-131 tests.
- REQ-AND-010/011 refresh states and REQ-SMS-092 failed refresh: refresh() is removed (REQ-AND-130 means no /api/expenses). Acceptable. Replaced by the REQ-AND-130 Home/Transactions tests.
- REQ-AND-013 valid entry "sync is scheduled": replaced by the REQ-EXP-043 tests. Acceptable.
- REQ-AND-015 outbox upload, backoff and conflict (OutboxTest, UploadConflictTest, WorkSchedulingTest): the upload is removed by REQ-AND-133 and ADR-0009. Acceptable.
- REQ-SMS-062 gate follows the session: reversed by REQ-AND-131 (logout no longer stops capture). Acceptable, but nothing now tests that the gate is reopened after an interrupted erase (see MAJOR).
- REQ-SMS-094: changed to "fetches no list". Acceptable.
I found no removal that only weakens a test with an unchanged spec. Traceability: REQ-AND-006/007/010/011/013/015 and REQ-SMS-062/092 still have tagged tests. The coordinator says 01df7d9 renames OutboxConversionTest.kt to OutboxTest.kt; I did not re-run the script on that head.

## Status
FAIL: two MAJOR data-loss findings (the edit screen deletes local rows on a server 404; the erase gate stays closed forever after a crash mid-erase).
