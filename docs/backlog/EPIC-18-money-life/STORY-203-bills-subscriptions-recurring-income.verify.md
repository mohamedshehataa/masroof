# STORY-203 verify report (qa-verifier, fresh context)

## Loops 1-2 - FAIL
Matching false positives (other merchants with the same first word or a similar amount marked a subscription paid).
These are the N1a-h cases, re-run in loops 3 and 4 and unpaid there.

## Loop 3 - head before the learned-key fix - FAIL
Gate green, TEST_INTEGRITY OK, 101 probe cases.
- [MAJOR] a typed subscription learned the longer entry key on a prefix match, so later real payments never matched
  (typed "Netflix" paid by "NETFLIX.COM AMSTERDAM" stored "netflix amsterdam"; "NETFLIX.COM" next month stayed unpaid)
- [MINOR] exact-key 50-200% price pass pays multi-product merchants; shop names that extend a brand matched in the
  10% pass; preset false negatives (e&, APPLE.COM/BILL, WE)

## Loop 4 - base origin/main `24274e4`, head `092251d` - PASS
Gate: `bash scripts/make/check-fast.sh android` -> `check-fast GREEN [android] in 135s`;
`./gradlew :app:testDebugUnitTest --rerun` -> 117 suites, 1198 tests, 0 failures, 0 skipped.
Installments still green (BillsReminderTest 15/15, BillsScreenTest 24/24). Throwaway probe, 122 cases, 7 problems.
- REQ-EXP-062: PASS - create/edit/pause/delete and form errors; erase leaves no notices or `entry:` keys (real DataStore)
- REQ-AN-170: PASS - 1,287,600 a year / 107,300 a month for 4 active; non-EGP row left out with the hint; totals cap
- REQ-AN-171: PASS - anchors kept; the spec's 199 -> 229 example paid with one notice; reminders without opening Bills,
  none for paused rows, none twice; the loop-3 MAJOR is fixed (key stays `netflix`; "Uber Eats" no longer pays "Uber");
  "Not this one" restores amount and renewal and ignores that entry; price pass only with a single candidate
- REQ-AN-172: PASS - 17 days to the 25th; salary on the 28th asked on 2027-03-01 -> 27 days; suggestions grouped,
  no duplicate next to a preset
FINDINGS (follow-ups, none blocks under the DECISIONS severity rubric):
- [MINOR] "Not this one" on an old notice overwrites a later user edit of amount and renewal (SubscriptionRepository.kt:452)
- [MINOR] the APPLE.COM/BILL alias counts as an exact key, so an App Store charge of 79 pays iCloud 49 (visible notice)
- [MINOR] exact-key price pass on a multi-product merchant (typed Talabat 60 paid by one TALABAT order of 100), and a
  second identical order re-matches after "Not this one"
- [MINOR] a generic name ("Gym") is paid by another merchant with the same first word at the same amount
- [MINOR] alias misses: "e& egypt" does not pay Etisalat, "WE HOME INTERNET" does not pay WE
- [NIT] two notice assertions check id/from/to but not name and currency; ignored `entry:` keys grow by one per
  reject (cleared on erase); rows without a stored anchor fall back to the month-end guess (backup carries anchors in
  STORY-183)
TEST_INTEGRITY: OK (`bash scripts/quality/test-integrity-check.sh origin/main` -> 0 -> 281 assertions, no skips)
STATUS: PASS
