# Verification report — STORY-002

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-23  ·  **Base:** `68008e6d546b285f0541f06cca4b8115ec0ccc76`  ·  **Tests:** `db2d8a251864e22dd3a5372d08434361433ec236`  ·  **Head:** `d86e255b7a4caef3fbb79b13a071864c0e6138cd`
- **Loop:** 1 of 3

## Environment
Windows 11, Git Bash, `source scripts/env.sh`, JAVA_HOME = Temurin 21.0.12.1, Docker Engine 29.9.2. Branch `story/STORY-002-walking-skeleton-health-version`, not committed. Exploratory plants were made in an isolated copy (`git archive HEAD backend` into the scratchpad, with its own `git init` and the repo `.gitattributes`). Nothing was planted in the real tree by hand. Only the gate probes and selftest planted files there, and both reverted them: `git status --porcelain backend/src` is clean afterwards.
- `./gradlew --console=plain checkFast --rerun-tasks --no-build-cache` -> BUILD SUCCESSFUL in 15 s. These tasks ran: compileJava, checkstyleMain, compileTestJava, spotlessJava/spotlessJavaCheck/spotlessCheck, spotbugsMain, test, checkFast, jacocoTestReport. The unit `test` task ran 4 tests (MoneyRuleProbeTest), 0 failed.
- `make check-fast MODULE=backend` x2 -> rc 0, 2 s / 2 s (warm).
- `make check-fast` (no MODULE) -> rc 0, "check-fast GREEN [sdlc backend] in 217s".
- `make check` -> rc 0 in 545 s, "check GREEN". It covered check-fast [sdlc backend], the gate probes (6/6 PASS), integrationTest, archTest, contract diff, the coverage gates, traceability and test integrity.
- `./gradlew archTest integrationTest migrationTest --rerun-tasks` -> archTest 9/9 (rules 1-7; rules 2 and 6 have two constants each), integrationTest 7/7, migrationTest 7/7.
- `make selftest` -> rc 0, "15 proven, 0 pending, 0 failed". The G4 row is now **PROVEN**. This run updated docs/SDLC_SELFTEST.md, which is selftest's own output.
- `make trace` -> "325 REQs, 12 covered, 12 passing, 0 unknown, gate PASS". REQ-OPS-023 and REQ-OPS-024 are both PASS.
- `python scripts/quality/sdlc-lint.py` -> "0 error(s), 0 warning(s)".
- `./gradlew pitest` (scratch copy) -> BUILD SUCCESSFUL, "No mutations found", no mutations.xml (accepted). With a planted domain class it generated 3 mutations and wrote mutations.xml, so targetClasses is wired.
- JaCoCo: `backend/build/reports/jacoco/test/jacocoTestReport.xml` is produced (sessioninfo only). The coverage gate reads it, and its backend rows are SKIP "no data for token". The PIT row is SKIP and points at make check-full (accepted).
- docs/DECISIONS.md:85 records google-java-format 1.36.0 (Spotless 8.10.4 + 1.37.0 fails), and build.gradle.kts pins `googleJavaFormat("1.36.0")`. DECISIONS:51 records the text vs varchar resolution in favour of the story.
- V1__baseline.sql is byte-for-byte the story DDL (text columns), plus the header comment.
- backend/README.md is 10 lines and covers checkFast, archTest, spotlessApply, integrationTest/migrationTest, pitest, the JaCoCo path, the root make targets and the do-not-edit files.

