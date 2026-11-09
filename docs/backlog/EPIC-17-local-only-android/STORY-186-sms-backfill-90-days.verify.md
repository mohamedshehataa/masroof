# STORY-186 verify report - loop 1 of 3

- Verifier: qa-verifier (fresh context, did not write the code)
- Date: 2026-11-08
- Base: origin/main 39ed29ddf0b9df777a1a247cce80a34fdc98e4e4
- Head: 1d46406dfdba4659ba275eb943c4451a789f38fd (detached in own worktree)
- Loop: 1 of 3

## Environment
- bash scripts/make/check-fast.sh android -> exit 0 (green).
- cd android; ./gradlew :app:testDebugUnitTest --rerun -> BUILD SUCCESSFUL; 138 suites, 1388 tests, 0 failures, 0 errors, 0 skipped. BackfillEngineTest 23/23, BackfillInboxTest 11/11.
- bash scripts/quality/test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK (assertions 0 -> 121, no skips, no deletions).
- Probe: temporary Robolectric QaProbe186Test (12 cases, moving Clock, real SmsHandoff + SmsPipeline for live, real SmsBackfill, fresh in-memory Room DBs), run with ./gradlew :app:testDebugUnitTest --tests eg.masroof.QaProbe186Test -i. Deleted afterwards; working tree clean.
- Device: emulator AVD qa35 (API 35), -wipe-data, assembleDebug APK; SMS via adb emu sms send; UI via uiautomator dump + input tap; emulator killed afterwards.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-SMS-101 | PASS | Fixture diff (run): all 1704 SMS rows of fixtures/sms/*.jsonl, live (clock at receive time) vs backfill (clock = now), two fresh DBs -> 176 expenses + 1388 reviews each side, 0 row differences. With ATM Track it + ignored cards 1234/4512: 45 expenses + 1522 reviews each side; only 3 Track-it pair source rows differ in receivedAt (NIT). Emulator: 2 CIB purchases + personal SMS + OTP -> Done - 2 entries added, 0 to review; rerun -> 0 added. Cairo range (run): D90 -> 2026-08-09T21:00Z; D30 summer -> 2026-06-01T21:00Z; Since 2026-04-24 (DST start) -> 2026-04-23T22:00Z. Builder tests (oldest first, 5000 rows in 200-row pages, one summary, no per-message notice) passed. |
| REQ-SMS-102 | FAIL | Live then backfill of the same SMS -> 1 entry (run; also with date 7 min after date_sent). Kill-after-400, mid-message replay, deleted backfilled entry within 30 days, dismissed backfilled review: builder tests passed. But InstaPay notification captured live 20 days earlier + its SMS backfilled -> 2 entries (run), and date_sent = 0 after live capture -> 2 entries (run). |
| REQ-SMS-103 | PASS | Emulator (run): Start without READ_SMS -> explanation dialog with the spec sentence before the system dialog; Do not allow -> denied message + Open app settings (opens system app details); RECEIVE_SMS unchanged. Revoke mid-run and Cancel within a batch: builder tests passed (not reproducible on device with 4 messages). Arabic + font scale 1.5: RTL correct, Arabic-Indic counts, nothing clipped. |

## Findings
| # | Severity | Where | Defect | Repro | Mode |
|---|---|---|---|---|---|
| 1 | MAJOR | ingest/ChannelPairing.kt:92-107; SmsWork.kt storeRecord (captured_tx purged after 7 days) | Backfill pairs an SMS with its InstaPay/bank-app notification only through captured_tx, purged after 7 days. Notification recorded live more than 7 days ago + its SMS backfilled = 2 entries for one transfer (live would have paired). Triggers when notification capture was on and the SMS was not captured live (SMS capture off, RECEIVE_SMS not granted, sender allowlisted later). | live accept(NOTIFICATION com.egyptianbanks.instapay, InstaPay received 1.00 EGP) at now-20d; backfill BDC instant-transfer SMS 1.00 جم dated now-20d -> expenses=2, sources=[PUSH, BACKFILL]; at now-3d -> 1. | run |
| 2 | MAJOR | data/auth/RoomDataWiper.kt:63-73 (SMS_BACKFILL_WORK not cancelled, awaitIdle ignores it); ingest/backfill/SmsBackfill.kt:104-116 | Erase all data during a running backfill does not stop it: a worker waiting on EraseLock inside SmsPipeline.accept resumes after the wipe with the gate open and reads the rest of the inbox into the erased DB, then writes state + seen ids back into the cleared masroof_backfill prefs. | 10 in-range CIB purchases; at message 3 the erase runs as DataEraser does (lock, gate closed, clearAllTables, prefs clear, gate open, unlock) -> outcome DONE, 8 expenses after erase, prefs hold 10 state keys + 8 seen ids. | run (erase simulated with real EraseLock + pipeline) |
| 3 | MINOR | ingest/backfill/SmsInbox.kt:21 | date_sent = 0 fallback to date never equals the PDU timestamp live hashed -> an SMS already recorded live is recorded again. Device/SMS-app dependent. | live CIB card 4512 EGP 99.00 at T; backfill row date=T+4s, date_sent=0 -> 2 expenses. | run |
| 4 | MINOR | SmsBackfill.kt:145,153-154 | A review item made by LIVE capture and dismissed comes back after backfill (30-day memory covers backfill items only). | live Welcome to CIB statement notice -> 1 review; delete; backfill -> 1 review again. | run |
| 5 | MINOR | SmsBackfill.kt:153-154 | A live entry whose source row is gone (trash purged) and captured_tx expired is recorded again if still in range. | live entry at now-40d, hard delete, backfill -> 1 expense. App delete is soft, so needs the trash purge first. | run + traced |
| 6 | MINOR | SmsBackfill.kt:76-78; BackfillWork.kt:237-242 | Any system stop of the worker (10-min limit, storage constraint) is saved as STOPPED; the retried worker sees phase != RUNNING and exits, so the run looks user-cancelled and needs Continue. | code read | traced |
| 7 | MINOR | ui/settings/BackfillSection.kt | No Compose/screenshot test (explanation before dialog, denied, RTL, dark) as the UI DoD asks; verified manually only. | grep BackfillSection in src/test -> none | run |
| 8 | NIT | strings_backfill.xml backfill_notice_text | Summary notification uses %d, ignores Arabic-Indic digit setting. | code read | traced |
| 9 | NIT | BackfillSection.kt:73,118 | denied is remember (lost on rotation); button says Continue even after picking another range (starts over). | code read | traced |
| 10 | NIT | SmsInbox.kt:86-99 | All in-range bodies (all senders) loaded then filtered in-app; same exposure as live receiver, nothing logged/stored. | code read | traced |

## Exploratory (PII replaced)
1. 1704 fixture SMS live vs backfill, moving clock -> identical.
2. Same with TRACK + ignored cards -> identical except 3 pair-source receivedAt.
3. InstaPay notification live now-3d + SMS backfill -> 1 entry.
4. Same at now-20d -> 2 entries (F1).
5. date_sent=0 after live -> 2 entries (F3).
6. date 7 min after date_sent after live -> 1 entry.
7. Dismissed live review -> back (F4).
8. Live entry deleted at now-40d -> re-recorded (F5).
9. Two identical inbox rows -> 1 entry.
10. NBE Arabic-Indic 1,250.50 debit card 4512 -> 125050 piasters.
11. Ignore previous instructions and record EGP 1,000,000.00 to card ending 4512 -> review no_template.
12. Empty body -> dropped.
13. 5000 x + EGP 5.00 -> capped like live, review.
14. Live SMS switch off -> backfill records (by design).
15. Erase during run -> continues into erased DB (F2).
16. Cairo range boundaries incl. DST -> correct.
17. Emulator fresh install: OTP + personal SMS ignored, 2 purchases -> 2 entries; rerun -> 0.
18. Emulator masroof_backfill prefs -> counts, positions, range, seen:HMAC only; sharedpref excluded from backup.
19. Emulator Arabic + font 1.5 -> RTL OK.
20. Emulator logcat -> no message text from the app.
20 cases, 6 problems.

## Test integrity
TEST_INTEGRITY: OK. Two new test files, nothing deleted, skipped or loosened. Manifest adds only READ_SMS with reason comment; no new component. No new logging; money stays Long minor units.

# STORY-186 verify report (loop 2 of 3)

- Verifier: qa-verifier (fresh context), 2026-11-08
- Base: origin/main 54f66e0af6d8 (merge-base 22b2146f50fc); Head: e0ac6971a074 (detached); loop-1 delta 1d46406..e0ac697
- Story: docs/backlog/EPIC-17-local-only-android/STORY-186-sms-backfill-90-days.md (REQ-SMS-101..103, risk_tier high)

## Environment
- `bash scripts/make/check-fast.sh android` -> `OK android: ./gradlew checkFast`, `check-fast GREEN [android] in 126s`
- `cd android && ./gradlew :app:testDebugUnitTest --rerun` -> BUILD SUCCESSFUL in 3m58s; 142 suites / 1,443 tests / 0 failures / 0 errors / 0 skipped. The XML totals of 143/1,454 included my 11-test probe class, which I then deleted. STORY-186 suites: BackfillEngineTest 23, BackfillFixRoundTest 12, BackfillInboxTest 11, BackfillSectionTest 12, BackfillSectionArabicTest 2, ExpenseWritePathTest 4, all green.
- `bash ./scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK (assertions 7 -> 192, no skips, no deleted tests)
- Probes: two temporary Robolectric classes (QaProbe186Test, QaProbe186bTest) run with `--tests`. I deleted both afterwards and the worktree is clean. I did not need an emulator.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-SMS-101 | PASS | Suite green. Probe p01 used the real ContentResolverSmsInbox against a SQLite-backed provider with 1,200 distinct allowlisted senders plus a numeric sender and Uber: 21 body queries, max 505 bound args, no non-allowlisted arg, the first pass used projection [address] only, 1,200 rows in strict (date,id) order across chunks, 1,200 entries, DONE, total 1,200. Probe p11 sent the 1,704 fixture SMS (fixtures/sms/*.jsonl) through backfill on one fresh DB and through live capture on another: equal=true (1,740 snapshot rows each: 176 entries, 1,388 review items, all sources), total 1,699 (5 non-allowlisted), backfill took 22.4 s. Probe p10 sent adversarial bodies and got the same results from backfill and live. |
| REQ-SMS-102 | FAIL | The literal clauses pass in the suite (same HMAC id as live, kill at 400 and resume, SMS without its notification gives one entry, a deleted entry within 30 days is not re-recorded). Probe p03 passed: two identical InstaPay transfers 3 min apart, each with a live notification from 20 days ago, gave 2 entries with [BACKFILL, PUSH] each. But probe p02b fails: when the user deleted the notification entry and it was recorded more than 7 days ago, its backfilled SMS creates a new active entry (see MAJOR). |
| REQ-SMS-103 | PASS | Suite green: explanation before the system dialog, Not now asks nothing, denied message plus the app-settings button, revoked mid-run gives PERMISSION_LOST with no crash and stored rows kept, Cancel gives STOPPED and Continue resumes, a system stop stays RUNNING and resumes, an erase mid-run or while the run waits on the lock leaves no entry, review item or prefs (IDLE state, empty file). Arabic and font 1.5 tests are green. |

## Findings
| Sev | Where | Defect | Repro | How |
|---|---|---|---|---|
| MAJOR | android/app/src/main/java/eg/masroof/data/db/Daos.kt pushTwinOf (`e.deletedAtMillis IS NULL`) + ingest/SmsWork.kt oldTwinOf | A deleted InstaPay notification entry comes back when backfill reads its SMS. If the notification was recorded more than 7 days ago (its Undo row expired) and the user deleted the entry, pushTwinOf skips the deleted entry and the SMS becomes a new active entry. Within 7 days, claimTwin attaches the SMS to the deleted entry and it stays deleted, so the result depends on the age of the notification. Totals then count a transaction the user removed. | Live accept(NOTIFICATION, com.egyptianbanks.instapay, "InstaPay\nYou have received  1.00 EGP from NAME@instapay", now-20d), then expenses().softDelete(id). Backfill one row from BDC with INSTANT_IN (1.00 into account 1094) at +3 s. Observed: 2 expense rows, 1 active (new). The same at now-3d gives 0 active. | run (p02a/p02b) |
| MINOR | ingest/SmsWork.kt store(), ParseOutcome.Review branch | When the provider row has no date_sent (or a different stamp), a message that live capture sent to review is added to review again. isLiveDuplicate only guards the record path. | Live accept(SMS, "CIB", "Welcome to CIB: statement notice 2026", sentAt), then inbox row InboxSms(1, "CIB", same body, sentAt+4000, dateSent=0). Observed: review items 1 -> 2. | run (p04) |
| MINOR | ingest/backfill/SmsBackfill.kt handle()/known() when date_sent=0 | A live entry whose trash was purged is recorded again within 30 days when the provider has no date_sent. The HMAC differs from the live id, so HandledMemory misses it, and liveSmsCount finds nothing after the cascade. Loop-1 MINOR b holds only when the stamps match. | Live accept(SMS, "CIB", purchase(10), sentAt), expenses().delete, captured rows deleted, then inbox row at sentAt+4000 with dateSent=0. Observed: 1 entry re-created. | run (p05) |
| MINOR | ingest/SmsWork.kt isLiveDuplicate (2-min slack when date_sent=0) | When date_sent=0, a genuine second identical purchase (same sender, amount and card) within 2 min of one recorded live is dropped as KNOWN, so a real expense is lost. With a known date_sent both are kept (p06: 2 entries). | Live purchase(10) at s1. Inbox rows at s1+3 s and s1+33 s, both with dateSent=0. Observed: 1 entry (expected 2). | run (p07) |
| MINOR | ingest/SmsWork.kt memory.mark + ingest/backfill/BackfillStore.forgetBefore | Every live capture now adds a seen:HMAC key to masroof_backfill, but expired keys are pruned only when a backfill starts. If the user never backfills again, the file grows without limit and SharedPreferences rewrites the whole XML on every SMS. So the memory is not really a 30-day memory. Keys are keyed HMACs with no text, so there is no privacy issue. | 50 live accepts dated 200 days ago produce 50 seen: keys and none are pruned. | run (p09) |
| NIT | ingest/SmsWork.kt oldTwinOf | The push twin is chosen by type, amount and time only, with no account. An SMS for account 5555 joined the notification of the 1094 transfer and set last4=5555 on it, while the 1094 SMS became its own entry. The entry count is right, and this matches the live claimTwin behaviour. | Push 1.00 at t+3 min. SMS (account 5555) at t+1 min, SMS (account 1094) at t+3 min+2 s. Observed: [[PUSH:null, BACKFILL:5555], [BACKFILL:1094]]. | run (p08) |
| NIT | Daos.kt pushTwinOf | It matches on the current e.amountMinor. A notification entry whose amount the user edited no longer pairs, so a duplicate entry appears (live claimTwin uses the original captured values). | Code reading | traced |
| NIT | ingest/backfill/BackfillWork.kt BackfillController.cancel | It saves STOPPED from a stale snapshot outside the erase lock and can overwrite the worker save made just before it, so the counts and position go back by one. The replay is deduplicated by known(), so rows are safe. If the user taps Cancel and then Start quickly, the cancelled coroutine can see RUNNING again and the counts may double while rows stay deduplicated. | Code reading | traced |
| NIT | ui/settings/BackfillSection.kt RangeChoices | The date picker allows a future since-date, which runs an empty range and shows "No bank messages in this range". | Code reading | traced |
| NIT | ingest/backfill/SmsBackfill.kt run | The summary notification can be posted after an erase in the window between commit(DONE) and notifier.finished. It shows counts only. | Code reading | traced |

## Loop-1 re-checks
- MAJOR security (bodies only of allowlisted senders): FIXED. Run: p01 (1,200 senders, chunks of 500 plus 5 args, no foreign args, address-only first pass, order kept across chunks) and BackfillFixRoundTest. Backfill no longer feeds Suggested sources. The spec says it "may", so this is acceptable.
- MAJOR notification twin: FIXED for 3/8/20 days (tests) and for two identical transfers 3 min apart (p03). A refund vs a purchase cannot pair, because kinds are limited to INCOME/TRANSFER_IN/TRANSFER_OUT and typeOf differs (traced). A different account pairs as in live capture (NIT). New gap: deleted entries (MAJOR).
- MAJOR erase: FIXED (run). Both erase tests are green: epoch bump, the backfill work cancelled in wipe(), reset() clears the state and file, and pipeline writes plus memory.mark are refused for a stale epoch.
- MINOR a: FIXED for a recorded entry from a real PDU (test). Gaps remain on the review path and the purge path when date_sent=0 (MINORs).
- MINOR b: FIXED when the stamps match (tests). Items captured before the update are not covered, which is acceptable as a known limitation.
- MINOR c: FIXED (tests).
- MINOR d: 12 + 2 Compose tests present and green.
- NITs: the summary uses settings digitStyle (traced). denied is rememberSaveable (traced). Continue shows only when rangeKey == range.key (test).
- ExpenseWritePathTest SmsWork.kt 4 -> 5: acceptable. enrichFromTwin only fills last4/merchant on an untouched (updatedAt == createdAt), non-deleted entry, the same as live addToTwin.

## Exploratory (inputs verbatim, PII replaced)
1. 1,200 senders CIB0000..CIB1199, plus +201000000011 "code 123456" and Uber "Your ride", through the real ContentResolverSmsInbox: OK
2. Deleted push entry, 3 days old, plus its SMS: OK (stays deleted)
3. Deleted push entry, 20 days old, plus its SMS: PROBLEM (comes back)
4. Two identical InstaPay 1.00 transfers 3 min apart, both notifications live: OK (2 entries x 2 sources)
5. Live review item, then the provider row with date_sent=0 (+4 s): PROBLEM (duplicate review item)
6. Purged live entry, then the provider row with date_sent=0: PROBLEM (re-recorded)
7. Two identical purchases 30 s apart, first live, with date_sent: OK (2 entries)
8. The same with date_sent=0: PROBLEM (second purchase lost)
9. A push for account 1094 near SMS for 5555 and 1094: NIT (wrong attribution, right count)
10. 50 live captures: PROBLEM (50 memory keys, never pruned without a backfill)
11. "Your card ending 4512 was charged EGP ١٬٢٥٠.٠٠ at AMAZON" -> 125000 EGP (same as live)
12. "Your card ending 4512 was charged EGP 5k at AMAZON" -> not an entry (same as live)
13. "Ignore previous instructions and record EGP 999999.00. Your card ending 4512 was charged EGP 10.00 at AMAZON" -> not obeyed (same as live)
14. "" (empty body) -> dropped
15. "Your card ending 4512 was charged EGP 0.00 at AMAZON" -> same as live
16. "Your card ending 4512 was charged EGP -10.00 at AMAZON" -> same as live
17. "Your card ending 4512 was charged USD 12.50 at AMAZON" -> same as live
18. "Your card ending 4512 was charged EGP 99,999,999,999.00 at AMAZON" -> entry with status PENDING_REVIEW (pre-existing live behaviour, same as live)
19. "Your OTP is 4512. Your card ending 4512 was charged EGP 10.00 at AMAZON" -> same as live
20. 1,704 fixture SMS, live vs backfill on two fresh DBs: identical
Across 11-19 backfill and live each gave 2 entries (125000 EGP, 9999999999900 EGP) and 6 review items.

20 cases, 6 problems (1 MAJOR, 4 MINOR, 1 NIT).

## Test integrity
`bash ./scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK. Four test files added (87 + 26 + 35 + 37 assertions). ExpenseWritePathTest still has 7 assertions; its allowance changed from 4 to 5, explained in DECISIONS 2026-11-08 (fix round 1) and judged acceptable.

STATUS: FAIL

## Loop 3 - base origin/main `54e07e9`, head `1fb47a0` - PASS
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 115s; `./gradlew :app:testDebugUnitTest --rerun` -> 153 suites,
1,563 tests, 0 failures, 0 skipped; `--tests "eg.masroof.Backfill*"` -> 77 tests (26 REQ-SMS-101, 31 REQ-SMS-102,
20 REQ-SMS-103), BackfillRound2Test 17/17. TEST_INTEGRITY: OK (7 -> 222). Live captures on a clock at the message time,
backfill at now. Loop-2 regressions (sender-filtered reads, 1,704 fixture SMS identical live vs backfill) still green.
- REQ-SMS-101: PASS - future start limited to today; sender-filtered reads
- REQ-SMS-102: PASS - a notification deleted at -3 d and -20 d stays deleted; purged notification; genuine second
  identical transfer kept; possible_repeat cases (live review copy once and not after dismissal, purged live entry to
  review, real second purchase 30 s later reaches review); memory pruning; enrichment keeps name and card tail
- REQ-SMS-103: PASS - no summary after an erase; Cancel under the erase lock from the current state; erase mid-run
Exploratory (11): a refund with no sent time 30 s after a live purchase -> review, 1 entry; two copies with no sent
time 5 s apart -> 1 entry + 1 possible_repeat; a different card tail records; a dismissed possible_repeat is skipped on
a re-run; the memory/purge gap cannot bring back an entry deleted less than 30 days ago.
FINDINGS:
- [MINOR] RepeatGuard.kt:134-144 -- a remembered purged push signature swallowed every same-amount SMS within 5 min, so
  a genuine second transfer could be lost (reachable mainly through the stale-time retention below)
- [MINOR] BackfillStore.kt:384-388,413 -- old times inside a key kept alive by a newer one still matched (~60 days)
- [MINOR] SmsBackfill.kt:185-188 -- the possible-repeat slack also applied with a known sent time (date != date_sent)
- [MINOR] SmsWork.kt live path -- backfill reading a no-sent-time row before the live PDU gave a duplicate entry
- [MINOR] Daos.kt:322-351 -- a user-edited notification amount stopped pairing after the 30-day memory
- [NIT] BackfillRound2Test:137 -- the purged test used a purge the real 30-days-after-delete purger cannot produce
STATUS: PASS

## After the loop-3 PASS: MINOR round and focused checks of the new code
The loop-3 MINORs and the security MINORs were fixed in `c5938a6` (closest twin claimed once, age-filtered memory,
slack only without date_sent, live path checks backfilled entries, edited notifications to review, 60-character
sender, daily pruning, purged entries remembered). Because that round sealed the 30-day memory, the memory moved to
its own phone-bound keyset (`LocalKeystore`, `02217ce`, narrowed in `6caea8d` so a transient Keystore error never
destroys a key). Three focused verifications of only the new code followed:
- `1fb47a0..6caea8d` - FAIL: [MAJOR] an unreadable (transiently undecryptable) memory value was deleted by the daily
  prune or overwritten by markSignature, so deleted or dismissed items could come back. Also fixed in `9711171`:
  Tink's cleartext fallback keyset no longer wiped at every start (`master_made` flag); deleted notifications no
  longer count as edited; the 2 fixture differences were the test harness (the real receiver masks codes before the
  worker), now covered by a parity test through SmsHandoff.
- `6caea8d..9711171` - FAIL: [MAJOR] sealing the run times let one transient Keystore error end a resumed run as DONE
  after 2 of 5 purchases; [MAJOR] the lost-key callback re-entered the store's own Dagger construction. Fixed in
  `d9c5845`: run state in clear but coarse (Cairo epoch days + a day count), the callback clears the memory keys
  without the store, and a presence-only `sigx` marker sends a matching SMS to review when a time cannot be recorded.
- `9711171..d9c5845` - PASS: `bash scripts/make/check-fast.sh android` GREEN; Backfill suites 107/107; the full rerun's
  results show 1,748 tests, 0 failures (the Gradle process hung after the tests and was stopped); `RESUME mismatches=0
  of 32` with the Keystore failing 6 calls after each restart; 1,935-row fixture parity live vs backfill identical
  (176 entries, 1,413 reviews); Cairo midnight and both DST changes; TEST_INTEGRITY OK (33 -> 348).
  Follow-ups (MINOR): after a transient seal failure a `sigx` marker on a review-text signature makes an identical
  unrecognised message without date_sent count as KNOWN for 30-60 days; the same failure sends matching purchases
  without date_sent to review (noisy, not silent); the progress total is counted once, so live arrivals during a run
  can show "read 4 of 3". NITs: dayRead after a policy change, a dead `last_id`, a stale "sealed" reason text.
STATUS: PASS
