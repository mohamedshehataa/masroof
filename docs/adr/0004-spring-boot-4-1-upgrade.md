# ADR-0004: Move the backend to Spring Boot 4.1.2 before the first endpoint ships

- **Status:** accepted (decision recorded in `docs/DECISIONS.md` 2026-10-23; this ADR fixes the version and the plan). Validation is pending until the STORY-143 implementation.
- **Date:** 2026-10-24
- **Story:** STORY-143 (supersedes ADR-0003)
- **Deciders:** architect, with the orchestrator/owner decision of 2026-10-23, then code-reviewer and security-reviewer at stage 7

## Context and problem statement
ADR-0003 kept STORY-002 on Spring Boot 3.5.16 and asked for a decision before STORY-143 adds the first HTTP endpoint.
`docs/DECISIONS.md` (2026-10-23) records that decision: the backend moves to 4.1.x in the STORY-143 design.
This ADR answers the remaining questions. Which 4.1.x? What changes in `backend/build.gradle.kts`? Which STORY-002
overrides can go? How are the lockfiles and verification metadata refreshed? What happens if a gate tool breaks?

Facts I checked on 2026-10-24:
- **Spring generations API** (`api.spring.io/projects/spring-boot/generations`):

  | Line | OSS support ends | Commercial support ends |
  |---|---|---|
  | 3.5.x | 2026-06-30 (ended) | 2032-06-30 |
  | 4.0.x | 2026-12-31 | 2027-12-31 |
  | 4.1.x | 2027-07-31 | 2028-07-31 |
  | 4.2.x | 2027-12-31 | 2028-12-31 (only 4.2.0-RC1 so far) |

  The releases API marks **4.1.2** as `current` (GA). It went to Maven Central on 2026-10-22.
- **Spring Boot 4.1.2 BOM** (`spring-boot-dependencies-4.1.2.pom`) manages: Spring Framework 7.0.10, Jackson 3.1.7 (and
  Jackson 2 BOM 2.21.7), Flyway 12.4.0, HikariCP 7.0.2, PostgreSQL JDBC 42.7.14, Log4j 2.25.5 (as `log4j-bom`, so
  `log4j-api` and `log4j-to-slf4j` stay aligned), Testcontainers 2.0.5, JUnit Jupiter 6.0.3, AssertJ 3.27.7,
  Mockito 5.23.0, Tomcat 11.0.27, Logback 1.5.38 and JSpecify 1.0.1.
- **OSV** (`api.osv.dev/v1/querybatch`) lists no advisories for any of these versions. As a control, the same query
  for pgjdbc 42.7.11, jackson-core 2.21.4 and log4j-api 2.24.3 returns the GHSAs that STORY-002 overrode.
- **System requirements:** Java 17 to 27, and Gradle 8.x (8.14 or later) or 9.x. Our Gradle 8.14.6 wrapper and JDK 21 qualify.
- **Boot 4.0 migration guide:** starters are modular now. Flyway needs `spring-boot-starter-flyway`, and `flyway-core`
  alone no longer triggers auto-configuration. `spring-boot-starter-web` became `spring-boot-starter-webmvc`. The test
  starters are per technology (`spring-boot-starter-webmvc-test`, `-jdbc-test`, `-flyway-test`), and each one brings
  `spring-boot-starter-test`. `@SpringBootTest` no longer configures MockMvc on its own (`@AutoConfigureMockMvc`
  must be added). `@MockBean` was removed in favour of `@MockitoBean`. Jackson 3 (`tools.jackson.*`) is the default.
  Boot ships JSpecify annotations, and the guide warns that null checkers may then fail compilation.
- **Class locations in 4.1.2:** `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` and `AutoConfigureMockMvc`
  are in `spring-boot-webmvc-test`. `org.springframework.boot.info.BuildProperties` is in `spring-boot`, and
  `ProjectInfoAutoConfiguration` is in `spring-boot-autoconfigure`. In the Gradle plugin, `BuildInfoProperties`
  exposes `time: Property<String>` and `additional: MapProperty<String, Object>`.
