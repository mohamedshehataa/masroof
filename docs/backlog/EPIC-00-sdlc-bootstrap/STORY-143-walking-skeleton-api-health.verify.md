# Verification report — STORY-143

- **Verifier:** qa-verifier (fresh context, combined verify + code review, low tier)  ·  **Date:** 2026-10-24  ·  **Base:** `db3d2bcf4f1731791b05fd3bd85de87f053754cc`  ·  **Head:** `627cce7b0067b1f703f9789a028b105b46010fe8`
- **Loop:** 1 of 3

## Environment
- Windows 11, Git Bash, JDK 21.0.12 (Temurin), Docker Desktop. Verified in a QA worktree detached at 627cce7. The main checkout was not touched.
- `make check-fast < /dev/null`: **check-fast GREEN [sdlc backend] in 63s**. Hook tests 624/624, quality tests 91/91, contract lint valid, `./gradlew checkFast` OK.
- `cd backend && ./gradlew --console=plain checkFast archTest integrationTest migrationTest`: **BUILD SUCCESSFUL in 29s**. `test --rerun` (live, not from cache) gave HealthControllerTest 6/6, ProdDatabasePasswordGuardHikariTest 1/1, ProdDatabasePasswordGuardContextTest 11/11, ProdDatabasePasswordGuardTest 6/6, MoneyRuleProbeTest 6/6 and LocalStartupWithoutGuardTest 1/1. archTest: ArchitectureRulesTest tests=10, skipped=0, failures=0. integrationTest: HealthDatabaseOutageIT 1/1 (2.43 s) and V1BaselineMigrationTest 7/7. migrationTest: 7/7.
- Linux, exactly as claimed (eclipse-temurin:21-jdk container, `./gradlew --console=plain checkFast`): **BUILD FAILED**. `:bootBuildInfo` fails with "A problem occurred starting process 'command 'git''", because the image has no git binary. The same command with `-PgitSha=627cce7 -PbuildTime=2026-10-24T12:00:00Z` gives **BUILD SUCCESSFUL in 3m 14s** with `verify-metadata=true`, so dependency verification is green on Linux.
- `make security`: **security GREEN** (gitleaks: no leaks; osv-scanner: no issues; trivy fs: 0 vulns and 0 Dockerfile misconfigs; trivy config clean). Semgrep was not installed locally.
- Semgrep in Docker (`semgrep/semgrep:latest semgrep scan --config p/default --config .semgrep/ --error`): rc=0, "Ran 474 rules on 408 files: 0 findings", 0 blocking.
- `make trace`: "336 REQs, 16 covered, 14 passing, 0 unknown, gate PASS". REQ-OPS-020 is PASS. The regenerated docs/TRACEABILITY.md was reverted afterwards.

## Criteria
Every row was run on the real compose stack: `docker compose --env-file SCRATCH/qa.env -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up -d --build --wait postgres backend` (the env file holds POSTGRES_PASSWORD and GIT_SHA=627cce7) gave rc=0 and both services Healthy. 627cce7 is the first 7 characters of the head SHA. The stack was torn down with `down -v`.

