# Verification report - STORY-178 (PR #83)

- Verifier: qa-verifier (fresh context, combined verify + review, speed mode), 2026-10-28. Loop 2 of 3.
- Head: 9c8dedab5c38d068ce7724c8885d2f6d90e06c1d; base origin/main 78da8d12306f8233542c2c729641f30585dd2281.

## Environment
- `bash scripts/make/check-fast.sh android` -> check-fast GREEN [android] in 133s; 572 tests, 0 skipped, 0 failures.
- `bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK (assertions 374 -> 440). The "tolerance" warning is a regex false positive on `deltaBps`; every money comparison is an exact assertEquals on Long/Int.
- `gh pr checks 83` -> all 14 checks pass incl. PR gate.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-AN-145 | PASS | Golden parity test passes for all 12 scenarios x both cash settings (checked == 24), now incl. period label and savingsRateBps (matches backend Projections.java). USD-only period shows 0 EGP + the not-included note (InsightsScreenTest). |
| REQ-AN-146 | PASS | By-category equals the golden file; archived/missing categories roll up, totals keep every amount. |
| REQ-AN-147 | PASS | Home always uses the engine (calendar-month fallback removed); Home spent equals the engine summary for month start 25; engine failure hides the summary card and keeps the list; observe failure after data shows the error state; no /api/analytics from Home summary or Insights Overview/Categories; filesDir/insights current/previous cache deleted. |
| REQ-AN-148 | PASS | Month start 25 on 2026-10-27 -> 2026-10-25..2026-11-24; 2026-10-24T21:30Z in the 25th period; invalid stored value -> 1, error code logged once; open screen moves to the new month after Cairo midnight. |
| REQ-AN-149 | PASS | 12 x 600 rows p95 < 200 ms over 20 runs; 72,000 rows without OOM (range query on indexed occurredAtMillis). |

Round 1 (FAIL) found: USD-only note not rendered (MAJOR), Home production path untested and calendar-month fallback (MAJOR), stale period across midnight, savings rate unchecked, budgets card server call undeclared; all fixed in 9c8deda.

## Accepted findings (MINOR/NIT, not blocking)
- MINOR: no test toggles the cash switch live on Home (Home shows the engine total, which the parity test covers for both settings).
- MINOR: the insights failure signal is shared across periods (a CURRENT failure on Home also flips Insights showing PREVIOUS to ERROR).
- MINOR: the REQ-AN-148 midnight test relies on a 300 ms real-time window; could time out on a very slow CI agent (move to virtual time if it ever flakes).
- MINOR: after an engine failure Home has no retry until the view model is recreated.
- NIT: the midnight timer ignores device clock/time-zone changes while a screen is open.
- Known limitations (documented in the story): Home budgets card stays server-backed until STORY-180; trend/calendar/weekday cards and Home sparkline empty until STORY-179.

STATUS: PASS