- **Testcontainers 2.0.5:** the artifacts are now `org.testcontainers:testcontainers-postgresql` and
  `testcontainers-junit-jupiter`. The new non-generic class is `org.testcontainers.postgresql.PostgreSQLContainer`
  (the old one is still present). `org.testcontainers.junit.jupiter.{Testcontainers,Container}` keep their package.
  It depends on commons-compress 1.28.0, which is the version STORY-002 had to force.
- **JUnit Platform consumers:** jqwik 1.10.2 (current), ArchUnit 1.5.1 (current, 2026-09-25) and
  pitest-junit5-plugin 1.2.3 are all built on JUnit Platform **1.x** (1.14.4 or older). Boot 4.1.2 aligns the platform
  to **6.0.3**. Whether they run on Platform 6 is **not verified**. jqwik's 1.10.1 release notes say it is "probably
  the last release using JUnit Platform version 1.x", and that later releases "if ever realised" will need Platform 6.
- **jqwik 1.10 usage clause:** since 1.10, jqwik's documentation contains an "Anti-AI Usage Clause". It says the
  project is not meant to be used by AI coding agents, and every engine run prints an extra line addressed to agents
  on stdout. Masroof is built by agents that read test output. Even if the line is ignored, using jqwik here goes
  against the maintainer's stated wish.

## Decision drivers
- OSS security fixes for the first internet-facing service (B1). 3.5.x gets none after 2026-06-30.
- The cheapest moment to migrate is now: one migration, one test class, no endpoints, no generated code.
- The S9 gates must stay real: Error Prone + NullAway `-Werror`, ArchUnit rules 1-7 with the G4 probe, Testcontainers,
  JaCoCo, PIT, lockfiles + verification metadata, and osv-scanner at 0 findings.
- Few lines: STORY-143 is capped at about 400 hand-written lines, so the upgrade must stay a small diff.

## Considered options
1. **Spring Boot 4.1.2** (current GA; OSS support until 2027-07-31).
2. **Spring Boot 4.0.9** (OSS support until 2026-12-31). The migration is the same, and support ends in two months. Dominated by option 1.
3. **Spring Boot 4.2.0-RC1** (GA expected 2026-11). It is a pre-release, and PROMPT/S9 requires released versions. Rejected; move to 4.2 in a later patch story.
4. **Stay on Spring Boot 3.5.16** with manual CVE overrides (the ADR-0003 fallback). This is free only until the next
   unpatched Spring CVE. Kept as the **fallback** (see below), not chosen.

For the jqwik question:
- a. Keep jqwik 1.10.2 on Platform 6. Compatibility is unverified, and the maintainer asks agents not to use it.
- b. Pin jqwik 1.9.3 (released before the clause). It is also built on Platform 1.x (1.13) and gets no further releases on that line.
- c. **Remove jqwik now.** No test uses it yet: property tests start with Money in STORY-004. The STORY-004 design
  then picks the property-testing approach in its own ADR (owner checkpoint). Candidates there: jqwik 1.9.3 with the
  Platform risk, JUnit 6 `@ParameterizedTest`/`@RepeatedTest` with seeded `RandomGenerator` generators and printed
  seeds (no shrinking), or Jazzer `@FuzzTest`.

## Decision outcome
Chosen: **option 1, Spring Boot 4.1.2**, applied as the first commit of STORY-143 (`build(backend): Spring Boot 4.1.2
[STORY-143]`), before any endpoint code. Also chosen: **jqwik option c (remove)**, with the replacement decided in
STORY-004. This option was picked because it is the only one with OSS support beyond 2026 that does not depend on a
pre-release. The BOM also ships fixed versions of every library that STORY-002 had to override.

