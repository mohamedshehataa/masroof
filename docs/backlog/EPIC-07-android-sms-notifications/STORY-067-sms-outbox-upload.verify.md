# Verify report: PR #63 fix/captured-upload (STORY-067, REQ-SMS-003, REQ-SMS-090..093), round 3

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-27
- Base: 15557fa2ca11d9c015135edbc85e697d71d8b20c (origin/main)
- Head: 5cb258d01ab514a0dd0067ce5a55e823a7d54fdb
- Loop: 3 of 3 (rounds 1 and 2 kept below for history; their findings are tracked here as RESOLVED or open)

## Environment
- JAVA_HOME Temurin 21.0.12. bash scripts/make/check-fast.sh android sdlc: check-fast GREEN [android sdlc] in 216s (gradlew checkFast OK, sdlc-lint 0 errors, hook tests 624/624, quality tests 145/145, contract lint OK).
- gradlew :app:testDebugUnitTest for CapturedPairingTest 12/12, CapturedRaceTest 10/10, CapturedDedupTest 8/8, CapturedUploadTest 1/1, RoomMigrationTest 5/5; 0 skipped, 0 failures.
- bash scripts/quality/test-integrity-check.sh origin/main: TEST_INTEGRITY: OK, assertions 96 (base) to 207, 0 in deleted files.
- python scripts/quality/traceability.py --out (scratchpad): 403 REQs, 125 covered, 0 unknown, gate PASS.
- gh pr checks 63: 14/14 pass on run 40406669395; gh run view gives headSha 5cb258d (incl. Security gitleaks/semgrep, Backend integration, Test integrity, PR gate).
- Throwaway Robolectric probe QaR3ProbeTest (8 tests; real SmsPipeline + SmsUploader + CapturedActions; in-memory Room or a real v7 DB built from schemas/7.json and migrated with MIGRATION_7_8; a fake server idempotent on local id that can store before answering; a mutable clock). Run with gradlew :app:testDebugUnitTest --tests eg.masroof.QaR3ProbeTest, then deleted; git status clean.

## Criteria
| # | Claim / REQ | Result | Evidence |
|---|---|---|---|
| 1 | A row ever claimed is never replaced; the newcomer is dropped (REQ-SMS-090) | PASS | q02 (push upload stored on the server, answer lost as Retry, SMS 30 s later, retry): server 1, captured NOTIFICATION with expense e1. q06 with the request in flight 150 / 299 / 301 / 400 s while the SMS arrives: server 1 each. CapturedPairingTest lost-answer and outlives-claim tests green |
| 2 | Same message again after skip / after replace makes no record | PASS | q03 SMS, push, push: outbox [SMS], captured 1. q04 push, SMS, push: outbox [SMS], captured 1. q04b the same after the SMS upload: server 1, outbox 0 |
| 3 | Queued twins paired at pass start | PASS | q09 real v7 DB (s1 SMS last4 1094, n1 push, 1 EGP INCOME, 3 s apart) migrated, uploadPending: attempts 0/0 gives requests [s1]; 3/0 gives [s1]; 0/3 gives [n1] (the tried one stays); 3/3 gives [s1, n1] by design. v8 queued unclaimed push next to an uploaded unpaired SMS twin: push dropped, server 1. Three queued v7 rows (s1, n1, n2 +60 s): server [s1, n2], correct |
| 4 | Live claim schedules a retry; release only own claim; callTimeout 90 s below 5 min | PASS | q05: claim 10 s old gives retry true, retryAfterSeconds 291, then sent after the claim runs out (CapturedPairingTest too); release with token 123 leaves claimedAtMillis unchanged; release with own token gives 0 (claims 1). ApiRemoteSource callTimeout 90 s vs UPLOAD_CLAIM_TTL_MILLIS 5 min (code read). Two concurrent passes: outer sent 1, inner retry at 301 s, server 1, no further retry |
| 5 | Undo paths | PASS | u1 Undo during the request: deferred, the server expense deleted, captured 0, outbox 0. u2 Undo of a row claimed but not yet in the lock set: deferred, row kept, after upload the server expense deleted. CapturedPairingTest: never-sent Undo forgets the row (removed, not deferred, outbox and captured empty); tried-row Undo goes through the deleter |
| 6 | TRANSFER fetched and mapped; failed refresh keeps cache; totals only EXPENSE (REQ-SMS-092) | PASS | CapturedPairingTest REQ-SMS-092: the real ApiRemoteSource asks EXPENSE, INCOME, TRANSFER and keeps type TRANSFER; a failing TRANSFER call gives RefreshOutcome.Offline with the cached row intact. CapturedRaceTest totals 5000 with TRANSFER and INCOME listed |
| 7 | Round-2 passes still pass | PASS | CapturedRaceTest 10/10 (order independence, pairing at most once, receive-time pairing, REQ-SMS-093 labels, 7 to 8 migration), CapturedDedupTest 8/8 (REQ-SMS-091 wording), RoomMigrationTest 5/5 (chains to 8). Probe cc: a 7 EGP push uploading while the 1 EGP push and SMS arrive gives server 2 |
| 8 | No assertion weakened | PASS | test-integrity OK (96 to 207). CapturedRaceTest a claim is atomic: the old final assertion (deletable after release) encoded exactly the round-2 F1 bug; it is replaced by a stricter sequence: wrong-token release is a no-op, own-token release frees the row for a new claim, a once-claimed row is never deletable. The unclaimed-row replacement premise is still asserted by a queued record that nobody is uploading is still replaced by the richer twin. Legitimate premise change, not a weakening |
| 9 | Gates | PASS | check-fast android sdlc GREEN; traceability 0 unknown; gh pr checks 63 14/14 at 5cb258d |
| REQ-SMS-003 | Captured record lands on the server | PASS | CapturedUploadTest 1/1 |
| REQ-SMS-091 | Notice wording | PASS | CapturedDedupTest REQ-SMS-091 green |

