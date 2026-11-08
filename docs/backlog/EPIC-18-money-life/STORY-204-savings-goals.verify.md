# STORY-204 verify report (qa-verifier, fresh context)

## Loop 1 - base origin/main `cce91cb`, head `3d4bf78`
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 109s; `--rerun` 691 tests, 0 failures. TEST_INTEGRITY: OK
(0 -> 145). Throwaway probe class (5 tests), 47 exploratory cases.
- REQ-AN-167: PASS (17 tests + probe); REQ-EXP-056: PASS (19 tests)
- Worked example: 1588 bp, 510 days, 17 periods, "Needs 42,059 EGP a month"; whole-pound ceiling verified on 20,000
  random cases (never under-states, always minimal)
- Periods for start days 1/15/28, due today/tomorrow, Feb 29, Cairo 23:30 vs 00:30 (winter and summer); on-track edges
- Writes: expense triggers recorded 0 rows across every goal operation; delete cascades only its movements
FINDINGS:
- [MINOR] name over 60 chars: Save silently does nothing (no field error)
- [MINOR] movement dates unbounded (future dates; take-out before creation can drive the chart below zero)
- [MINOR] celebration fires again after take-out and re-reach
- [NIT] 99.5% shows "100%" without Reached; dialog button says Save; Arabic gender/phrasing in two strings
STATUS: PASS -> MINORs fixed before integration