| REQ / clause | Result | Evidence (command → observed) |
|---|---|---|
| REQ-OPS-020 200 body | PASS | `curl -sS -D - http://127.0.0.1:8080/health` → 200, `Content-Type: application/json`, `{"status":"UP","version":"0.1.0","gitSha":"627cce7","buildTime":"2026-10-24T13:24:38Z","time":"2026-10-24T13:25:14Z","checks":{"db":"UP"}}`. The test clock read 2026-10-24T13:25:14Z (skew 0 s). buildTime before time. Version equals `version = "0.1.0"` in build.gradle.kts. `checks` has only `db`. HealthControllerTest `healthShape` asserts the same with strict map equality. |
| REQ-OPS-020 Accept ignored | PASS | `Accept: application/xml` → 200 application/json with the same body. `text/html`, `application/problem+json` and `image/png` → 200 application/json. Never 406. Parameterised MockMvc test 3/3. |
| REQ-OPS-020 healthchecks + non-root | PASS | `compose ps` → "backend running healthy 127.0.0.1:8080->8080/tcp" and "postgres running healthy 5432/tcp" (postgres port not published). Both containers have Config.Healthcheck set (pg_isready; busybox wget /health). `docker inspect` Config.User of masroof/backend:dev → 10001:10001. `docker exec masroof-backend-1 id` → uid=10001(masroof). ReadonlyRootfs=true, CapDrop=[ALL], no-new-privileges:true. |
| REQ-OPS-020 GIT_SHA missing / empty / unknown | PASS | `docker build --progress=plain -f backend/Dockerfile -t masroof/backend:gitsha-probe .` with no arg, with an empty GIT_SHA and with GIT_SHA=unknown → rc=1 for all 3, with "ERROR: build arg GIT_SHA must match ^[0-9a-f]{7,40}$ (got '')" or "(got 'unknown')". `docker image inspect masroof/backend:gitsha-probe` → rc=1, so no image was produced. Build context was 355 kB (whitelist .dockerignore). |
| REQ-OPS-020 DB down → 503 within 3 s | PASS | `compose stop postgres`, then 3 sequential curls → 503, `Content-Type: application/problem+json`, body `{"detail":"Database unavailable","instance":"/health","status":503,"title":"Service Unavailable","type":"about:blank"}`, elapsed 2.011 / 2.005 / 2.004 s. 40 parallel calls → all 503, max 2.015 s. No header or body matches the leak regex (headers: Content-Type, Transfer-Encoding, Date, Connection). The IT asserts the same: 1/1. |
| REQ-OPS-020 recovery within 30 s, no restart | PASS | `compose start postgres`, then poll every 0.5 s → "200 after 3s", checks.db=UP. Backend RestartCount=0 and StartedAt=2026-10-24T13:24:47.743488004Z, both unchanged. PID 1 `java -jar /app/app.jar` elapsed went 1:29 → 1:41, so it is the same JVM. The backend healthcheck went unhealthy → healthy without a restart. The IT asserts the JVM and context start times are unchanged: 1/1. |
| REQ-OPS-020 long / unknown query | PASS | `GET /health?verbose=true&x=` plus 2000 'a' characters → same body as without the query (ignoring `time`). Count of "verbose" in the body → 0. MockMvc `queryIsIgnored` 1/1. |

