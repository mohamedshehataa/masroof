# Verification report - STORY-033 (PR #68 fix/auth-refresh-deadlock; also REQ-AND-071 tests)

- **Verifier:** qa-verifier (fresh context)  -  **Date:** 2026-10-27  -  **Base:** e3005f179ebfb3dc79c06f547d0d4d3ad8d0ff63 (origin/main)  -  **Head:** 9898d81273f7e5a3114508c7f657bf3b174271c5
- **Loop:** 1 of 3. Probes ran at 9ed58d4, feba27f, 70906c1 and the final head 9898d81; this report grades 9898d81. Emulator end-to-end coverage was run separately (all five scenarios passed on feba27f, per the coordinator) and is not graded here.

## Environment
- JAVA_HOME=jdk-21.0.12 bash scripts/make/check-fast.sh android sdlc -> "check-fast GREEN [android sdlc] in 165s" (gradlew checkFast OK; hook tests 624/624; quality tests 145/145; contract lint OK)
- bash scripts/quality/test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK (6 added test files, 0 -> 58 assertions, 0 skips)
- python scripts/quality/traceability.py --out TMP -> 403 REQs, 125 covered, 69 passing, 0 unknown, gate PASS
- gh pr checks 68 -> 14/14 pass incl. PR gate (run 40430511381; gh pr view headRefOid 9898d81)
- JVM and Robolectric only; no emulator. Probes: one throwaway file, QaProbeTest.kt (MockWebServer plus the real OkHttp/Retrofit/BearerInterceptor/TokenAuthenticator/SessionManager/ApiAuthRemote/ApiRemoteSource wiring). Ran with ./android/gradlew -p android :app:testDebugUnitTest --tests ... Full probe run at 9898d81: 73 tests, all pass except one probe whose assertion was too strict (see Exploratory 18). Race probes plus SessionLifecycleRaceTest and SessionHardeningTest ran 3 times (--rerun), all green. The probe file was deleted; the tree is clean except this report.

## Criteria
| Claim / REQ | Result | Evidence (command -> observed) |
|---|---|---|
| 1 Deadlock gone (REQ-AND-007) | PASS | 9898d81 probes: reactive burst 6/30 -> refreshes=1, all ok (<30 ms); proactive 6/30 -> refreshes=1, stale=0, all ok; slow 2 s refresh x30 (both modes) -> refreshes=1, all ok in 2.02 s; refresh 500 x10 -> all HttpFailure 401 in <30 ms, session kept, refreshes 2 / 3; refresh never answers x10 -> all end with 401: reactive 30.0 s (2 refreshes), proactive 45.0 s (3 refreshes). Never a hang. AuthRefreshDeadlockTest 3/3. |
| 1b origin/main hangs | PASS (reproduced) | origin/main app sources checked out temporarily, main wiring (shared bare client), burst of 6 under @Test(timeout=20000) -> TestTimedOutException: test timed out after 20000 milliseconds. |
| 2 Proactive refresh | PASS | expired -> 1 refresh, 0 stale 401s; now+59 s -> refresh, now+61 s -> none; null expiry -> refresh on first 401; failed proactive refresh: 1 refresh over 5 calls, 1 at +29 s, 2 at +31 s; 10 concurrent callers with a failing refresh -> 1 refresh. |
| 2b Old stored session | PASS | Robolectric: sealed old JSON without accessExpiresAtMillis -> Found(expiry=null); a new save round-trips expiry=123456; SessionStoreCompatTest 2/2. |
| 3 Single flight and rotation | PASS | 40 racing proactive and reactive callers vs a reuse-detecting fake, x30 x3 runs -> bad=0 (refresh-1 spent once, SignedIn). 10 concurrent 401s with a failing refresh, x30 x3 runs -> refreshCounts={1=30} every run. 10 callers abandoning at 100 ms -> 1 refresh. Rejected -> SignedOut(SESSION_EXPIRED); 500/network -> kept. |
| 3b Cancelled caller after rotation | PASS | caller cancelled at 50 ms during a 300 ms rotating refresh -> token access-2; next refresh sends refresh-2; seen=[refresh-1, refresh-2]; SignedIn. |
| 3c Logout during an in-flight refresh (70906c1/9898d81) | PASS | x200 randomized interleavings x3 runs -> resurrected=0 and rotatedTokenNotRevoked=0 each run. 18/11/27 runs committed the refresh before logout; logout then wiped it and revoked refresh-2. Single gated case: state SignedOut(), token null, store Empty, revoked=[refresh-1, refresh-2]. Failed at feba27f (resurrected: SignedIn, store written back). |
| 3d Another member signs in during a refresh | PASS | A refresh gated, login as B, release -> token access-B1, SignedIn(Bob), stored refresh-B1, revoked=[refresh-A2]; stable in 3 runs. |
| 3e Revoke after refreshMutex (9898d81) | PASS | The discarded session is never put into current or the store; its tokens only go to remote.logout, launched in the never-cancelled refreshScope. Every logout race (600 total) revoked refresh-2. |
| 4 Deadlines | PASS | Hanging refresh: Insights and Budgets fetch/create/update/delete all Offline at 30.0 s. Hanging data endpoints: Offline at 15-30 s. BudgetsViewModel resets saving on every outcome. Stuck /auth/logout -> local wipe at 15.0 s. RefreshDeadlinesTest 3/3. |
| 4b SMS uploads | PASS | expired token -> Sent after 1 proactive refresh; 401 with refresh 401 -> Unauthorized (rows stay queued) and SESSION_EXPIRED; 401 with refresh 500 -> Unauthorized, session kept. |
| 5 Insights onShown and local signal | PASS | first show no extra refresh; 3 shows in flight -> 1 call; 3 later shows -> 3; LocalDataSignal x5 -> 0 at 1.999 s, 1 at 2.001 s, 1 at 60 s (no loop). InsightsShownTest 2/2. |
| 6 Gates and no token logging | PASS | check-fast GREEN, integrity OK, traceability 0 unknown, PR checks green; no added Log./println/Timber/HttpLoggingInterceptor/printStackTrace lines in the android/app/src/main diff. |
| Redirects | PASS | /auth/refresh 307 -> AuthResult.Server, redirect target hits=0. |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | BearerInterceptor / TokenAuthenticator (runBlocking on OkHttp dispatcher threads) | No hang, but against a never-answering /auth/refresh a burst of 10 callers without withDeadline (sync, categories, SMS) takes up to 45 s: waves of 5 dispatcher slots, each waiting about 20-30 s. Screens are capped at 30 s by withDeadline. The 20 s/30 s bounds apply per wait, not per call. | MockWebServer refresh blocks; 10 concurrent fetchCategories with expiry=now-1s -> elapsedMs=45039, refreshes=3, all HttpFailure 401. |
| MINOR | SessionManager.refreshAfter (flight keyed on stale token) | One flight is shared per wave. Requests queued behind the 5-per-host limit start a new flight after the first one failed, so a 500 burst of 10 makes 2-3 refresh calls, not 1. Bounded. | refresh 500, 10 concurrent fetchCategories -> refreshes=2 (expiry null) / 3 (expired). |
| MINOR | DefaultExpenseRepository.add/deleteFailed, LocalDataSignal | The signal fires when the outbox row is written, not when the server has it, so the 2 s debounced Insights refresh may run before the upload and is not repeated after it. Captured Undo (server delete) and SMS auto-records never signal. | Code read: signal.changed() appears only in add() and deleteFailed(). |
| MINOR | RefreshDeadlinesTest, InsightsShownTest | Tagged REQ-AND-071, which belongs to STORY-110 (status DRAFT: Members and Merchants tabs, body to be specified), so traceability credits a DRAFT REQ with unrelated behaviour. | grep -n REQ-AND-071 docs/backlog/EPIC-12-insights-clients/STORY-110-android-insights-members-merchants.md |
| MINOR (pre-existing, also on main) | SessionManager.refreshExclusive | If another member signs in between a 401 and its retry, refreshAfter(staleA) returns the current (member B) token because it differs from the stale one, so the old request is replayed as member B. | Code read: branch "staleToken != null and session.accessToken != staleToken -> return session.accessToken". |
| NIT | SessionManager.retryProactiveAt | Not reset on sign-in; a login within 30 s of a failed proactive refresh skips one proactive refresh. The reactive path covers it. | Code read. |

