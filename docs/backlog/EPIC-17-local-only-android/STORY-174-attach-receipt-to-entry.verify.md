# Verify report: STORY-174 (loop 3 of 3)
Verifier: qa-verifier (fresh context) | Date: 2026-10-28 | Base: fe0c4bb (origin/main), loop-2 ref da5b4ae | Head: 32b9010

## Environment
- Detached worktree at origin/story/STORY-174-attach-receipts = 32b9010; JDK 21.0.12 (Adoptium).
- `bash scripts/make/check-fast.sh android` -> "check-fast GREEN [android] in 122s", exit 0.
- `./gradlew :app:testDebugUnitTest --tests 'eg.masroof.Receipt*'` -> ReceiptStoreTest 22/0 fail, ReceiptViewerTest 5/0, ReceiptsViewModelTest 6/0, 0 skipped.
- `gh pr checks 84` -> all pass except "Android (check-fast)" still pending at report time.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-RCPT-015 | PASS | 4 tests green incl. new "two attaches at once both complete and an out of memory is a failure that leaves nothing" |
| REQ-RCPT-016 | PASS | 5 tests green (magic bytes, byte cap, endless stream, 72 MP header, 30-page PDF) |
| REQ-RCPT-017 | PASS | 8 tests green incl. "stored long edge is exactly 2560", EXIF/GPS strip, orientation 6 |
| REQ-RCPT-018 | PASS | 6 tests green incl. "pages longer than 50 to 1 are not drawn", 612x792 -> 1440x1863, Compose 10x3000 page |
| REQ-RCPT-019 | PASS | 7 tests green incl. new thumbnail remove, purge, sweeper |
| REQ-RCPT-020 | PASS | 3 tests green |

## Findings
| Sev | Where | What | Repro |
|---|---|---|---|
| MINOR | android/app/src/test/java/eg/masroof/ReceiptViewerTest.kt (test "a 1 by 300 page shows without throwing") | The 10x3000 bitmap goes into a plain non-scrolling Column, which already caps child height at the window (1920 px), so the test passes with or without `heightIn(max = 8000.dp)`. The real viewer uses a LazyColumn (unbounded height), so the cap is not regression-guarded. The test name also says "1 by 300" but the bitmap is 10x3000. | Read the test; Column passes bounded maxHeight to children |
| NIT | ReceiptViewer.kt:181 | Too-long pages use `error()` -> IllegalStateException -> generic rcpt_failed line; this works (caught at :248). | n/a |
| INFO | PR 84 CI | Android (check-fast) job was still pending; merge only after it is green. | `gh pr checks 84` |

## Bounds analysis (item 1)
- `pageBitmapSize` returns null when ph > 50*pw or pw > 50*ph (Long math, no overflow). For every non-null result, the width is at most widthPx (<= 1440, ReceiptViewer.kt:238), height <= 50*width, and the binary search keeps w*h <= 4,000,000 (lo=1 always fits because heightFor(1) <= 50).
- Python port, 300k random inputs incl. 2^31-1 -> max 4,000,000 px, max height 14,126.
- Layout: the LazyColumn item height is at most min(50*layoutWidth, 8000dp*density) = 32,000 px at xxxhdpi, well under 262,143. FillWidth scales to the constrained height, so it cannot request more.
- `openPdf` (:197-207): fd open and the PdfRenderer constructor are both inside the try; catch(Exception) closes fd (runCatching) -> null -> rcpt_failed state (:223-225).
- Decode (ReceiptStore.kt:256-259): sample doubles while (w/s)*(h/s) > 16M. 5119x5119 -> s=2; 2560-output test still green.

## Exploratory (pageBitmapSize, widthPx 1440 unless stated)
(1,50)->282x14100; (50,1)->1440x28; (4000,200000)->282x14100; (200000,4000)->1440x28; (612,792)->1440x1863; (595,842)->1440x2037; (1,51)->null; (0,0)->1440x1440; (-5,100)->null; (2^31-1,2^31-1)->1440x1440; (1,50,widthPx=1)->1x50; (14400,288,1)->1x1; (10,500)->282x14100; (3,149)->283x14055; plus 300k random. Also checked: decode 5119x5119 and 10000x1600 sample math, purge/sweeper merge, and Settings composition. No problems beyond the findings.

## Merge check (item 5)
`git diff origin/main...HEAD --stat` -> 20 files, all STORY-174 (receipts, DAO additions, strings, DECISIONS, story). MasroofApp.kt keeps cancelLeftoverUploadWork, deleteLegacyInsightsCache, eraser.recoverInterrupted, purgeScheduler.schedule, and adds the sweeper. SettingsScreen shows AnalyticsSettingsSection + SmsCaptureSection + ReceiptsStorageSection + MembersRoute. The only removed lines vs main are a DAO KDoc line, one EditExpenseScreen line (replaced) and story status.

## Test integrity
`bash scripts/quality/test-integrity-check.sh origin/main` -> 3 added files, assertions 0 -> 121, TEST_INTEGRITY: OK. Changes since loop 2: in the viewer, (1,100) became (1,50)!!, which matches the new null contract and is covered by the new null test. In the VM, assertFalse(exists) became awaitGone with a 10 s withTimeout that throws, so it only waits and is not weaker.
