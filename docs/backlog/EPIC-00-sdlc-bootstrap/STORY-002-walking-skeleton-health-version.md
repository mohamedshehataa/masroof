---
id: STORY-002
epic: EPIC-00
title: "Walking skeleton (backend): Gradle module + fast gate + V1 baseline + ArchUnit rules and probe"
phase: 0
status: READY
size: M
modules: [backend]
depends_on: [STORY-001]
reqs: [REQ-OPS-023, REQ-OPS-024]
implements: ["PROMPT.md S15 item 7", "PROMPT.md S15 item 6 (ArchUnit guard proven)", "PROMPT.md §2 (stack)", "PROMPT.md §8 (household)", "PROMPT.md S8 (make check-fast, make check)", "PROMPT.md S9 (ArchUnit, backend gates)"]
owner_agent: backend-engineer
---

# STORY-002 — Walking skeleton (backend): Gradle module + fast gate + V1 baseline + ArchUnit rules and probe

## Goal
The first slice of the S15 item 7 walking skeleton: a Spring Boot `backend/` module exists with the S9 backend gates, the `V1` baseline migration and the ArchUnit architecture rules (with the S15 step 6 G4 probe proven), so `GET /health` (STORY-143), the web page (STORY-141) and `make up` (STORY-142) land on a gated module. It is the first story run through the whole S5 pipeline.

Split decisions (spec review 2026-10-23, orchestrator): the original STORY-002 became STORY-002 (this story), STORY-143 (`/health`, Dockerfile, compose), STORY-141 (web) and STORY-142 (stack). Rate limiting of `/health` moved to STORY-010 (REQ-SEC-051).

## Requirements and acceptance criteria
### REQ-OPS-023 — Backend fast gate and full check are green
**Given** the `backend/` module **When** `./gradlew checkFast` runs **Then** it executes, and fails on any violation of, exactly: `spotlessCheck`, `checkstyleMain`, `compileJava` and `compileTestJava` with `-Werror` plus Error Prone and NullAway, SpotBugs (fails on any high-priority finding), and the unit `test` task (tests tagged `integration` excluded) — and exits 0. `archTest` (ArchUnit) and `integrationTest` (Testcontainers) are separate tasks, as `backend/CLAUDE.md` defines.
**Given** a warm cache (second consecutive run; Gradle daemon and dependency cache populated) **When** `make check-fast MODULE=backend` runs at the repo root **Then** it dispatches to `./gradlew checkFast` and exits 0 in < 3 min (S8 budget); **when** `make check-fast` runs with no `MODULE` **Then** it runs the `sdlc` and `backend` gates (the present modules) and exits 0.
**Given** the repo after this story **When** `make check` runs **Then** it exits 0 within 15 min, which includes `./gradlew integrationTest` and `./gradlew archTest` green, the coverage gate (`scripts/quality/quality-thresholds.yaml`) and traceability green.
Unhappy path: **Given** a planted Java source file indented with tabs, or with an unused import **When** `make check-fast MODULE=backend` runs **Then** it exits non-zero and the output names the file and the failing task (`spotlessCheck` resp. `compileJava`/Checkstyle).
Unhappy path: **Given** a planted method that dereferences a `@Nullable` parameter without a check **When** `./gradlew checkFast` runs **Then** compilation fails with a NullAway error naming the file and line.

### REQ-OPS-024 — V1 baseline migration and architecture rules
**Given** an empty Postgres 16 database (Testcontainers, `integrationTest`) **When** the backend application context starts **Then** Flyway applies exactly one migration, `V1__baseline.sql`, `flyway_schema_history` has one row with `version = '1'` and `success = true`, and the schema contains this table (DDL fixed by spec review, orchestrator decision 11):
```sql
CREATE TABLE household (
  id              uuid        PRIMARY KEY,
  name            text        NOT NULL,
  base_currency   char(3)     NOT NULL DEFAULT 'EGP',
  timezone        text        NOT NULL DEFAULT 'Africa/Cairo',
  month_start_day int         NOT NULL DEFAULT 1 CHECK (month_start_day BETWEEN 1 AND 28),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
```
Unhappy path: **Given** the V1 schema **When** a row is inserted with `month_start_day = 29` (or `0`) **Then** Postgres rejects it with SQLSTATE `23514` (check violation); an insert with only `id` and `name` succeeds and gets `base_currency = 'EGP'`, `timezone = 'Africa/Cairo'`, `month_start_day = 1` and non-null `created_at`/`updated_at`.
(Container start-up behaviour when Postgres is unreachable is specified with the compose services in STORY-143.)

