# Verification report — STORY-177

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-27  ·  **Base:** `d7a26672ca32ffdcada4f87a19e5b200294ba23a` (origin/main, merge base)  ·  **Head:** `7fc35e4794e9da1793a9ce9042104a8957a79d03`
- **Loop:** 1 of 3 (PR #78, branch `story/STORY-177-analytics-golden-file`)

## Environment
- `bash scripts/make/check-fast.sh backend` (JDK 21.0.12) -> `check-fast GREEN [backend] in 28s`.
- `bash scripts/make/backend-integration.sh` (Docker 29.9.2) -> `integrationTest executed 128 test(s)`, `archTest executed 16 test(s)`, exit 0.
- `./backend/gradlew -p backend integrationTest --tests '*AnalyticsParityIT' --rerun-tasks` -> BUILD SUCCESSFUL; AnalyticsParityIT tests=6 skipped=0 failures=0 errors=0; worktree clean afterwards.
- Pinned Postgres image (IT and infra/docker-compose.yml) is postgres:16.15-alpine3.24 by digest f4610795...: `psql -c "select datcollate ..."` -> `en_US.utf8`, `PostgreSQL 16.15 on x86_64-pc-linux-musl`; ordering 'Carrefour','carrefour','apple','Banana' -> `Banana,Carrefour,apple,carrefour` (musl = byte order).

## Claims
1. **Test-only diff - PASS.** `git diff --name-only origin/main...HEAD` -> AnalyticsParityIT.java (src/test), docs/DECISIONS.md, story file (READY to IN_PROGRESS), fixtures/analytics/README.md, fixtures/analytics/parity-v1.json. Nothing under backend/src/main, android/ or db/migration.
2. **Real service, strict comparison - PASS (test logic; gating gap in Findings).** SpringBootTest + Testcontainers, tag integration, real AnalyticsService, seed inserted into Postgres, month_start_day 1 and 25, Primary movable Clock at 12:00 Cairo on 6 todays per start day (first/mid/last enforced by checkCases); outputs summary, summaryWithCash, forecast, byCategory(+cash), merchants(50), heatmap(+cash), trend6/12(+cash). 12 scenarios, 21,654 leaf values.
   - 1 piaster in the real file (heatmap.weekdayHour[5][23] 6030 to 6031, run with --rerun) -> FAIL `output 'heatmap' field 'weekdayHour[5][23]': expected 6031 but computed 6030 (monthStartDay=1 today="2026-10-14")`.
   - Swap merchants items[0]/[1] -> FAIL `output 'merchants' field 'items[0].name': expected "Grid Power" but computed "Delta Fuel"`.
   - Integer written as 0.0, deltaBps -3724 to null, extra key -> FAIL `field '[0].total': expected 0.0 but computed 0`, `field 'deltaBps': expected null but computed -3724`, `field 'extra': missing in the computed output`.
   - MASROOF_PARITY_GENERATE: found only in the IT, README and _readme; not set in .github/workflows. Set on a corrupted file -> test PASSES and silently rewrites the file. Set on the pristine file -> byte-identical regeneration (committed file equals generator output). No guard that it is unset in CI (MINOR).
   - Cache experiment RUN: fixture-only edit -> `Task :integrationTest UP-TO-DATE`; full `bash scripts/make/backend-integration.sh` with the corrupted file -> exit 0, AnalyticsParityIT failures="0", XML timestamp unchanged (2026-10-27T20:05:36Z, restored from build cache). See MAJOR.
3. **Malformed seed rows - PASS.** r050 in the real file: amountMinor 2222.5 -> `IllegalArgumentException: seed row r050: amountMinor must be an integer of at least 1`; type REJECTED -> `seed row r050: type is not a known enum value`; currency EGPP -> `seed row r050: currency must be 3 upper-case letters`. All thrown by validateSeed before loadSeed/compute. The in-test case also covers 12.5, "100", 0, BONUS, EG, egp, date-only occurredAt, unknown categoryKey.
4. **REQ-AN-144 coverage - PASS.** All listed cases present (transfer r017/r042, cash r016/r038/r076, USD r018/r041/r043, PENDING_REVIEW r025/r040/r078, IGNORED r034, POSSIBLE_DUPLICATE r035, deleted r036/r044, income, uncategorised, car.fuel under car, " Carrefour "/carrefour, Lotus Styles net -30000, DST pairs r049/r050 and r085..r088, _readme with version 1 / never changes / parity-v2.json). Removing r027 in the real file (a case not in the test's own removal list) -> FAIL `seed is missing the case 'merchant-net-not-positive'`.
5. **Independent truth check - PASS.** An independent Python oracle (scratchpad; hand-coded Egypt DST +3 between 2026-04-23T22:00Z and 2026-10-29T21:00Z, no tzdata; integer half-away-from-zero rounding; ADR-0009 D2 rules) recomputed all 12 scenarios: **0 differences over 21,654 values** with byte-order merchant names (3 name-only diffs, "Carrefour" vs "carrefour", under glibc-style collation). Hand-checked values, all equal to the file:
   - Summary with refund netting (1, 2026-04-15): 6000+31000-4000+27500+90000-120000+12000+800+3000+3500+2000+48000+25000+18000+3x9000+1111+2222+3333+4444+7+5555 = **186472** (r022 at Apr 1 00:00 in; r055 at May 1 00:00 EEST out; IGNORED, POSSIBLE_DUPLICATE, deleted, USD out). pendingReviewCount **4** (r025, r040 INCOME, r042 TRANSFER, r043 USD).
   - includeCashWithdrawals: April 186472+200000 = **386472**; (1, 2026-10-14) 214400+150000 = **364400**.
   - Roll-up: Car (1, 2026-04-15) = r033 car.fuel **48000**, previous 55000+120000 = 175000, deltaBps **-7257**, shareBps 48000x10000/216472 = **2217** (share base excludes Clothing -30000). Bills (25, 2026-04-10) = r020 electricity 38000 + r047 9000 = **47000**, count 2, previous 9900, deltaBps **37475**.
   - Merchants (1, 2026-10-14): Carrefour 33000-3000 = **30000**, visits 2, averageTicket 15000, groceries, name "Carrefour"; Lotus Styles (90000-120000) absent from all April lists; ATM never listed.
   - Heatmap October DST: 2026-10-29 = r085 (23:59:59 EEST) + r086 (23:00 EET) + r087 (23:59:59 EET) = **6030 / 3**; 2026-10-30 = r088 **4010**; weekdayHour Thu[5][23] **6030**, Fri[6][0] **4010**, Sat[0][23] **7030** (r089 5010 + r080 2020). April (25, 2026-04-24): day 04-24 = r050+r051 = **5555 / 2**.
   - Forecast (1, 2026-10-14): spentSoFar **214400**, linear 214400x31/14 = **474743**, weekdayAware 115500 + Thu 52500x3/2 + Fri 33000x3/2 + Sat -3000x3/2 + Mon 19000 + Wed 14000 = **272250**. Trend Oct: income 2200000, savingsRateBps **9025**.
6. **Deviations - PASS with MINOR notes.** REJECTED does not exist (ExpenseStatus = CONFIRMED, PENDING_REVIEW, POSSIBLE_DUPLICATE, IGNORED; V3 CHECK constraint), substitution correct. Category reference_key matches seed_household_categories (asserted in the IT) and the Android cat-reference_key ids. Collation: exactly one value is collation-dependent, merchants name "Carrefour"; on the pinned Alpine/musl image it is byte order, which equals Kotlin String.compareTo, so deterministic for the port (glibc Postgres would give "carrefour"). Total ties (Alpha Kiosk / Metro Ride / Water Co at 9000; Bills / Transport at 9000) differ at the first letter, so collation-independent. No mode() tie in any merchant group (Mixed Mart 2 groceries vs 1 dining), so nothing depends on random UUID tie-breaks. Not pinned: mode() ignoring NULL categories (no merchant mixes NULL and non-NULL).
7. **Privacy - PASS.** `gitleaks dir fixtures/analytics --no-banner --redact` -> `no leaks found`. Longest digit runs are 9 digits (amounts such as 123456789 and derived totals); no 14-19 digit run; no Egyptian mobile pattern; merchants invented (Carrefour is mandated by the story, a public brand).
8. **README vs JSON keys - PASS.** Key inventory: categories, expenses, cases, scenarios, the 12 output names, summary / line / forecast / byCategory / merchants / heatmap (7x24) / trend / period fields and topCategories max 5 all match. README omits top-level `_readme`, `version`, `baseCurrency` (NIT).

## Criteria
| REQ | Result | Evidence (command -> observed) |
|---|---|---|
| REQ-AN-143 | PASS | `gradlew integrationTest --tests '*AnalyticsParityIT' --rerun-tasks` -> 6/6 green; real-file mutations (1 piaster, swap, int vs decimal, null, extra key) each FAIL naming output + field; malformed amount/enum/currency fail in validateSeed before the service; independent oracle agrees on 21,654/21,654 values |
| REQ-AN-144 | PASS | all listed cases present; removing r027 -> `seed is missing the case 'merchant-net-not-positive'`; missingCaseIsNamed and readmeStatesTheRules green |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MAJOR | backend/build.gradle.kts taggedTest / AnalyticsParityIT FILE | parity-v1.json is not a declared input of integrationTest and org.gradle.caching=true, so an edit that touches only the oracle file does not re-run the judge: the task is UP-TO-DATE or restored from the build cache (shared by all worktrees on one machine; in CI setup-java caches ~/.gradle/caches). `make backend-integration` / `make check` can go green on a wrong oracle, the exact failure this story guards against. Fix idea: inputs.dir("../fixtures/analytics") on integrationTest. | Run `bash scripts/make/backend-integration.sh` on head (green); add 1 to scenarios[4].outputs.heatmap.weekdayHour[5][23]; run it again -> exit 0, AnalyticsParityIT failures="0", XML timestamp unchanged. `gradlew integrationTest --tests '*AnalyticsParityIT'` -> UP-TO-DATE. |
| MINOR | AnalyticsParityIT.java:162-166 | With MASROOF_PARITY_GENERATE set, the golden test passes and overwrites the file instead of failing or aborting. A stray env var (CI, IDE run config) turns the judge into a rubber stamp. CI does not set it today. | `MASROOF_PARITY_GENERATE=1 gradlew integrationTest --tests '*AnalyticsParityIT' --rerun` on a file with a 1-piaster edit -> BUILD SUCCESSFUL, file silently rewritten |
| MINOR | fixtures/analytics/README.md | Non-obvious pinned semantics the Kotlin port must reproduce are undocumented, and v1 freezes at merge: (a) merchant name = min trimmed raw name in byte/code-point order (Alpine musl), total ties ordered by that name; (b) spentSoFar/total/linear/dailyAverage include rows dated after today within the period, weekdayAware uses elapsed days only (at (1, 2026-10-14) linear 474743 vs weekdayAware 272250); (c) pendingReviewCount counts live PENDING_REVIEW rows of any type and currency; (d) totals include negative-net categories but shareBps uses positive ones only; (e) merchant category mode ignores NULLs. | Compare README with the values in claim 5 |
| MINOR | parity-v1.json seed | No merchant mixes NULL and non-NULL categories, so mode() ignoring NULLs is not pinned for the port. Mode ties are rightly absent. Add a row before merge or accept explicitly. | Oracle: no mixed-null merchant group in any scenario |
| NIT | AnalyticsParityIT.java:81-86, 393-398 | dst-october-after is pinned to 2026-10-29T21:00:00Z (23:00 EET, still 29 Oct), not the post-midnight 22:00:00Z (r088). Removing r088 would not trip coverage. merchant-net-not-positive groups by UTC month, not household period. | Read code |
| NIT | AnalyticsParityIT.java:250-261 | missingCaseIsNamed exercises 8 of 21 coverage cases | Removing r027 gives a named failure (claim 4) |
| NIT | fixtures/analytics/README.md | Top-level `_readme`, `version`, `baseCurrency` not listed | Key inventory |
| NIT | test-integrity output | "tolerance 0 -> 4" WARN is a false positive (regex `delta` matches deltaBps) | Run the script |

## Exploratory
15 cases tried, 3 problems. Temporary edits to fixtures/analytics/parity-v1.json, each restored with a checkout of that file:
1. scenarios[4] heatmap.weekdayHour[5][23] 6030 to 6031 -> named FAIL
2. swap scenarios[4] merchants.items[0] and [1] -> named FAIL
3. scenarios[0] trend6[0].total 0 to 0.0 -> FAIL (kind check)
4. scenarios[0] summary.deltaBps -3724 to null -> FAIL
5. extra key scenarios[0] summary.extra = 1 -> FAIL
6. r050 amountMinor 2222.5 -> seed error before the service
7. r050 type "REJECTED" -> seed error
8. r050 currency "EGPP" -> seed error
9. drop r027 -> coverage names merchant-net-not-positive
10. MASROOF_PARITY_GENERATE=1 on a corrupted file -> green plus silent rewrite (problem)
11. MASROOF_PARITY_GENERATE=1 on the pristine file -> byte-identical regeneration
12. fixture-only edit, gradlew integrationTest without --rerun -> UP-TO-DATE (problem)
13. fixture-only edit, scripts/make/backend-integration.sh -> exit 0 from the build cache (problem)
14. collation probe on the pinned image -> byte order
15. independent Python oracle over all 12 scenarios -> 0 diffs (byte-order names)

## Test integrity
OK - `bash scripts/quality/test-integrity-check.sh origin/main` -> AnalyticsParityIT.java added, 0 -> 41 asserts, 0 skips, tol 0 -> 4 (WARN, false positive on deltaBps), `TEST_INTEGRITY: OK`. No test deleted, skipped or weakened.

Verdict: parity-v1.json is TRUE (independently reproduced, 21,654/21,654 values) and the comparison is strict. FAIL only because of the open MAJOR: on fixture-only edits the build cache can bypass the judge.

STATUS: FAIL