## Criteria
| REQ | Result | Evidence (command -> observed) |
|---|---|---|
| REQ-OPS-023 | PASS | `./gradlew checkFast --rerun-tasks --no-build-cache` -> exit 0. All listed tasks executed. `-Werror` + Error Prone + NullAway (AnnotatedPackages=eg.masroof) are present on all JavaCompile tasks. Exploratory runs confirmed each one bites: an EP warning (MissingOverride), a rawtypes lint, NullAway and SpotBugs HIGH (IL infinite recursion) all fail the build. `make check-fast MODULE=backend` warm -> rc 0 in 2 s (< 180 s). `make check-fast` -> rc 0, sdlc + backend headers present. `make check` -> rc 0 in 545 s (< 15 min). Inside it, `backend-gate-probes.sh` gave PASS for the tab file (named + spotless/checkstyleMain/compileJava), the unused import (named + compileJava), the NullAway nullable dereference (`GateProbeNull.java:N` + `[NullAway]`), dispatch, warm < 3 min, and the clean tree. archTest and integrationTest are green there. |
| REQ-OPS-024 | PASS | `./gradlew integrationTest migrationTest --rerun-tasks` -> 7/7 each on postgres:16-alpine. flyway_schema_history has exactly (1, true, V1__baseline.sql). The columns, types, NOT NULL and PK match. Defaults are EGP / Africa/Cairo / 1 / non-null timestamps. month_start_day 0 and 29 -> 23514; 1 and 28 are accepted. `./gradlew archTest` -> 9 rule constants for rules 1-7, all pass on the skeleton with allowEmptyShould. MoneyRuleProbeTest (in `test`/checkFast) -> the BadMoney AssertionError contains "BadMoney". `make selftest` -> G4 PROVEN. Reproducing the G4 plant in the scratch copy fails archTest in MONEY_RULE with "Field <eg.masroof.domain.SelftestPlant.amount> has raw type double" in the JUnit XML. |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | commits db2d8a2 + d86e255 | The hand-written size is about 812 code/test/script lines (+39 docs), against the ~400 budget (story DoR, design estimate ≈ 388). Tests are 607 lines (gate probes 178 vs ~55 estimated, V1BaselineMigrationTest 174 vs ~65, ArchitectureRulesTest 162 vs ~70). Implementation is about 205 lines (within its ≈173 estimate). | `git diff --stat db2d8a2~1..d86e255` excluding gradlew, gradlew.bat, gradle/wrapper and config/checkstyle -> 835 insertions |
| MINOR | scripts/quality/traceability.py:49 (`EXCLUDED_DIRS` contains "out") | Pre-existing, first hit by this story: every file under `.../adapters/out/...` is skipped by the REQ scanner. V1BaselineMigrationTest, the only test for the DB half of REQ-OPS-024, is missing from the REQ-OPS-024 evidence. Results are still matched by JUnit case name, so a failure would still turn the row FAIL. But a REQ tested only by an outbound-adapter test would show as uncovered. | `make trace`, then count V1Baseline on line 365 of docs/TRACEABILITY.md -> 0 |
| MINOR | ArchitectureRulesTest MONEY_RULE (design-acknowledged scope) | A floating-point money **parameter** passes archTest when the method name is not money-like, and so does any **constructor** parameter in domain/application: (a) `public static long pay(float fee)` in `eg.masroof.application.PayFee`; (b) `public Expense(double egp)` in `eg.masroof.domain.Expense`. Case (a) would be caught only by semgrep `masroof-money-not-floating-java` (not installed locally; CI/`make security` only). Case (b) is caught by nothing. | Plant either class in main sources, run `./gradlew archTest` -> BUILD SUCCESSFUL |
| MINOR | backend/build.gradle.kts test tasks (no `testLogging`) | archTest/test console output never names the violating class or rule. Under `-q` (make check, selftest) it only says "There were failing tests", so the SDLC_SELFTEST G4 evidence cell reads "exit 1; " with no detail. The class and rule are only visible in build/test-results XML / HTML. | Plant `SelftestPlant` (`public double amount`) in domain, run `./gradlew -q archTest` -> 0 occurrences of SelftestPlant/MONEY_RULE in the output |
| NIT | MONEY_RULE NAME regex | Substring false positive: `record Scale(double multiplier)` in domain fails MONEY_RULE, because "mul-tip-lier" matches `tip`. | Plant that record in `eg.masroof.domain`, run `./gradlew archTest` -> MONEY_RULE FAILED |
| NIT | V1__baseline.sql (matches spec DDL verbatim) | The schema accepts `base_currency` "egp" and "EG" (stored padded), `timezone` "Mars/Phobos", and an empty `name`. `id` has no default. Validation is for later stories / the API layer. | psql against V1 on postgres:16-alpine; the inserts are listed under Exploratory |
| NIT | `make check-fast` (all modules) | 217 s and 231 s (> the Makefile's "< 3 min" headline). The sdlc gate dominates; backend is 2 s. The REQ-OPS-023 budget only covers MODULE=backend, so this is not a criterion failure. A concurrent verifier may have added load. | `make check-fast < /dev/null` -> "check-fast GREEN [sdlc backend] in 217s" |
| NIT | Gradle (same project dir) | `./gradlew test --rerun-tasks` run concurrently with `./gradlew checkFast --rerun-tasks` made `MoneyRuleProbeTest.archTestImportSeesMainClassesOnly` (MoneyRuleProbeTest.java:58) fail, because the other build rewrote build/classes. 3 rounds of normal (non-rerun) concurrent runs after a source edit were all green. | Two `--rerun-tasks` runs in parallel in backend/ |

## Exploratory
29 cases tried, 5 problems (E1, E12, E13, E10 lax values, E11a). Inputs, verbatim, run in the isolated scratch copy unless noted:
- E1 `eg.masroof.application.PayFee`: `public static long pay(float fee)` -> checkFast + archTest GREEN (**gap**, see Findings)
- E2 `eg.masroof.domain.SpringyDomain` annotated `@org.springframework.stereotype.Component` -> archTest FAIL in DOMAIN_IS_FRAMEWORK_FREE
- E2b `eg.masroof.domain.JpaDomain` importing `jakarta.persistence.Entity` -> compileJava FAIL (JPA is not on the classpath yet)
- E3 `eg.masroof.adapters.out.log.Printer`: `static final PrintStream OUT = System.out;` -> archTest FAIL in NO_STANDARD_STREAMS (XML names Printer.java:7)
- E4 `eg.masroof.adapters.out.persistence.Repo`: `@Autowired private JdbcTemplate jdbc;` -> checkFast green, archTest FAIL in NO_FIELD_INJECTION
- E5 `V2__dup_household.sql` = `CREATE TABLE household (id uuid PRIMARY KEY);` -> migrationTest FAIL, "relation household already exists"
- E5b `V2_bad_name.sql` = `SELECT 1;` -> context fails, "FlywayException: Invalid SQL filenames found"
- E6 checkFast with JAVA_HOME unset -> green (java on PATH is Temurin 21)
- E6b JAVA_HOME = Android Studio JBR 21.0.10, `--rerun-tasks` -> green
- E7 `./gradlew --offline checkFast` warm -> green, 1 s
- E8 `Crlf.java` with CRLF line endings, otherwise well formatted -> spotlessJavaCheck FAIL, file named
- E9 `adapters.in.rest.Ctl` calling `adapters.out.persistence.Store.id()` -> archTest FAIL in ADAPTERS_IN_NOT_TO_OUT + ADAPTER_SLICES_INDEPENDENT
- E10 (throwaway postgres:16-alpine, V1 applied via psql):
  - rejected: month_start_day NULL -> 23502, -1 -> 23514, 2147483647 -> 23514, id NULL -> 23502, insert without id -> 23502
  - accepted: name "" / base_currency "egp" / base_currency "EG" / timezone "Mars/Phobos" / an Arabic name with Arabic-Indic digits
- E11a `./gradlew test --rerun-tasks` concurrently with `./gradlew checkFast --rerun-tasks` -> test FAIL (MoneyRuleProbeTest.java:58, race on build/classes)
- E11b 3 rounds of concurrent `./gradlew test` + `./gradlew checkFast` after editing MasroofApplication.java -> all green
- E12 `eg.masroof.domain.Expense(double egp)` constructor -> archTest GREEN (**gap**)
- E13 `eg.masroof.domain.Scale(double multiplier)` record -> MONEY_RULE FAIL (false positive)
- E14 `Loop.f(int)` infinite recursion with `@SuppressWarnings("InfiniteRecursion")` -> spotbugsMain FAIL "H C IL ... Loop.java:[line 9]"
- E15 unused `import java.util.List;` in a **test** source -> compileTestJava FAIL [RemoveUnusedImports]
- E16 raw `List l = new ArrayList();` -> compileJava FAIL (-Xlint rawtypes + -Werror)
- E17 tab-indented **test** source -> spotlessJavaCheck FAIL, file named (checkstyleTest is disabled, but Spotless covers it)
- E19 `eg.masroof.application.Quote(Double amount)` with `Double totalPrice()` -> MONEY_RULE FAIL (field, accessor and method named)
- E20 PIT with planted `eg.masroof.domain.Clamp.day(int)` -> 3 mutations generated, mutations.xml written
- E21 `toString()` without `@Override` -> compileJava FAIL ([MissingOverride] under -Werror)
- G4 replica: `SelftestPlant` with `public double amount` in domain, `./gradlew -q archTest` -> exit 1. The XML names SelftestPlant.amount; the console does not.

## Test integrity
OK. `bash scripts/quality/test-integrity-check.sh 68008e6` -> "assertions: 323 (base) -> 549 (now)", "TEST_INTEGRITY: OK". `git diff --stat db2d8a2..d86e255 -- backend/src/test scripts/quality/tests` is empty: the implementer touched no test-designer file. `git log 68008e6..HEAD -- backend/src/test scripts/quality/tests/backend-gate-probes.sh` lists only db2d8a2. `.claude/state/test-integrity.log` has 6 entries, all from STORY-001 work on test-test-integrity-check.sh, none for STORY-002 files.

STATUS: PASS
