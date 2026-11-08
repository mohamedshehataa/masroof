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

## Loop 2 - head `cdb3bf8`
Gate: `bash scripts/make/check-fast.sh android` -> GREEN; `--rerun` 721 tests, 0 failures. TEST_INTEGRITY: OK (0 -> 307;
changed expectations are stricter). 115 questions x month start day 1 and 25 + ~40 engine cases; parity still exact.
- MAJOR 1 merchant outside top 50: PASS; MAJOR 2 periods (last year, 2025, months ago, day ranges, weeks): PASS;
  MAJOR 3 modal may/mar: PASS for the listed cases; MAJOR 4 category frequency: PASS (EN + AR)
- MINORs/NITs 5-12: PASS (subjects summed, roast rounding, بشهر, roast chicken, plurals, resources, draft, AskRow)
FINDINGS:
- [MAJOR] `ask/ParserLexicon.kt` FILLER + `PeriodScanner.hasCue` -- bare "may" dropped: "groceries may" -> this month's
  total (loop 1 answered May)
- [MINOR] "ع الاكل" unknown again; a bare 2050 overrides "this month"; "groceries vs dining" merged; duplicate label
  names; formal Arabic dual forms
STATUS: FAIL -> fix round 2 sent

## Loop 3 - head `fff9a57`
Gate GREEN in 56s; `--rerun` 745 tests, 0 failures; TEST_INTEGRITY: OK. 145 questions x start day 1 and 25; parity exact.
- MAJOR A (may/march): PASS (17 cases incl. 10 new); MINORs B-F: PASS
FINDINGS:
- [MAJOR] `ask/PeriodScanner.kt` yearVerdict -- "groceries 2025 vs 2024" -> 2024 vs 2023 (regression from loop 2)
- [MINOR] future year ("groceries 2027") -> silent this month; "kids" treated as a generic word though it is a
  category; Arabic "لماما" not generic. [NIT] Year.now() instead of the injected clock; unnamed year label in Compare
STATUS: FAIL -> fix round 3 (year grammar + generated combination test), past the 3-loop limit by owner rule

## Loop 4 - head `ebc3a86`
Gate GREEN in 67s; `--rerun` 748 tests, 0 failures; TEST_INTEGRITY: OK. 172 questions x start day 1 and 25; parity exact.
- Loop-3 MAJOR ("groceries 2025 vs 2024"): PASS; kids / لماما: PASS
FINDINGS:
- [MAJOR] `PeriodScanner.yearRule` -- any cue-free 1996-2026 number is a year: "صرفت 2000 على البقالة الشهر ده" ->
  compare this month vs 2000; "spent 2000 on groceries" -> "in 2000"
- [MAJOR] `QuestionParser.manyPeriods` -- two periods without a compare word become a Compare: "may 31 2026" -> May vs
  2026; "april may june" -> May vs April
- [MINOR] "october 2030" accepted; "بسنة 2024" not read. Generated test oracle restates the rule (696 combinations, not
  1,152)
STATUS: FAIL -> fix round 4: explicit-cue rules (orchestrator product decision, DECISIONS)

## Loop 5 - head `82933ac`
Gate GREEN in 95s; `--rerun` 750 tests, 0 failures; TEST_INTEGRITY: OK. 200 questions x start day 1 and 25; parity exact.
- Loop-4 MAJORs: PASS (2000 as an amount; "may 31 2026" one day; "april may june" unreadable); cue rules hold for 20+
  new questions incl. Arabic بسنة/لسنة and same-period answers
FINDINGS:
- [MAJOR] "مقارنة 2025 و 2024" -> this month vs last month (و missing as a connector; template removed from the test)
- [MAJOR] "this year vs 2024" -> vs 2025; "groceries this month vs 2025" -> vs last month (year dropped next to a named period)
- [MINOR] "since 2024" answers one year; for/of cues read amounts as years; Franco year cues; two bare years + "and" ->
  this month; "may 31" ignores the day
STATUS: FAIL -> fix round 5 (small, targeted) + severity rubric (DECISIONS)
