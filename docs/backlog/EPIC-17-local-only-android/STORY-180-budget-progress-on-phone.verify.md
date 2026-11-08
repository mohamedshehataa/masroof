# STORY-180 verify report (loop 1 of 3)

- Verifier: qa-verifier (fresh context; did not write this code)
- Date: 2026-11-08
- Base: origin/main 39ed29ddf0b9df777a1a247cce80a34fdc98e4e4
- Head: 5b64a196a53f8e9c246a809d23bb2a2331251e56 (origin/story/STORY-180-budget-progress-on-phone, checked out detached)
- Diff: 5 files, +645/-2 (AnalyticsCalculator.kt, new BudgetCalculator.kt, new BudgetProgress.kt, new BudgetCalculatorTest.kt, DECISIONS.md)

## Environment
| command | result |
|---|---|
| `bash scripts/make/check-fast.sh android` | `OK android: ./gradlew checkFast`, `check-fast GREEN [android] in 56s`, exit 0 |
| `./gradlew :app:testDebugUnitTest --rerun` (in android/) | exit 0; JUnit XML: 137 suites, 1383 tests, 0 failures, 0 errors, 0 skipped; BudgetCalculatorTest 29/29, AnalyticsEngineTest (parity-v1.json) 15/15 |
| `bash scripts/quality/test-integrity-check.sh origin/main` | one added file, assertions 0 -> 105, skips 0, TEST_INTEGRITY: OK |
| `./gradlew --offline test --tests BudgetProgressTest --tests BudgetServiceTest` (in backend/, Gradle, no mvnw) | BudgetServiceTest 12 tests 0 fail, BudgetProgressTest 5 tests 0 fail |
| `git status --short` after probes deleted | clean |

## Criteria
| REQ | result | evidence |
|---|---|---|
| REQ-AN-153 | PASS | BudgetCalculatorTest REQ-AN-153 cases green in the --rerun (29/29). Java cases ported one to one (table below). Spec example: groceries 250000+100000-50000 refund = spent 300000, remaining 100000, 7500 bps, alerts [50]. Cap: limit 1, spent 100,000,000 and 2x Long.MAX -> exactly 1,000,000, spent saturates at Long.MAX. ISO week Sun 23:30 / Mon 00:10 Cairo both before (UTC+3) and after the 30 Oct DST end (UTC+2). Probe: 890 spent/limit pairs (800 seeded random incl. full Long range, negatives, limits above Long.MAX/100, plus 90 edge pairs) -> 0 mismatches vs BigInteger, 0 mismatches vs the Java formula on the 677 pairs where Java does not throw (on 213 pairs Java throws ArithmeticException in multiplyExact/shareBps; Kotlin returns the BigInteger result). |
| REQ-AN-154 | PASS | Ported rollover cases (7000 leftover -> remaining 12000, 2941 bps; -5000 overspend -> 0 remaining, 10000 bps), exhausted limit (10000 bps nothing spent, 1,000,000 with 1 spent, alert 100, not on track), created this month -> 0, only one period back. Probe: created exactly at prev.endExclusive -> 0; 1 ms before -> 7000; created 2026-09-20 (mid previous period) -> 7000, identical to the Java rule createdAt.isBefore(prev.endExclusive()); weekly budget created during the current week -> 0; zero-amount budget -> 10000 bps, alerts [50,80,100], onTrack false. |
| REQ-AN-155 | PASS (calculator level) | Total 20,000 EGP, start day 25, today 2026-11-05, spent 9,000: period 25 Oct-24 Nov, daysLeft 19, remaining 1,100,000, projected 900000*31/12 = 2,325,000 above limit -> onTrack false (Java rule: limit>0 and spent<=limit and linear<=limit); 3,000 spent -> on track. AnalyticsCalculator.compute(..., budgets) fills remainingBudget 6000, null without budgets, null for category-only, null for week periods (probe 15). Two totals: oldest (createdAt, id; same order as the server query ORDER BY created_at, id) wins for remainingBudget, the other flagged duplicate. AnalyticsEngineTest + parity-v1.json unchanged (not in the diff) and green. Home does not show it yet (finding 1, STORY-181). |

