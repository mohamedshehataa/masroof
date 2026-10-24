# Verification report — STORY-144

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-24  ·  **Base:** `b2eabfc80abe002259dfd5b37fc14e70572aef90`  ·  **Head:** `857eeccf33262ccd47b99034c8a34864f8c00712`
- **Loop:** 1 of 3
- Verified in an isolated worktree checked out detached at 857eecc. The tamper and regeneration probes ran in a scratch copy of `backend/`, so no committed file was overwritten.

## Environment
- `make check-fast MODULE=backend` was run twice. Run 1 exited 0 (3.1 s wall). Run 2 (warm) exited 0 with "check-fast GREEN [backend] in 2s", under the 180 s budget.
- `cd backend && ./gradlew --rerun-tasks --no-build-cache checkFast archTest integrationTest migrationTest < /dev/null` -> BUILD SUCCESSFUL in 36 s, 12 of 12 actionable tasks executed, exit 0.
- `make check < /dev/null` -> exit 0, "check GREEN", real 2m21s. Its steps: check-fast [sdlc backend] 84 s, backend gate probes, integration, ArchUnit, contract diff vs main, coverage gates, traceability gate PASS, test integrity vs main OK.
- `make security` -> exit 0, "security GREEN". gitleaks (full history), osv-scanner and trivy fs were OK. **semgrep was skipped (not installed).**
- `make trace` -> exit 0, "330 REQs, 15 covered, 14 passing, 0 unknown, gate PASS". The regenerated `docs/TRACEABILITY.md` in the worktree was restored afterwards.
- Tools: JDK 21.0.12 (Temurin), Gradle 8.14.6, Docker running, osv-scanner 2.7.0.

