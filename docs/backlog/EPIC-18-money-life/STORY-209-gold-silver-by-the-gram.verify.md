# STORY-209 verify report (qa-verifier, fresh context)

## Loop 1 - head `270874c`, base origin/main `7c3056b`
`--rerun` 869 tests, 0 failures; gate GREEN; TEST_INTEGRITY: OK (0 -> 142). ~95 probe cases.
- REQ-AN-173: PASS (worked example exact: 35 g, value 168,000, average 4,125/g, cost 144,375, gain 23,625 = 1636 bp,
  realised 3,375; interleaved sales; milligram rounding; newest price per karat; stale after 7 days; silver 999/925)
- REQ-AN-174: PASS (goal saved = movements + holding value; follows the price; one goal per holding; delete unlinks)
- REQ-EXP-063: FAIL
FINDINGS:
- [MAJOR] `Daos.kt` analytics queries ignore `excludedFromTotals`: a lot-linked 120,000 card payment stays in Insights
  while the app says it no longer counts
- [MINOR] grams parser read "1,250" as 1.25 g; goal picker allowed one holding per goal; no entry-list marker
- [NIT] two lots on one entry; Undo on an already-excluded entry; delete outside the erase lock; gain one decimal
STATUS: FAIL -> fix round 1

## Loop 2 - head `203d553` (main incl. STORY-200 merged)
`--rerun` 974 tests, 0 failures; gate GREEN; TEST_INTEGRITY: OK (0 -> 161). 120 cases.
- REQ-EXP-063: PASS (linked entry leaves every Insights figure and the Home total; Undo restores; balances unchanged;
  no other spending path leaks); REQ-AN-173: PASS; REQ-AN-174: PASS (several holdings per goal; ineligible goals
  rejected); grams parsing fixed; gain "+16.36%"
FINDINGS: [MINOR] editing a lot to link a different entry offered no Undo; [MINOR] gold-only goal completion fires no
celebration (DECISIONS, accepted); [NIT] Wealth list delete bypasses the erase lock (harmless race)
STATUS: PASS

## Final changes - head `1b34599` (diff reviewed by the orchestrator)
Ask (STORY-199) merged from main: `askRows` now also skips `excludedFromTotals = 1`, proved by
`eg.masroof.ask.AskLinkedGoldTest` (Ask and Insights both 12,050,000 -> 50,000 after linking -> 12,050,000 after Undo);
editing a lot onto another entry offers Undo that restores the first link (`MetalUndoTest`). Gate rc=0.