### Claims to verify
| # | Claim | Result | Evidence |
|---|---|---|---|
| 1 | All GWT and unhappy paths hold on compose | TRUE | Criteria table above. `make e2e` itself was NOT RUN; its steps were run one by one instead. |
| 2 | Tests in c6a86f0 unchanged; only rule 8 added | TRUE | `git diff --stat c6a86f0..627cce7 -- '*Test*.java' '*IT.java' infra/tests` → only ArchitectureRulesTest.java, 13 insertions, 0 deletions (rule 8 only). `make test-integrity BASE=db3d2bc` → TEST_INTEGRITY: OK, assertions 9 → 46. |
| 3 | Gradle green on Windows; checkFast green on Linux | PARTLY FALSE | Windows: green. Linux, as written: BUILD FAILED (no git binary in eclipse-temurin:21-jdk). Green only when -PgitSha/-PbuildTime are passed. See finding 3. |
| 4 | check-fast, security, semgrep, trace green | TRUE | See Environment. |
| 5 | Prod guard rejects blank or unresolved hikari password without leaking it | TRUE | `docker run --rm --network masroof_default -e SPRING_PROFILES_ACTIVE=prod -e POSTGRES_HOST=postgres -e POSTGRES_PASSWORD=PW -e SPRING_DATASOURCE_HIKARI_PASSWORD= masroof/backend:dev` → rc=1. The same with the hikari password set to `canary77` followed by an unresolved NOPE_VAR placeholder → rc=1. Both logs contain "spring.datasource.hikari.password must resolve to a non-empty value without '${' when profile 'prod' is active". Grep for canary77 and for PW on both logs → 0. |
| 6 | No DB error details or secrets logged or returned; 503 carries no exception message | TRUE for responses and compose logs; one caveat | 503 body is fixed text. Compose backend log during the outage: only "database probe failed: TimeoutException" lines, and 0 matches for the password. Caveat: in the IT run Hikari's own WARN logged a "PSQLException: This connection has been closed." stack trace and SQLSTATE. That is not a secret, but it is DB error detail (finding 4). |
| 7 | Hand-written lines at most ~400, excluding lockfile and verification metadata | FALSE (orchestrator decision) | `git diff --numstat db3d2bc..HEAD` → production/config 331 + tests 474 (+5 docs), about **805**. The story's own size definition counts tests ("hand-written lines incl. tests"). The implementation commits alone (excluding the test-designer's tests) come to 344. |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | story size (backlog rule 6) | 1. **ORCHESTRATOR DECISION REQUIRED.** About 805 hand-written lines against the ~400 cap and the 430 itemised estimate. The tests alone are 474 lines against 213 estimated. The story says the orchestrator decides: accept, or move infra/tests/req-ops-020-backend-compose.sh (112 lines) to STORY-142. | `git diff --numstat db3d2bc..627cce7` |
| MINOR | backend/src/test (design "Tests" table) | 2. `HealthServiceTest` and `JdbcDatabaseProbeTest` from the design's test table were never written. The probe's timeout and cancel path, its RuntimeException path, and HealthService fail-fast on missing or `unknown` gitSha, version or time are untested. I observed the behaviour manually (`docker pause` postgres → 503 in 2.004 s), but nothing protects it from regressions. | `ls backend/src/test/java/eg/masroof/application` → absent; no JdbcDatabaseProbeTest in adapters/out/persistence |
| MINOR | backend/build.gradle.kts:62-66 | 3. Every Gradle build without -PgitSha/-PbuildTime needs a git binary and a valid work tree. Without one it fails with the opaque "A problem occurred starting process 'command 'git''". This breaks the claimed Linux container command, source-tarball builds, and worktrees mounted into containers. | `docker run --rm -v WT:/w -w /w/backend eclipse-temurin:21-jdk ./gradlew --console=plain checkFast` → BUILD FAILED at :bootBuildInfo |
| MINOR | Hikari logging during outage | 4. Hikari's WARN with a PSQLException stack trace and SQLSTATE goes past the probe's "class name only" logging. No secret was seen, but pgjdbc connect errors can contain host:port. | `./gradlew integrationTest`, then grep WARN in build/test-results/integrationTest/TEST-eg.masroof.adapters.in.rest.HealthDatabaseOutageIT.xml |
| NIT | JdbcDatabaseProbe.java:31-37 | 5. `executor.submit` sits outside the try block. After close() (shutdown), a RejectedExecutionException escapes as a 500, which breaks the port's "never throws" contract. Also, the log shows ExecutionException instead of the cause's class. | reasoning, NOT RUN |
| NIT | ProdDatabasePasswordGuard.java:36-39 | 6. For the hikari property the hint "(set POSTGRES_PASSWORD or SPRING_DATASOURCE_PASSWORD)" is misleading. ProdDatabasePasswordGuardHikariTest is tagged REQ-OPS-020 for REQ-OPS-143 behaviour, and it covers only the blank case. | log line in claim 5 |
| NIT | infra/tests/req-ops-020-backend-compose.sh:77 | 7. The buildTime/time comparison is strict, but the spec says buildTime is less than or equal to time. | reading |
| NIT | backend/Dockerfile:5 | 8. `grep -Eqx` checks line by line, so a multi-line GIT_SHA (first line 7 hex chars) would pass the build check. HealthService's matches() would then refuse to start. | reasoning, NOT RUN |

## Exploratory
14 cases tried, 0 REQ problems:
1. Accept application/problem+json → 200 json
2. Accept image/png → 200 json
3. HEAD /health → 200
4. POST /health → 405 (Boot default error JSON, not problem+json; out of scope)
5. OPTIONS /health → 200
6. GET /health/ → 404
7. GET /HEALTH → 404
8. GET /actuator/health → 404 (no actuator)
9. GET /health;jsessionid=x → 200
10. GET /health?q=%D9%A1%D9%AC%D9%A2%D9%A5%D9%A0&x=%3Cscript%3E (Arabic-Indic 1,250 plus a script tag) → 200, not echoed
11. 40 parallel requests with the DB down → all 503, at most 2.015 s
12. `docker pause masroof-postgres-1` (hung, no RST) → 503 in 2.004 s x3, then healthy again after unpause
13. Hikari password blank → start refused
14. Hikari password with an unresolved placeholder → start refused, no canary in the log

## Test integrity
OK. `make test-integrity BASE=db3d2bc` → TEST_INTEGRITY: OK (assertions 9 → 46, skips 0 → 0). `git diff c6a86f0..627cce7` on tests → only additive rule 8 in ArchitectureRulesTest.

STATUS: PASS