## Criteria
| REQ | Result | Evidence (command → observed) |
|---|---|---|
| REQ-OPS-142 | PASS | The from-scratch `./gradlew --rerun-tasks --no-build-cache checkFast archTest integrationTest migrationTest` exited 0 (36 s). `bash scripts/quality/tests/backend-boot41-checks.sh` reported 22/22 PASS: plugin 4.1.2; overrides 1-6 removed; no jqwik; `spring-boot:4.1.2` in the lockfile; 0 × `3.5.16` in both lockfiles; log4j-api = log4j-to-slf4j = 2.25.5; no webmvc/jdbc-test starters; no `tomcat-embed-core` runtimeClasspath entry (no tomcat appears anywhere in the lockfile or metadata); stale-metadata checks; archTest totals `9 0 0 0`. `osv-scanner -r --no-ignore backend` (the default `scan source` subcommand) exited 0 with "No issues found" over 179 lockfile, 53 buildscript and 374 metadata packages. The kept buildscript `commons-lang3:3.18.0` is justified: `./gradlew buildEnvironment` shows `commons-lang3:3.16.0 -> 3.18.0`, pulled via buildpack-platform and commons-compress 1.27.1. The junit-bom import chain claim is true. `dependencyInsight --configuration spotbugs --dependency commons-lang3` resolves 3.21.0 from `com.github.spotbugs:spotbugs:4.10.5`. The parent of `commons-lang3-3.21.0.pom` is `commons-parent:105`, and that pom imports `org.junit:junit-bom` at `commons.junit.version` = 5.14.4 (scope import, pom/module only, no jar). |
| REQ-OPS-142 (metadata) | PASS | Metadata was regenerated from scratch (committed file moved aside) with `./gradlew --write-verification-metadata sha256 checkFast archTest integrationTest migrationTest pitest bootJar` -> exit 0. It was then regenerated cold with `--rerun-tasks --no-build-cache` -> exit 0, every task executed, including pitest. The output equals the committed file after CRLF→LF normalisation (`cmp` exit 0). The raw `cmp` differs only because Gradle writes CRLF on Windows, which `.gitattributes eol=lf` normalises. Tamper probes: lockfile `postgresql 42.7.14→42.7.13` made `checkFast` exit 1 (dependency verification failed for testRuntimeClasspath). An unlisted `implementation("org.apache.commons:commons-text:1.15.0")` made it exit 1 ("Resolved ... which is not part of the dependency lock state"). Changing the postgresql jar sha256 made it exit 1 ("One artifact failed verification"). Changing the boot plugin jar sha256 made `./gradlew help` exit 1 at configuration. |
| REQ-OPS-142 (tests, docs, size) | PASS | ArchitectureRulesTest 9/0/0/0 (archTest). MoneyRuleProbeTest 6/0/0/0 (test). V1BaselineMigrationTest 7/0/0/0 in both integrationTest and migrationTest. It uses Testcontainers 2 `org.testcontainers.postgresql.PostgreSQLContainer` with the digest pin `postgres@sha256:f461…5c36`, as the binding DECISIONS line requires. G4 equivalent: planting `public double amount` in `eg.masroof.domain` (scratch copy) made `archTest` exit 1 with "rule 3 (money) ... violated". Rule 1 `tools.jackson..`: a planted domain class referencing `tools.jackson.databind.ObjectMapper` made `archTest` exit 1 with "rule 1 ... violated". `grep -rnE 'SuppressWarnings\(.*NullAway' backend/src` finds nothing, `NullAway:AnnotatedPackages` is `eg.masroof`, and `-Werror` is unchanged. `grep -c jqwik backend/CLAUDE.md .claude/rules/money.md` gives 0 and 0. CLAUDE.md reads "Spring Boot 4.x (ADR-0004)", "JUnit 6" and "pending OWNER-010". `git show --numstat 46a68af`, hand-written files only: 3+9+4+5+1+4 = **26 added, ≤ 60**. DECISIONS lines exist for ArchUnit (no workaround needed; the 1.5.1 engine runs 9 tests), PIT (launches, 0 mutations), the lang3 constraint, the digest pin and junit-bom. |
| REQ-OPS-143 | PASS | The from-scratch run's `build/test-results/test` shows ProdDatabasePasswordGuardContextTest 11/0/0/0, LocalStartupWithoutGuardTest 1/0/0/0 and ProdDatabasePasswordGuardTest 4/0/0/0. The testcase names cover (a)-(i) (e twice), "SPRING_DATASOURCE_PASSWORD alone ... starts" and "local start-up fails on the datasource connection, not on the guard", all tagged `@Tag("REQ-OPS-143")`. End to end, `java -jar build/libs/masroof-backend-0.1.0.jar` was run with `POSTGRES_PORT=1`. (1) prod + `SPRING_DATASOURCE_PASSWORD='leak-canary-9f3${MISSING_VAR}'` and no POSTGRES_PASSWORD: exit 1 with `Caused by: java.lang.IllegalStateException: spring.datasource.password must resolve to a non-empty value when profile 'prod' is active (set POSTGRES_PASSWORD or SPRING_DATASOURCE_PASSWORD)`. `grep -c leak-canary` over the **entire** console output (48 lines: all cause chains and logs) = 0. (2) prod + `POSTGRES_PASSWORD=leak-canary-9f3` + `SPRING_DATASOURCE_PASSWORD=`: guard ISE, canary count 0. (3) prod + `SPRING_DATASOURCE_PASSWORD=s3cret-test` alone: passes the guard, then fails on `PSQLException`/`ConnectException` (localhost:1). (4) prod + `POSTGRES_PASSWORD=s3cret-test` alone: passes the guard, then fails on the connection. (5) `SPRING_PROFILES_ACTIVE=PROD`: no guard, connection failure. README line 13 says lowercase `prod`, says `PROD`/`Prod` do not activate the guard, and says `SPRING_DATASOURCE_PASSWORD` alone is accepted. The unit test gains the placeholder and blank cases, and the existing assertions are kept (plus `hasMessageContaining(PROPERTY)`). |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | `backend/gradle.lockfile` (Gradle locking semantics, in place since STORY-002) | The claim "lockfile tamper fails the build" only holds when the tampered version is absent from `verification-metadata.xml`. If a lock entry is downgraded to a version that is already verified for another configuration, the build accepts it without error, because Gradle applies lock entries as strict constraints. This story did not introduce it, but reviewers have to read lockfile diffs. | In a copy of `backend/`, change the line `org.apache.commons:commons-lang3:3.20.0=testCompileClasspath,testRuntimeClasspath` to `3.18.0`, then run `./gradlew checkFast` -> exit 0. `./gradlew dependencyInsight --configuration testRuntimeClasspath --dependency commons-lang3` -> 3.18.0, "enforced by Dependency Locking". |
| MINOR | `ProdDatabasePasswordGuard` (STORY-143 scope; the security-reviewer already ticketed it, and I confirmed it independently) | The guard does not cover an empty `SPRING_DATASOURCE_HIKARI_PASSWORD`. With `POSTGRES_PASSWORD` set, the guard passes and start-up continues to the datasource, where Hikari's own password binding applies. REQ-OPS-143 as written covers only `spring.datasource.password`, so this is outside it. | `SPRING_PROFILES_ACTIVE=prod POSTGRES_PASSWORD=s3cret-test SPRING_DATASOURCE_HIKARI_PASSWORD= POSTGRES_PORT=1 java -jar masroof-backend-0.1.0.jar` -> no guard ISE; start-up fails only on `PSQLException: Connection to localhost:1 refused`. |
| MINOR | `make security` environment | `make security` reports GREEN while semgrep is skipped as not installed. The semgrep rules `money-no-float` and `no-log-body` therefore did not run in this verification. | `make security` -> `skipped (not installed): semgrep`, then `security GREEN`. |
| NIT | `ProdDatabasePasswordGuard.java`, the `password.contains("${")` check | A real prod password that contains a literal `${` (Spring escape `\${`) is rejected. Failing closed here is acceptable, but the README does not mention it. | `SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_PASSWORD='ab\${cd}' POSTGRES_PORT=1 java -jar ...` -> guard ISE. |
| NIT | `backend/README.md:12` | The README's metadata refresh command (`checkFast archTest integrationTest pitest dependencies buildEnvironment`) is not the story's regeneration command (`... migrationTest pitest bootJar`). Only the story's command was shown to produce a byte-identical file. | Compare README line 12 with the metadata bullet of REQ-OPS-142. |
| NIT | Metadata regeneration on Windows | Gradle writes the regenerated file with CRLF line endings. It is byte-identical only after LF normalisation (git `eol=lf`), so a raw `cmp` on Windows reports a false difference. | Raw `cmp` -> "differ: char 39, line 1"; after `tr -d '\r'`, `cmp` exits 0. |

