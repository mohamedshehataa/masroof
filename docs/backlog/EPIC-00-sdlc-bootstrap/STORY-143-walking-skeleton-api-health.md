---
id: STORY-143
epic: EPIC-00
title: "Walking skeleton (API): GET /health + backend Dockerfile + compose postgres+backend"
phase: 0
status: READY
size: M
modules: [backend, infra, contracts]
depends_on: [STORY-002]
reqs: [REQ-OPS-020]
implements: ["PROMPT.md S15 item 7", "PROMPT.md §2 (stack, compose)", "PROMPT.md §9 (contract-first)", "PROMPT.md §12 (no leakage, non-root)", "PROMPT.md S9 (image hardening)"]
owner_agent: backend-engineer
---

# STORY-143 — Walking skeleton (API): GET /health + backend Dockerfile + compose postgres+backend

## Goal
The first HTTP slice of the S15 item 7 walking skeleton: the gated backend module from STORY-002 answers `GET /health` from a container talking to Postgres, so the web page (STORY-141) and the full `make up` stack (STORY-142) complete the path **browser → Caddy → (web bundle | backend → Postgres)**.

Split (spec review 2026-10-23): the original STORY-002 became STORY-002 (module, gate, V1, ArchUnit), STORY-143 (this story), STORY-141 (web) and STORY-142 (stack). Rate limiting of `/health` moved to STORY-010 (REQ-SEC-051); container start-up resilience when Postgres is late moved to STORY-142.

## Requirements and acceptance criteria
### REQ-OPS-020 — GET /health returns build information
**Given** the backend and Postgres are running through the dev-overlay command (`GIT_SHA=$(git rev-parse HEAD | cut -c1-7) docker compose --env-file .env -f infra/docker-compose.yml -f <dev overlay> up -d --build --wait postgres backend`; the overlay publishes the backend on `127.0.0.1:8080` only; the architect names the file) **When** `GET http://127.0.0.1:8080/health` is called with no `Authorization` header **Then** the response is `200` with `Content-Type: application/json` and a body valid against the contract schema `Health` where:
- `status == "UP"`;
- `version` equals the `version` property of `backend/build.gradle.kts` (initially `0.1.0`) and matches `^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$`;
- `gitSha` equals `git rev-parse HEAD | cut -c1-7` at image build time (passed as the docker build arg `GIT_SHA`);
- `buildTime` and `time` are RFC 3339 timestamps ending in `Z`; `time` is within ±5 s of the test's clock; `buildTime <= time`;
- `checks` is an object with exactly one key, `db`, whose value is `"UP"`.

**gitSha provenance.** Only the Docker image build enforces `GIT_SHA`: it fails, naming `GIT_SHA`, when the arg is missing, empty or does not match `^[0-9a-f]{7,40}$`. Outside Docker (`./gradlew` tasks, `bootRun`) `gitSha` is computed by Gradle as `git rev-parse HEAD | cut -c1-7` and `./gradlew checkFast` never needs `GIT_SHA` set; tests assert only the contract pattern `^[0-9a-f]{7,40}$` or inject a fixed value through build-info.

**Given** the same running backend **When** `GET /health` is sent with `Accept: application/xml` (or `Accept: text/html`) **Then** the response is still `200 application/json` with the same body shape (the `Accept` header is ignored; never `406`).
**Given** `docker compose ps` **Then** `postgres` and `backend` both define a `healthcheck` and report `healthy`; the backend image runs as a non-root user (`docker inspect` `Config.User` set and neither `root` nor `0`).

Unhappy path (build provenance): **Given** the docker build arg `GIT_SHA` is missing, empty, or `unknown` (does not match `^[0-9a-f]{7,40}$`) **When** the backend image is built **Then** the build fails with a message naming `GIT_SHA` and no image is produced.

Unhappy path (DB down): **Given** the backend is running and Postgres is then stopped (`docker compose stop postgres`, or the Testcontainers container is stopped) **When** `GET /health` is called **Then** the response arrives within 3 s and is `503` with `Content-Type: application/problem+json` and a body equal to (member order irrelevant):
```json
{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Database unavailable","instance":"/health"}
```
(STORY-010 may later add an optional `traceId` member; no other members are allowed in this story.) Neither the body nor any response header matches the case-insensitive pattern `jdbc:|postgres(ql)?://|password|exception|hikari|:5432|\.java:|\tat ` (no connection strings, credentials, hostnames-with-ports or stack traces).

