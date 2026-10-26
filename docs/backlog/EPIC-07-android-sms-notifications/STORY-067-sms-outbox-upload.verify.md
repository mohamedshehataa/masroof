# Verify report: PR #63 fix/captured-upload (STORY-065 / STORY-067, REQ-SMS-003/090/091)

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-26
- Base: 1934da475cc899ad37a27d94b85b7b7484136852 (origin/main)
- Head: 3bc424a465ceb9d0144edde4046349a434a0b36d (code verified at 0ce9062; 3bc424a only adds REQ-SMS-090/091 to STORY-067 + TRACEABILITY.md, confirmed by git diff --stat)
- Loop: 1 of 3

## Environment
- bash scripts/make/check-fast.sh backend android sdlc at 0ce9062 -> check-fast GREEN [backend android sdlc] in 145s (hook tests 624/624, quality tests 145/145, CapturedDedupTest 8/8, CapturedUploadTest 1/1, RoomMigrationTest 5/5 freshly run).
- python scripts/quality/traceability.py at 3bc424a -> 401 REQs, 0 unknown, gate PASS (at 0ce9062 CI failed: unknown REQ-SMS-090/091; fixed by 3bc424a).
- ./gradlew integrationTest --tests eg.masroof.adapters.in.rest.ParsedIngestApiIT -> 13 tests, 0 failures, incl. "REQ-SMS-003 the exact JSON the phone sends for a received InstaPay transfer lands".
- semgrep custom rules (docker semgrep/semgrep on a git-archive copy, no .git): Ran 9 rules on 268 files: 0 findings.
- gh pr checks 63 (run 40398183147): all pass except Android (check-fast) still pending at time of check.
- Throwaway Robolectric probes (QaProbeTest, QaLabelProbeTest) drove the real SmsIntakeWorker (TestListenableWorkerBuilder) with the real SmsPipeline, SmsUploader, AndroidCapturedNotifier and in-memory Room, with a fake ParsedRemote. Deleted afterwards, nothing committed.

## Criteria
| # | Claim | Result | Evidence |
|---|---|---|---|
| 1a | SMS then InstaPay push gives 1 record and 1 notice "Received 1 EGP via ..." | PASS | probe p01: serverRecords=1 SMS:INCOME:100:1094, titles=[Received 1 EGP via Banque du Caire] |
| 1b | Push then SMS (online) gives 1 record | PASS | p02: serverRecords=1 (NOTIFICATION record, already uploaded, so the SMS is dropped) |
| 1c | Equal transfers more than 5 min apart are both kept | PASS | p03 (+6 min) gives 2; p03c (5m01) gives 2; p03b (4m59) gives 1 |
| 1d | Different amounts are kept | PASS | p04 gives 2 |
| 1e | Outgoing SMS + outgoing InstaPay push dedup | PASS | p15 (instantOut SMS then "You sent EGP 750.00") gives 1; p15b offline push then SMS gives 1 (the SMS with last4 replaces it) |
| 1f | A purchase is never deduped with a transfer | PASS | p06 / p06b / p06c / p15c each give 2 |
| 2a | Upload happens within the intake work | PASS | p01: record on the fake server right after doWork, with no upload job run |
| 2b | Offline is retried later | PASS | p07: outbox=1, scheduleUploadRetry(35s, 1); next uploadPending sends 1 |
| 2c | Session expired uploads after sign-in | PASS | p11: Unauthorized keeps the row; refresh Rejected gives SignedOut(SESSION_EXPIRED); login emits SignedIn, the MasroofApp collector schedules the upload; uploadPending sends 1 |
| 3 | Room 7->8 keeps rows; 3->8 chain | PASS | p13: captured c1 kept (undo=true, kind=null, occ=0), expense type=EXPENSE status=CONFIRMED, outbox m1 and parsed c1 kept; RoomMigrationTest 5/5 incl. the 2->8, 3->8, 4->8, 5->8 and 6->8 chains |
| 4a | INCOME / PENDING_REVIEW labels in the list | FAIL | QaLabelProbeTest: "Money received - pending review" shown on 2 rows, one of them an EXPENSE purchase of 60,000 EGP with PENDING_REVIEW status (see F3) |
| 4b | Home / Insights totals exclude income | PASS | p14: observeTotal = 6005300 (income 100 + 900 excluded); Insights are server-side and JdbcAnalyticsQueries already filter type in EXPENSE/REFUND (unchanged) |
| 5 | Backend accepts the exact Android JSON (INCOME, PENDING_REVIEW) | PASS | ParsedIngestApiIT 13/13 |
| 6 | Gates | PASS | check-fast green; test-integrity OK; semgrep 0; trace PASS at 3bc424a; CI green except Android still pending |

