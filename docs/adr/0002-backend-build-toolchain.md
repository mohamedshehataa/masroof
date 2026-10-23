# ADR-0002: Backend build toolchain — Gradle 8.14 Kotlin DSL, tag-split test tasks, Error Prone + NullAway, Google style

- **Status:** accepted
- **Date:** 2026-10-23
- **Story:** STORY-002
- **Deciders:** architect (+ code-reviewer at stage 7)

## Context and problem statement
STORY-002 creates `backend/` and must make the S9 backend gates real: `./gradlew checkFast` (Spotless, Checkstyle,
`-Werror` with Error Prone + NullAway, SpotBugs, unit tests), plus `integrationTest`, `archTest`, and the JaCoCo
report that `scripts/quality/coverage-gate.py` reads. `backend/CLAUDE.md` fixes the task names and the layout
(one `src/test/java` with the tag `integration`). The scripts that call Gradle (`scripts/make/check-fast.sh`,
`check.sh`, `check-full.sh`, `module.sh`, `scripts/sdlc/selftest.sh`) already exist and must not change shape.
There is no system Gradle on the dev machine (Windows 11, Git Bash); CI is ubuntu-latest with Temurin 21.

Question: which build tool versions, plugins and task wiring give a deterministic, fast (< 3 min warm) gate that
the SDLC scripts can call, while staying within the story's ~400-line budget?

## Decision drivers
- Task names and semantics are binding (`backend/CLAUDE.md`): `checkFast`, `integrationTest`, `archTest`, `pitest`, `migrationTest`.
- S9 tools are fixed: Error Prone + NullAway, Spotless, Checkstyle (Google style, line length 120), SpotBugs (no high-priority findings), JaCoCo, PIT, jqwik, Testcontainers, ArchUnit.
- The gate's failures must name the file and the task (REQ-OPS-023 unhappy paths), including under `./gradlew -q`.
- Identical behaviour on Windows (Git Bash) and Linux CI; no global tool installs beyond a JDK 21.
- Few moving parts: one module, one build file; no version indirection that only one consumer uses.

## Considered options
**Build tool / version**
1. Gradle 8.14.6 (latest 8.x patch, 2026-10-08) with Kotlin DSL via the wrapper.
2. Gradle 9.x (current 9.8.2).
3. Maven — rejected by PROMPT §2 (Gradle Kotlin DSL is the stack).

**Dependency management**
1. Gradle-native `platform(SpringBootPlugin.BOM_COORDINATES)` plus `platform(junit-bom)`.
2. `io.spring.dependency-management` plugin (Maven-style overriding).

**Test separation**
1. One `test` source set; tasks select by JUnit Platform tag (`integration`, `arch`, `migration`).
2. Separate source sets / JVM Test Suites (`src/integrationTest`, `src/archTest`).

**Null safety**
1. Error Prone + NullAway with `NullAway:AnnotatedPackages=eg.masroof`, JSpecify `@Nullable`.
2. NullAway `onlyNullMarked` with `@NullMarked` package-info in every package.
3. Checker Framework nullness checker.

**Formatting / style**
1. Spotless + google-java-format (2-space) + Checkstyle `google_checks.xml` vendored with `LineLength` 120.
2. Palantir-java-format (4-space, 120) + a custom Checkstyle config.
3. Checkstyle config loaded from the Checkstyle jar (no vendoring).

**Version pinning**
1. Versions inline in `build.gradle.kts` (plugins block and dependency strings).
2. Gradle version catalog `gradle/libs.versions.toml`.

## Decision outcome
Chosen: **Gradle 8.14.6 (wrapper, Kotlin DSL) / Spring Boot 3.5.16 (see ADR-0003) / native platforms / tag-split
test tasks over one source set / Error Prone + NullAway with AnnotatedPackages / Spotless google-java-format +
vendored google_checks.xml (120) / inline pinned versions**, because they match the binding layout and scripts
with the fewest files and give a deterministic failure per probe.

Pinned versions (verified on Maven Central / Gradle Plugin Portal / services.gradle.org on 2026-10-23):

