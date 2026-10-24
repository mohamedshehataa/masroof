---
id: STORY-144
epic: EPIC-00
title: "Backend on Spring Boot 4.1.2 + prod guard NITs"
phase: 0
status: READY
size: M
modules: [backend]
depends_on: [STORY-002]
reqs: [REQ-OPS-142, REQ-OPS-143]
implements: ["ADR-0004", "PROMPT.md §2 (stack)", "PROMPT.md S9"]
owner_agent: backend-engineer
---

# STORY-144 — Backend on Spring Boot 4.1.2 + prod guard NITs

## Goal
The gated `backend/` module from STORY-002 moves from Spring Boot 3.5.16 (OSS support ended 2026-06-30) to **4.1.2** with every S9 gate still green and 0 OSV findings, and the prod datasource-password guard checks the password Spring actually uses. This must land before STORY-143 adds the first HTTP endpoint (ADR-0004; PROMPT §2 stack, "note any change in DECISIONS.md"; S9 backend gates).

Split (2026-10-24, `docs/DECISIONS.md`): STORY-143's design came to ≈ 480 hand-written lines. Its commits 1-2 (the Boot upgrade and STORY-002 review NITs 16-18) moved here, so STORY-143 keeps only REQ-OPS-020 and depends on this story.

Deviation from Part I (already recorded in `docs/DECISIONS.md` 2026-10-24 and ADR-0004): S9 names jqwik for property tests. This story **removes** jqwik and adds no replacement. The replacement is chosen in the STORY-004 design (OWNER-010, default QuickTheories). No test uses jqwik today.

## Requirements and acceptance criteria
### REQ-OPS-142 — Backend runs on Spring Boot 4.1.2 with the full gate green
**Given** `backend/build.gradle.kts` declares plugin `org.springframework.boot` version `4.1.2` **When**, in `backend/`, `./gradlew checkFast archTest integrationTest migrationTest` runs **Then** it exits 0, and:
- `grep -E "spring-boot:4\.1\.2" backend/gradle.lockfile` matches, and `grep -c "3\.5\.16" backend/gradle.lockfile backend/buildscript-gradle.lockfile` reports 0 for both files. Both lockfiles were regenerated with `./gradlew dependencies buildEnvironment --write-locks` and are committed.
- In `backend/gradle.lockfile`, `org.apache.logging.log4j:log4j-api` and `org.apache.logging.log4j:log4j-to-slf4j` have the same version (2.25.5 at the time of writing). This closes STORY-002 review NIT 18.
- `backend/gradle/verification-metadata.xml` was regenerated from scratch (not appended) with `--write-verification-metadata sha256` over at least `checkFast archTest integrationTest migrationTest pitest`. It is committed, it has no `<component>` with `version="3.5.16"` in group `org.springframework.boot`, and it has no `net.jqwik` component. A clean `./gradlew checkFast` with the committed metadata reports no verification failure.
- `net.jqwik:jqwik` is not declared in `build.gradle.kts` and does not appear in `gradle.lockfile`. No other property-testing library is added.
- These STORY-002 overrides are **removed** from `build.gradle.kts`, because the 4.1.2 BOM or plugin now ships fixed versions:
  1. `platform("com.fasterxml.jackson:jackson-bom:2.21.8")`;
  2. the constraint `org.postgresql:postgresql:42.7.14`;
  3. the constraint `org.apache.logging.log4j:log4j-api:2.25.5`;
  4. the test constraint `org.apache.commons:commons-compress:1.28.0`;
  5. `platform("org.junit:junit-bom:5.14.4")`;
  6. the `buildscript` constraints `org.apache.httpcomponents.client5:httpclient5:5.6.5`, `org.apache.httpcomponents.core5:httpcore5:5.4.4` and `httpcore5-h2:5.4.4`.

  The `buildscript` constraints `jackson-bom 2.21.8` and `commons-lang3 3.18.0` are removed too, unless `./gradlew buildEnvironment` would then resolve a lower version. If one is kept, its comment names the plugin that still pulls the old version.
- `osv-scanner scan source -r --no-ignore backend` reports **0 findings** (exit 0, "No issues found").
- ArchUnit still runs under the JUnit Platform: `archTest` reports at least 7 executed rule tests (rules 1-7 from STORY-002) and 0 skipped. Rule 1 also lists `"tools.jackson.."` (Jackson 3 package) among the packages banned in `eg.masroof.domain..`. `MoneyRuleProbeTest` passes in `test`. `make selftest` row G4 is `PROVEN`.
- `V1BaselineMigrationTest` passes on Testcontainers 2.x in `integrationTest` and `migrationTest`. It still finds exactly one `flyway_schema_history` row, `('1', true, 'V1__baseline.sql')`.
- `-Werror`, Error Prone and NullAway are unchanged in strength. `grep -rnE 'SuppressWarnings\(.*NullAway' backend/src` finds nothing, and `NullAway:AnnotatedPackages` is still `eg.masroof`. New findings caused by Spring's JSpecify annotations are fixed in code.
- `make check-fast MODULE=backend` is run twice and the **second (warm) run exits 0 in < 180 s** (S8 budget, REQ-OPS-023 probe case 1). `make check` exits 0. `make security` is GREEN.