## Findings
| Sev | Where | What | Reproduce |
|---|---|---|---|
| MAJOR | android/.../ingest/SmsWork.kt:243-247 (supersedeOrSkipDuplicate) | A "richer" SMS supersedes a twin whose upload is IN FLIGHT. The outbox and captured rows are deleted while the HTTP call runs, so the server ends up with both expenses (a duplicate income) and the push expense has no captured row (no Undo). Intake jobs are not unique, and every intake now calls uploadPending, so the window is the round trip of a real upload. CapturedLocks.isInFlight is not consulted. | Fake ParsedRemote: while uploading the NOTIFICATION record, call pipeline.accept(SMS). Probes p08 and p16 give serverRecords=2 [NOTIFICATION:INCOME:100, SMS:INCOME:100:1094], captured=1 |
| MAJOR | SmsWork.kt:231-242 (otherChannel match) | Matched twins are never consumed, so one SMS absorbs any number of other-channel records. The result also depends on arrival order: SMS, push1, push2 (two separate 1 EGP transfers 57 s apart) gives 1 record (one real transfer silently lost), while push1, push2, SMS gives 2. This contradicts the rule of the PR itself that same-channel messages are never merged. | p09: smsIn; pushIn(+3s); pushIn(+60s) gives serverRecords=1. p09c (online) and p09b (offline) in reverse order give 2 |
| MAJOR | android/.../ui/components/Components.kt:226-231 | The label keys off reviewPending alone. Any EXPENSE with status PENDING_REVIEW is labelled "Money received - pending review". The backend sets PENDING_REVIEW for any parsed amount above REVIEW_CEILING_MINOR (5,000,000 = 50,000 EGP), so a big purchase is shown as received money. | QaLabelProbeTest: rows (EXPENSE/PENDING_REVIEW 60,000 EGP), (INCOME/PENDING_REVIEW), (INCOME/CONFIRMED) give pendingReviewLabels=2 receivedLabels=1 |
| MINOR | MIGRATION_7_8 + otherChannel | Rows queued before the upgrade have kind=null and never match. The two records of the owner from v0.1.3, still in the outbox, will both upload after the update, so the 1 EGP transfer lands twice. | p13: v7 queued push c1, then the SMS after the migration gives parsed=[NOTIFICATION, SMS] |
| MINOR | MessageParser occurredAt vs notification post time | Dedup compares the SMS body time against the notification post time. An SMS without a time in the body that arrives more than 5 min late, or a bank clock or tzdata 1 h off Cairo, is not deduped. | p10 (SMS without a time, 10 min later) gives 2; p10b (receive 21:04Z, body 23:04 Cairo = 20:04Z) gives 2 |
| MINOR | ApiRemoteSource.kt:234-238 | Outgoing transfers become type TRANSFER on the server. Android fetches only EXPENSE + INCOME, so the user sees "Sent 750 EGP via ..." and then nothing in the app: the same symptom as the reported bug, for money out (pre-existing, not addressed). | Code read + ParsedTransactionService.java:251 maps TRANSFER_OUT/TRANSFER_IN to TRANSFER |
| MINOR | CapturedDedupTest.kt:194 | Tagged REQ-UI-040, which is the web duplicate-card requirement (STORY-088). The income-excluded-from-totals behaviour has no correct REQ id. | grep REQ-UI-040 docs/backlog |
| MINOR | RoomMigrationTest | No dedicated 7->8 test seeds v7 captured/parsed/expense rows; the existing tests only append MIGRATION_7_8 to older chains. | git diff of SmsLifecycleTest.kt |
| NIT | strings (ar) + noticeLabel | The Arabic notice mixes in the English institution: "استلمت 1 ج.م عن طريق Banque du Caire". | p12 |
| RESOLVED | CI SDLC job at 0ce9062 | Failed on unknown REQ-SMS-090/091; fixed by 3bc424a, SDLC job passes on run 40398183147. | gh pr checks 63 |