Unhappy path (recovery): **Given** the DB-down state above **When** Postgres is started again **Then** within 30 s `GET /health` returns `200` with `checks.db == "UP"`, without the backend being restarted (container `RestartCount` and the JVM start time are unchanged).

Unhappy path (input): **Given** `GET /health?verbose=true&x=<2,000-character string>` **Then** the response is the same `200` body as without the query (unknown parameters are ignored, nothing is echoed).

**Test location.** Compose checks live in `infra/tests/*.sh`, are tagged with the REQ id in the file name or a comment, and run via `make e2e`. This story creates `infra/scripts/e2e.sh` (the target `make e2e` already delegates to) running every `infra/tests/*.sh`; STORY-142 extends it with the Playwright run. Controller, DB-down and recovery checks are JUnit tests (Testcontainers for DB down/recovery).

## UX states (UI stories only)
N/A — no UI in this story (the version page is STORY-141).

## Contract impact
`GET /health`, `Health` and `Problem` already exist in `contracts/openapi.yaml` v0 and `x-req` is already `REQ-OPS-020` — no change needed. The architect rewrites the `200` description to "Service and database are up." (drop "or degraded, reported as status DOWN": a down database is the `503` Problem) and may note in `Health.checks` that only `db` exists until later stories add keys — both description-only, non-breaking (oasdiff clean). No `406` response is added (Accept is ignored). The existing `429` response stays; it is implemented by STORY-010 (REQ-SEC-051).
Client code: the backend controller is **hand-written** in this story; STORY-006 switches it to implement the generated `HealthApi` interface without behaviour change.

## Data / migration impact
None (uses the V1 baseline from STORY-002; the health check runs a read-only probe, no writes).

