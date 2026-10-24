# Review report — STORY-143

- **Reviewers:** qa-verifier acting as code-reviewer (low tier, combined verify + review, fresh context)
- **Date:** 2026-10-24  ·  **Diff:** `git diff db3d2bc...627cce7` (commits 3370491, c6a86f0, 7e559b5, 627cce7)

## Findings
| # | Severity (BLOCKER/MAJOR/MINOR/NIT) | Reviewer | Location | Why it matters | Suggested fix | Resolution |
|---|---|---|---|---|---|---|
| 1 | MINOR | code-reviewer | story size | About 805 hand-written lines (331 prod/config + 474 tests) against the ~400 cap. The story leaves the decision to the orchestrator. | The orchestrator either accepts it or moves infra/tests/req-ops-020-backend-compose.sh to STORY-142. | open (orchestrator) |
| 2 | MINOR | code-reviewer | test suite | The design's HealthServiceTest (fail-fast on missing or `unknown` gitSha, version or time) and JdbcDatabaseProbeTest (5 s getConnection returns false in under 2.5 s; SQLException and RuntimeException return false) are missing. The probe timeout path is covered by no automated test, and a stopped container gives an RST, not a hang. | test-designer adds both classes as specified in the design's Tests table. | open |
| 3 | MINOR | code-reviewer | backend/build.gradle.kts:62-66 | `providers.exec git` is a hard dependency for any Gradle build without -PgitSha/-PbuildTime. A container without git, a tarball, or a mounted worktree fails with an opaque process error. Observed on eclipse-temurin:21-jdk. | Fail with a clear message ("pass -PgitSha/-PbuildTime or run inside a git checkout"), or document that the Linux checkFast command needs git or the props. | open |
| 4 | MINOR | code-reviewer | Hikari WARN logging | During an outage HikariPool logs a PSQLException stack trace at WARN, so the probe's class-name-only rule does not cover the whole log. | Lower `com.zaxxer.hikari.pool` to ERROR in prod, or accept and record it (no secret seen). | open |
| 5 | NIT | code-reviewer | JdbcDatabaseProbe.java:31-37 | `submit` is outside the try. After close(), RejectedExecutionException breaks "never throws" and gives a 500 during shutdown. The ExecutionException is logged instead of its cause's simple name. | Move submit inside the try and catch RejectedExecutionException. Log `e.getCause().getClass().getSimpleName()`. | open |
| 6 | NIT | code-reviewer | ProdDatabasePasswordGuard.java:36-39; ProdDatabasePasswordGuardHikariTest | The hikari hint names the wrong variables. The test carries REQ-OPS-020 for REQ-OPS-143 behaviour and covers only blank, not unresolved or no-leak (both verified at runtime). | Property-specific hint; tag REQ-OPS-143; add the unresolved/canary case. | open |
| 7 | NIT | code-reviewer | infra/tests/req-ops-020-backend-compose.sh:77 | Strict string comparison of buildTime and time, but the spec says less than or equal. | Accept equality. | open |
| 8 | NIT | code-reviewer | backend/Dockerfile:5 | `grep -x` is line-based, so a multi-line GIT_SHA passes the build check (runtime regex still refuses to start). | Use a `case` pattern or `[ "$(printf %s "$GIT_SHA" | wc -l)" = 0 ]` plus grep. | open |

### Checklist notes (all observed)
- **Hexagonal boundaries:** HealthReport, DatabaseProbe and HealthService are in `application.health` with no adapter imports. JdbcDatabaseProbe is in adapters.out.persistence. HealthController is in adapters.in.rest and depends only inward. Rule 8 (JDBC only in adapters.out) was added. `archTest` → 10 tests, 0 failures. No domain types (operational concern), which is fine.
- **Bounded probe:** a virtual-thread executor and `future.get(2 s)`, with cancel(true) on timeout. Hikari connection-timeout 2000 / validation-timeout 1000 / pgjdbc connectTimeout 2. Observed: stop → 2.0 s, pause (hang) → 2.004 s, 40 parallel calls with the DB down → max 2.015 s. Each stuck virtual thread is bounded by Hikari's timeouts. Concurrency is bounded by Tomcat workers, because the request thread blocks on the future. With an exhausted pool the probe returns false after 2 s, so /health reports 503. That is acceptable for a liveness-style check but worth knowing for STORY-142's healthcheck semantics. Executor closed via AutoCloseable.
- **Clock injection:** ClockConfiguration provides `Clock.systemUTC()`. HealthService uses `Instant.now(clock).truncatedTo(SECONDS)`. The test fixes the clock.
- **Dockerfile:** both stages are pinned by digest. GIT_SHA is validated as the first step. Whitelist `.dockerignore` (355 kB context, no .env/.git). uid 10001. No `# syntax=`. trivy: 0 misconfigs.
- **Compose:** the base file has no `ports`. The dev overlay publishes `127.0.0.1:8080` only (observed in PortBindings). Explicit env list, no env_file. Hardening observed: read_only, cap_drop ALL, no-new-privileges, tmpfs /tmp. POSTGRES_PASSWORD uses `:?`.
- **Test quality:** the MockMvc tests use strict whole-body equality and leak regex checks. The IT checks real stop/start with a fixed host port and JVM/context start-time checks. The compose script is thorough. Gap: finding 2.
- **TODO without story id / new skip:** none in the diff (grep of added lines for TODO, FIXME, @Disabled, double, float → no match).

## Invariants checked
- [x] Money types: no money in this diff; no double/float added (grep).
- [x] Cairo TZ: only UTC Instants rendered with Z; no business dates.
- [x] Log redaction: the probe logs the class name only; no bodies. Hikari WARN caveat (finding 4).
- [x] Untrusted LLM input: n/a. Nothing from the request is bound (query and Accept tests).
- [x] Migrations immutable: no migration touched.
- [x] Generated code untouched: none exists. The controller is hand-written by design (STORY-006 switches it).

STATUS: APPROVE
