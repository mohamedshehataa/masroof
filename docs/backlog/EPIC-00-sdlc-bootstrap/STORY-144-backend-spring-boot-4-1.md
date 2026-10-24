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
implements: ["PROMPT.md §2 (stack)", "PROMPT.md S9"]
owner_agent: backend-engineer
---

# STORY-144 — Backend on Spring Boot 4.1.2 + prod guard NITs

## Goal
The gated `backend/` module from STORY-002 moves from Spring Boot 3.5.16 (OSS support ended 2026-06-30) to **4.1.2** with every S9 gate still green and 0 OSV findings, and the prod datasource-password guard checks the password Spring actually uses. This must land before STORY-143 adds the first HTTP endpoint. Links: [ADR-0004](../../adr/0004-spring-boot-4-1-upgrade.md) (the upgrade plan); PROMPT §2 stack ("note any change in DECISIONS.md"); S9 backend gates.

Split (2026-10-24, `docs/DECISIONS.md`): STORY-143's design came to ≈ 480 hand-written lines. Its commits 1-2 (the Boot upgrade and STORY-002 review NITs 16-18) moved here, so STORY-143 keeps only REQ-OPS-020 and depends on this story.

Deviation from Part I (already recorded in `docs/DECISIONS.md` 2026-10-24 and ADR-0004): S9 names jqwik for property tests. This story **removes** jqwik and adds no replacement. The replacement is chosen in the STORY-004 design (OWNER-010, default QuickTheories). No test uses jqwik today.

Deviation from ADR-0004's build table (recorded by the orchestrator in `docs/DECISIONS.md`): `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test` and `spring-boot-starter-jdbc-test` are **not** added here. They land with STORY-143's first endpoint, so this story adds no HTTP listener and keeps `spring-boot-starter-test`.

## Requirements and acceptance criteria
### REQ-OPS-142 — Backend runs on Spring Boot 4.1.2 with the full gate green
**Given** `backend/build.gradle.kts` declares plugin `org.springframework.boot` version `4.1.2` **When**, in `backend/`, `./gradlew checkFast archTest integrationTest migrationTest` runs **Then** it exits 0, and:
- `grep -E "spring-boot:4\.1\.2" backend/gradle.lockfile` matches, and `grep -c "3\.5\.16" backend/gradle.lockfile backend/buildscript-gradle.lockfile` reports 0 for both files. Both lockfiles were regenerated with `./gradlew dependencies buildEnvironment --write-locks` and are committed.
- In `backend/gradle.lockfile`, `org.apache.logging.log4j:log4j-api` and `org.apache.logging.log4j:log4j-to-slf4j` have the same version (2.25.5 at the time of writing). This closes STORY-002 review NIT 18.
- No web runtime: `build.gradle.kts` declares none of `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-jdbc-test`, and `grep -E '^org\.apache\.tomcat\.embed:tomcat-embed-core:.*[Rr]untimeClasspath' backend/gradle.lockfile` finds nothing (no `tomcat-embed-core` in `runtimeClasspath` or any `*RuntimeClasspath` configuration).
- `backend/gradle/verification-metadata.xml` was regenerated **from scratch** (the old file moved aside, not appended) with `./gradlew --write-verification-metadata sha256 checkFast archTest integrationTest migrationTest pitest bootJar`. If `pitest` fails on JUnit Platform 6, the same command is run with `--continue`, so that PIT's configuration is still resolved and recorded. The file is committed, and a clean `./gradlew checkFast` with the committed metadata reports no verification failure.
- `net.jqwik:jqwik` is not declared in `build.gradle.kts` and does not appear in `gradle.lockfile`. No other property-testing library is added.
- These STORY-002 overrides are **removed** from `build.gradle.kts`, because the 4.1.2 BOM or plugin now ships fixed versions:
  1. `platform("com.fasterxml.jackson:jackson-bom:2.21.8")`;
  2. the constraint `org.postgresql:postgresql:42.7.14`;
  3. the constraint `org.apache.logging.log4j:log4j-api:2.25.5`;
  4. the test constraint `org.apache.commons:commons-compress:1.28.0`;
  5. `platform("org.junit:junit-bom:5.14.4")`;
  6. the `buildscript` constraints `org.apache.httpcomponents.client5:httpclient5:5.6.5`, `org.apache.httpcomponents.core5:httpcore5:5.4.4` and `httpcore5-h2:5.4.4`.

  The `buildscript` platform `jackson-bom 2.21.8` and the `buildscript` constraint `commons-lang3 3.18.0` are removed too, unless `./gradlew buildEnvironment` would then resolve a lower version. If one is kept, its comment names the plugin that still pulls the old version.