## Exploratory
Inputs (PII as in the report from the owner, already in committed fixtures):
1. SMS BDC "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 رقم المعاملة bda86be1 للمزيد أتصل ب 16990", then push com.egyptianbanks.instapay "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay" (+3 s)
2. The same pair in reverse order
3. Push at +6 min, +5m01, +4m59 relative to the body time
4. Push with "2.00" instead of "1.00"
5. SMS CIB "Transfer of EGP 750.00 to Sara via InstaPay was successful. Available balance EGP 4,000.00" + push "InstaPay\nYou sent EGP 750.00 to Sara" (the CIB SMS goes to review, not auto-recorded)
6. SMS BDC "تم تنفيذ تحويل لحظي من حسابكم 1094 بمبلغ 750.00 جم في 23:04 يوم 10/26/26 رقم المعاملة bda86be2 للمزيد أتصل ب 16990" + push "InstaPay\nYou sent EGP 750.00 to Sara", both orders, online and offline
7. Arabic push "تم التحويل بنجاح\nتم تحويل 750 جنيه الى سارة احمد" then the CIB SMS
8. Card purchase "Your card ending 4512 was charged EGP 1.00 at AMAZON" + income push 1.00; and 750.00 + outgoing push 750
9. Income SMS 1.00 + outgoing push "You sent EGP 1.00 to Sara"; outgoing SMS 750 + incoming push 750
10. Offline (Retry) then online
11. Upload in flight while the SMS intake runs (race), plus the upload-job variant
12. SMS, push1, push2 (+1 min); and push1, push2, SMS (online and offline)
13. SMS without a body time "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED" delivered 10 min after the push
14. Phone receive time 21:04Z with the body saying 23:04
15. 401 then refresh rejected, a message during expiry (dropped by design), then login
16. Arabic locale notice titles
17. v7 DB with a queued push, migrated, then the SMS arrives
18. Totals with EXPENSE/PENDING_REVIEW 60,000 EGP + INCOME rows + a pending local row
19. List labels for EXPENSE/PENDING_REVIEW, INCOME/PENDING_REVIEW, INCOME/CONFIRMED

19 cases, 6 problems (F1, F2, F3, legacy rows, late or off-clock SMS, Arabic label).

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main gives TEST_INTEGRITY: OK. Assertions 96 -> 132; no deletions, skips or loosened tolerances. SmsLifecycleTest changes only append MIGRATION_7_8 to the migration chains.

---

## Round 2 (head 879d27c)

# Verify report: PR #63 fix/captured-upload (STORY-067, REQ-SMS-003, REQ-SMS-090..093), round 2

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-27
- Base: 15557fa2ca11d9c015135edbc85e697d71d8b20c (origin/main)
- Head: 879d27c1faa9ce55c5637a5cd74121edcd17356a
- Loop: 2 of 3 (round-1 report replaced; its findings are tracked below as RESOLVED or still open)

