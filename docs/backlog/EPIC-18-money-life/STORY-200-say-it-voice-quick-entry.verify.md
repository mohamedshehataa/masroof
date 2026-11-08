# STORY-200 verify report (qa-verifier, fresh context)

## Loop 1 - base origin/main `ee4e824`, head `30cae43`, 2026-11-08
Runs on a byte-identical `git archive` copy of the branch; throwaway probe tests (104 parser inputs, 6 view-model
scenarios, 4 screen/RTL/TalkBack checks, 1 recognizer flow) deleted before the full suite.
Gate: `bash scripts/make/check-fast.sh android` -> `check-fast GREEN [android] in 102s`; `:app:testDebugUnitTest --rerun`
-> 704 tests, 0 failures, 0 skipped. TEST_INTEGRITY: OK (`test-integrity-check.sh origin/main`: 0 -> 216 assertions in 4
new files; `AddExpenseScreenTest` unchanged and passing; its REQ-AND-013 intent re-proved with the new controls on).

STORY: STORY-200
CRITERIA:
- Claim1-tables + adversarial lines: FAIL -- all 12 story rows exact; 18 of the verifier's own lines wrong (below)
- Claim2-integer money: PASS -- no Double/Float/BigDecimal on money; overflow and 1,000,000,000,000 cap hold
- Claim3-nothing saved without Save: PASS -- probe through fill, mic, deny forever, error, late text, close -> 0 rows
- Claim4-mic/permission/recognizer: PASS (on-device branch confirmed by reading only)
- Claim5-Income/USD save: PASS -- real Room: INCOME, USD, 2000, no category; D2 code untouched
- Claim6-no network/logging, strings: PASS -- 23 keys in en and ar
- Claim7-test integrity: PASS
- Claim8-REQ ids: PASS (PARSE-098 19, PARSE-099 14, AND-144 23, EXP-052 13 passing tests)
- Claim9-gate: PASS
- REQ-PARSE-098: FAIL (MAJOR 1); REQ-PARSE-099: FAIL (MAJOR 3); REQ-AND-144: FAIL (MAJOR 2); REQ-EXP-052: PASS
FINDINGS:
- [MAJOR] `ingest/quick/QuickDates.kt` monthDate yearIndex -- a 4-digit amount after a date becomes the year:
  `3 oct 1500 pounds uber` -> 1500-10-03, no amount; `uber oct 3 2500 le` -> 2500-10-03 (future); `rent 5 nov 2000`
- [MAJOR] `ui/viewmodel/QuickFill.kt` apply sets `quickText = ""` -- the recognised text is never shown (REQ-AND-144)
- [MAJOR] `ingest/quick/QuickLexicon.kt` SCALE_WORDS lacks colloquial تلاف: `خمس تلاف جنيه` -> 500 (1000x too low)
- [MINOR] malformed digit groups give wrong amounts (`1,2345 fuel` -> 500, `150.5.5 fuel` -> 500, `1k5` -> 100500)
- [MINOR] paying a salary reads as INCOME (`paid salary to nanny 3000`, `دفعت مرتب الشغالة 3000`)
- [MINOR] `E£150` -> GBP; no fallback on ERROR_LANGUAGE_NOT_SUPPORTED; typed text lost when voice text arrives; income
  sticky across lines; a two-number line replaces the user's note; weak test `assertTextContains("")`
- [NIT] minus sign dropped (`-150 fuel` -> 15000); `ميت جنيه`, `تلت آلاف` -> no amount; `100 جنيه وخمسين قرش` drops piasters
EXPLORATORY: 115 cases, 23 problems (listed above); Cairo midnight and summer-time defaults correct; RTL mic placement
and Arabic labels correct; 10k-char inputs capped at 400 chars, 0-1 ms
TEST_INTEGRITY: OK
STATUS: FAIL -> fix round 1 sent with the security review's MINORs (see `.review.md`)

## Loop 2 - head `04997c1`
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 119s; `--rerun` 721 tests, 0 failures. TEST_INTEGRITY: OK (0 ->
264; 4 assertions replaced by stricter ones for the fixes). 145 parser cases (104 from loop 1, 0 regressions, 15 changed
exactly as intended) + 41 new; 12 view-model steps.
- MAJOR 1 years: PASS (`3 oct 1500 pounds uber` -> 1,500 EGP on 2026-10-03; future years dropped)
- MAJOR 2 heard text: PASS (kept, editable, Done re-parses; typing ignored while listening)
- MAJOR 3 تلاف: PASS (`خمس تلاف جنيه` -> 5,000; 15 new Arabic number phrases correct)
- Verifier and security MINORs: PASS (online notice, bidi strip, merchant cap, require checks - single caller, KWD removed)
FINDINGS:
- [MAJOR] `QuickNumbers.kt` -- نص/ربع before a scale word: `نص مليون` -> 1,000,000 (should be 500,000);
  `خمسة ونص ألف` -> 5,000.50; `ألف إلا ربع` -> 1,000
- [MINOR] `150E£`/`£E 150` -> GBP; online notice skipped on the language fallback; unsupported currency words -> EGP;
  ISO date `2026-10-03` kills the amount
STATUS: FAIL -> fix round 2 sent (owner rule: keep iterating; see DECISIONS)