Unhappy path (ArchUnit on JUnit Platform 6): **Given** the ArchUnit JUnit engine reports 0 tests or an engine error under the Boot 4.1.2 platform **When** the implementer applies ADR-0004's documented workaround (the rules run from a Jupiter class tagged `arch`, one `@Test` per rule calling `RULE.check(...)` on main classes imported with `DoNotIncludeTests`) **Then** `archTest` reports one passing test per rule (≥ 7), G4 is still `PROVEN`, the rule definitions are unchanged, and the story's diff adds a `docs/DECISIONS.md` line naming the workaround and the ArchUnit version.
Unhappy path (PIT on JUnit Platform 6): **Given** `./gradlew pitest` fails to launch on Platform 6 **Then** the story is not blocked, because PIT runs only in `make check-full` and no mutable code exists yet. The diff adds a `docs/DECISIONS.md` line with the error's first line and states that STORY-004 settles it. `make check` (which does not run PIT) stays green.
Unhappy path (new advisory): **Given** `osv-scanner` reports a finding after the overrides are removed **When** the implementer fixes it **Then** only the one override that removes that finding is added back, with a comment naming the advisory id, and the scan reports 0 findings again. Ignore files and `--no-ignore` removal are not allowed.
Unhappy path (stale verification metadata): **Given** an old `verification-metadata.xml` entry is kept for an artefact that is no longer resolved **Then** this counts as a failure: the diff of the regenerated file against STORY-002's version shows only components in the 4.1.2 graph (reviewed in the PR), and `grep -c 'version="3.5.16"'` is 0.
Unhappy path (fallback, never a silent downgrade): **Given** any gate above cannot be made green on 4.1.2 with released versions within this story, or the upgrade's hand-written diff grows beyond about 60 lines (ADR-0004) **Then** the story ends **BLOCKED** with the failing command, its exit code and the first error lines in the status log and the handoff. The plugin is not changed back to 3.5.x in this story, and no gate is relaxed. Falling back to 3.5.16 under ADR-0004 needs an orchestrator decision.

### REQ-OPS-143 — Prod datasource password guard checks the effective password
**Given** profile `prod` is active **When** the application context starts **Then** `ProdDatabasePasswordGuard` decides on the **effective** `spring.datasource.password` (as resolved by the Spring `Environment`), not on the `POSTGRES_PASSWORD` variable alone:
- prod with `SPRING_DATASOURCE_PASSWORD=s3cret-test` set and **no** `POSTGRES_PASSWORD` -> the guard passes (start-up continues to the datasource).
- prod with `POSTGRES_PASSWORD=s3cret-test` and no `SPRING_DATASOURCE_PASSWORD` -> the guard passes (`application.yml` maps it).

Unhappy path (missing or blank): **Given** profile `prod` **When** `spring.datasource.password` resolves to null, `""` or whitespace only (for example `"   "`), or still contains an unresolved `${` placeholder (for example a literal `${POSTGRES_PASSWORD}`), or the property cannot be resolved at all **Then** start-up fails with an `IllegalStateException` in the cause chain. Its message names `spring.datasource.password` and `POSTGRES_PASSWORD`, never the value.
Unhappy path (empty override): **Given** profile `prod`, `POSTGRES_PASSWORD=s3cret-test` **and** `SPRING_DATASOURCE_PASSWORD=` (set to empty, which overrides the yml mapping) **When** the context starts **Then** start-up fails with the guard's message, as above.
Unhappy path (no secret in output): **Given** any of the failing cases above, with a password-like value present in another property (for example `POSTGRES_PASSWORD=leak-canary-9f3`) **Then** neither the exception message chain nor the captured log output contains `leak-canary-9f3`.

**Given** a test using `ApplicationContextRunner` with the guard as its user configuration (no datasource, no Docker) **Then** these cases hold, each tagged `@Tag("REQ-OPS-143")`:
- (a) `spring.profiles.active=prod` and no password -> `context.getStartupFailure()` is non-null, with an `IllegalStateException` in its cause chain whose message contains `spring.datasource.password`;
- (b) prod + `spring.datasource.password=s3cret-test` -> the context starts and has exactly one `ProdDatabasePasswordGuard` bean;
- (c) no active profile and no password -> the context starts and has no `ProdDatabasePasswordGuard` bean. The guard never blocks local or dev start-up, which goes on to the datasource connection attempt. With no profile and no password, a full `bootRun` against an unreachable or password-requiring DB fails with a connection or authentication error, never with the guard's message.
- (d) `spring.profiles.active=PROD` (upper case) and no password -> the context starts and has **no** guard bean. This pins the accepted behaviour (STORY-002 verify NIT): Spring profile names are case-sensitive.