### Changes to `backend/build.gradle.kts` (exact)
| Area | From | To |
|---|---|---|
| plugin `org.springframework.boot` | `3.5.16` | `4.1.2` |
| `buildscript` constraints | jackson-bom 2.21.8, commons-lang3 3.18.0, httpclient5 5.6.5, httpcore5/-h2 5.4.4 | Remove **httpclient5/httpcore5/httpcore5-h2**: the 4.1.2 plugin depends on httpclient5 5.6.5, which needs httpcore5 5.4.4. Remove **jackson-bom** and **commons-lang3** too, but only if `./gradlew buildEnvironment` then resolves no version below 2.21.8 / 3.18.0. Otherwise keep that line and update its comment to name the plugin that pulls it. The Boot plugin itself now uses Jackson 3. |
| `platform("com.fasterxml.jackson:jackson-bom:2.21.8")` | present | **remove**. Flyway 12 uses Jackson 3 (3.1.7), and the BOM manages Jackson 2 at 2.21.7 (no advisories). |
| constraint `org.postgresql:postgresql:42.7.14` | present | **remove**. The BOM manages 42.7.14. |
| constraint `log4j-api:2.25.5` | present | **remove**. The BOM imports `log4j-bom` 2.25.5, so `log4j-api` and `log4j-to-slf4j` are aligned. This closes STORY-002 review NIT 18 without an extra platform. |
| constraint `commons-compress:1.28.0` (test) | present | **remove**. Testcontainers 2.0.5 depends on 1.28.0. |
| `platform("org.junit:junit-bom:5.14.4")` | present | **remove**. The BOM manages JUnit 6.0.3. |
| `org.flywaydb:flyway-core` | implementation | `org.springframework.boot:spring-boot-starter-flyway`. Keep `runtimeOnly("org.flywaydb:flyway-database-postgresql")`. |
| web | none | `implementation("org.springframework.boot:spring-boot-starter-webmvc")` (Tomcat 11, Jackson 3). |
| `org.jspecify:jspecify:1.0.0` | pinned | `org.jspecify:jspecify` (BOM 1.0.1) |
| `spring-boot-starter-test` | testImplementation | `spring-boot-starter-webmvc-test` and `spring-boot-starter-jdbc-test`. Both bring `spring-boot-starter-test`. |
| `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql` | 1.21.4 | `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-postgresql` (BOM 2.0.5) |
| `com.tngtech.archunit:archunit-junit5` | 1.4.1 | `1.5.1` (also adds Java 27 class files and catches `java.lang.IO` in rule 4) |
| `net.jqwik:jqwik:1.10.2` | testImplementation | **remove** (option c) |
| `spring-boot-testcontainers`, Error Prone 2.51.0, NullAway 0.14.3, Spotless 8.10.4 / gjf 1.36.0, Checkstyle 14.3.0, SpotBugs plugin 6.5.13, PIT plugin 1.19.0 / junit5 plugin 1.2.3, JaCoCo 0.8.13, Gradle 8.14.6 | | unchanged |

