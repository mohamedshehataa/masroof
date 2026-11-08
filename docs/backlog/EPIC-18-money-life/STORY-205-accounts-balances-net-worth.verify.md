# STORY-205 verify report (qa-verifier, fresh context)

## Loop 1 - head `706f382` (cherry-picked onto origin/main `cce91cb`)
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 159s; `--rerun` 693 tests, 0 failures. TEST_INTEGRITY: OK
(0 -> 155). One throwaway probe class, ~100 cases.
CRITERIA: REQ-EXP-057 FAIL; REQ-AN-168 PASS (12,500 EGP exact; latest check wins; statuses; excluded flag; card sign);
REQ-EXP-058 PASS (day 29-31 clamping, Cairo boundaries); REQ-EXP-059 FAIL; REQ-AN-169 FAIL. Honest numbers PASS (no
percent for Say's -480% case; overdraft counts as debt). Validation PASS. Snapshot worker PASS (one unique periodic job).
FINDINGS:
- [MAJOR] `MoneyLifeDaos.observeFlowRows` -- SMS captures have accountId NULL (only instrumentLast4), so captured
  spending never moves balances
- [MAJOR] archived accounts still in the Add-expense picker (`LookupDao.observeAccounts`)
- [MAJOR] `AssetValuation` -- certificate interest always added (spec: only when paid at maturity); monthly-payout
  certificates double count
- [MAJOR] `NetWorthChart.kt` -- no "History starts today" with zero snapshots (normal first use)
- [MAJOR] `NetWorthHistory.yearChange` -- `toIntExact` overflow crashes the Net worth tab (first 10 EGP, now 3M EGP)
- [MINOR] shares sum to 9999/10002; no amount cap (Long overflow); next due date can be in the past; set balance date
  only; free-text currency; chart not readable by TalkBack; branch conflicts with main
STATUS: FAIL -> fix round 1 sent