## Findings
| Sev | Where | What | Reproduce |
|---|---|---|---|
| MINOR (R3-1) | ChannelPairing + SmsPipeline.storeRecord knows() + CapturedActions.undo | The pair memory (pairedLocalId) lives only on the winner captured row; Undo deletes that row, so the dropped or replaced twin message accepted again afterwards is stored as a new transfer. After an uploaded winner is undone the server gets a NEW record under a new local id; after an unsent winner is undone the transfer comes back. Needs the same message to be accepted again after the Undo (intake job re-run, re-posted notification in the same post second). | q04c: push, SMS replaces it, upload, Undo SMS (server expense deleted), the same push again, upload: server 2 distinct records, captured [NOTIFICATION]. u5: push, SMS, Undo SMS (never sent, forgotten), the same push again, upload: server 1 record although the user undid the transfer |
| MINOR (R3-2) | MIGRATION_7_8 claims backfill, one-time residuals | Pre-v8 pairs that cannot be paired safely still upload twice: both rows tried (3/3, by design); a v7 upload killed mid-request (attempts 0 but on the server: the poorer row is dropped and the other one sent); a v7 pair where one record was already uploaded (uploaded v7 rows get no kind, so they never match). | q09 3/3: requests [s1, n1]. q09_kill (server already holds n1, both attempts 0): server [n1, s1]. q09_up (s1 uploaded in v7, n1 queued): server [s1, n1] |
| MINOR (R3-3) | SmsUploader.heldStep + ParsedOutboxDao.claim | A claim left by a killed upload lies in the future when the phone clock is then moved back; the row is not sent until the clock passes it again. Retries keep coming (backoff ceiling 6 h) but cannot claim. Not an endless loop, but a stuck row for as long as the clock jump. | q05: claim at now, kill, clock minus 1 day: retry true, retryAfterSeconds 86701, 0 requests; clock plus 1 day 301 s: sent 1 |
| NIT (R3-4) | SmsUploader.moveToReview | A tried row the user undid (deferred Undo) that the server then rejects for good shows up in the local Needs review list. | u3: Retry, Undo deferred, Rejected: reviews 1 |
| NIT (R3-5) | SmsUploader.recordSent | Undo of a tried row whose upload then answers Sent without an expense id stays requested forever and the server expense is never deleted. Pre-existing; the server returns an id for created and duplicate records. | u4: captured [undoRequested true, expenseId null], deleter never called |
| NIT | values-ar notice | The Arabic notice still mixes the English institution name (open since round 1). | round-1 p12 |
| RESOLVED | round-2 F1 lost answer | q02 server 1 | probe q02, CapturedPairingTest |
| RESOLVED | round-2 F2 same message again | q03 / q04 outbox [SMS] | probes q03, q04 |
| RESOLVED | round-2 F3 queued v7 pair | q09 0/0 gives 1 | probe q09, CapturedPairingTest |
| RESOLVED | round-2 F4 held row not retried | retry 291 s | probe q05, CapturedPairingTest |
| RESOLVED | round-2 F5 / F6 TTL vs call timeout, release of a foreign claim | 5 min TTL above the 90 s callTimeout; release is token-checked; q06 up to 400 s in flight gives 1 | probes q05, q06 |
| RESOLVED | round-2 F7 TRANSFER fetch untested | CapturedPairingTest REQ-SMS-092 refresh tests | gradlew run above |
| RESOLVED | round-2 NIT Undo ignores the claim column | removeUnsent uses deleteIfUnclaimed | probe u2 |