Test-source changes the upgrade forces (all in existing files):
- `V1BaselineMigrationTest` uses `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic). The image becomes
  `postgres:16.15-alpine3.24@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36`, the same pin as compose.
- `ArchitectureRulesTest` rule 1 also bans `"tools.jackson.."`, as ADR-0003's validation requires.
- NullAway now sees Spring's `@NullMarked` packages. A call whose result Spring declares `@Nullable` (for example
  `JdbcTemplate.queryForObject`, `Environment.getProperty`, `BuildProperties.getVersion`) fails `compileJava` or
  `compileTestJava` if it is dereferenced unchecked. Fix each one with an explicit check or
  `Objects.requireNonNull(..., "<what>")`. Never suppress or relax the check.
- If a new jar triggers a javac `-Xlint` warning (a missing annotation class, a deprecation), add the annotation jar
  as `compileOnly`/`testCompileOnly` or stop using the deprecated API. `-Werror` stays.

### Lockfile, verification metadata and SCA refresh (in this order, in `backend/`)
1. `./gradlew dependencies buildEnvironment --write-locks` rewrites `gradle.lockfile` and `buildscript-gradle.lockfile`.
   Check: `grep -E "log4j-(api|to-slf4j)" gradle.lockfile` shows the same 2.25.5 for both.
   Check: `grep -c "3\.5\.16" *.lockfile` is 0.
2. Move the old `gradle/verification-metadata.xml` aside and regenerate it so that the stale Boot 3.5 entries go:
   `./gradlew --write-verification-metadata sha256 checkFast archTest integrationTest migrationTest pitest bootJar`.
   `bootJar` and `bootBuildInfo` are new in this story, and the Docker build runs them. Then diff the new file
   against the old one: every added component must be in the new BOM graph.
3. `osv-scanner scan source -r --no-ignore backend` must report 0 findings. If it reports some, add back only the
   override that fixes the finding, with a reason comment.
4. `make check-fast MODULE=backend` (twice; the second run must take < 3 min, REQ-OPS-023 probe) and `make check` must be green.

### Risk and fallback
- **ArchUnit engine on JUnit Platform 6.** If `archTest` reports zero tests or an engine error, keep Boot 4.1.2 and
  run the rules from a Jupiter class instead: `@Tag("arch")`, with one `@Test` per rule calling
  `RULE.check(new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("eg.masroof"))`.
  That is about 15 lines. Rules and probe are unchanged (the probe already calls `MONEY_RULE.check` directly).
  `make selftest` G4 must still be PROVEN. Record it in DECISIONS.
- **Error Prone / NullAway.** Both are javac plugins and do not depend on the framework version, so the risk is new
  NullAway findings, not incompatibility. Fix them in code.
- **PIT on Platform 6.** It runs only in `make check-full`, and no mutable code exists yet. If `pitest` fails to
  launch, record that in DECISIONS and settle it in the STORY-004 design (first mutable code). The upgrade is not blocked.
- **Fallback to 3.5.16.** If, after the steps above, a gate cannot be made green with released versions, or the upgrade
  commit grows beyond about 60 hand-written lines: revert that commit, set this ADR to "rejected (fallback)", add
  "Accepted risk (ADR-0003): Spring Boot 3.5.16 past OSS EOL; Spring advisories tracked manually" to THREAT_MODEL §5,
  add a DECISIONS line, and split the upgrade into its own story before STORY-006. The `/health` design in STORY-143
  works on either line. It uses only `spring-boot-starter-web(mvc)`, `BuildProperties`, `ProblemDetail` and HikariCP properties.

### Consequences
- Good: OSS-supported framework until 2027-07-31. Five overrides fewer, so less drift to maintain.
  Log4j alignment comes from the BOM.
- Good: JSpecify-annotated Spring APIs make NullAway stronger at the framework boundary.
- Bad: three test-engine consumers (ArchUnit, PIT, and formerly jqwik) are built against Platform 1.x. They have been
  checked only by running them, and the fallback for ArchUnit is spelled out above.
- Bad: S9's "jqwik" is not used for now. Property-based testing is deferred to the STORY-004 ADR, so the owner
  must make that decision before Money ships.
- Bad: `backend/CLAUDE.md` and PROMPT §2 say "Spring Boot 3.x". `backend/CLAUDE.md` changes to "Spring Boot 4.x
  (ADR-0004)" in this story. PROMPT §2 is satisfied by the DECISIONS line ("note any change in DECISIONS.md").
- Neutral: 4.2.0 GA is due around 2026-11-30. A patch-level bump within 4.1.x is Dependabot's job, and moving to 4.2
  is a later decision.

## Validation
- `make check-fast MODULE=backend`, `make check` and `make selftest` (G4 PROVEN) are green on 4.1.2.
- `grep -E "spring-boot:4\.1\.2" backend/gradle.lockfile` matches, and no `3.5.16` remains in either lockfile.
- `osv-scanner scan source -r --no-ignore backend` reports 0 findings.
- `ArchitectureRulesTest` rule 1 lists `tools.jackson..`. `archTest` reports 8 rules executed (7 + the new JDBC-confinement rule from STORY-143).

## Links
- ADR-0002 (toolchain), ADR-0003 (superseded by this ADR), STORY-143 design notes, STORY-002 review NITs 16-18.
- https://api.spring.io/projects/spring-boot/generations, https://docs.spring.io/spring-boot/system-requirements.html,
  https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide, https://jqwik.net/docs/1.10.1/user-guide.html