| What | Version |
|---|---|
| Gradle wrapper (bin, with `distributionSha256Sum`) | 8.14.6 |
| `org.springframework.boot` plugin + BOM | 3.5.16 (manages Flyway 11.7.2, PostgreSQL JDBC 42.7.11, Testcontainers 1.21.4) |
| `net.ltgt.errorprone` plugin / `error_prone_core` | 5.1.1 / 2.51.0 (needs JDK 21 to run, which is our JDK) |
| `com.uber.nullaway:nullaway` | 0.14.3 |
| `org.jspecify:jspecify` | 1.0.0 |
| `com.diffplug.spotless` plugin / google-java-format | 8.10.4 / 1.37.0 |
| Checkstyle `toolVersion` (core Gradle plugin) | 14.3.0 — *verify at implementation* that Gradle 8.14's Checkstyle task runs it; if not, use the newest version it runs and vendor that tag's `google_checks.xml` |
| `com.github.spotbugs` plugin | 6.5.13 (bundled SpotBugs version) |
| JaCoCo `toolVersion` | 0.8.13 |
| `info.solidsoft.pitest` plugin / `pitest-junit5-plugin` | 1.19.0 / 1.2.3 |
| `com.tngtech.archunit:archunit-junit5` | 1.4.1 |
| `net.jqwik:jqwik` | 1.10.2 (built on JUnit Platform 1.14.4) |
| `org.junit:junit-bom` | 5.14.4 — overrides Boot 3.5.16's JUnit 5.12.2 so jqwik's platform 1.14 and Jupiter stay aligned |

Wiring (the story's design notes hold the full sketch):
- `checkFast` = `dependsOn(spotlessCheck, checkstyleMain, compileJava, compileTestJava, spotbugsMain, test)`;
  `test` excludes tags `integration` and `arch` and is `finalizedBy(jacocoTestReport)`.
- `integrationTest` (tag `integration`), `archTest` (tag `arch`, JaCoCo agent disabled), `migrationTest` (tag `migration`)
  are `Test` tasks over the `test` source set's classes and runtime classpath.
- All `JavaCompile`: `-Xlint:all,-processing -Werror`, Error Prone `error("NullAway", "RemoveUnusedImports")`,
  `NullAway:AnnotatedPackages=eg.masroof`. Error Prone's `RemoveUnusedImports` makes an unused import a
  `compileJava` error (deterministic task name; Spotless's leaf task is `spotlessJavaCheck`).
- Checkstyle: vendored `config/checkstyle/google_checks.xml` from the same Checkstyle tag, one edit (`LineLength`
  max 100 -> 120), `maxWarnings = 0` and property `org.checkstyle.google.severity=error` so violations print at ERROR
  level (visible under `./gradlew -q`). `checkstyleTest` and `spotbugsTest` are disabled (S9 names main only;
  tests are still formatted by Spotless and compiled with Error Prone + NullAway).
- SpotBugs `reportLevel = Confidence.HIGH` (fails only on high-priority findings, per S9).
- JaCoCo report merges `test.exec` + `integrationTest.exec` and excludes only `eg/masroof/MasroofApplication.class`
  (Spring Boot entrypoint, no logic; `main` is never called by tests).
- `.editorconfig` `[*.java]` indent becomes 2 (google-java-format); Kotlin keeps 4.

### Consequences
- Good: every S9 backend gate is a named Gradle task the existing scripts already call; failures name file + task.
- Good: one source set keeps `backend/CLAUDE.md` true and lets PIT and IDEs see all tests without extra wiring.
- Good: AnnotatedPackages covers every new package automatically; no per-package `@NullMarked` to forget.
- Bad: tag-based selection depends on authors tagging correctly — an untagged Testcontainers test would run in the
  unit `test` task (slow, needs Docker). Mitigation: test-designer and code-reviewer check tags; a missing Docker
  makes such a test fail loudly in `checkFast`, not silently pass.
- Bad: google-java-format wraps at 100, so the effective line length is 100 although Checkstyle allows 120
  (gjf has no width option). Accepted: S9 asks for a *relaxed* limit, not a wider formatter.
- Bad: the JUnit override must be re-checked on every Spring Boot upgrade (drop it once Boot manages >= 5.14).
- Bad: Gradle 8.14 is the last 8.x line; Gradle 9 adds `failOnNoDiscoveredTests` and stricter rules — upgrade is a
  later, separate decision.
- Neutral: `cyclonedxBom` (SBOM story) and `openApiGenerate` (STORY-006) are not defined yet; `pitest` and
  `migrationTest` are defined now so `make check-full` does not fail on a missing task.

## Validation
- `make check-fast MODULE=backend` green twice in a row, second run < 3 min (probe case in
  `scripts/quality/tests/backend-gate-probes.sh`, run by `make check`).
- Probes: tab-indented file -> non-zero exit naming the file and `checkstyleMain`/`spotless`; unused import ->
  `compileJava` + `RemoveUnusedImports`; `@Nullable` dereference -> `[NullAway]` with `File.java:<line>`.
- `make check` green: `integrationTest`, `archTest`, coverage gate (JaCoCo XML present), traceability.
- `make selftest` row G4 `PROVEN`.

## Links
- STORY-002 design notes; ADR-0003 (Spring Boot line); `backend/CLAUDE.md`; PROMPT §2, S8, S9.
- https://github.com/tbroyer/gradle-errorprone-plugin (JDK 16+ handled by an automatic forking compiler).