### Claim 1 - Java vs Kotlin case by case
| Java case | Kotlin test | same inputs/expectations |
|---|---|---|
| BudgetProgressTest.rounding (5 asserts) | REQ-AN-153 progress ... rounds half away from zero | yes, all 5 |
| .thresholds (6) | REQ-AN-153 thresholds 50 80 100 ... | yes, all 6 |
| .customThresholds (1) | REQ-AN-153 custom thresholds ... | yes + 1 extra (unsorted input) |
| .edges (4) | REQ-AN-153 no spend, refunds beyond spend, huge overspend | yes + 3 extra (Long.MAX cases) |
| .exhaustedLimit (6) | REQ-AN-154 a limit eaten by rollover ... | yes, all 6 |
| BudgetServiceTest.monthStartDay25 | REQ-AN-153 month start day 25 ... | yes; query range asserted as period.start()/endExclusive() (21:00Z / 22:00Z), equivalent |
| .categoryBudget | REQ-AN-153 category budget ... | yes (8500, 8500 bps, [50,80], 1500, off track, 6 days) |
| .overspent | REQ-AN-153 overspent ... | yes |
| .rollover | REQ-AN-154 rollover carries ... | yes |
| .rolloverExhaustsLimit | REQ-AN-154 rollover that uses up the limit ... | yes + spent case |
| .overIsOffTrack | REQ-AN-153 already over the limit ... | yes |
| .totalRemaining | REQ-AN-155 total remaining ... | yes + weekly-kind null |
| .noRolloverForNewBudget | REQ-AN-154 a budget created this month ... | yes; Kotlin adds a Sept 3000 row (still detects a wrong rollover) |
| .weekly | REQ-AN-153 weekly budget uses the ISO week | yes (W43, 1000, 0 days) |
| .monthlyForWeek | REQ-AN-153 monthly budget asked for a week ... | yes |
| .validation, .conflictAndUpdate | not ported | CRUD/validation, not status cases; belong to STORY-181 storage |

Deliberate, spec-mandated deviation: the Java budgets never count CASH_WITHDRAWAL (cash=false hard-coded); Kotlin follows includeCash as REQ-AN-153 says (cash withdrawals only with the setting); logged in DECISIONS.md. Kotlin saturates where Java throws (only for amounts above the 1e12 creation cap).

## Findings
| sev | where | defect | repro | run/traced |
|---|---|---|---|---|
| MINOR | android/app/src/main/java/eg/masroof/data/insights/LocalInsightsRepository.kt:132 (pre-existing on main, not in this diff) | toSnapshot hard-codes remainingBudget = null, so the REQ-AN-155 value that AnalyticsCalculator.compute now produces never reaches the Home summary even after STORY-181 passes budgets. No wrong number today (no budgets are stored yet). | read the line; a search for remainingBudget in android/app/src/main/java shows it is the only consumer | traced |
| MINOR | android/app/src/main/java/eg/masroof/core/analytics/BudgetCalculator.kt:46-69, 128-142 (rows contract) | statuses() takes one row list, but a WEEKLY budget resolved for a month period can need rows outside the month window: current month at 29 Oct resolves to W44 26 Oct-1 Nov (a 1 Nov row counts only if loaded); past month 2026-09 resolves to W40 28 Sep-4 Oct. The KDoc only says rows must cover the previous period, so a STORY-181 caller reusing the analytics range (prev-month start .. month end) would silently undercount weekly budgets. Same period rule as Java, but Java queried the DB per period. | probe: statusOf(WEEKLY, AnalyticsPeriod.parse(current, 2026-10-29, 1), today 2026-10-29, rows [2026-11-01T09:00Z 50]) -> period W44 2026-10-26..2026-11-01, spent 50; periodFor(WEEKLY, 2026-09, 1, 2026-10-25) -> W40 2026-09-28..2026-10-04 | run |
| NIT | BudgetCalculator.kt:46-50 / AnalyticsRow | Safety against excluded/soft-deleted rows is by contract only: AnalyticsRow has no excludedFromTotals/deletedAt/failed fields, so the calculator cannot filter them. Every current feeder filters them in SQL (Daos.kt observeAnalyticsRows, analyticsRows, askRows, observeWalletRows: failed = 0 AND excludedFromTotals = 0 AND deletedAtMillis IS NULL), so the builder claim holds for all present paths; a future caller using another query would count them with no guard. | read Daos.kt:80-121 | traced |
| NIT | BudgetProgress.kt:39 | Duplicate thresholds fire twice ([100,50,50,201] at 200% -> [50,50,100]); identical to Java, which relied on BudgetService.thresholds() validation (1..200, unique). STORY-181 must port that validation. | probe 8 | run |

