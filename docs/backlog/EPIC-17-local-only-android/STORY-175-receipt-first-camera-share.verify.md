# Verify report: STORY-175 (receipt-first camera and share), loop 3 of 3

- Verifier: qa-verifier (fresh context)
- Date: 2026-10-29
- Base: a3c5e3addcf0954536c9f5b117397436e62ef3d7 (previous loop head), origin/main d7ecef72640b9980c0f77c631a38862a875aea97
- Head: 864f36ecfa5d1689a6c4620eb125d54c8f62c767
- Scope this round: `git diff a3c5e3a..HEAD`: ReceiptStore concurrency (in-flight registry, abandonable inactivity copy, quota in commit), MasroofApp camera cleanup on appScope+IO, 4 new tests.

## Environment
- `JAVA_HOME=".../jdk-21.0.12.101-hotspot" bash scripts/make/check-fast.sh android` -> `OK android: ./gradlew checkFast`, `check-fast GREEN [android] in 80s`, exit 0.
- Test results (android/app/build/test-results/testDebugUnitTest, this run, 2026-10-29T11:26Z): ReceiptCameraShareTest tests=27 skipped=0 failures=0 errors=0; zero `<failure|<error|<skipped` across all result XML.
- `gh pr checks 86` at head 864f36e: all completed jobs pass; Android (check-fast), Backend (integration + ArchUnit) and Build artifacts were still pending when checked.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-RCPT-021 | PASS | 6 tagged tests in ReceiptCameraShareTest passed (camera hidden without resolver, photo stored unattached, FileProvider scope, stale camera clean, cache target and backout, no permissions/not exported) |
| REQ-RCPT-022 | PASS | 16 tagged tests passed, including the 4 new ones: sweep during running attach, late open leaves no .part and closes stream, re-share at cap is Duplicate while a new file is QUOTA, odd telephony authorities refused |
| REQ-RCPT-023 | PASS | 5 tagged tests passed (new entry from receipt, list order + stale hint, sweeper keeps old unattached, delete removes row+file, attach picks last 30 days) |
| REQ-RCPT-019 (STORY-174 regression) | PASS | ReceiptStoreTest "the startup sweeper deletes files without a row and part files, and keeps files with a row" passed |

## Findings
| Severity | Where | What | Repro |
|---|---|---|---|
| MINOR | ReceiptStore.kt:322, :339 | The re-encoded/copied target name is added to `inFlight` before `copyTo`/`compress`; if that throws (disk full, OOM in compress) `onStored` is never called, so `stored` is null and the `finally` in attach (:93-97) neither removes the name nor deletes the partial target. Leak is in-memory only and the file is cleaned by the next process start's sweep, so impact is small. | Make `raw.copyTo(target)` throw (e.g. read-only dir after the copy) -> name stays in `inFlight` for the process lifetime |
| MINOR | ReceiptStore.kt:287-292, :300-303 | A source whose `read` returns 0 forever: each 0-byte read counts as activity, so the 30 s inactivity limit never fires and only the 5-min overall cap stops it; the header loop has no `abandoned` check, so after abandon such a stream spins its IO thread indefinitely (pre-existing behaviour of the header loop). | Share a provider stream whose `read(b,off,len)` returns 0 |
| NIT | ReceiptCameraShareTest | No test for caller cancellation during the copy (abandon + stream close + .part delete); by code reading `withTimeoutOrNull` rethrows the parent cancellation, so the finally at :172-178 runs promptly. | n/a |

No BLOCKER or MAJOR. No deadlock: attachLock is only taken in copyAndStore and eraseLock is taken inside it (commit); no code holds eraseLock and then calls attach (grep of all `eraseLock.mutex` users). Quota now runs under eraseLock after the duplicate check, counts the new file (`files+1 > MAX`, `bytes+size > MAX`), skips entry attaches, and a refused file is deleted by the attach finally (result !is Added).

## Exploratory (code-level scenarios traced)
1. Sweep while the attach copy is blocked in read -> .part kept (inFlight), stray .part deleted (test passes).
2. Sweep between storeImage target creation and commit -> name in inFlight, kept; commit needs eraseLock so the row cannot appear mid-sweep.
3. open() returns after the timeout -> abandoned seen, stream closed, null returned, finally deletes .part (test passes).
4. Job completes between the idle check and the caller finally -> not abandoned, attach finally deletes raw; no leftover.
5. Job's finally ran before abandon was set -> its write is done, attach's raw.delete() runs after; no leftover.
6. Caller cancelled during poll -> withTimeoutOrNull rethrows parent cancellation (runCatching inside cannot suppress the scope's cancelled state), finally abandons.
7. Re-share of a waiting file at cap -> Duplicate, not QUOTA (test passes).
8. New file at cap -> QUOTA, stored file deleted by finally.
9. Duplicate held by a soft-deleted entry, receipt-first, at cap -> QUOTA (counts the moved row).
10. Entry attach at cap -> no quota check, Added.
11. Read returning 0 forever -> 5-min cap, not 30 s (finding 2).
12. copyTo/compress throwing after inFlight.add -> name leak (finding 1).
13. Authorities `com.android.calllog`, `user@sms`, `SMS`, `Mms-Sms` -> refused (test passes).
Problems: 2 (both MINOR).

## Test integrity
`bash scripts/quality/test-integrity-check.sh origin/main` -> ReceiptCameraShareTest.kt added, asserts 0 -> 77, skips 0, `TEST_INTEGRITY: OK`. The diff since a3c5e3a only adds tests (4 new, none removed or loosened).

STATUS: PASS
