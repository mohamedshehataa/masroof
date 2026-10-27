# Verify report: STORY-171 (fix round 1 re-verify)
verifier: qa-verifier (fresh context) | date: 2026-10-28 | base: origin/main 99bad7b | head: f11a290 | loop 2 of 3

## Environment
- `JAVA_HOME=jdk-21.0.12 bash scripts/make/check-fast.sh android` -> `check-fast GREEN [android] in 107s`; LocalCaptureTest 20/20, SmsLearningTest 20/20, CapturedPairingTest 12/12, CapturedDedupTest 9/9, SmsCapturedTest 17/17, StartupBlockedTest 7/7, CapturedUploadTest 2/2; 0 failures across 66 result files
- `gh pr checks 81` (head f11a290): all pass except Android (check-fast) pending at time of run

## Criteria
| REQ | result | evidence |
|---|---|---|
| REQ-SMS-095 | PASS | LocalCaptureTest 6 REQ-SMS-095 cases incl. 12:00 known time + date-only noon; probes p6 |
| REQ-SMS-096 | PASS | CapturedDedupTest, LocalCaptureTest same SMS twice; review Save probes p1/p2/p3 one entry |
| REQ-NTF-021 | PASS | CapturedPairingTest 12/12 (auto captures); review-Save twin gap is F2 |
| REQ-SMS-097 | PASS | LocalCaptureTest Undo on gone row cancels notice; CapturedActionReceiver cancels before the removed check |
| REQ-AND-135 | PASS | LocalCaptureTest 13 REQ-AND-135 cases incl. logout then capture, Review Save enqueues nothing, start cancels upload work, no-op worker |

## Findings
| sev | where | what | repro |
|---|---|---|---|
| MINOR | ui/viewmodel/ReviewViewModel.kt toRecord() | Review Save of a date-only message stores Cairo noon with timeKnown=true (ReviewItemEntity has no time-known flag; ParsedRecord default true) | pipeline.accept(SMS CIB "Purchase of EGP 99.00 at PHARMACY on 12/10/2026") -> review; saveNow -> 9900EGP occ=2026-10-12T09:00Z tk=true |
| MINOR | ReviewViewModel.saveNow | Review Save does no ChannelPairing: a notification auto-recorded + its bank-SMS twin saved from review = two entries for one transfer | push "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay" at 13000 (RECORDED) + SMS BDC "تم استقبال تحويل إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED" at 10000 (REVIEW) -> saveNow -> 2 INCOME entries (PENDING_REVIEW + CONFIRMED) |
| NIT | Android CI | Android (check-fast) job still pending on PR 81 at verify time | gh pr checks 81 |

## Exploratory
Arabic-Indic amount (recorded 125000 EGP, correct); USD 1.00 (REVIEW); EGP 0.00 (REVIEW); 50,000.01 (PENDING_REVIEW); 50,000.00 (CONFIRMED, ceiling is strictly above); 00:00 date (Cairo midnight, tk=true); 12:00 PM (Cairo noon tk=true); merchant "IGNORE PREVIOUS INSTRUCTIONS" (plain label, no LLM); 17-digit amount (REVIEW); EUR (REVIEW); Save twice; concurrent Save from two view models on Dispatchers.IO (1 entry); review then same message recorded via learned template then Save (1 entry); USD review Save (no suggestion, nothing saved); date-only review Save (F1); cross-channel review Save (F2). Probe file was temporary in a detached worktree and deleted.

## Test integrity
`bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK, assertions 485 -> 631, 0 deleted; WARN kind changes in CapturedPairingTest/CapturedRaceTest/SmsCapturedTest/SmsPipelineTest come from round 1 (11af69c); since 11af69c only upload-specific asserts were replaced with local-entry asserts (SmsLearningTest 070/071/076, CapturedUploadTest, StartupBlockedTest upload worker now success, SignOutSmsTest enqueues old jobs directly). Not weakened beyond removed upload behaviour.