## Exploratory (inputs; temporary JUnit probes, deleted afterwards)
1. 800 seeded random (spent, limit) pairs, Random(4242), mixes: full Long range, -1000..1000, 1..1e12, Long.MAX/200..Long.MAX, small -> 0 mismatch vs BigInteger and vs the Java formula.
2. 90 edge pairs: spent in {0,1,-1,99,100,101,Long.MAX,Long.MIN,922337203685477,922337203685478} x limit in {0,1,-1,3,Long.MAX,Long.MIN,Long.MAX/100,Long.MAX/100+1,92233720368548} -> 0 mismatch.
3. Moving clock weekly: every 10 min over 20 days from 2026-04-15Z, 2026-10-20Z, 2027-04-20Z (both DST changes): 8640 points; period Monday = Cairo Monday, start/endExclusive = Cairo midnights, row counted, daysLeft right -> 0 bad.
4. Moving clock monthly: start days 1, 15, 25, 28, every 97 min 2026-01-01Z..2027-03-15Z (incl. Feb 2027 and both DST changes): 26012 points; period contains today, from.day = start day, row counted, a row 1 ms before period start never counted -> 0 bad. (Start days 29-31 are rejected by AnalyticsPeriod 1..28, same as the server.)
5. Budget createdAt = prev.endExclusive -> rolled 0; minus 1 ms -> 7000; 2026-09-20 -> 7000.
6. Previous period only a 3000 refund -> rolled 13000, remaining 23000 (Java same).
7. Empty thresholds at 200% -> alerts [].
8. Thresholds [100,50,50,201] -> [50,50,100] (NIT above).
9. Category tree p > c > g: budget p = 110 (grandchild g not counted, same as the server SQL c.id = ? or c.parent_id = ?), c = 11, g = 1.
10. Archived child of p still counts under p (110); empty category list -> 100.
11. Future month 2026-12 asked on 25 Oct: onTrack true, daysLeft 31, 0 bps (Java same).
12. WEEKLY budget for past month 2026-09 -> W40 28 Sep..4 Oct (MINOR above).
13. WEEKLY budget for current month at 29 Oct -> W44 through 1 Nov (MINOR above).
14. compute() start day 25, today 5 Nov, weekly total 5 + monthly total 2,000,000 -> remainingBudget 1,100,000 (weekly ignored).
15. compute() for a week period with a monthly total -> null.
16. Two totals with equal createdAt, ids b, a -> a kept, b duplicate.
17. Two specs with the same id -> neither flagged (cannot happen with UUID keys).
18. Status/currency: PENDING_REVIEW 5 counted; DISMISSED 7, lowercase egp 11, USD 13 not -> 5.
19. Projected exactly equal to limit (16000 after 16 of 31 days, limit 31000) -> on track true (<=, as Java).
20. Limit Long.MAX, spent Long.MAX/2 on day 2 -> linear overflows: onTrack false, 5000 bps, no crash (Java would throw; unreachable under the 1e12 cap).
21. MONTHLY start-day-25 budget asked for W44 with rollover -> month 2026-10 (25 Oct..24 Nov), spent 1000, rolled 6000.
22. WEEKLY budget created during the current week with rollover -> 0.
23. Current period refund-only -3000 -> 0 bps, on track, remaining 13000.
24. Zero-amount budget -> 10000 bps, alerts [50,80,100], off track.
Result: 24 cases, 1 problem (cases 12-13, MINOR); the rest match the Java rules.

## Test integrity
test-integrity-check.sh origin/main -> TEST_INTEGRITY: OK (1 added test file, 0 -> 105 assertions, no skips, no deleted or loosened tests). AnalyticsEngineTest and fixtures/analytics/parity-v1.json untouched (the diff stat shows neither). Invariants in the diff: no Double/Float, no logging, no TODO, money as Long minor units, Cairo dates via BusinessCalendar; no migration or generated code touched.

STATUS: PASS