## Security / privacy / LLM-safety impact
Trust boundary: **internet → Caddy → backend `/health`** (Caddy and its routing arrive in STORY-142; in this story the backend is reached only on `127.0.0.1:8080` through the dev overlay).
- `/health` is unauthenticated by design. Disclosure of `version`, `gitSha` and `buildTime` is **accepted** (owner's private two-person deployment; recorded in `docs/DECISIONS.md`); no hostnames, connection strings, credentials or stack traces are disclosed (DB-down pattern check above).
- Actuator endpoints are internal only: Caddy never routes `/actuator/*` (asserted in STORY-142 REQ-OPS-022 and STORY-003 REQ-OPS-031).
- Rate limiting of `/health` and every other unauthenticated endpoint is owned by STORY-010 (REQ-SEC-051), not this story.
- Non-root backend image; `.env` stays gitignored; `GIT_SHA` is a build arg, not a secret.
- No household data, so no IDOR surface; no LLM involvement.

## Eval impact
None (no parsing).

## Design notes (architect)
ADRs: [ADR-0004 Spring Boot 4.1.2](../../adr/0004-spring-boot-4-1-upgrade.md) (accepted; it supersedes [ADR-0003](../../adr/0003-spring-boot-3-5-after-oss-eol.md)).
Contract: description-only (see "Contract" below). Migration: none.

### Commit order (each commit keeps `make check` green)
1. `build(backend): Spring Boot 4.1.2 [STORY-143]`: ADR-0004's table, the lockfile/verification/osv refresh, the
   V1-test Testcontainers 2 change, ArchUnit rule 1 + `tools.jackson..`, jqwik removed, `backend/CLAUDE.md` "3.x" -> "4.x (ADR-0004)".
2. `fix(backend): prod password guard checks the effective datasource password [STORY-143]`: NITs 16 and 17. NIT 18 is closed by commit 1.
3. `feat(backend): GET /health with build info and DB probe [STORY-143]`: build-info wiring, use case, probe, controller, yml, ArchUnit rule 8, JUnit tests.
4. `feat(infra): backend image, compose postgres+backend, dev overlay, e2e runner [STORY-143]`: Dockerfile, `.dockerignore`, compose files, `compose.sh`, `infra/scripts/e2e.sh`, `infra/tests/req-ops-020-backend-compose.sh`.

### Hexagonal placement (REQ-OPS-020)
| Layer / package | Type | Responsibility |
|---|---|---|
| `eg.masroof.application.health` | `public record HealthReport(String version, String gitSha, Instant buildTime, Instant time, boolean databaseUp)` | Result of one check. Pure data. |
| `eg.masroof.application.health` | `public interface DatabaseProbe { boolean isUp(); }` | **Outbound port.** Contract (in its Javadoc): returns within 2 s and never throws. `false` means "unknown or down". |
| `eg.masroof.application.health` | `@Service public class HealthService` with ctor `(BuildProperties build, DatabaseProbe database, Clock clock)` and method `HealthReport check()` | **Use case and inbound port.** The controller calls it directly; no extra interface. The constructor fails fast with `IllegalStateException` (message names the key, never a value) when `build.getVersion()`, `build.get("gitSha")` or `build.getTime()` is null, or when gitSha does not match `^[0-9a-f]{7,40}$`. So the app does not start with broken provenance. `check()` returns `new HealthReport(version, gitSha, buildTime, Instant.now(clock).truncatedTo(SECONDS), database.isUp())`. |
| `eg.masroof.adapters.out.persistence` | `final class JdbcDatabaseProbe implements DatabaseProbe, AutoCloseable` (`@Component`, package-private) | **Adapter.** Ctor `(DataSource)`. `isUp()` submits `() -> { try (Connection c = ds.getConnection()) { return c.isValid(1); } }` to a `Executors.newVirtualThreadPerTaskExecutor()` and waits `future.get(2, SECONDS)`. On `TimeoutException` it calls `future.cancel(true)` and returns `false`. On `ExecutionException` it returns `false`. On `InterruptedException` it re-interrupts and returns `false`. Every failure logs one WARN `database probe failed: {}` with **only** `e.getClass().getSimpleName()` (pgjdbc messages contain `host:port`). `close()` shuts the executor down, and Spring infers the destroy method. |
| `eg.masroof.adapters.in.rest` | `final class HealthController` (`@RestController`, package-private), ctor `(HealthService)` | **Adapter.** `@GetMapping("/health") ResponseEntity<Object> getHealth()` has no `produces`, no parameters and no `@RequestHeader`. Up: `ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(new HealthResponse("UP", version, gitSha, buildTime.toString(), time.toString(), Map.of("db", "UP")))`. Down: `status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)`. **Why Accept is ignored:** when a `ResponseEntity` already carries a Content-Type, Spring MVC uses that type and skips Accept negotiation, so `application/xml` and `text/html` get JSON and never 406. The method name `getHealth` matches the contract's `operationId` for the STORY-006 switch. |
| same | nested `record HealthResponse(String status, String version, String gitSha, String buildTime, String time, Map<String, String> checks)` | Wire shape of the contract's `Health`. It holds exactly these 6 members, and `checks` is exactly `{db}`. `status` is always `"UP"` on 200. `DOWN` exists in the contract enum but is never emitted, because a down DB is the 503. |
| same | 503 body | `ProblemDetail p = ProblemDetail.forStatusAndDetail(SERVICE_UNAVAILABLE, "Database unavailable"); p.setType(URI.create("about:blank")); p.setTitle("Service Unavailable"); p.setInstance(URI.create("/health"));`. **Fallback:** if Spring 7's Jackson 3 mixin adds or omits a member (the strict-JSON test shows this), replace it with a nested `record HealthProblem(String type, String title, int status, String detail, String instance)` with the same values. Nothing else changes. |
| `eg.masroof.MasroofApplication` (root) | `@Bean Clock clock() { return Clock.systemUTC(); }` (package-private) | Injected clock (backend/CLAUDE.md "Clock is injected"). |

No domain types: health is an operational concern, not business logic. No actuator: `spring-boot-starter-actuator` is
**not** added, so `/actuator/*` does not exist. STORY-003 adds it with exposure restricted, and Caddy never routes it (STORY-142).

**Sequence (happy path):** Docker healthcheck, curl or Caddy -> Tomcat -> `HealthController.getHealth()` ->
`HealthService.check()` -> `JdbcDatabaseProbe.isUp()` (Hikari borrow, then `isValid(1)`) -> `HealthReport` -> 200 JSON.

**Failure modes:**
| Step | Failure | Result |
|---|---|---|
| Startup | build-info missing, or gitSha/version/time missing or bad | Context fails, process exits non-zero. Never `"unknown"`. |
| Startup | Postgres unreachable | Flyway or Hikari init fails, exit non-zero. The restart policy belongs to STORY-142. |
| Probe | Postgres stopped (connections reset, DNS NXDOMAIN or refused) | Hikari `connection-timeout` 2000 ms or `isValid` false -> `false` -> **503 Problem in ≤ 2 s** |
| Probe | Postgres hung or partitioned (no RST) | The 2 s `future.get` deadline -> `false` -> 503 at about 2 s. The stuck virtual thread ends within Hikari's 2 s + 1 s validation bound, so threads cannot pile up without limit. |
| Probe | Any `RuntimeException` | Caught -> `false` -> 503 (never 500) |
| Recovery | Postgres back | The next borrow creates a fresh connection -> 200. No restart: Hikari evicts dead connections on validation. |
| Request | `?verbose=true&x=<2,000 chars>` | Not bound, not echoed -> same 200 body |
| Request | `Accept: application/xml` or `text/html` | Preset Content-Type -> 200 `application/json` |

### `application.yml` additions (first document, before the profile documents)
```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 2000      # ms; /health answers 503 within 3 s when Postgres is down (REQ-OPS-020)
      validation-timeout: 1000      # ms; must be < connection-timeout
      data-source-properties:
        connectTimeout: 2           # pgjdbc, seconds; bounds connection creation
```
No `server.error.*` change: Boot's defaults already never include stack traces or messages.

### Build info and gitSha (`build.gradle.kts`, about 12 lines)
```kotlin
// /health build info (STORY-143): the Docker build passes -PgitSha/-PbuildTime; local builds read git, so checkFast needs no env.
val gitSha = providers.gradleProperty("gitSha").orElse(
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim().take(7) })
val buildTime = providers.gradleProperty("buildTime").orElse(   // commit time keeps bootBuildInfo up to date between builds
    providers.exec { commandLine("git", "show", "-s", "--format=%ct", "HEAD") }.standardOutput.asText
        .map { java.time.Instant.ofEpochSecond(it.trim().toLong()).toString() })
springBoot { buildInfo { properties { time = buildTime; additional.put("gitSha", gitSha) } } }
tasks.bootJar { archiveFileName = "masroof-backend.jar" }
tasks.jar { enabled = false }
```
- `take(7)` of the full SHA equals `git rev-parse HEAD | cut -c1-7` exactly. `--short` would not, because it can return more than 7 characters.
- `version` comes from `project.version` (`0.1.0`).
- Local `buildTime` is the HEAD **commit** time, so `bootBuildInfo` stays up to date and the warm `checkFast` < 3 min
  probe is unaffected. Images report the real build time. In both cases `buildTime <= time`.
- `@SpringBootTest` sees the real `META-INF/build-info.properties` (`bootBuildInfo` feeds main resources).
  `@WebMvcTest` does not load `ProjectInfoAutoConfiguration`, so its test supplies a fixed `BuildProperties`.

### Dockerfile (`backend/Dockerfile`, build context = repo root)
```dockerfile
# Backend image (STORY-143, ADR-0004). From the repo root:
#   docker build -f backend/Dockerfile --build-arg GIT_SHA=$(git rev-parse HEAD | cut -c1-7) .
FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS build
ARG GIT_SHA
RUN echo "${GIT_SHA}" | grep -Eqx '[0-9a-f]{7,40}' || { echo "ERROR: build arg GIT_SHA must match ^[0-9a-f]{7,40}\$ (got '${GIT_SHA}'); pass --build-arg GIT_SHA=\$(git rev-parse HEAD | cut -c1-7)" >&2; exit 1; }
WORKDIR /src/backend
COPY backend/ ./
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon --console=plain -q bootJar -PgitSha="${GIT_SHA}" -PbuildTime="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
RUN addgroup -S -g 10001 masroof && adduser -S -D -H -u 10001 -G masroof masroof
WORKDIR /app
COPY --from=build /src/backend/build/libs/masroof-backend.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
- The GIT_SHA check is the first build step, so a missing, empty or `unknown` value fails before Gradle runs, the
  error names `GIT_SHA`, and no image is tagged.
- The jar is root-owned and read-only to uid 10001. `Config.User` is `10001:10001`.
- There is no `# syntax=` line on purpose: it would pull an unpinned frontend image, and the built-in BuildKit supports `--mount=type=cache`.
- `sh ./gradlew` avoids depending on the exec bit, which Windows contexts do not carry.
- The digests are the multi-arch index digests from Docker Hub (2026-10-24). Dependabot `docker` `/backend` keeps them fresh.
- Root `.dockerignore` is a whitelist, so `.env`, `.git` and `web/` never enter the context:
  `*`, `!backend/`, `backend/build/`, `backend/.gradle/`, `backend/out/`. STORY-006 adds `!contracts/`.

### Compose (`infra/docker-compose.yml`, base) and dev overlay (`infra/docker-compose.dev.yml`)
```yaml
# Base stack. STORY-143: postgres + backend; STORY-142 adds minio, web, caddy and the restart policy.
# No host ports here; the dev-only overlay docker-compose.dev.yml publishes the backend on 127.0.0.1:8080.
name: masroof
services:
  postgres:
    image: postgres:16.15-alpine3.24@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-masroof}
      POSTGRES_USER: ${POSTGRES_USER:-masroof}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -q -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 5s
      timeout: 3s
      retries: 12
      start_period: 10s
      start_interval: 1s
  backend:
    build:
      context: ..
      dockerfile: backend/Dockerfile
      args:
        GIT_SHA: ${GIT_SHA:-}          # empty -> the image build fails naming GIT_SHA (never "unknown")
    image: masroof/backend:${IMAGE_TAG:-dev}
    environment:                       # explicit list; never env_file: .env (it would ship every secret and POSTGRES_HOST=localhost)
      SPRING_PROFILES_ACTIVE: prod     # containers always require a DB password (ProdDatabasePasswordGuard)
      POSTGRES_HOST: postgres
      POSTGRES_PORT: "5432"
      POSTGRES_DB: ${POSTGRES_DB:-masroof}
      POSTGRES_USER: ${POSTGRES_USER:-masroof}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}
    depends_on:
      postgres:
        condition: service_healthy
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/health"]
      interval: 10s
      timeout: 4s
      retries: 3
      start_period: 60s
      start_interval: 2s
volumes:
  pgdata:
```
Dev overlay, the whole file:
```yaml
# Dev-only overlay (STORY-143): backend on loopback for curl and the Vite proxy (STORY-141). Never loaded by make up or deploys.
services:
  backend:
    ports: ["127.0.0.1:8080:8080"]
```
- The AC command is `GIT_SHA=$(git rev-parse HEAD | cut -c1-7) docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up -d --build --wait postgres backend`.
- `GIT_SHA` uses `:-` on purpose, not `:?`, so `ps`, `down` and `config` keep working without it.
- With the DB down, busybox `wget` exits non-zero on 503, so the backend turns `unhealthy`. Compose never restarts on
  unhealthy, so `RestartCount` stays unchanged.
- `infra/README.md`: the implementer renames the `docker-compose.local.yml` row to `docker-compose.dev.yml` (dev-only, backend on `127.0.0.1:8080`). The other rows are STORY-142's.

### `make up` wiring (`scripts/make/compose.sh`, 3 lines)
`up)` becomes `export GIT_SHA="${GIT_SHA:-$(git rev-parse HEAD | cut -c1-7)}"; docker compose ... up -d --build --wait || exit 1; if [ -f infra/scripts/seed-demo.sh ]; then bash infra/scripts/seed-demo.sh; fi`.
- Without this, `make up` would break as soon as the compose file exists: the image build would fail on the empty `GIT_SHA`.
- It also stops returning exit 1 when the seed script is missing.
- `make up` loads the base file only, so no backend port is published.
- REQ-OPS-022 (STORY-142) owns the tests for both behaviours.

### e2e runner (`infra/scripts/e2e.sh`, about 8 lines)
`make e2e` -> `scripts/make/e2e.sh` -> `infra/scripts/e2e.sh`. The runner `cd`s to the repo root, runs `bash` on every
`infra/tests/*.sh` (`nullglob`, sorted), prints `===== e2e: <file>` and `FAIL <file>`, and exits 1 if any test failed.
STORY-142 adds the Playwright step and the `make up` precondition.

### STORY-002 review NITs ticketed here
- **NIT 16:** `ProdDatabasePasswordGuard` checks the **effective** `spring.datasource.password`. It reads
  `environment.getProperty("spring.datasource.password")` and catches `IllegalArgumentException` (unresolvable
  placeholder) as missing. It rejects null, blank, or a value still containing `${`. The message names
  `spring.datasource.password` / `POSTGRES_PASSWORD`, never a value. `SPRING_DATASOURCE_PASSWORD` set directly is valid.
  The existing unit tests switch to `withProperty("spring.datasource.password", ...)`, and one case adds an unresolved `${POSTGRES_PASSWORD}`.
- **NIT 17:** new `ProdDatabasePasswordGuardContextTest` with `ApplicationContextRunner().withUserConfiguration(ProdDatabasePasswordGuard.class)`:
  (a) `withPropertyValues("spring.profiles.active=prod")`, no password -> `context.getStartupFailure()` has an `IllegalStateException` in its cause chain naming `spring.datasource.password`;
  (b) prod + `spring.datasource.password=s3cret-test` -> it starts and `hasSingleBean(ProdDatabasePasswordGuard.class)`;
  (c) no profile -> it starts and `doesNotHaveBean`. Tag `REQ-OPS-020` is not appropriate here; tag `@Tag("REQ-SEC-020")`.
- **NIT 18:** closed by ADR-0004. The Boot 4.1.2 BOM imports `log4j-bom` 2.25.5, and the single constraint is deleted.
  The proof is the lockfile grep in ADR-0004 step 1.

### Guardrails
- ArchUnit rule 1 + `"tools.jackson.."` (commit 1).
- **New rule 8, `JDBC_ONLY_IN_OUTBOUND_ADAPTERS`:** `noClasses().that().resideOutsideOfPackage("eg.masroof.adapters.out..").should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..", "com.zaxxer.hikari..").allowEmptyShould(true)`.
  This story introduces the first JDBC use, and the rule keeps the probe's JDBC out of application and rest.
- The `@RestController`-placement rule is deferred to STORY-006 (generated interfaces).
- No new detekt/ESLint/`.claude/rules` changes.

### Invariants and where they are enforced
- **Money:** none involved (rule 3 + probe unchanged).
- **Cairo TZ / UTC:** `time` and `buildTime` are UTC `Instant`s rendered with `Z` (`Instant.toString`). There are no
  business dates. Asserted by the regex in the tests.
- **Log redaction:** the probe logs only the exception class name. There are no request bodies. Rule 4 still bans `System.out`.
- **Untrusted input:** nothing is bound from the request. Asserted by the query/Accept tests.
- **Immutable migrations:** V1 untouched; no new migration.
- **Generated code untouched:** none exists yet. The hand-written controller is replaced by the generated `HealthApi` in STORY-006.
- **Secrets:** compose passes only the 5 DB variables. `.dockerignore` whitelist. `GIT_SHA` is not a secret.

### Tests (test-designer). Every JUnit class carries `@Tag("REQ-OPS-020")` unless stated otherwise.
| Level | Class / file | Cases | Task |
|---|---|---|---|
| slice (MockMvc) | `adapters/in/rest/HealthControllerTest`: `@WebMvcTest(HealthController.class)` + `@Import({HealthService.class, Fixture.class})`. The `@TestConfiguration Fixture` provides `BuildProperties` (version `0.1.0`, gitSha `1a2b3c4`, time `2026-10-23T10:00:00Z`), `Clock.fixed(2026-10-23T12:34:56Z)` and a mutable `DatabaseProbe` (an `AtomicBoolean` lambda). | (1) 200, `application/json`, exactly 6 members, `status` UP, `version`/`gitSha`/`buildTime`/`time` equal to the fixture, `checks` == `{"db":"UP"}`, the semver and `^[0-9a-f]{7,40}$` regexes, `buildTime <= time`. (2) `@ParameterizedTest` with Accept `application/xml`, `text/html`, `*/*` and none -> 200 `application/json`, same body. (3) `?verbose=true&x=` + 2,000×`a` -> same body, and the body contains neither `verbose` nor the long string. (4) probe false -> 503, `application/problem+json`, strict JSON equal to the story body. Then the leak regex `(?i)jdbc:|postgres(ql)?://|password|exception|hikari|:5432|\.java:|\tat ` is matched against the body and every header name and value: no match. | `test` (checkFast) |
| unit | `application/health/HealthServiceTest` | Missing gitSha, `unknown`, missing version, missing time -> `IllegalStateException` naming the key. Valid -> `check()` passes the probe result through, with `time` = the clock truncated to seconds. | `test` |
| unit | `adapters/out/persistence/JdbcDatabaseProbeTest` (Mockito `DataSource` and `Connection`) | `isValid` true -> true. `getConnection` throws `SQLException` -> false. `isValid` throws `RuntimeException` -> false. `getConnection` sleeps 5 s -> false, and the call takes < 2.5 s. | `test` |
| integration (Testcontainers) | `adapters/in/rest/HealthDatabaseOutageIT`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Testcontainers`, `@Tag("integration")`. `PostgreSQLContainer` (the compose image pin) with a **fixed host port**: pick a free port via `new ServerSocket(0)`, then `withCreateContainerCmdModifier(c -> c.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(5432))))`. That way a docker stop/start keeps the JDBC URL from `@ServiceConnection` valid. HTTP via the JDK `HttpClient` (5 s timeout) against `@LocalServerPort`. | (a) Baseline 200: `checks.db` UP, gitSha matches the contract pattern, version matches semver (real build-info). (b) `DockerClientFactory.instance().client().stopContainerCmd(id).exec()`, then GET -> elapsed < 3 s, 503, `application/problem+json`, the exact body, and the leak regex on body and headers. (c) `startContainerCmd(id).exec()`, then poll every 500 ms for ≤ 30 s until 200 with `checks.db` UP. Assert `ManagementFactory.getRuntimeMXBean().getStartTime()` and the `ApplicationContext` startup date are unchanged (the JVM equivalent of "no restart"). | `integrationTest` |
| integration (context) | `adapters/out/persistence/ProdDatabasePasswordGuardContextTest` (NIT 17) and the updated `ProdDatabasePasswordGuardTest` (NIT 16) | as listed above | `test` |
| architecture | `ArchitectureRulesTest` rule 1 (+ `tools.jackson..`) and rule 8 | The existing probes still bite. | `archTest`, `test` |
| e2e (compose) | `infra/tests/req-ops-020-backend-compose.sh` (comment `# REQ-OPS-020`), run by `make e2e` | It runs the AC dev-overlay command and, on EXIT, `... -f infra/docker-compose.dev.yml down` (no `-v`). STORY-142's port test must not see the overlay. (1) `docker compose config --format json` has a `healthcheck.test` for `postgres` and `backend`. (2) `docker compose ps --format json` (`jq -s` handles both line and array output) shows both `healthy`. (3) `docker inspect -f '{{.Config.User}}' masroof/backend:dev` is non-empty and its user part is neither `root` nor `0`. (4) `curl -sS -D -` on `127.0.0.1:8080/health` -> 200, `application/json`. jq: status UP; version == `sed -n 's/^version = "\([^"]*\)".*/\1/p' backend/build.gradle.kts` and semver; gitSha == `git rev-parse HEAD \| cut -c1-7`; `checks` keys == `["db"]` and UP; both times end in `Z`; `\|time - date -u +%s\| <= 5` (GNU `date -d`); `buildTime <= time`. (5) Accept `application/xml` and `text/html` -> 200 `application/json`. (6) For GIT_SHA missing, `""` and `unknown`: `docker build --progress=plain -f backend/Dockerfile -t masroof/backend:gitsha-probe [--build-arg GIT_SHA=…] .` -> rc ≠ 0, the output contains `GIT_SHA`, and afterwards `docker image inspect masroof/backend:gitsha-probe` fails (`docker image rm -f` it first). | `make e2e` (needs Docker) |