**Given** `backend/README.md` **Then** the deployment line states that the profile must be lowercase `prod`, that `PROD`/`Prod` do **not** activate the guard (profile names are case-sensitive), and that `SPRING_DATASOURCE_PASSWORD` alone is accepted.
The existing `ProdDatabasePasswordGuardTest` unit cases switch to the effective property and gain the unresolved-placeholder and blank cases above. Existing assertions are not weakened.

## UX states (UI stories only)
N/A — no UI.

## Contract impact
None (no endpoint; `contracts/openapi.yaml` is untouched).

## Data / migration impact
None. V1 is unchanged and immutable. Only the Testcontainers class and image pin used by `V1BaselineMigrationTest` change, as ADR-0004 lists.

## Security / privacy / LLM-safety impact
- **Supply chain (build boundary: Maven Central / Plugin Portal -> build):** a framework major BOM change, five-plus overrides removed, and lockfiles and verification metadata regenerated. Mitigated by the 0-finding `osv-scanner --no-ignore` criterion, the from-scratch verification-metadata regeneration reviewed by diff, exact pins, and `gradle/actions/wrapper-validation` in CI. **security-reviewer is required** at stage 7.
- **Secrets (B7 backend -> Postgres):** the guard closes the gap where `SPRING_DATASOURCE_PASSWORD=` overrode a set `POSTGRES_PASSWORD` and prod started with an empty password (STORY-002 review NIT 16). The message never contains a value (canary case). The case-sensitive profile name is an accepted and documented risk: compose (STORY-143) hard-codes lowercase `prod`.
- No runtime listener, no household data, no LLM.

## Eval impact
None (no parsing).

## Design notes (architect)
The plan is in [ADR-0004](../../adr/0004-spring-boot-4-1-upgrade.md) ("Changes to `backend/build.gradle.kts`", "Lockfile, verification metadata and SCA refresh", "Risk and fallback"). It was written for STORY-143's commits 1-2, which this story now carries:
1. `build(backend): Spring Boot 4.1.2 [STORY-144]`. ADR-0004's table: starters `-flyway`, `-webmvc-test`, `-jdbc-test`; Testcontainers 2 coordinates; ArchUnit 1.5.1; jqwik removed; overrides dropped. Then the refresh steps 1-4 in order, the V1-test Testcontainers 2 change and image pin, ArchUnit rule 1 plus `tools.jackson..`, and `backend/CLAUDE.md` "Spring Boot 3.x" -> "4.x (ADR-0004)". Whether `spring-boot-starter-webmvc` (runtime) lands here or with STORY-143's first endpoint is the architect's call. Without it this story adds no listener.
2. `fix(backend): prod password guard checks the effective datasource password [STORY-144]`: NITs 16 and 17, as specified in REQ-OPS-143. NIT 18 is closed by commit 1 (lockfile log4j check).

Gradle 8.14.6 meets Boot 4.1.2's requirement (8.14+ or 9.x), so the wrapper is unchanged. ADR-0004's validation still says "`archTest` reports 8 rules". Rule 8 (`JDBC_ONLY_IN_OUTBOUND_ADAPTERS`) belongs to STORY-143, so this story expects ≥ 7.

## Dependencies
STORY-002 (DONE: gated module, lockfiles, verification metadata, guard). Unblocks STORY-143 (`/health` on 4.1.2) and through it STORY-141, STORY-142 and STORY-006.

## Size estimate (hand-written lines incl. tests; regenerated lockfiles and verification metadata excluded)
Build script delta ≈ 20 (additions; removals are not counted). V1 test Testcontainers 2 change ≈ 4, ArchUnit rule 1 ≈ 1, NullAway fixes ≈ 10, guard change ≈ 8, guard unit-test updates ≈ 15, `ProdDatabasePasswordGuardContextTest` (a-d + canary) ≈ 40, README/CLAUDE.md ≈ 3, a possible ArchUnit workaround ≈ 15, DECISIONS lines ≈ 4. **Total ≈ 120.** The story is sized M, not S, to leave headroom for unknown NullAway/JSpecify fixes. The ADR's ≈ 60-line upgrade bound is the fallback trigger.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 120)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1-2 spec | product-analyst | READY — split out of STORY-143 (size ≈ 480): Boot 4.1.2 upgrade (ADR-0004) + STORY-002 review NITs 16-18; REQ-OPS-142/143; ≈ 120 lines |