- `osv-scanner scan source -r --no-ignore backend` reports **0 findings** (exit 0, "No issues found").
- ArchUnit still runs under the JUnit Platform. Summed over `backend/build/test-results/archTest/*.xml`, the totals are exactly **tests=9, skipped=0, failures=0, errors=0**: one test per `@ArchTest` field of `ArchitectureRulesTest` (9 fields, rules 1-7 from STORY-002). Rule 1 also lists `"tools.jackson.."` (Jackson 3 package) among the packages banned in `eg.masroof.domain..`. `MoneyRuleProbeTest` passes in `test`. `make selftest` row G4 is `PROVEN`. Note: STORY-143 adds rule 8 (`JDBC_ONLY_IN_OUTBOUND_ADAPTERS`) and then expects tests=10.
- `V1BaselineMigrationTest` uses `org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.x, non-generic) with exactly the image `postgres:16.15-alpine3.24@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36`. It passes in `integrationTest` and `migrationTest` and still finds exactly one `flyway_schema_history` row, `('1', true, 'V1__baseline.sql')`.
- `-Werror`, Error Prone and NullAway are unchanged in strength. `grep -rnE 'SuppressWarnings\(.*NullAway' backend/src` finds nothing, and `NullAway:AnnotatedPackages` is still `eg.masroof`. New findings caused by Spring's JSpecify annotations are fixed in code.
- Docs say what the build does. `backend/CLAUDE.md` reads "Spring Boot 4.x (ADR-0004)" where it said "Spring Boot 3.x", "JUnit 6" where it said "JUnit 5", and "property-testing library: pending OWNER-010 (STORY-004)" where it said "jqwik" (both mentions); `grep -c jqwik backend/CLAUDE.md` is 0. The implementer updates `backend/CLAUDE.md` in this story's diff. `.claude/rules/money.md` gets the same jqwik replacement **via the orchestrator** (the retro-facilitator owns `.claude/rules/` edits); `grep -c jqwik .claude/rules/money.md` is 0 before the story is DONE.
- `make check-fast MODULE=backend` is run twice and the **second (warm) run exits 0 in < 180 s** (S8 budget, REQ-OPS-023 probe case 1). `make check` exits 0. `make security` is GREEN.

Unhappy path (ArchUnit on JUnit Platform 6): **Given** the ArchUnit JUnit engine reports 0 tests or an engine error under the Boot 4.1.2 platform **When** the implementer applies ADR-0004's documented workaround (the rules run from a Jupiter class tagged `arch`, one `@Test` per `@ArchTest` field calling `RULE.check(...)` on main classes imported with `DoNotIncludeTests`) **Then** the class has exactly 9 `@Test` methods, the `archTest` totals are still tests=9, skipped=0, failures=0, errors=0, G4 is still `PROVEN`, the rule definitions are unchanged, and the story's diff adds a `docs/DECISIONS.md` line naming the workaround and the ArchUnit version.
Unhappy path (PIT on JUnit Platform 6): **Given** `./gradlew pitest` fails to launch on Platform 6 **Then** the story is not blocked, because PIT runs only in `make check-full` and no mutable code exists yet. Verification metadata is still regenerated with `--continue` (see above). The diff adds a `docs/DECISIONS.md` line with the error's first line and states that STORY-004 settles it. `make check` (which does not run PIT) stays green.
Unhappy path (new advisory): **Given** `osv-scanner` reports one or more findings after the overrides are removed **When** the implementer fixes them **Then** for each finding exactly one constraint is added, pinning the **lowest released version that fixes it**, with a comment naming the advisory id, and the scan reports 0 findings again. **Given** a finding has no released fixed version **Then** the story ends BLOCKED through the fallback below. Ignore files (`osv-scanner.toml` or similar) and removing `--no-ignore` are not allowed.
Unhappy path (stale verification metadata): **Given** an old `verification-metadata.xml` entry is kept for an artefact that is no longer resolved **Then** this counts as a failure. Mechanical checks on the committed file, each must find nothing:
- a `<component>` in a group starting with `org.junit` with `version="5.14.4"` that holds a `.jar` artifact (a pom/module-only BOM entry still imported by another resolved pom, such as `org.junit:junit-bom:5.14.4` via `org.apache.commons:commons-parent`, is resolved metadata, not stale; the byte-identical regeneration below proves it);
- a `<component>` in group `org.testcontainers` with `version="1.21.4"`;
- a `<component>` in group `com.tngtech.archunit` with `version="1.4.1"`;
- a `<component>` in group `net.jqwik`;
- any `version="3.5.16"` (`grep -c 'version="3.5.16"'` is 0).

  And: moving the committed file aside and running the same `--write-verification-metadata` command into an empty file produces a byte-identical file (`cmp` exits 0).
Unhappy path (fallback, never a silent downgrade): **Given** any gate above cannot be made green on 4.1.2 with released versions within this story, or the upgrade commit has **> 60 added+changed hand-written lines per `git diff --numstat`** (a changed line counts in the added column; `gradle.lockfile`, `buildscript-gradle.lockfile` and `verification-metadata.xml` excluded) **Then** the story ends **BLOCKED**, with the failing command (or the numstat output), its exit code and the first error lines in the status log and the handoff. The **OWNER** decides the next step through `docs/ESCALATIONS.md` (the orchestrator adds the `docs/DECISIONS.md` line amending ADR-0004's fallback). The plugin is not changed back to 3.5.x in this story, and no gate is relaxed.

### REQ-OPS-143 — Prod datasource password guard checks the effective password
**Given** profile `prod` is active **When** the application context starts **Then** `ProdDatabasePasswordGuard` decides on the **effective** `spring.datasource.password` (as resolved by the Spring `Environment`), not on the `POSTGRES_PASSWORD` variable alone:
- prod with `SPRING_DATASOURCE_PASSWORD=s3cret-test` set and **no** `POSTGRES_PASSWORD` -> the guard passes (start-up continues to the datasource).
- prod with `POSTGRES_PASSWORD=s3cret-test` and no `SPRING_DATASOURCE_PASSWORD` -> the guard passes (`application.yml` maps it).

Unhappy path (missing or blank): **Given** profile `prod` **When** `spring.datasource.password` resolves to null, `""` or whitespace only (for example `"   "`), or still contains an unresolved `${` placeholder (for example a literal `${POSTGRES_PASSWORD}`), or the property cannot be resolved at all **Then** start-up fails with an `IllegalStateException` in the cause chain. Its message names `spring.datasource.password` and `POSTGRES_PASSWORD`, never the value.
Unhappy path (empty override): **Given** profile `prod`, `POSTGRES_PASSWORD=s3cret-test` **and** `SPRING_DATASOURCE_PASSWORD=` (set to empty, which overrides the yml mapping) **When** the context starts **Then** start-up fails with the guard's message, as above.
Unhappy path (no secret in output): **Given** any of the failing cases above, with a password-like value present (for example `POSTGRES_PASSWORD=leak-canary-9f3`) **Then** no message in the exception chain (every `getMessage()` from the startup failure down through `getCause()`) contains `leak-canary-9f3`. When resolving the property itself throws, the guard does not keep the resolver's exception as the cause. This check covers the exception chain only. Log output is not asserted here; the logging rules in `backend/CLAUDE.md` stay binding.

**Given** a test using `ApplicationContextRunner` with the guard as its user configuration (no datasource, no Docker) **Then** these cases hold, each tagged `@Tag("REQ-OPS-143")`. "Fails" means `context.getStartupFailure()` is non-null, with an `IllegalStateException` in its cause chain whose message contains `spring.datasource.password`:
- (a) `spring.profiles.active=prod` and no password -> fails;
- (b) prod + `spring.datasource.password=s3cret-test` -> the context starts and has exactly one `ProdDatabasePasswordGuard` bean;
- (c) no active profile and no password -> the context starts and has no `ProdDatabasePasswordGuard` bean;
- (d) `spring.profiles.active=PROD` (upper case) and no password -> the context starts and has **no** guard bean. This pins the accepted behaviour (STORY-002 verify NIT): Spring profile names are case-sensitive;
- (e) prod + `spring.datasource.password` blank, once as `""` and once as `"   "` -> fails;
- (f) prod + `spring.datasource.password=${POSTGRES_PASSWORD}` with `POSTGRES_PASSWORD` not set (unresolved placeholder) -> fails;
- (g) prod + `spring.datasource.password=${POSTGRES_PASSWORD}` (the `application.yml`-style mapping) with `POSTGRES_PASSWORD=s3cret-test` -> the context starts and has exactly one guard bean;
- (h) prod, with a `SystemEnvironmentPropertySource` holding `SPRING_DATASOURCE_PASSWORD=""` and `POSTGRES_PASSWORD=leak-canary-9f3`, plus the yml-style mapping from (g) in a lower-precedence source (the env source is added via `addFirst`, the mapping via `addLast`, mirroring Boot's system-environment-above-application-config order) -> fails, and no message in the chain contains `leak-canary-9f3`;
- (i) prod + `spring.datasource.password=leak-canary-9f3${MISSING_VAR}` -> fails, and no message in the chain contains `leak-canary-9f3` (the resolver's exception is not kept as the cause).

**Given** no active profile, the test sets neither `spring.datasource.password` nor `SPRING_DATASOURCE_PASSWORD` (the default-profile mapping `${POSTGRES_PASSWORD:}` applies, whatever it resolves to), and `spring.datasource.url=jdbc:postgresql://127.0.0.1:1/x` (nothing listens on port 1; no Docker) **When** a test tagged `@Tag("REQ-OPS-143")` starts the `@SpringBootApplication` class with `new SpringApplicationBuilder(MasroofApplication.class).run("--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/x")` inside `assertThrows` (the test class has no `@SpringBootTest`) **Then** start-up fails, the cause chain contains an `org.postgresql.util.PSQLException` or `java.net.ConnectException`, and no message in the chain contains both `spring.datasource.password` and `POSTGRES_PASSWORD`. The guard never blocks local or dev start-up; the failure comes from the datasource connection attempt.

**Given** `backend/README.md` **Then** the deployment line states that the profile must be lowercase `prod`, that `PROD`/`Prod` do **not** activate the guard (profile names are case-sensitive), and that `SPRING_DATASOURCE_PASSWORD` alone is accepted.
The existing `ProdDatabasePasswordGuardTest` unit cases switch to the effective property and gain the unresolved-placeholder and blank cases above. Existing assertions are not weakened.

## UX states (UI stories only)
N/A — no UI.

## Contract impact
None (no endpoint; `contracts/openapi.yaml` is untouched).

## Data / migration impact
None. V1 is unchanged and immutable. Only the Testcontainers class and image pin used by `V1BaselineMigrationTest` change, as ADR-0004 lists.

## Security / privacy / LLM-safety impact
- **Supply chain (build boundary: Maven Central / Plugin Portal -> build):** a framework major BOM change, five-plus overrides removed, and lockfiles and verification metadata regenerated. Mitigated by the 0-finding `osv-scanner --no-ignore` criterion (one lowest-fixed-version constraint per new finding, no ignore file), the from-scratch verification-metadata regeneration with mechanical stale-entry checks and a byte-identical re-run, exact pins, and `gradle/actions/wrapper-validation` in CI. **security-reviewer is required** at stage 7.
- **Secrets (B7 backend -> Postgres):** the guard closes the gap where `SPRING_DATASOURCE_PASSWORD=` overrode a set `POSTGRES_PASSWORD` and prod started with an empty password (STORY-002 review NIT 16). No exception message contains a value (canary cases h and i). The case-sensitive profile name is an accepted and documented risk: compose (STORY-143) hard-codes lowercase `prod`.
- No runtime listener (no webmvc, no Tomcat on the runtime classpath), no household data, no LLM.

## Eval impact
None (no parsing).

## Design notes (architect)
The plan is in [ADR-0004](../../adr/0004-spring-boot-4-1-upgrade.md) ("Changes to `backend/build.gradle.kts`", "Lockfile, verification metadata and SCA refresh", "Risk and fallback"). It was written for STORY-143's commits 1-2, which this story now carries, with the amendments below:
1. `build(backend): Spring Boot 4.1.2 [STORY-144]`. From ADR-0004's table: plugin 4.1.2; `spring-boot-starter-flyway`; jspecify from the BOM; Testcontainers 2 coordinates; ArchUnit 1.5.1; jqwik removed; overrides dropped. **Not** from the table: `spring-boot-starter-webmvc`, `-webmvc-test` and `-jdbc-test` (they land with STORY-143; `spring-boot-starter-test` stays). Then the refresh steps 1-4 in order (step 2 with `--continue` if `pitest` fails), the V1-test Testcontainers 2 change and image pin, ArchUnit rule 1 plus `tools.jackson..`, and the `backend/CLAUDE.md` wording (Spring Boot 4.x, JUnit 6, property-testing library pending OWNER-010).
2. `fix(backend): prod password guard checks the effective datasource password [STORY-144]`: NITs 16 and 17, as specified in REQ-OPS-143. NIT 18 is closed by commit 1 (lockfile log4j check).

Gradle 8.14.6 meets Boot 4.1.2's requirement (8.14+ or 9.x), so the wrapper is unchanged. ADR-0004's validation says "`archTest` reports 8 rules". Rule 8 (`JDBC_ONLY_IN_OUTBOUND_ADAPTERS`) belongs to STORY-143, so this story expects exactly 9 tests (the 9 `@ArchTest` fields of rules 1-7) and STORY-143 expects 10.

Fallback amendment: ADR-0004 says "revert and fall back to 3.5.16". For this story the implementer instead stops at BLOCKED with evidence and the OWNER decides via `docs/ESCALATIONS.md`. The orchestrator records that amendment in `docs/DECISIONS.md`.

## Dependencies
STORY-002 (DONE: gated module, lockfiles, verification metadata, guard). Unblocks STORY-143 (`/health` on 4.1.2, plus the webmvc starters and rule 8) and through it STORY-141, STORY-142 and STORY-006.

## Size estimate (hand-written lines incl. tests; regenerated lockfiles and verification metadata excluded)
Commit 1 (upgrade; counted against the 60-line fallback trigger): build script delta ≈ 10 added/changed (plugin, flyway starter, jspecify, two Testcontainers coordinates, ArchUnit, kept-constraint comments; removals are not counted), V1 test Testcontainers 2 change and pin ≈ 4, ArchUnit rule 1 ≈ 1, NullAway fixes ≈ 10, `backend/CLAUDE.md` ≈ 3, DECISIONS lines ≈ 2. **≈ 30 without the ArchUnit workaround; ≈ 55 with it** (9 `@Test` methods plus importer ≈ 25), which leaves little headroom: new-advisory constraints (≈ 2 each) can push it past 60 and trigger the fallback.
Commit 2 (guard): guard change ≈ 8, guard unit-test updates ≈ 15, `ProdDatabasePasswordGuardContextTest` cases (a)-(i) ≈ 70, full-context no-guard-message test ≈ 20, README ≈ 2, DECISIONS (PIT) ≈ 1. **≈ 115.**
**Total ≈ 145-170.** Sized M: just over the S bound, with headroom for unknown NullAway/JSpecify fixes.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 170 worst case)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1-2 spec | product-analyst | READY (split from STORY-143) |
| 2026-10-24 | 2 spec-review | spec-reviewer | CHANGES_REQUESTED (loop 1) -> APPROVE (loop 2, 3 MINOR wording applied by orchestrator) |
| 2026-10-24 | 3 design | architect | covered by ADR-0004 (STORY-143 design) |
| 2026-10-24 | 1-2 spec | product-analyst | READY — split out of STORY-143 (size ≈ 480): Boot 4.1.2 upgrade (ADR-0004) + STORY-002 review NITs 16-18; REQ-OPS-142/143; ≈ 120 lines |
| 2026-10-24 | 2 spec fix loop 1 | product-analyst | READY — spec-reviewer loop-1 findings applied with orchestrator decisions: guard context cases (e)-(i) + full-context no-guard-message test; secret check limited to exception chain; archTest totals 9/0/0/0; V1 image pin; no webmvc/Tomcat (deferred to STORY-143); metadata over `... pitest bootJar` with `--continue`; stale-metadata greps + byte-identical re-run; one lowest-fix constraint per advisory; fallback = BLOCKED + OWNER via ESCALATIONS, > 60 numstat lines; CLAUDE.md/money.md wording; ADR-0004 moved to body links; size re-estimated ≈ 145-170 |
