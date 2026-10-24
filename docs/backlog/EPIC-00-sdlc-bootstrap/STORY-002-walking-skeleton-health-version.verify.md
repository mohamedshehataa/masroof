# Verification report — STORY-002

- **Verifier:** qa-verifier (fresh context)  ·  **Date:** 2026-10-24  ·  **Base:** `68008e6d546b285f0541f06cca4b8115ec0ccc76`  ·  **Tests:** `db2d8a251864e22dd3a5372d08434361433ec236`  ·  **Head:** `3f5ac581e40cfc68f487eb6c22f5a9c8e908604a` (fix commit)
- **Loop:** 2 of 3

## Environment
Windows 11, Git Bash, `source scripts/env.sh`, JAVA_HOME = Temurin 21.0.12.101, Docker running. Branch `story/STORY-002-walking-skeleton-health-version`; nothing was committed. All plants were made in isolated copies made with `git archive HEAD` in the scratchpad (one backend-only copy and one full-repo copy, each with its own `git init`). The only real-tree side effect is `docs/TRACEABILITY.md`, which `make check` regenerates. `git status --porcelain backend/src scripts` is clean. A network outage hit during the run. Every result below was re-run or confirmed after it; one `--refresh-dependencies` run that the outage broke is excluded.
- `./gradlew --console=plain checkFast archTest integrationTest migrationTest --rerun-tasks` -> rc 0 in 43 s. Results:
  - test 9/9: MoneyRuleProbeTest 6, ProdDatabasePasswordGuardTest 3
  - archTest 9/9
  - integrationTest 7/7 and migrationTest 7/7, on `postgres:16.11-alpine` (Testcontainers log line "Container postgres:16.11-alpine started")
- `make check-fast MODULE=backend` x2 (warm) -> rc 0 in 3 s / 3 s.
- `make check` -> rc 0 in **436 s** (previously 545 s), "check GREEN". Some of my scratch-copy builds overlapped this run, so 436 s is an upper bound. Steps:
  - check-fast [sdlc backend] OK in 365 s
  - gate probes 6/6 PASS; case 5 is now `make -n`
  - integrationTest OK, archTest OK, contract diff OK
  - coverage gates PASS
  - traceability "326 REQs, 13 covered, 13 passing, gate PASS"
  - test integrity OK
- `bash scripts/quality/tests/backend-gate-probes.sh` standalone -> rc 0 in 18 s, "6 case(s), 0 failed".
- `make security` x2 -> rc 0 / rc 0 (5 s each), "security GREEN". gitleaks reported "no leaks found". osv-scanner reported "No issues found". trivy fs OK. semgrep was skipped (not installed). The output was identical across both runs, so it is not flaky.
- `osv-scanner scan source -r --no-ignore backend` -> rc 0, "No issues found". It scanned `gradle.lockfile` (183 packages), `buildscript-gradle.lockfile` (53) and `gradle/verification-metadata.xml` (373).
- `bash scripts/quality/test-integrity-check.sh db2d8a2` -> "assertions: 34 (base) -> 99 (now)", "TEST_INTEGRITY: OK". `.claude/state/test-integrity.log` has no STORY-002 entries; its 6 entries are STORY-001 work on test-test-integrity-check.sh.
- Lockfiles:
  - runtime: jackson-core/databind 2.21.8, postgresql 42.7.14 and log4j-api 2.25.5 on the runtime/compile classpaths
  - test classpath: commons-compress 1.28.0
  - buildscript: jackson 2.21.8, commons-lang3 3.18.0, httpclient5 5.6.5, httpcore5/-h2 5.4.4
  - `verification-metadata.xml` has `verify-metadata=true` (sha256) and `verify-signatures=false` (documented).
- Fix-loop claims checked in the diff and present:
  - `.github/workflows/pr.yml` and `main.yml` both have the `gradle/actions/wrapper-validation@v4` step.
  - THREAT_MODEL has the B7 channel-binding / migration-user text, the new S row and the "Build supply chain" table (4 rows).
  - ASVS_CHECKLIST has the `V13.1.x (backend)` row.
  - application.yml uses `${POSTGRES_PASSWORD:}` only under `on-profile: "!prod"` and `${POSTGRES_PASSWORD}` under `prod`.
- Architecture: ProdDatabasePasswordGuard sits in `eg.masroof.adapters.out.persistence`. That fits backend/CLAUDE.md: adapters/out/persistence owns the datasource and Flyway, and the guard protects that connection. archTest passes with it (9/9), including ADAPTER_SLICES_INDEPENDENT, which now has a real `out.persistence` slice. No placement finding.