## Environment
- JAVA_HOME Temurin 21.0.12, bash scripts/make/check-fast.sh android sdlc: check-fast GREEN [android sdlc] in 149s (gradlew checkFast OK, hook tests 624/624, quality tests 145/145, contract lint OK). Fresh results: CapturedRaceTest 10/10, CapturedDedupTest 8/8, CapturedUploadTest 1/1, 0 skipped.
- bash scripts/quality/test-integrity-check.sh origin/main: TEST_INTEGRITY: OK, assertions 96 (base) to 169, 0 in deleted files.
- python scripts/quality/traceability.py --out (scratchpad file): 403 REQs, 125 covered, 69 passing, 0 unknown, gate PASS.
- gh pr checks 63: all 14 checks pass on run 40401605599 (headSha 879d27c confirmed with gh run view), incl. Security (gitleaks, semgrep with the custom rules), Backend integration (ParsedIngestApiIT), Test integrity, PR gate. Semgrep not re-run locally; the Android diff adds no Log/Timber/println and no execSQL built from string templates (grep of git diff origin/main...HEAD).
- Throwaway Robolectric probe QaR2ProbeTest: real SmsPipeline, real SmsUploader, in-memory Room, a fake ParsedRemote that records each request and can accept a message or move the clock while the request is on the wire, a mutable Clock; q09 builds a real v7 database from schemas/7.json and runs MIGRATION_7_8. Ran with gradlew testDebugUnitTest --tests eg.masroof.QaR2ProbeTest; deleted afterwards, git status clean.

