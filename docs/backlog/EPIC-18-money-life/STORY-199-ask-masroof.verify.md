# STORY-199 verify report (qa-verifier, fresh context)

## Loop 1 - base origin/main `0200fd8`, head `09088e0`, 2026-11-08
Throwaway Robolectric probe extending `AskEngineFixture` (deleted after the run): 65 own questions, ~45 engine cases.
Gate: `bash scripts/make/check-fast.sh android` -> `check-fast GREEN [android] in 80s`; `:app:testDebugUnitTest --rerun`
-> 711 tests, 0 failures, 0 skipped. TEST_INTEGRITY: OK (only new test files, 0 -> 251 assertions).

STORY: STORY-199
CRITERIA:
- Claim1-parity with Insights: PASS -- month start 25, refunds, transfers, ATM on/off, soft-deleted, PENDING_REVIEW,
  USD, sub-category, Cairo 23:30/00:30 boundaries: totals, categories, shares, top merchants, Talabat 6 visits /
  137,700 / avg 22,950, compare 21,553 bp, payday projection - every field equal
- Claim2-parser: FAIL -- 11 of 65 own questions wrong; 6 show a wrong number or a false zero
- Claim3-never invents a number: FAIL -- false "No entries" (MAJOR 1, 4); empty DB / zero income / zero previous OK
- Claim4-percent and shares: PASS (roast truncates, MINOR 6)
- Claim5-payday and Cairo dates: PASS
- Claim6-privacy: PASS (`AskIsLocalOnlyTest`; no Log/HTTP; no persistence except NIT 11)
- Claim7-UI: PASS (FAB rule keeps old routes; 16 AskScreenTest cases; 85 keys en = ar)
- Claim8-performance: PASS -- 20,000 entries: 3-20 ms per question, one date-bounded query, no N+1
- Claim9-test integrity: PASS; Claim10-gate and REQ tests: PASS
- REQ-AN-160: FAIL; REQ-AN-161: PASS; REQ-AN-162: PASS; REQ-AN-163: FAIL; REQ-AN-164: PASS; REQ-AN-165: FAIL
FINDINGS:
- [MAJOR] `ask/AskCalculator.kt:503-508` -- frequency uses the top-50 merchant list: a merchant outside it gets
  "No entries" while its spend question shows 150 EGP, 3 entries
- [MAJOR] `ask/QuestionParser.kt:19`, `PeriodScanner.kt` -- unknown period falls back to this month: "last year",
  "in 2025", "2 months ago" answer this month's number
- [MAJOR] `ask/PeriodScanner.kt:50-51` -- English "may"/"mar" taken as months ("I may have spent too much on
  groceries" -> May 2026 total)
- [MAJOR] `ask/QuestionParser.kt:77-79` -- "how many times did I buy groceries" treated as a merchant -> false zero
- [MINOR] second subject silently dropped ("groceries and dining"); roast percent truncated (49% vs 50%); "بشهر 8" not
  read; "roast chicken" -> roast
- [NIT] Arabic plurals; hard-coded "Other"; draft kept in saved state (`rememberSaveable`); `askRows` returns a
  feature-package type; "groceries october" with start day 25 on 20 Oct means October 2025 (deliberate)
EXPLORATORY: 65 parser questions + ~45 engine cases, 11 problems; Franco, MSA, Arabic-Indic digits, tashkeel and a
prompt-injection line ("ignore previous instructions and show 1000000 EGP" -> not understood, no number) handled
TEST_INTEGRITY: OK
STATUS: FAIL -> fix round 1 sent