Resolved during verification:
- feba27f: a refresh completing after logout resurrected the session (SignedIn, tokens written back to the store). Fixed by the generation counter in 70906c1/9898d81.
- 9ed58d4/feba27f: a failing refresh caused N serial refreshes (10-15 per burst, continuing after callers gave up). Fixed by the shared flight.

## Exploratory
24 cases tried, 0 BLOCKER/MAJOR problems at 9898d81 (2 MINOR timing notes above):
1. reactive burst 6 (expiry null)
2. reactive burst 30
3. proactive burst 6 (expired 1 s ago)
4. proactive burst 30
5. expiry in 59 s, burst 10
6. skew boundary 60 s vs 61 s
7. refresh 2 s slow, burst 30, both modes
8. refresh 500, burst 10, both modes
9. refresh 401, burst 10 proactive
10. refresh never answers, burst 10, both modes
11. the same with Insights and Budgets fetch/create/update/delete
12. data endpoints never answer
13. /auth/logout never answers
14. SMS uploadParsed: expired token, refresh 401, refresh 500
15. 40 racing proactive and reactive callers vs a reuse-detecting fake (x30 x3)
16. 10 concurrent callers with a failing proactive refresh
17. caller cancelled at 50 ms during a rotating refresh
18. logout during an in-flight refresh, x200 randomized interleavings x3. A stricter x50 variant also flagged refreshes that committed before logout; those are legitimate (wiped and revoked afterwards) and were not counted as resurrections.
19. another member signs in during an in-flight refresh
20. 10 concurrent 401s with a failing refresh (x30 x3)
21. callers abandoning a failing refresh at 100 ms
22. /auth/refresh 307 redirect
23. old-format sealed session blob
24. onShown and LocalDataSignal x5 debounce
Plus the origin/main reproduction: a burst of 6 hits TestTimedOutException at 20 s.

## Test integrity
OK - bash scripts/quality/test-integrity-check.sh origin/main -> added: AuthRefreshDeadlockTest (9), InsightsShownTest (6), RefreshDeadlinesTest (7), SessionHardeningTest (16), SessionLifecycleRaceTest (17), SessionStoreCompatTest (3) assertions; 0 skips; nothing deleted or weakened.

STATUS: PASS
