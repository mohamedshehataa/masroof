# STORY-206 verify report (qa-verifier, fresh context)

## Loop 1 - head `6c9edbf` merged with origin/main `24274e4` - FAIL
Gate: `bash scripts/make/check-fast.sh android` -> `check-fast GREEN [android] in 36s`; `--rerun` 1108 tests, 0 failures.
TEST_INTEGRITY: OK (0 -> 82). 17 exploratory cases, 5 problems. Wallet equals Insights under both ATM settings
(15700 / 46700); Cairo midnight and month start 1/15/25 correct.
- REQ-UI-081: FAIL (restore after rotation/process death, per-tab scroll and Back never demonstrated)
- REQ-UI-082: FAIL (account picker)
- REQ-AN-175: PASS
FINDINGS:
- [MAJOR] account picker dropped SMS spending (accountId null, only instrumentLast4): CIB Spent 10000 vs balance 60000
- [MAJOR] old Home tests (REQ-AND-010, REQ-AND-074, REQ-AN-165) ran against an unreachable HomeContent
- [MINOR] two 5e18 expenses today -> long overflow uncaught on the start screen (crash on launch)
- [MINOR] bills-due tile summed currencies and labelled EGP; overdue counting inconsistent
- [MINOR] archived or non-EGP selected account showed the wrong state
- [MINOR] "You owe" left out installments and overdrawn accounts (Wealth's Debt includes them)
- [MINOR] tab highlight and Back disagreed for Wallet tiles that open another tab's screen
- [NIT] card with no balance counted as "without a balance"; observeTotal ignores ATM (unused); budgets from server cache
STATUS: FAIL

## Loop 2 - head `643415e` (fix round 1 `0f2f49c` + origin/main `b3acb6f` with STORY-203) - FAIL
Gate: `check-fast GREEN [android] in 25s`; `./gradlew :app:testDebugUnitTest --rerun` -> 121 suites, 1231 tests,
0 failures, 0 skipped. TEST_INTEGRITY: OK (141 -> 256); the two tolerance WARNs are the regex `delta` matching the
`deltaBps` parameter (InsightsScreenTest.kt:225,255; WalletFixtures.kt:24,34), nothing loosened.
All 8 loop-1 findings fixed (probes on in-memory Room): CIB Spent 60000 = Wealth; overflow -> ERROR state, no crash;
BillsDue(count=5, totalMinor=55000, otherCurrency=1); archived/USD/unknown selection resets to All; owed 105000 =
Wealth Debt; Wallet tiles switch tab first; card without balance not counted. Parity: Wallet = Insights in 18 runs
(month start 1/15/25 x ATM off/on x 3 periods, 21-row D2 seed). Deep links: bills/<uuid> and subscriptions/<uuid>
accepted; 11 malformed variants rejected.
- REQ-UI-081: PASS - REQ-UI-082: PASS - REQ-AN-175: PASS
FINDINGS:
- [MAJOR] HomeViewModel / HomeInsightsViewModel are dead code, yet REQ-AN-147, REQ-AND-074, REQ-AND-130 and REQ-EXP-043
  tests prove those REQs only on them; WalletViewModel has no such test
- [MINOR] Top goal tile ignores gold backing (100 of 1,000 vs Goals 500 of 1,000)
- [MINOR] highlight vs Back still disagree for reminder deep links, the FAB on other tabs, and edit from Ask
- [MINOR] error state hides balance, "You owe", period pill and picker; Insights still overflows (Failure, no crash)
- [NIT] stand-in NavHost in nav tests; StateRestorationTester is not process death; excluded switch On not asserted;
  OFFLINE state never produced by WalletViewModel
STATUS: FAIL

## Loop 3 - base origin/main `a0d0ea1`, head `66fee5a` - PASS
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 29s; `./gradlew :app:testDebugUnitTest --rerun` -> 121 suites,
1235 tests, 0 failures, 0 skipped. TEST_INTEGRITY: OK (385 -> 521); the 3 tolerance WARNs are the regex `delta` matching
`deltaBps`. 12 exploratory cases, 2 problems.
- REQ-UI-081: PASS - WalletNavigationTest 6/6; restore on edit over bills/x keeps Bills lit and Back goes edit -> bills/x
  -> bills; rotation on goals/7 keeps Plan; Ask then Back -> Wallet
- REQ-UI-082: PASS - WalletTest 26 REQ cases; parity probe RoomWalletSource vs RoomAnalyticsEngine 18/18 SAME (12 Cairo
  midnights Sep-Nov incl. after the DST change, month start 1/15/25 x ATM off/on); empty DB -> firstRun; Arabic 1.5x error
  state keeps pill, picker, balance and "You owe" unclipped
- REQ-AN-175: PASS - ExcludedFromTotalsTest 3/3, EditExcludeToggleTest 2/2; excluded rows left out in all 18 runs
Retargeted tests compared assertion by assertion with origin/main: REQ-AN-147, REQ-AN-152, REQ-AND-130 x2, REQ-EXP-043
equivalent; the two `repo.refreshed` checks in REQ-AND-074 tested a server refresh on start, obsolete under ADR-0009 /
REQ-AND-130 (recorded in DECISIONS).
FINDINGS:
- [MINOR] InsightsViewModelTest.kt:174-191 - the replacement retry check was vacuous (fake returns the same empty flow) ->
  fixed at integration: `REQ-AND-074 retry after a failed load clears the error and shows the figures` (first rows() call
  throws, ERROR; after retry() READY with 7,000 and two calls)
- [NIT] the shared `banner_error` text ("Could not refresh - showing saved data") reads oddly on a local-only Wallet
- [NIT] tapping the current tab restores its saved stack instead of its first screen
- [NIT] `@SuppressLint("RestrictedApi")` on MasroofRoot for one `currentBackStack` read; a public option exists
- [NIT] budgets are still the server cache until STORY-180/181, so excluded entries cannot be tested there
STATUS: PASS