## Criteria
| REQ | Result | Evidence (command -> observed) |
|---|---|---|
| REQ-OPS-023 | PASS | **Gradle gates:** `./gradlew checkFast archTest integrationTest migrationTest --rerun-tasks` -> rc 0. These tasks ran: compileJava, checkstyleMain, compileTestJava, spotlessCheck, spotbugsMain, test, checkFast. **Warm budget:** `make check-fast MODULE=backend` warm -> rc 0 in 3 s (< 180 s). **Full check:** `make check` -> rc 0 in 436 s (< 15 min). Its first step "check-fast [sdlc backend]" proves the no-MODULE dispatch exits 0. **Probes:** `backend-gate-probes.sh` -> 6/6 PASS. Case 3 now requires `RemoveUnusedImports` + `compileJava`; case 5 is `make -n` and greps `check-fast.sh sdlc backend`. **Locking and verification:** both are on and the build stays green. A tampered lock, a tampered sha256 or an unlocked new dependency each fail the build (X9-X12). **Test logging:** a failing test under `-q` now prints its class and assertion (X13). |
| REQ-OPS-024 | PASS | **Migrations:** `./gradlew integrationTest migrationTest --rerun-tasks` -> 7/7 + 7/7 on postgres:16.11-alpine. **ArchUnit:** `./gradlew archTest` -> 9/9 rule constants pass. **Money-rule probes:** MoneyRuleProbeTest 6/6. MONEY_RULE throws an AssertionError naming BadMoney (field), BadParam (a `double` parameter in a money package) and BadReturn (a money-named method returning `Double`). **G4 replica:** `SelftestPlant { public double amount; }` in domain, `./gradlew -q archTest` -> rc 1. The console now names "MONEY_RULE FAILED" and "Field <eg.masroof.domain.SelftestPlant.amount>" (the loop-1 MINOR is resolved). **NO_STANDARD_STREAMS without allowEmptyShould:** passes on the skeleton and fails on a planted `System.err` (X16). |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | scripts/quality/tests/backend-gate-probes.sh:93 (`printf ... \| plant GateProbeTabs.java`) | Case 2 calls `plant` on the right-hand side of a pipe, so it runs in a subshell. `planted+=` therefore never reaches the parent shell, and the new `trap 'exit 130' INT TERM` exits 130 **without removing GateProbeTabs.java**. A Ctrl-C/TERM during case 2 leaves a tab-indented file in `backend/src/main/java/eg/masroof/`, which turns the next check-fast red. Cases 3 and 4 use heredocs and clean up correctly. | In a full-repo copy: `bash scripts/quality/tests/backend-gate-probes.sh &`, wait for `GateProbeTabs.java` to appear, `kill -TERM $!` -> rc 130. `git status --porcelain backend/src` -> `?? backend/src/main/java/eg/masroof/GateProbeTabs.java`. The same test during case 3 (GateProbeImport) -> rc 130 and a clean tree. |
| MINOR | backend/src/main/java/eg/masroof/adapters/out/persistence/ProdDatabasePasswordGuard.java:17 | The guard checks the `POSTGRES_PASSWORD` property, not the effective `spring.datasource.password`. With `SPRING_DATASOURCE_PASSWORD=` (empty) overriding the yml value, `prod` starts and runs Flyway with an empty password (CWE-1188 remains reachable). The reverse also happens: `SPRING_DATASOURCE_PASSWORD` set without `POSTGRES_PASSWORD` is rejected. | Throwaway `postgres:16.11-alpine` with `POSTGRES_HOST_AUTH_METHOD=trust` on port 55432. `POSTGRES_PASSWORD=x-test SPRING_DATASOURCE_PASSWORD= POSTGRES_PORT=55432 java -jar build/libs/masroof-backend-0.1.0.jar --spring.profiles.active=prod` -> "Successfully applied 1 migration", "Started MasroofApplication", rc 0 |
| MINOR | docs/backlog/EPIC-01-foundation/STORY-004-core-schema-and-seeds.md (review findings 9 and 14, "ticketed -> STORY-004") | The known money-rule gap has not regressed, and it is still open: a `float` constructor parameter in domain passes checkFast and archTest. But STORY-004 does not mention it. Neither the camelCase/constructor money-rule extension nor the V2 CHECKs/updated_at trigger appears in STORY-004; the "ticket" exists only in the review report. | Plant `eg.masroof.domain.Expense` with `public Expense(float egp)`, run `./gradlew checkFast archTest` -> rc 0. `grep -niE 'archunit\|float\|constructor\|trigger' docs/backlog/EPIC-01-foundation/STORY-004-core-schema-and-seeds.md` -> no money-rule or trigger item |
| NIT | backend/src/test/java/eg/masroof/adapters/out/persistence/ProdDatabasePasswordGuardTest.java:11 | The new test has no `@Tag("REQ-...")`. backend/CLAUDE.md says to "Tag every test with its REQ id", and THREAT_MODEL cites REQ-SEC-020. The test is invisible to `make trace`. | `grep -n '@Tag' ProdDatabasePasswordGuardTest.java` -> none |
| NIT | application.yml / ProdDatabasePasswordGuard `@Profile("prod")` | Profile names are case-sensitive. `--spring.profiles.active=PROD` activates the `!prod` document and starts with an empty password. | `java -jar ... --spring.profiles.active=PROD` with no password against the trust DB -> "Started MasroofApplication" |
| NIT | ArchitectureRulesTest.java:26 (Javadoc) | The stated reason for keeping `allowEmptyShould` on rule 5 is imprecise. Removing it does fail ("failed to check any classes"), as claimed. But the rule's subject is **all** fields, not annotated fields: one plain `private final int days` in main makes it pass without allowEmptyShould. So STORY-004 (Money.amountMinor) can drop it. | Remove `.allowEmptyShould(true)` from NO_FIELD_INJECTION -> archTest FAIL. Then add `eg.masroof.domain.Plain` with one unannotated field -> archTest rc 0 |
| NIT | backend-gate-probes.sh case 5 (now `make -n`) | No JUnit/trace case asserts that a real no-MODULE `make check-fast` exits 0 any more. That fact is now carried only by `make check`'s first step ("OK check-fast [sdlc backend]"), which is not trace-visible. The change was requested by review finding 10. | `make trace`; the REQ-OPS-023 row lists only "dispatches" for case 5 |
| NIT | `make check-fast` (all modules) | Took 365 s inside `make check` (> the Makefile's "< 3 min" headline). My concurrent scratch builds added load. This is the loop-1 NIT carried over. The REQ budget covers MODULE=backend only (3 s). | `make check < /dev/null` -> "check-fast GREEN [sdlc backend] in 365s" |

## Exploratory
24 cases tried, 6 problems (X6, X7, X8, X15b, X17, X22). Unless noted, they ran in the scratch copy. Inputs, verbatim:
- X1 `./gradlew --offline checkFast bootJar` -> rc 0, 3 s
- X2 `env -u POSTGRES_PASSWORD java -jar build/libs/masroof-backend-0.1.0.jar --spring.profiles.active=prod` -> rc 1, "IllegalStateException: POSTGRES_PASSWORD must be set to a non-empty value when profile 'prod' is active"
- X3 `POSTGRES_PASSWORD=" "` with the same command -> rc 1, same message
- X4 `POSTGRES_PASSWORD=x-test POSTGRES_PORT=59999`, prod -> rc 1, flywayInitializer "Connection to localhost:59999 refused". The guard passed and the app went on to the DB attempt.
- X5 prod with no password against a **reachable** trust DB (55432) -> rc 1 from the guard, and `flyway_schema_history` does not exist. The guard runs before Flyway connects.
- X6 `SPRING_DATASOURCE_PASSWORD=real-test` without POSTGRES_PASSWORD, prod -> rc 1 from the guard (false positive)
- X7 `POSTGRES_PASSWORD=x-test SPRING_DATASOURCE_PASSWORD=` (empty), prod, trust DB -> started and applied V1 with an empty password (**problem**)
- X8 `--spring.profiles.active=PROD` without a password -> started (**NIT**)
- X9 lockfile `org.postgresql:postgresql:42.7.14=` -> `42.7.13=`:
  - `./gradlew dependencies --configuration runtimeClasspath` -> rc 0, but the report says "postgresql:{strictly 42.7.13} FAILED". The report task never fails; that is how Gradle behaves.
  - `./gradlew bootJar --rerun-tasks` -> rc 1, "Dependency version enforced by Dependency Locking" conflict with 42.7.14
- X10 lockfile `jackson-databind:2.21.8` -> `2.21.7`, then `./gradlew compileJava --rerun-tasks` -> rc 1, "Dependency verification failed for configuration ':compileClasspath'"
- X11 verification-metadata: postgresql-42.7.14.jar sha256 first char `7` -> `f`, then `./gradlew checkFast` -> rc 1, "Dependency verification failed for configuration ':testRuntimeClasspath'"
- X12 `implementation("org.apache.commons:commons-lang3:3.18.0")` added without `--write-locks`, then `./gradlew compileJava` -> rc 1, "Resolved 'org.apache.commons:commons-lang3:3.18.0' which is not part of the dependency lock state"
- X13 planted `eg.masroof.qa.QaPlantedFailTest` (`assertThat(1).as("qa planted marker").isEqualTo(2)`), `./gradlew -q test` -> rc 1. The console prints "QaPlantedFailTest > plantedFailure() FAILED", the assertion and the line.
- X14 `SelftestPlant { public double amount; }` in domain, `./gradlew -q archTest` -> rc 1, "MONEY_RULE FAILED ... SelftestPlant.amount"
- X15 NO_FIELD_INJECTION with `.allowEmptyShould(true)` removed -> archTest FAIL "failed to check any classes" (the stated reason holds)
- X15b the same plus `eg.masroof.domain.Plain` holding `private final int days = 1;` -> archTest rc 0 (so the Javadoc reason is imprecise; **NIT**)
- X16 `eg.masroof.adapters.out.persistence.Shout` calling `System.err.println("x")` -> archTest FAIL in NO_STANDARD_STREAMS, naming Shout.java:7
- X17 `eg.masroof.domain.Expense(float egp)` constructor -> checkFast + archTest rc 0 (known gap, not regressed; not in STORY-004, see Findings)
- X18 `./gradlew pitest` -> rc 0, "No mutations found" (accepted for the skeleton, failWhenNoMutations=false)
- X19 `sha256sum gradle/wrapper/gradle-wrapper.jar` = `ad16bca46cb71bb5887c546860d93992aeee35c1fdaf4a242b0a612375d937e0`, which equals the official https://services.gradle.org/distributions/gradle-8.14.6-wrapper.jar.sha256. `distributionSha256Sum` `7988ed07...7bd24` equals the official gradle-8.14.6-bin.zip.sha256.
- X20 `make security` twice in the real tree -> GREEN / GREEN, identical output (trivy is not flaky)
- X21 two `make check-fast MODULE=backend` runs at once in the real tree -> rc 0 / rc 0, 11 s, tree clean
- X22 full-repo copy: probes script sent SIGTERM while `GateProbeTabs.java` was planted -> rc 130, file **left behind** (**problem**)
- X23 full-repo copy: SIGTERM while `GateProbeImport.java` was planted -> rc 130, tree clean
- X24 `osv-scanner scan source -r --no-ignore backend` -> finds both lockfiles + verification-metadata, "No issues found"
- (Not counted: SIGINT to a backgrounded probes run is ignored by non-interactive bash, which is an invalid harness; TERM was used instead. One `dependencies --refresh-dependencies` run failed during the network outage with "Did not resolve ... part of the dependency lock state".)

## Test integrity
OK. `bash scripts/quality/test-integrity-check.sh db2d8a2` -> "TEST_INTEGRITY: OK", assertions 34 -> 99. Since db2d8a2, only 3f5ac58 touched test files. Each change:
- V1BaselineMigrationTest: the image is pinned `postgres:16-alpine` -> `postgres:16.11-alpine` (stronger).
- ArchitectureRulesTest: `allowEmptyShould` removed from NO_STANDARD_STREAMS (stronger), plus a Javadoc change. Assertion count 9 -> 9. NO_FIELD_INJECTION keeps allowEmptyShould, justified by X15.
- MoneyRuleProbeTest: +2 probes (BadParam, BadReturn) with their fixtures (stronger, 8 -> 12 assertions).
- ProdDatabasePasswordGuardTest: new, 3 assertions (an addition).
- backend-gate-probes.sh:
  - case 3 now requires RemoveUnusedImports + compileJava (stronger)
  - the trap now exits 130 (stronger, except for the case-2 leak above)
  - case 5 is narrowed from a real no-MODULE run to `make -n`. Review finding 10 requested this, and the real run is still executed and gated by `make check` step 1 (observed OK). It is not counted as an unexplained weakening.

`.claude/state/test-integrity.log` has no STORY-002 entries.

STATUS: PASS
