# Verification report - STORY-179 (PR #85)

- Verifier: qa-verifier (fresh context, combined verify + review, speed mode), 2026-10-29. Loop 1 of 3.
- Verified head: 10fe02d; base origin/main 3194558. Follow-up commit 4d9a62f fixed the three MINORs below (orchestrator-checked: check-fast android GREEN, test-integrity OK per builder; CI gate green before merge).

## Environment
- `bash scripts/make/check-fast.sh android` -> check-fast GREEN [android].
- `./gradlew :app:testDebugUnitTest --tests eg.masroof.AnalyticsEngineTest --rerun` -> 14 tests, 0 failures, 0 skipped.
- `bash scripts/quality/test-integrity-check.sh origin/main` -> TEST_INTEGRITY: OK (63 -> 88 assertions).
- `gh pr checks 85` -> all green incl. PR gate.

## Criteria
| REQ | Result | Evidence |
|---|---|---|
| REQ-AN-150 | PASS | Parity test compares name, total, visits, averageTicket, categoryKey for all 24 cases (12 scenarios x cash on/off); spelling rule matches the server SQL (min(btrim) grouped by lower(btrim)); refund-cancelled merchant not listed. |
| REQ-AN-151 | PASS | Per-Cairo-day totals/counts and the full 7x24 weekday-hour grid equal the file; DST rows and Cairo-noon untimed rows covered; Saturday-first weekday order. |
| REQ-AN-152 | PASS | 6- and 12-period trends equal the file (label, start, total, income, savings rate incl. null, category lines) for start days 1 and 25; 12 periods on a 2-month phone returned with zeros. |
| Home sparkline (carried from STORY-178) | PASS | Engine's last 6 trend points equal the file's 6-period trend; HomeInsightsViewModel takes the last 6 of 12. |

## Findings
- MINOR (fixed in 4d9a62f): no UI test for "No merchants yet", savings "-", and the Home sparkline taking the last 6 points -> view-model test with a 12-point trend + Compose test added.
- MINOR (fixed in 4d9a62f): Kotlin trim() vs server btrim() -> merchant grouping now trims ASCII spaces only, with a test.
- MINOR (fixed in 4d9a62f): Arabic number agreement for "12 months" -> plurals in values and values-ar.
- NIT (accepted): merchants.period / heatmap.period / trend end date not compared; MerchantLine categoryId dropped; DefaultInsightsRepository kept only for its tests; TrendCard average truncates; merchant spelling rests on server byte-order collation.

STATUS: PASS