## Exploratory
20 cases tried, 3 problems (2 MINOR, 1 NIT):
1. lockfile `org.postgresql:postgresql:42.7.14` -> `42.7.13` -> checkFast exit 1 (verification). OK.
2. lockfile `commons-lang3:3.20.0` (test classpaths) -> `3.18.0` -> checkFast **exit 0**. Problem (MINOR, pre-existing).
3. undeclared `implementation("org.apache.commons:commons-text:1.15.0")` added -> exit 1 (lock state). OK.
4. `postgresql-42.7.14.jar` sha256 `73914527…` -> `03914527…` -> exit 1. OK.
5. `spring-boot-gradle-plugin-4.1.2.jar` sha256 `aa64e39d…` -> `0a64e39d…` -> `./gradlew help` exit 1. OK.
6. jar, prod, `SPRING_DATASOURCE_PASSWORD='leak-canary-9f3${MISSING_VAR}'`, no POSTGRES_PASSWORD -> guard ISE, canary count 0 in the full output. OK.
7. jar, prod, `POSTGRES_PASSWORD=leak-canary-9f3`, `SPRING_DATASOURCE_PASSWORD=` -> guard ISE, canary count 0. OK.
8. jar, prod, `SPRING_DATASOURCE_PASSWORD=s3cret-test` alone -> datasource ConnectException. OK.
9. jar, prod, `POSTGRES_PASSWORD=s3cret-test` alone -> datasource ConnectException. OK.
10. jar, `SPRING_PROFILES_ACTIVE=PROD`, no password -> no guard, ConnectException (documented behaviour). OK.
11. jar, `SPRING_PROFILES_ACTIVE=' prod '` -> Boot trims it ("1 profile is active: prod") and the guard throws an ISE. OK.
12. jar, `SPRING_PROFILES_ACTIVE='dev,prod'` -> guard ISE. OK.
13. jar, prod, circular `POSTGRES_PASSWORD='leak-canary-9f3${spring.datasource.password}'` -> guard ISE, canary count 0. OK.
14. jar, prod, `SPRING_DATASOURCE_PASSWORD='${MISSING: }'` (whitespace default) -> guard ISE. OK.
15. jar, prod, `SPRING_DATASOURCE_PASSWORD='ab\${cd}'` (escaped literal) -> guard ISE. NIT (rejects a valid password).
16. jar, prod, `POSTGRES_PASSWORD=s3cret-test`, `SPRING_DATASOURCE_HIKARI_PASSWORD=` -> guard passes. Problem (MINOR, STORY-143).
17. G4 plant: `public double amount` in `eg.masroof.domain` -> archTest exit 1 (rule 3). OK.
18. domain class referencing `tools.jackson.databind.ObjectMapper` -> archTest exit 1 (rule 1). OK.
19. cold regeneration `--rerun-tasks --no-build-cache --write-verification-metadata sha256 … pitest bootJar` -> identical to the committed file (LF-normalised). OK.
20. raw regenerated file vs committed file, `cmp` on Windows -> differs only in CRLF. NIT.

## Test integrity
OK. `bash scripts/quality/test-integrity-check.sh b2eabfc` -> exit 0, "assertions: 544 (base) -> 761 (now); net change 217; in deleted files: 0", `TEST_INTEGRITY: OK`. `make check`'s "test integrity vs main" step is also OK.

Test-designer files were touched only where the story allows:
- `V1BaselineMigrationTest`: Testcontainers 2 class and digest pin; assertions 17→17.
- `ArchitectureRulesTest`: added `"tools.jackson.."`; 9→9.
- `ProdDatabasePasswordGuardContextTest`: `@Nullable` on the 3 helper parameters only; 25→25.
- `backend-boot41-checks.sh`: the junit check is narrowed to jar artefacts, following the story amendment in 9dee296 and its DECISIONS line. It adds two self-test cases that prove the narrowed check still fails on a 5.14.4 jar.

`ProdDatabasePasswordGuardTest` went from 3 to 4 assertions with nothing loosened. No new skip or disable.

STATUS: PASS