**Given** `./gradlew archTest` (imports main classes only, `ImportOption.DoNotIncludeTests`) **When** it runs **Then** these rules exist and pass:
1. classes in `eg.masroof.domain..` do not depend on `org.springframework..`, `jakarta.persistence..`, `jakarta.servlet..`, `com.fasterxml.jackson..` or `java.net.http..`;
2. classes in `..adapters.in..` do not depend on `..adapters.out..` and vice versa;
3. money rule: no field, method/constructor parameter or return type of type `double`, `float`, `Double` or `Float` in any class under `..money..`, `..parsing..`, `..dedup..` or `..analytics..`; and no field, parameter or return type of those types on any member whose name matches `(?i).*(amount|price|total|balance|fee|vat|tip|share).*` anywhere in `..domain..` or `..application..`;
4. no class accesses `System.out` or `System.err`;
5. no field injection (`@Autowired`/`@Inject`/`@Value` on fields).
**Given** some of these packages contain no classes yet (skeleton) **Then** the rules still pass (empty packages are explicitly allowed, not a "failed to check any classes" error).
Unhappy path (negative probe, S15 step 6 G4): **Given** a test-source fixture `eg.masroof.archfixture.domain.BadMoney` declaring `double amount;` **When** a dedicated test imports that class and evaluates the money rule against it **Then** the rule's check throws an `AssertionError` whose message contains `BadMoney`, and the test asserts exactly that (so a rule that silently passes fails the build). **Given** `make selftest` after this story **Then** row G4 of `docs/SDLC_SELFTEST.md` is `PROVEN` (the selftest script additionally plants a `double amount` class under `backend/src/main/java/.../domain/` and observes `./gradlew archTest` fail).

## UX states (UI stories only)
N/A — no UI.

## Contract impact
None (no endpoint in this story; `GET /health` is STORY-143).

## Data / migration impact
New migration `V1__baseline.sql` (expand only; the `household` table above, nothing else). V1 becomes immutable once on `main` (`protect-files.sh`); STORY-004 adds `V2`/`V3`.

## Security / privacy / LLM-safety impact
None at runtime (no endpoint, no external input, no LLM): this story adds build-time guards only. The ArchUnit rules are themselves a control (money rule, no `System.out` that could leak data to stdout). The database password comes from `.env` / Testcontainers, never committed.

## Eval impact
None (no parsing).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-001 (hooks proven first). Unblocks STORY-143 (`/health`), STORY-004 (V2/V3 on top of V1), STORY-008.

## Size estimate (orchestrator decision 13 — honest count)
Hand-written lines, generated scaffolding excluded (Gradle wrapper, Spring Initializr base files, vendored Checkstyle config): production + build/config ≈ 165 (Gradle build with Error Prone/NullAway/SpotBugs/Spotless/Checkstyle/test-task split ≈ 100, version catalog ≈ 20, application class ≈ 10, `application.yml` ≈ 15, V1 ≈ 12, Checkstyle suppressions ≈ 5); tests ≈ 120 (ArchUnit rules + probe + fixture ≈ 80, migration integration test ≈ 40). **Total ≈ 285.**

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 285; scaffolding generated by tools is excluded from the count)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 2 spec review | spec-reviewer | CHANGES_REQUESTED (split, rate limit, DB down, provenance, ArchUnit scope, V1 DDL) |
| 2026-10-23 | 1-2 spec fix | product-analyst | DRAFT — split into STORY-002/141/142, findings applied; size ≈ 615 lines > 400 |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — orchestrator accepted the size split: REQ-OPS-020 moved to STORY-143; ≈ 285 lines |
