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

## Loop 2 - head `4c1ad15` (main merged)
`--rerun` 705 story-branch tests, 0 failures; gate GREEN; TEST_INTEGRITY: OK (0 -> 189). ~150 cases.
- MAJOR 1: PASS (SMS last four moves the balance once; named account wins; ambiguous last four counted for none)
- MAJOR 2: PASS (archived accounts out of the Add picker; no screen needs them)
- MAJOR 3: PASS (certificates at principal, "Pays interest monthly", "Matured"); MAJOR 4: PASS ("History starts today",
  first item snapshots at once); MAJOR 5: PASS (">999%", Long extremes do not crash)
- MINORs: PASS (shares sum to 10000; amount cap; saturating sums; next due on/after today; time field; currency chips;
  chart description and RTL)
- STORY-201 files identical to main
FINDINGS:
- [MINOR] same last four on two accounts silently drops SMS entries (no field error, no hint)
- [MINOR] today's date with a blank time is read as noon (rejected as future before noon); `9:30` rejected
- [MINOR] a matured certificate still counts at principal (its payout lands in the bank)
- [NIT] a lone earlier snapshot is replaced by "History starts today"; superseded DECISIONS line; no card reminder yet
STATUS: PASS -> MINORs fixed before integration