## Exploratory
Inputs: SMS from BDC: تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 رقم المعاملة bda86be1 للمزيد أتصل ب 16990. Push from com.egyptianbanks.instapay: InstaPay, newline, You have received  1.00 EGP from mohamed.shehataaaaa AT instapay. Receive time 2026-10-26T20:04:10Z, push +3 s, clock 21:05:00Z.
1. q02 lost answer then SMS: 1, OK
2. q06 in flight 150 / 299 / 301 / 400 s: 1 each, OK
3. q03 SMS, push, push: 1, OK
4. q04 push, SMS, push, before and after upload: 1, OK
5. q04c Undo of the uploaded winner, then the replaced push again: 2 server records, R3-1
6. u5 Undo of the unsent winner, then the replaced push again: the transfer comes back, R3-1
7. q09 v7 pair attempts 0/0, 3/0, 0/3: 1 each, the tried one kept, OK
8. q09 v7 pair attempts 3/3: 2, R3-2 by design
9. q09 v7 killed mid-request, n1 already on the server: 2, R3-2
10. q09 v7 SMS uploaded, push queued: 2, R3-2
11. q09 three queued v7 rows s1, n1, n2 +60 s: 2, OK
12. v8 queued unclaimed push next to an uploaded unpaired SMS: push dropped, 1, OK
13. q05 claim 10 s old / wrong-token release / own-token release: retry 291 s / no-op / freed, OK
14. q05 clock moved back 1 day after a killed upload: stuck about 1 day, R3-3
15. u1 Undo during the request: expense deleted, OK
16. u2 Undo between claim and lock: deferred, expense deleted, OK
17. u3 Undo then Rejected: review item appears, R3-4
18. u4 Undo then Sent without id: never deleted, R3-5 pre-existing
19. two concurrent passes: 1 record, the second pass schedules one retry at 301 s, no loop, OK
20. a 7 EGP push uploading while the 1 EGP push and SMS arrive: 2, OK

20 cases, 8 problems across 5 findings (R3-1 x2, R3-2 x3, R3-3, R3-4, R3-5); none BLOCKER or MAJOR.

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main: TEST_INTEGRITY: OK; assertions 96 to 207, none in deleted files, no new skip; SmsLifecycleTest 36 to 36, ParsedIngestApiIT 60 to 63. The CapturedRaceTest assertion change is a premise change required by the round-2 F1 fix and is replaced by stricter assertions (criterion 8). The script did not write .claude/state/test-integrity.log in this worktree; its stdout was used.

## Round 1 and round 2 (history)

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

---

# PR #69 verification (fix/sync-after-capture-upload): REQ-SMS-094 + STORY-112 budgets duplicate adopt

