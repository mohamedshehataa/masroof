# Verification report — STORY-176

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-27  ·  **Base:** `f8392be4e55048a9eb78566f36338bb9fa22e9fb` (origin/main)  ·  **Head:** `f31b949e12f207b12094c471b4d43e909a8335fd` (PR #75)
- **Loop:** 1 of 3

## Environment
- `JAVA_HOME=.../jdk-21.0.12.101-hotspot bash scripts/make/check-fast.sh android` → `OK android: ./gradlew checkFast`, **check-fast GREEN [android] in 50s**. JUnit XML (`android/app/build/test-results/testDebugUnitTest`, timestamp 2026-10-27T18:02Z): `AnalyticsPeriodTest` 17 tests, `ProjectionsTest` 12, `ProjectionsReferenceTest` 8; 0 skipped, 0 failures, 0 errors.
- `bash scripts/make/check-fast.sh sdlc` (the story file changed) → **check-fast GREEN [sdlc] in 187s** (sdlc-lint, hook tests, quality script tests, contract lint all OK).
- Kover `reportDebug.xml`, package `eg/masroof/core/analytics`: LINE 98/98, BRANCH 76/76, INSTRUCTION 688/688 (100%).
- CI on the PR head (`gh pr checks 75`): Android check-fast, Backend check-fast, integration, build all pass. In the Security job, **semgrep (p/default + .semgrep/) passed** and gitleaks passed. **osv-scanner failed**: GHSA-xxph-c9ww-hj94 on `com.google.guava:guava` 32.1.3/33.7.1 in `backend/*.lockfile` and `verification-metadata.xml`. The diff touches none of these. Main passed Security at 13:03Z, so this advisory was published afterwards. `PR gate` is therefore red for reasons outside this change.
- Differential harness (scratchpad, not committed): the backend `AnalyticsPeriod.java`, `Projections.java` and `BusinessCalendar.java` were compiled with `javac` from `backend/src/main`. They were run against the Kotlin classes Gradle produced (`android/app/build/tmp/kotlin-classes/debug`) plus kotlin-stdlib 2.3.0 on identical inputs. Outputs were compared as strings, `OK:<value>` or `EX:<exception class>`.

## Criteria
| REQ | Result | Evidence (command → observed) |
|---|---|---|
| REQ-AN-140 | PASS | `./gradlew checkFast` → `AnalyticsPeriodTest` 17/17 green. Side-by-side: all 11 Java `AnalyticsPeriodTest` cases and all 3 `BusinessCalendarTest` cases have a Kotlin twin with identical expected values. Nothing is missing. Extras: start day 25 on 2026-10-27 → start `2026-10-24T21:00:00Z`, endExclusive `2026-11-24T22:00:00Z`; start days -1/0/29/31 throw on parse/currentMonth/month; `abc` is rejected; DST Fri 24 Apr 2026 (23 h day; April = 30 d − 1 h; elapsedDays 25) and Thu 29 Oct 2026 (25 h day; W44 elapsed 4). Differential: `java harness.Diff 20261027 20000` → 100,000 random parse/currentMonth/month/week/ctor cases, plus current/previous for every start day 1..28 over 2020–2032, plus every day 2014–2034 for startOfDay/endOfDayExclusive/dateOf/week+previous → **0 mismatches** (tzdata 2026b). |
| REQ-AN-141 | PASS | `ProjectionsTest` 12/12 and `ProjectionsReferenceTest` 8/8 green. All 10 Java `ProjectionsTest` cases are ported with identical expected values (1250, -5000, 0, 3333, 6667, -6667, empty for prev 0, 2500, 0, 2000, -2000, empty for income 0, 2500, 0, 31000, 0, 25000, 25000, IAE on divisor 0, 50000, 18000, 18, and the 3 property tests). The reference test uses 1,500 iterations per function with fixed seeds and values up to ±10^15. Its expected values come from an independent remainder-based half-away-from-zero formula (`divideAndRemainder`, `r*2 >= d`) and never call `Projections`. Differential: 140,014 projection cases including Long.MIN/MAX edges, negative divisors and negative daily spend → **0 mismatches**, including identical exception classes. |
| REQ-AN-142 | PASS | `linear(Long.MAX_VALUE,1,31)`, `shareBps(MAX,10)`, `deltaBps(MAX,1)`, `deltaBps(MIN,1)`, `savingsRateBps(1,MIN)`, `divideRounded(MAX,1)`, `weekdayAware([MAX,1])` and `deltaBps(10^12,1)` (Int overflow) → `ArithmeticException` in the Kotlin test and identical in the harness (`linear(MAX,1,31)` and `divideRounded(MIN,1)` both EX:ArithmeticException). No-float: a grep of `core/analytics` for android/androidx imports, Double, Float, toDouble and toFloat → no hits. The semgrep kotlin money regex → no hits locally, and semgrep passed in CI. Gradle `checkNoFloatMoney` (part of checkFast) is green and scans `/core/`. The source-grep unit test resolves its relative path under Gradle: it asserts that at least 3 files are found, and it passed. Run from a scratch working directory against mutated copies: adding `fun ratio(a: Long): Double` → fails ("BusinessCalendar.kt matches Double"); adding `import android.util.Log` → fails. |

## Claims to verify (orchestrator list)
1. **Every Java test case ported**: confirmed side by side (above). The 3 Java property tests use `java.util.Random(42/7/99)`. The Kotlin ports use `kotlin.random.Random(42/7/99)`, so they draw different samples (NIT). The properties and assertions are identical.
2. **Logic line by line**: confirmed by reading and by the harness. `divideRounded` uses the same `addExact(multiplyExact(abs(n),2),d) / multiplyExact(d,2)` formula and the same sign handling. Negatives: -5/2 → -3, -1/2 → -1, -3/2 → -2, equal in both. previous() across the year: Jan start-day-25 → 2025-11; W01 previous is covered by the 2014–2034 week sweep. ISO week 53: 2020-W53, 2026-W53, 2004-W53 and 2015-W53 accepted; 2025-W53 and 2027-W53 rejected in both. Start day 25 and Cairo DST 2026: equal.
3. **BigInteger reference independent**: yes, see above. Effective coverage is weaker than it looks: replaying the exact `kotlin.random` sequences (`harness.Stats`) shows how many cases actually reach the BigInteger comparison of a computed value. deltaBps 527, shareBps 566, savingsRateBps 541, linear 631, weekdayAware 660, divideRounded 1500, dailyAverage 1361. The remaining cases go to the null/0/shortcut or overflow branches. The overflow branch is never reached for divideRounded, dailyAverage, linear or weekdayAware, because ±10^15 cannot overflow them (MINOR).
4. **Overflow → ArithmeticException**: confirmed (REQ-AN-142 row).
5. **Invalid start days / malformed specs throw like Java**: confirmed. IllegalArgumentException in both for -5..0 and 29..34 random start days and for 40 malformed specs; 0 mismatches.
6. **No Double/Float/android/Room; grep test path and sensitivity**: confirmed. Gaps: the unit test misses `.toDouble()` and inferred-Double literals (`a * 1.5`), and its non-recursive `listFiles` would miss subpackages. Both mutants still pass the unit test (`OK (8 tests)`), but the `checkNoFloatMoney` regex flags both lines, so the gate as a whole catches them (NIT).
7. **Locale.ROOT for week labels**: safe and correct. Harness: server code under default `ar-EG` emits Arabic-Indic digits for the week label, while the Kotlin port under `ar-EG` and `ar-EG-u-nu-arab` emits `2026-W05`. The prod server image (alpine JRE, no LANG or user.language in `backend/Dockerfile` or `JAVA_TOOL_OPTIONS`) uses the default en locale, so the server output is `2026-W05` and the port matches it. Month labels use `YearMonth.toString()`, which is not affected by locale.
8. **Differential harness**: done. Two seeds (176/N=5000 → 170,334 cases; 20261027/N=20000 → 350,334 cases, 33,294 of them exception-class comparisons) → **0 mismatches**.

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | CI `PR gate` / Security / osv-scanner | The PR gate is red because of new advisory GHSA-xxph-c9ww-hj94 (guava 32.1.3/33.7.1, fixed in 33.7.2) in `backend/gradle.lockfile`, `backend/buildscript-gradle.lockfile` and `backend/gradle/verification-metadata.xml`. The PR does not cause it (the diff touches only `android/.../core/analytics` and the story file), but the PR cannot merge until a guava bump lands on main. | `gh pr checks 75`; `gh run view 40556345778 --job 122373313987 --log-failed` |
| MINOR | `android/app/src/test/java/eg/masroof/core/analytics/ProjectionsReferenceTest.kt:50-54,86-133` | The symmetric `value()` sends about half the cases into the prev/whole/income ≤ 0 shortcut. Only 527–660 of the 1,500 cases per function reach the BigInteger comparison for deltaBps, shareBps, savingsRateBps, linear and weekdayAware, which is below the spec "at least 1,000 cases per function compared". The overflow branch is never reached for divideRounded, dailyAverage, linear or weekdayAware; explicit cases in `ProjectionsTest` cover it. Parity itself is proven by the differential harness. | Replay the seeds with `harness.Stats` (kotlin.random.Random(1..6), 1500 iterations) → counts above |
| MINOR | `android/app/src/main/java/eg/masroof/core/analytics/AnalyticsPeriod.kt:55,74-81` | Possible on-device divergence (simulated, not observed on a device). On Android, java.util.regex is ICU, where the digit class matches any Unicode digit, and `String.toInt()` accepts Arabic-Indic digits. So `parse("٢٠٢٦-١٠", ...)` would return period 2026-10 on the phone, while the server and the JVM unit tests throw IllegalArgumentException. Low impact: specs are built by app code, not typed by users. An ASCII class `[0-9]` would make it identical. | `Pattern.compile(<same spec regex>, UNICODE_CHARACTER_CLASS).matcher("٢٠٢٦-١٠").matches()` → true; `Integer.parseInt("٢٠٢٦")` → 2026 (harness output) |
| NIT | `ProjectionsReferenceTest.kt:216-226` | The source-grep test catches only literal `Double`/`Float` tokens and android/Room imports, not `.toDouble()` or float literals, and it scans one directory level only. `checkNoFloatMoney` covers the first gap. | Mutants `fun ratio(a: Long) = a.toDouble() / 3` and `fun ratio(a: Long) = a * 1.5` → `JUnitCore ProjectionsReferenceTest` → `OK (8 tests)`; the same lines match the `checkNoFloatMoney` regex |
| NIT | `ProjectionsTest.kt:79-131` | The ported property tests use `kotlin.random.Random` rather than `java.util.Random` with the same seeds, so they check the same properties on different samples than the Java tests. | Read the code |

## Exploratory
20 inputs tried (all through the differential harness unless noted), 1 problem (the simulated Android ICU digit case):
1. `"2026-10<LF>"` (trailing newline) → both IAE
2. `" 2026-10"`, `"2026-10 "` → both IAE
3. `"٢٠٢٦-١٠"` (Arabic-Indic) → both IAE on the JVM; **ICU-style regex would accept it on Android** (finding)
4. `"２０２６-10"` (fullwidth digits) → both IAE on the JVM
5. `"2026-W53"`, `"2004-W53"`, `"2015-W53"` → equal accepted periods; `"2027-W53"`, `"2025-W53"` → both IAE
6. `"0000-01"`, `"9999-12"`, `"9999-W52"`, `"0000-W01"`, `"0001-W01"` → equal
7. `"CURRENT"`, `"Current"`, `"2026-w01"`, `"2026W10"`, `"2026-10-01"`, `"2026-W5"`, `"2026-W053"`, `"+026-10"`, `"-026-10"` → both IAE
8. Random `YYYY-MM` with month 00..99 and `YYYY-Www` with week 00..99 across years 0000..9999 → equal
9. monthStartDay random in -5..34 → identical IAE / results
10. `linear(Long.MAX_VALUE, 1, 31)` → both ArithmeticException
11. `divideRounded(Long.MIN_VALUE, 1)` → both ArithmeticException; `divideRounded(1, 0)`, `(1, -1)` → both IAE
12. `divideRounded(-5,2)`, `(5,2)`, `(-1,2)`, `(-3,2)`, `(±7,14)`, `(±6,14)` → equal half-away-from-zero results
13. `divideRounded(Long.MAX_VALUE/2, Long.MAX_VALUE)` → equal (ArithmeticException on `d*2`)
14. `weekdayAware(2026-01-01, [1], Integer.MIN_VALUE)` → both return the total
15. weekdayAware with negative daily values (refund-heavy days) and 10^15-sized daily values → equal, including overflow exceptions
16. dailyAverage/linear with elapsed/length in -10..69 → equal
17. Constructor with `to` before `from` in both week flags → both IAE; previous() on hand-built periods whose `from` day is above 28 → both IAE
18. Default locale `ar-EG` / `ar-EG-u-nu-arab` → the Kotlin labels stay ASCII; the server code would not, but the prod server runs with the en default
19. Every Cairo day 2014–2034 (startOfDay, endOfDayExclusive, dateOf at +23h30) → equal, including all Egyptian DST transitions in range
20. Copies of the analytics sources mutated with an explicit Double type, `.toDouble()`, a float literal and an android import, run through the real compiled `ProjectionsReferenceTest` → the Double type and the android import fail the test; the other two pass the test but are caught by `checkNoFloatMoney`

## Test integrity
OK — `bash scripts/quality/test-integrity-check.sh origin/main` → 3 test files added (assertions 0 → 123), 0 skips. The two "tolerance increased" WARNs are false positives from the identifier `deltaBps` matching `delta`. The only real tolerance (`abs(2*delta - exactTimes2) <= 2`) is copied verbatim from the Java test. No test was deleted, skipped or loosened.

Verdict: the port is correct. The differential harness shows 0 mismatches over 350k cases, and every REQ is shown by a real run. Merge is blocked only by the unrelated osv advisory on the backend guava.

STATUS: PASS