- No contract test: STORY-006 adds schema validation against `openapi.yaml`.
- No property test or eval: no parsing and no money.
- DB down and recovery over compose (`docker compose stop postgres`) are covered by the JUnit IT, as the story's
  "Test location" says. A compose variant is optional and not in the size budget.

### Contract
- `paths./health.get.responses.200.description` -> "Service and database are up."
- `Health.checks.description` -> "Component checks. Only `db` exists until later stories add keys (for example `storage`, `queue`)."
- No `406`. The `429` stays (STORY-010).
- `make contract-lint`: "Woohoo! Your API description is valid." `make contract-diff BASE=main`: "No breaking changes to report, but the specs are different." / "[oasdiff] no breaking changes against main".

### Threats (summary; full rows in `docs/security/THREAT_MODEL.md` "B1a" and "Build supply chain")
Boundary: compose network / `127.0.0.1` -> backend `/health`. There is no internet exposure in this story, because
the base compose publishes nothing.
- **S:** n/a (unauthenticated by design).
- **T:** no request input is bound.
- **R:** n/a.
- **I:** the build-info disclosure is accepted. The DB-down body is fixed text, and the leak regex is tested on body and headers. Probe logs carry only the class name.
- **D:** each call costs one DB round trip, bounded at 2 s with Hikari's pool. Rate limiting is STORY-010 (REQ-SEC-051).
- **E:** uid 10001, `read_only`, `cap_drop: ALL`, `no-new-privileges`, no actuator on the classpath.
- **Supply chain:** base images pinned by digest; GIT_SHA validated; whitelist build context; Gradle artifacts checked
  against the verification metadata inside the image build; Boot 4.1.2 BOM with 0 OSV findings.
