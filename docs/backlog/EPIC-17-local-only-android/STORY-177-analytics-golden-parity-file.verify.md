# Verification report — STORY-177

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-27  ·  **Base:** `d7a26672ca32ffdcada4f87a19e5b200294ba23a` (origin/main)  ·  **Head:** `4eecfb1ec05d2ff5678fb58039093d1c04d8d271` (previous head `7fc35e4`)
- **Loop:** 2 of 3 (PR #78, branch `story/STORY-177-analytics-golden-file`). Judged from the diff 7fc35e4..4eecfb1: build.gradle.kts (inputs.dir), AnalyticsParityIT.java, README, parity-v1.json, story status, verify report.

## Environment
- `bash scripts/make/check-fast.sh backend` (JDK 21.0.12) -> `check-fast GREEN [backend] in 28s`.
- `bash scripts/quality/test-integrity-check.sh origin/main` -> `TEST_INTEGRITY: OK` (AnalyticsParityIT added, 0 -> 42 asserts, 0 skips; the tol WARN is the known delta/deltaBps false positive).
- Docker 29.9.2; Postgres pinned by digest (postgres:16.15-alpine3.24, musl, byte-order collation, probed in loop 1).

## Claims (loop 2)
1. **Fixture edits re-run the judge - PASS.** build.gradle.kts now declares `inputs.dir("../fixtures/analytics").withPathSensitivity(RELATIVE)` on integrationTest.
   - A, clean run: `bash scripts/make/backend-integration.sh` -> exit 0, 128 integration + 16 arch tests, Parity XML timestamp 20:24:47Z (executed in this run).
   - B, one value edited (sed on the first `"name":"Split Shop","total":6100` to 6101; diff = 1 line), same recipe -> exit 1, `128 tests completed, 1 failed`, `output 'merchants' field 'items[10].total': expected 6101 but computed 6100 (monthStartDay=1 today="2026-04-01")`, new timestamp 20:26:35Z. With the same edit, `gradlew integrationTest archTest --console=plain` shows `> Task :integrationTest` (no UP-TO-DATE or FROM-CACHE), then FAILED.
   - C, restored with a checkout of the file, same recipe -> exit 0 in about 8 s with the run-A timestamp 20:24:47Z. This is a FROM-CACHE hit keyed on content identical to the green run A, so the cache hit is correct.
2. **Generator never green - PARTIAL (MINOR).** After writing, the code now throws AssertionError "parity file regenerated; review the diff and re-run without MASROOF_PARITY_GENERATE". With the one-value edit applied, `MASROOF_PARITY_GENERATE=1 gradlew integrationTest --tests '*AnalyticsParityIT'` executes and FAILS with that message. The file is rewritten to the computed values (status clean, 6101 back to 6100), so the diff is reviewable. But with a pristine file, `MASROOF_PARITY_GENERATE=1 bash scripts/make/backend-integration.sh` -> **exit 0**, served from the build cache (timestamp 20:24:47Z): the env var is not a task input, so the generator never ran. This is harmless for the oracle (a changed file always executes), but "always ends red" does not hold.
3. **Fixture diff and truth - PASS.** Structural diff of the old vs new file: _readme, version, baseCurrency, categories and cases are equal; the old 96 expenses are an unchanged prefix; added r097 and r098 (Split Shop, NULL category, 2500 and 2600) and r099 (Split Shop, groceries, 1000). Changed scenarios: the three April scenarios for month start day 1 (all outputs) and trend12/trend12WithCash of the six Oct/Nov scenarios, which is consistent with rows on 26 Apr 2026. The Mar/Apr scenarios for month start day 25 are unchanged, as expected. Independent Python oracle over the whole new file: **0 differences over 21,723 values**. Split Shop = total 6100, visits 3, averageTicket 2033, categoryKey groceries (mode ignores NULLs: 2 NULL vs 1 groceries).
4. **README - PASS.** It lists the top-level `_readme`, `version` and `baseCurrency` keys. Every behaviour note matches the oracle and the server code: whole-period totals including rows after today; negative-net categories counted in total but not in lines or the share base; tie orders (Bills/Transport, Alpha Kiosk/Metro Ride/Water Co at 9000); byte-order display name Carrefour; averageTicket rounding; mode ignoring NULLs; pendingReviewCount for any type, currency or cash flag; income rules; DST notes (21:00Z is the repeated 23:00 of 29 Oct, 22:00Z is midnight of 30 Oct, 22:00Z on 23 Apr is 01:00 on 24 Apr).
5. **Coverage loop covers every case - PASS.** coverage() has 20 cases: 16 cases.put calls plus 4 DST entries, including the new merchant-mixed-null-category. dst-october-after is now 2026-10-29T22:00:00Z, the true midnight. missingCaseIsNamed asserts hasSize(20); for each name it greedily strips minimal witness sets until the case is false and expects checkCoverage to name it. It is green in executed run A.
6. **check-fast backend and test integrity - PASS** (see Environment).

## Criteria
| REQ | Result | Evidence (command -> observed) |
|---|---|---|
| REQ-AN-143 | PASS | `bash scripts/make/backend-integration.sh` -> 128+16 green (executed). One-value fixture edit -> the task executes and fails naming output merchants, field items[10].total. Independent oracle: 0 / 21,723 differences. Loop-1 evidence (strict compare, malformed seed rows rejected before the service) still applies: this diff does not touch compare or validateSeed. |
| REQ-AN-144 | PASS | 20 coverage cases, all present in the seed. missingCaseIsNamed strips the witnesses of every case and gets a named failure; green in the executed run. |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | backend/build.gradle.kts integrationTest / AnalyticsParityIT.java:162-167 | MASROOF_PARITY_GENERATE is not a task input, so with unchanged inputs a generator run is served from the build cache, exits 0 and regenerates nothing (not always red). The oracle cannot be corrupted this way, but a maintainer may think they regenerated. Fix idea: an inputs.property fed by providers.environmentVariable("MASROOF_PARITY_GENERATE"), or no caching when it is set. | Pristine file after a green run: `MASROOF_PARITY_GENERATE=1 bash scripts/make/backend-integration.sh` -> exit 0, Parity XML timestamp unchanged |
| NIT | docs/backlog/EPIC-17-local-only-android/STORY-177-analytics-golden-parity-file.md | Status set to DONE inside the PR before verification passed (integration normally sets it) | Diff of the story between the heads |
| NIT | test-integrity output | "tolerance 0 -> 4" WARN is a false positive (regex delta matches deltaBps) | Run the script |

Loop-1 findings: the MAJOR (fixture not a Gradle input) is resolved (claim 1). The generator MINOR is reduced to the cache case above. The README MINOR, the mixed-NULL-mode MINOR, the DST-midnight NIT, the coverage-loop NIT and the README-keys NIT are resolved.

## Exploratory
9 cases tried this loop, 1 problem:
1. One-value edit (Split Shop total 6100 to 6101, first April scenario), full recipe -> executed, red, named field.
2. Same edit, gradlew integrationTest archTest --console=plain -> Task :integrationTest executed, FAILED.
3. Restore, full recipe -> green from the cache with the timestamp of the earlier green run (correct hit).
4. Edited file plus MASROOF_PARITY_GENERATE=1, targeted run -> red with the regenerate message; file rewritten to the computed values.
5. Pristine file plus MASROOF_PARITY_GENERATE=1, full recipe -> green from the cache, generator not run (problem, MINOR).
6. Structural diff of the old and new fixture -> only r097..r099 added; scenario changes limited to April month-start-1 scenarios and trend12 of the Oct/Nov scenarios.
7. Independent oracle over the new file -> 0 / 21,723 differences.
8. Split Shop mode with 2 NULL + 1 groceries -> groceries; averageTicket 6100/3 = 2033.
9. Coverage case count: 16 cases.put + 4 DST = 20, matching hasSize(20).

## Test integrity
OK - `bash scripts/quality/test-integrity-check.sh origin/main` -> AnalyticsParityIT.java 0 -> 42 asserts, 0 -> 0 skips, `TEST_INTEGRITY: OK`. Between the heads, missingCaseIsNamed was strengthened from 8 hand-picked removals to every coverage case; nothing was removed or loosened.

Verdict: the oracle file is TRUE (0 / 21,723 independent differences), fixture edits now re-run the judge and fail, and no BLOCKER or MAJOR is open.

STATUS: PASS
