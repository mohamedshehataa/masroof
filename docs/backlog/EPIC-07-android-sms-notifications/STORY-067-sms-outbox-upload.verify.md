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