- B7 is unchanged apart from Hikari timeouts.
- **security-reviewer: required.** This is the first network listener, the first container image and a framework major upgrade, and it closes NITs 16-18.

### Size re-estimate (hand-written)
| Part | Lines |
|---|---|
| Upgrade (commit 1, additions only) | ≈ 16 |
| NIT 16 | ≈ 6 |
| Health production Java | ≈ 115 |
| yml | 6 |
| Gradle build info | 10 |
| Dockerfile | 20 |
| `.dockerignore` | 5 |
| Compose base + overlay | 50 |
| `compose.sh` | 3 |
| `e2e.sh` | 8 |
| README | 1 |
| **Production / config total** | **≈ 240** |
| Tests (MockMvc 60, HealthService 20, probe 25, IT 60, NIT 17 context test 25, arch 10, infra test 40) | **≈ 240** |
| **Total** | **≈ 480** |

**This exceeds ~400**, because the Boot upgrade and the NITs (about 75 lines with their tests) were not in the
story's 390 estimate. To stay under the cap without losing an AC, commits 1-2 (the upgrade plus NITs 16-18, about
80 lines) are a **prerequisite counted separately**, the same way STORY-002 handled its `[SDLC]` prerequisite. They
go to code review as their own diff. Commits 3-4 (REQ-OPS-020 itself) come to about 400. If the orchestrator does not
accept the prerequisite, the split is: **STORY-144 "Backend on Spring Boot 4.1.2 + prod guard NITs"** (commits 1-2,
REQ-OPS-023/024 regression, about 80 lines), which STORY-143 then depends on.