## Criteria
| # | Claim / REQ | Result | Evidence |
|---|---|---|---|
| 1 | One server record under any interleaving (REQ-SMS-090) | FAIL | Forward race (SMS accepted while the push upload is in flight): CapturedRaceTest green. Reverse (push accepted while the SMS upload is in flight) q01: server [SMS], 1 record, captured SMS row has expenseId e1 so Undo works: PASS. But q02 (push upload times out after the server stored it, then the SMS arrives) gives server [NOTIFICATION, SMS]; q03/q04 (the same message accepted again) give 2 queued records; q06 (request in flight more than 2 min) gives server [NOTIFICATION, SMS]. See F1, F2, F5 |
| 2 | Each record pairs at most once, order independent | PASS (listed cases) | CapturedRaceTest: SMS, push +3s, push +60s gives 2; push, push, SMS gives 2 offline and online. CapturedDedupTest: +6 min gives 2, different amount gives 2, same-channel SMS twins give 2. q07a income SMS + outgoing push gives 2; q07b purchase SMS + income push gives 2; q07c two pushes 1 s apart give 2 |
| 3 | Pairing by phone receive time | PASS | CapturedRaceTest SMS without body time at +4 min gives [SMS]. q08a 4m59 gives 1, q08b 5m01 gives 2, q08c both receive times shifted +1 h (body still 23:04 Cairo) gives 1, q08d SMS with receive time 0 (falls back to the clock) + push 1 s later gives 1 |
| 4 | Killed upload never stuck; retry after a stored timeout makes no second record | FAIL | q05: claim 10 s old from a killed upload: uploadPending gives sent 0, retry false (no retry scheduled), row stays; after the clock passes 2 min it is sent (sent 1). Server idempotency on (device_id, local_id): ParsedTransactionService.java:134-153 returns DUPLICATE with the same expense id; Backend integration job green at 879d27c (ParsedIngestApiIT). But q02: after a lost answer the queued row is replaced by the twin under a NEW local id, so server idempotency cannot help: 2 server records (F1) |
| 5 | List labels follow the type (REQ-SMS-093) | PASS | CapturedRaceTest REQ-SMS-093 green: EXPENSE/PENDING_REVIEW 60,000 EGP gives expense_pending_review, INCOME/PENDING_REVIEW gives income_pending_review, INCOME/CONFIRMED gives income_received, TRANSFER gives transfer_label, EXPENSE/CONFIRMED gives null. values-ar has transfer_label, transfer_pending_review, expense_pending_review |
| 6 | TRANSFER rows fetched + listed; totals only EXPENSE (REQ-SMS-092) | PASS (totals); fetch not demonstrated | CapturedRaceTest REQ-SMS-092: total 5000 with EXPENSE 5000 + TRANSFER 75000 + INCOME 100, 3 rows listed; CapturedDedupTest REQ-SMS-092 green. ApiRemoteSource.fetchExpenses now asks EXPENSE, INCOME and TRANSFER (code read only, no test: F7) |
| 7 | Room 7 to 8 | FAIL | CapturedRaceTest migration test green (captured, outbox, parsed_outbox, expense rows kept; kind and last4 backfilled; claimedAtMillis 0; type EXPENSE, status CONFIRMED); RoomMigrationTest chains green in checkFast. But q09 (the owner's two still-queued v0.1.3 records, bank SMS s1 + InstaPay push n1, 1 EGP, captured 3 s apart) migrates to s1 INCOME/1094/paired false and n1 INCOME/null/paired false, and the next uploadPending sends both: server [SMS, NOTIFICATION], sent 2 (F3) |
| 8 | No assertion deleted or weakened | PASS | test-integrity OK (96 to 169). CapturedUploadTest push moved from +3 s to +6 min: assertions unchanged (2 queued, server rules accept both bodies, 2 sent, every captured row gets an expense id), so it still proves both payload shapes pass the server rules through the real Retrofit client; the +3 s owner case is now covered by CapturedRaceTest and CapturedDedupTest with a fake remote. CapturedDedupTest only renamed REQ-UI-040 to REQ-SMS-092 |
| 9 | Gates | PASS | check-fast android sdlc GREEN; traceability 0 unknown; gh pr checks 63 all pass at 879d27c (semgrep in the Security job) |
| REQ-SMS-003 | Captured record lands on the server | PASS | CapturedUploadTest 1/1 (real Retrofit + server rules) |
| REQ-SMS-091 | Notice wording | PASS | CapturedDedupTest REQ-SMS-091 green |

## Findings
| Sev | Where | What | Reproduce |
|---|---|---|---|
| MAJOR (F1) | SmsWork.kt matchOtherChannel + Daos.kt deleteIfUnclaimed | A queued record whose upload reached the server but whose answer was lost (read timeout or dropped connection, mapped to SmsUploadResult.Retry; the claim is released in finally) is still replaced by the richer SMS. The server keeps the push record and then gets the SMS under a different local id: 2 records for one transfer. deleteIfUnclaimed ignores attempts. | Probe q02: accept push at+3s; uploader with a remote that records the request and answers Retry; clock +30 s; accept the bank SMS; uploader answers Sent. Observed server [NOTIFICATION, SMS], captured SMS only |
| MAJOR (F2) | SmsWork.kt storeRecord / matchOtherChannel (regression of this round) | A skipped or replaced message leaves no trace of its own local id, and its twin is now marked paired, so accepting the SAME message again (same local id, e.g. WorkManager re-running an intake job that was stopped after its transaction committed, more likely now that the intake job also uploads) stores it as a new transfer. In round 1 the twin stayed matchable and the re-run was skipped. | Probe q03: accept SMS, push at+3s, the same push again: outbox [SMS, NOTIFICATION], captured SMS paired true + NOTIFICATION paired false. Probe q04: push, SMS (replaces it), the same push again: outbox [SMS, NOTIFICATION] |
| MAJOR (F3) | Daos.kt MIGRATION_7_8 | The backfill gives queued v7 rows a kind, but nothing pairs two rows that are BOTH already queued; pairing only runs when a new message arrives. The owner's two queued v0.1.3 records (the reported 1 EGP transfer) both upload after the update. | Probe q09: v7 db from schemas/7.json with captured_tx s1 (SMS) and n1 (NOTIFICATION) + parsed_outbox rows (INCOME, 100, EGP, s1 last4 1094, attempts 3), migrate, uploadPending: server [SMS, NOTIFICATION], sent 2 |
| MINOR (F4) | SmsWork.kt uploadOne | A row held by a fresh claim (an upload killed less than 2 min ago) is skipped with an empty Step: no retry is scheduled, so it waits until the next message, sign-in or app start. | Probe q05: claim the queued row at now-10s, uploadPending: sent 0, retry false, row still queued; clock +121 s, uploadPending: sent 1 |
| MINOR (F5) | SmsWork.kt UPLOAD_CLAIM_TTL_MILLIS vs OkHttp | The 2 min claim TTL is not tied to a call timeout (AppModule sets 15 s connect/read, no callTimeout), so a slow-trickle response or refresh plus retry can exceed it; the twin then replaces the in-flight row and the server gets both. | Probe q06: the remote moves the clock +150 s and accepts the SMS while the push request is on the wire: server [NOTIFICATION, SMS] |
| MINOR (F6) | SmsWork.kt uploadOne finally / Daos.kt release | release sets claimedAtMillis to 0 without checking it is still its own claim, so an uploader whose stale claim was taken over wipes the new live claim and reopens the replacement window. Same root as F5. | Code read |
| MINOR (F7) | ApiRemoteSource.kt fetchExpenses | TRANSFER fetching has no test (Fakes.kt overrides fetchExpenses); one failing call of the three now fails the whole refresh. | grep fetchExpenses in android/app/src/test: only Fakes.kt |
| NIT | Captured.kt CapturedActions.undo | Undo of a queued row deletes it without looking at the claim column; it relies on the in-memory CapturedLocks entered after the claim statement returns. Pre-existing pattern. | Code read |
| NIT | values-ar notice | The Arabic notice still mixes the English institution name (unchanged from round 1). | Round-1 p12 |
| RESOLVED | round-1 F1 in-flight supersede | SMS accepted while the push upload is in flight now leaves 1 server record that can be undone. | CapturedRaceTest green; probe q01 (reverse order) also 1 |
| RESOLVED | round-1 F2 twins never consumed | SMS, push, push gives 2; push, push, SMS gives 2 online and offline. | CapturedRaceTest green |
| RESOLVED | round-1 F3 label from review state | A large PENDING_REVIEW expense reads Needs review. | CapturedRaceTest REQ-SMS-093 |
| RESOLVED | round-1 late SMS / clock | Pairing uses the receive time. | q08a-d, CapturedRaceTest |
| RESOLVED | round-1 TRANSFER not fetched, REQ-UI-040 tag, no 7 to 8 test | Fetch added (untested, F7), tag renamed to REQ-SMS-092, migration test added. | git diff 3bc424a..879d27c |
| PARTIAL | round-1 legacy queued rows | Kind is backfilled, but the owner's queued pair still uploads twice. | F3 |

## Exploratory
Inputs (PII as in the owner's report, already in committed fixtures). SMS from BDC: تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 رقم المعاملة bda86be1 للمزيد أتصل ب 16990. Push from com.egyptianbanks.instapay: InstaPay (newline) You have received  1.00 EGP from mohamed.shehataaaaa(at)instapay. at is 2026-10-26T21:04:10Z, clock 21:05:00Z.
1. q01: SMS queued; push accepted while the SMS upload is on the wire: 1 record, OK
2. q02: push upload answered Retry after the server stored it; SMS 30 s later: 2 records (F1)
3. q03: SMS, push, the same push again: 2 records (F2)
4. q04: push, SMS, the same push again: 2 records (F2)
5. q05: claim left by a killed upload 10 s ago: not sent, no retry; sent after 2 min (F4)
6. q06: request in flight 150 s while the SMS arrives: 2 records (F5)
7. q07a: income SMS + push InstaPay (newline) You sent EGP 1.00 to Sara: 2, OK
8. q07b: CIB SMS Your card ending 4512 was charged EGP 1.00 at AMAZON + income push: 2, OK
9. q07c: two identical-text pushes 1 s apart: 2, OK
10. q08a/b: SMS received 4m59 / 5m01 after the push: 1 / 2, OK
11. q08c: both receive times +1 h, SMS body still 23:04: 1, OK
12. q08d: SMS with receive time 0 (clock fallback) + push 1 s later: 1, OK
13. q09: v7 database with the owner's queued SMS + push, migrated, uploaded: 2 records (F3)

13 cases, 6 problems (F1, F2 twice, F3, F4, F5).

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main: TEST_INTEGRITY: OK; assertions 96 to 169, none in deleted files, no new skip. SmsLifecycleTest assertion count unchanged (36 to 36). The CapturedUploadTest timing change keeps every assertion and its purpose (the server rules accept both payload shapes). The script did not write .claude/state/test-integrity.log in this worktree; its stdout was used.