- Verifier: qa-verifier (fresh context), 2026-10-27, loop 1 of 3
- Base 9898d81273f7e5a3114508c7f657bf3b174271c5 (PR #68 head), head 45dabb8cce087414541639d6440542d0296e4b89; diff = git diff 9898d81...45dabb8 (8 files, +260/-3)
- JVM/Robolectric level only (emulator run is separate). JAVA_HOME = Temurin 21.0.12.

## Environment
- JAVA_HOME=... bash scripts/make/check-fast.sh android sdlc -> check-fast GREEN [android sdlc] in 121s (gradle checkFast OK, hook tests 624/624, quality tests 145/145, sdlc-lint 0/0, contract lint OK)
- gh pr checks 69 -> all 14 checks pass incl. PR gate, Android check-fast, Test integrity, Security, SDLC traceability
- python scripts/quality/traceability.py --out (scratch) -> 404 REQs, 0 unknown, gate PASS; REQ-SMS-094 row: 2 tests PASS
- ./gradlew :app:testDebugUnitTest --tests eg.masroof.CapturedRefreshTest --tests eg.masroof.BudgetsLogicTest --tests eg.masroof.QaProbe069Test -> BudgetsLogicTest 17/17, CapturedRefreshTest 2/2, probe 5/5 (probe file deleted afterwards; git status clean)

## Criteria
| Claim | Result | Evidence |
|---|---|---|
| 1 REQ-SMS-094 one refresh + one signal per pass with sent>=1, none with sent=0 | PASS | CapturedRefreshTest 2/2 PASS; probe: sent-then-retry sent=1 refreshes=1; sent-then-401 sent=1 refresh+1; rejected / gate closed / 401-only / Retry-only -> no refresh, fetches unchanged |
| 1a no sync -> upload -> sync loop | PASS | ExpenseListRefresher depends only on ExpenseRepository + LocalDataSignal; DefaultExpenseRepository.refresh = syncOutbox (manual outbox) + 3 GETs, no SmsWorkScheduler/SmsUploader; LocalDataSignal is only collected by InsightsViewModel; SessionState.SignedIn is a data class, so a token refresh with the same name/persisted does not re-emit to the MasroofApp scheduleUpload collector; probe concurrent run uploads=[a,b,c,d] (each row once, no extra upload) |
| 1b refresh failure does not fail/roll back the pass | PASS | probe refresh-fail: IOException / HTTP 401 / HTTP 500 -> sent=2, pendingLeft=0, no exception, signal still sent (signals 1,2,3) |
| 1c concurrent passes | PASS | probe: two uploadPending on Dispatchers.Default, 50 ms remote latency, 4 rows -> sents=[2,2], uploads=[a,b,c,d], refreshes=2, left=0 |
| 1d server rows visible via repository flow | PASS | CapturedRefreshTest asserts observeAll ids {server-1, server-2}; probe concurrent list=[srv-1, srv-2] |
| 2 duplicate create adopts same category+period, updates when values differ | PASS | BudgetsLogicTest 2 new REQ-AND-073 tests PASS; probe B8 rollover-only diff -> update (50000,true) saved=1; B9 Arabic-Indic digits -> update 90000 |
| 2a no match -> Duplicate stays; never other category/period | PASS | probe B2 weekly Groceries vs monthly create -> DUPLICATE, no update; B3 All-spending vs only Car -> DUPLICATE; B3b Car vs only All-spending rows -> DUPLICATE; B7 empty list -> DUPLICATE; B6 edit path Duplicate -> no adopt |
| 2b money Long minor units | PASS | BudgetDraft.amountMinor (Long) compared with BudgetRowUi.limit.amountMinor (Long); no double/float in diff |
| 3 no weakened tests; FakeBudgetsRepository open is additive | PASS | bash scripts/quality/test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK, 62 -> 140 assertions, 0 in deleted files, 0 skips; BudgetsFixtures diff is only class -> open class |
| 4 gates + no logging of bodies/amounts | PASS | check-fast GREEN, PR checks green, trace 0 unknown; git diff 9898d81...HEAD -- android/app/src/main has no Log/println/Timber/logger lines |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| MAJOR | BudgetsViewModel.kt adoptExisting + BudgetsScreen.kt BudgetDialog | A Duplicate on create now silently overwrites an existing budget that the user did not choose to edit. The Add dialog starts with categoryId = null (All spending) even when hasTotal is true; the menu only hides the item. With a total budget already shown, Add -> amount 900 -> Save gets 409, then adoptExisting updates the existing total budget from 4,000 EGP to 900 EGP and closes the dialog as saved. Before this PR the user got the DUPLICATE error. The same happens when the local cache is stale and the other member already created a budget for that category: the limit the other member set is replaced. The adopt cannot tell an earlier save of mine that timed out from a budget that was already there. Suggestion: adopt only when the list before the save had no budget for that category+period (it appeared between attempts); otherwise keep DUPLICATE. | probe B1: FakeBudgetsRepository with mutation=Duplicate, snapshot=BudgetsFixtures.snapshot() (holds ID_TOTAL at 400000); vm.save(null, null, "900", false) -> saved=1 error=null updated=[(22222222-...-222222222222, (90000,false))] |
| MINOR | SmsWork.kt uploadPending + CapturedRefresh.kt | refreshAfterUpload is not contained. DefaultExpenseRepository.refresh catches only IOException, HttpFailure and SerializationException; any other exception (Room/SQLite, IllegalStateException from the HTTP stack) escapes uploadPending after rows were sent. The worker then fails, and the computed retry for Retry/held rows is never scheduled (rows wait for the next trigger). Sent rows are not affected. Suggestion: runCatching around the refresh (rethrow CancellationException), or compute the report first. | probe: rows a,b; remote a=Sent, b=Retry(30); refresher throws IllegalStateException -> uploadPending throws IllegalStateException, pendingLeft=[b], report (retry=true, 30 s) lost |
| MINOR | BudgetsLogicTest.kt | The committed REQ-AND-073 tests cover only the two happy adopt paths. Nothing pins that no match after refresh keeps DUPLICATE, or that another category or period is never adopted (the claim-2 guards). Only these verifier probes cover them. | add tests that mirror probes B2, B3, B3b, B7 |
| NIT | BudgetsViewModel.kt adoptExisting | The refresh outcome is ignored. When the refresh fails, a stale cached row is adopted and updated (probe B5 saved=1). The update still goes to the server, so this is harmless. Currency is not part of the match (one household currency today). | probe B5 refreshOutcome=Offline |

## Exploratory (inputs verbatim)
1. Upload pass, rows a=Sent, b,c=Retry(30) -> sent=1 retry=true after=30, 1 refresh
2. Rows b=Sent, c=Unauthorized -> sent=1 retry=false, refresh still runs once, c stays queued
3. All Rejected -> sent=0, no refresh
4. CaptureGate closed, row d queued, remote would answer Sent -> sent=0, no refresh, no fetch
5. Only Unauthorized -> sent=0, no refresh
6. Refresh fetchExpenses throws IOException("offline") / HttpFailure(401) / HttpFailure(500) -> pass sent=2, queue empty, signal still emitted
7. Refresher throws IllegalStateException("boom") with a=Sent, b=Retry(30) -> exception escapes, retry lost (MINOR)
8. Two concurrent passes over 4 rows with 50 ms latency -> each row uploaded once, 2 refreshes, list shows server rows
9. Budget create 409, All-spending 4,000 exists, user adds All-spending "900" -> existing overwritten to 900 (MAJOR)
10. Budget create 409, only WEEKLY Groceries exists, create MONTHLY Groceries "1000" -> DUPLICATE kept
11. Budget create 409, only Car exists, create All-spending "1000" -> DUPLICATE kept
12. Budget create 409, only All-spending rows, create Car "1000" -> DUPLICATE kept
13. Budget create 409, Car exists, adopt update returns Offline -> error OFFLINE, not saved
14. Budget create 409, refresh Offline, stale cache has Car -> adopted + updated (NIT)
15. Budget edit (editingId=Car) returns Duplicate -> no adopt, DUPLICATE
16. Budget create 409, empty list after refresh -> DUPLICATE
17. Budget create 409, Car 500 exists, user enters "500" with rollover on -> update (50000, rollover true), saved
18. Amount "٩٠٠" (Arabic-Indic) -> 90000 minor, adopt update
19. Amount "92233720368547758.07" -> 9223372036854775807 (Long.MAX, no overflow), adopt update
20. Budget create returns Offline -> no adopt, OFFLINE, 1 refresh only

20 cases, 2 problems (9, 7).

## Test integrity
bash scripts/quality/test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK (62 -> 140 assertions, none in deleted files, no new skip/tolerance). The only change to existing test code is open class FakeBudgetsRepository; no member changed.

Verdict: FAIL (1 MAJOR: a duplicate-adopt silently overwrites an existing budget).