### Open items for the orchestrator
- **Owner:** the property-testing tool for STORY-004. jqwik is removed in commit 1 (ADR-0004 option c), and S9 names jqwik.
- devops follow-up: Dependabot `docker-compose` ecosystem for `/infra`. The `docker` ecosystem does not update image digests in compose files.
- The prerequisite-vs-split decision above.

## Dependencies
STORY-002 (gated backend module, V1 baseline). Unblocks STORY-141 (its Vite dev proxy needs a running `/health` on `127.0.0.1:8080`), STORY-142 (full stack), STORY-006 (switches this controller to the generated interface).

## Size estimate (hand-written lines incl. tests; generated scaffolding excluded)
Production + config ≈ 240 (health use case + DB probe + controller + 503 Problem ≈ 150, Gradle build-info / git SHA wiring ≈ 15, Dockerfile ≈ 25, compose `postgres`+`backend` + dev overlay ≈ 40, `infra/scripts/e2e.sh` ≈ 10); tests ≈ 150 (controller tests incl. Accept/leakage/query ≈ 65, Testcontainers DB-down + recovery ≈ 60, `infra/tests` compose checks for healthy/non-root/`GIT_SHA` ≈ 25). **Total ≈ 390.**

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 390; generated scaffolding excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — split out of STORY-002 (size decision, option b); REQ-OPS-020 moved here |
| 2026-10-23 | 2 spec review (loop 2) | spec-reviewer | CHANGES_REQUESTED |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — gitSha provenance outside Docker, compose-check location, start-up resilience moved to STORY-142; ≈ 390 lines |
| 2026-10-24 | 3 design | architect | BLOCKED (size only): design complete (ADR-0004 Boot 4.1.2, /health, image, compose, tests, NITs 16-18). The total is ≈ 480 lines, and the REQ-OPS-020 part is ≈ 400. Decide: the Boot upgrade + NITs as a separately counted prerequisite, or split them out as STORY-144. Contract description-only; lint valid, oasdiff no breaking changes |
