---
id: STORY-002
epic: EPIC-00
title: "Walking skeleton (backend): Gradle module + fast gate + V1 baseline + ArchUnit rules and probe"
phase: 0
status: DONE
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
Unhappy path: **Given** a planted Java source file indented with tabs, or with an unused import **When** `make check-fast MODULE=backend` runs **Then** it exits non-zero and the output names the file and at least one of `spotlessCheck`, `checkstyleMain` or `compileJava`.
Unhappy path: **Given** a planted method that dereferences a `@Nullable` parameter without a check **When** `./gradlew checkFast` runs **Then** compilation fails with a NullAway error naming the file and line.

### REQ-OPS-024 — V1 baseline migration and architecture rules
**Given** an empty Postgres 16 database (Testcontainers, `integrationTest`) **When** the backend application context starts **Then** Flyway applies exactly one migration, `V1__baseline.sql`, `flyway_schema_history` has one row with `version = '1'` and `success = true`, and the schema contains this table (DDL fixed by spec review; recorded in `docs/DECISIONS.md`):
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
5. no field injection (`@Autowired`/`@Inject`/`@Value` on fields);
6. classes in `..domain..` do not depend on `..application..` or `..adapters..`; classes in `..application..` do not depend on `..adapters..`;
7. top-level adapter slices `eg.masroof.adapters.(in|out).(*)..` do not depend on each other.
**Given** some of these packages contain no classes yet (skeleton) **Then** the rules still pass (empty packages and empty slices are explicitly allowed for rules 1-7, not a "failed to check any classes" error).
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
ADRs: [ADR-0002 backend build toolchain](../../adr/0002-backend-build-toolchain.md) (accepted), [ADR-0003 Spring Boot 3.5 after OSS EOL](../../adr/0003-spring-boot-3-5-after-oss-eol.md) (proposed, owner decision before STORY-143). Contract: none (`git diff --stat main -- contracts/` is empty; `make contract-lint` reports "Your API description is valid"; `make contract-diff BASE=main` reports "No changes detected / no breaking changes against main").

### Where each REQ sits (hexagonal placement)
- No domain, application or adapter **behaviour** in this story. Production code is only the Boot entrypoint `eg.masroof.MasroofApplication` (root package, outside every layer), one `package-info.java` per layer package (`domain`, `application`, `adapters.in`, `adapters.out`), `application.yml` and `db/migration/V1__baseline.sql`. The package-info files are needed so git tracks the directories, which the G4 selftest (`find ... -name domain`) and later stories rely on.
- REQ-OPS-023 lives in the build (`backend/build.gradle.kts`) and is tested by a gate-probe script. REQ-OPS-024 lives in the migration (Flyway runs as part of the adapters/out persistence concern) and in ArchUnit tests over main classes.
- No ports, no endpoints and no domain types are introduced; `Money` arrives in STORY-004/005.

### Files (H = hand-written, counts toward size; G = generated or vendored, excluded)
| Path under `backend/` | H/G | Content |
|---|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` | G | Gradle 8.14.6 wrapper, `distributionType=bin`, `distributionSha256Sum` set. Generate it by downloading the official `gradle-8.14.6-bin.zip` and its `.sha256` from `services.gradle.org` into a scratch directory, checking the hash, and running `<unzipped>/bin/gradle wrapper --gradle-version 8.14.6 --distribution-type bin --gradle-distribution-sha256-sum <sha>` in `backend/` (no system Gradle exists; the download needs the owner's OK). Then `git update-index --chmod=+x backend/gradlew` (Windows does not record the executable bit; `check-fast.sh` tests `[ -x backend/gradlew ]` and CI runs it on Linux). `.gitattributes` already forces LF for `gradlew` and CRLF for `*.bat`. |
| `config/checkstyle/google_checks.xml` | G | Copied from the Checkstyle 14.3.0 jar that Gradle has already downloaded (`unzip -p <gradle cache>/checkstyle-14.3.0.jar google_checks.xml`), with one edit: `LineLength` `max` 100 -> 120. No suppressions file is needed now. |
| `settings.gradle.kts` | H | `rootProject.name = "masroof-backend"` (+ `pluginManagement { repositories { gradlePluginPortal() } }`, which is optional) |
| `gradle.properties` | H | `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`, `org.gradle.caching=true`, `org.gradle.java.installations.auto-download=false` |
| `build.gradle.kts` | H | see the sketch below |
| `src/main/java/eg/masroof/MasroofApplication.java` | H | `@SpringBootApplication public class MasroofApplication` with a `main` that calls `SpringApplication.run`. Javadoc on the class and on `main`, because google_checks `MissingJavadocType`/`MissingJavadocMethod` are errors. |
| `src/main/java/eg/masroof/{domain,application,adapters/in,adapters/out}/package-info.java` | H | One-sentence Javadoc stating the layer rule, then the `package` line. No annotations, because NullAway uses AnnotatedPackages. |
| `src/main/resources/application.yml` | H | see "Runtime config" below |
| `src/main/resources/db/migration/V1__baseline.sql` | H | REQ-OPS-024 DDL verbatim (the `text` columns; see DECISIONS 2026-10-23) |
| `src/test/java/eg/masroof/architecture/ArchitectureRulesTest.java` | H | rules 1-7 (`@AnalyzeClasses` + `@ArchTest` constants) |
| `src/test/java/eg/masroof/architecture/MoneyRuleProbeTest.java` | H | negative G4 probe (a JUnit Jupiter unit test) |
| `src/test/java/eg/masroof/archfixture/domain/BadMoney.java` | H | fixture: `final class BadMoney { double amount; }` |
| `src/test/java/eg/masroof/adapters/out/persistence/V1BaselineMigrationTest.java` | H | Testcontainers migration test |
| `../scripts/quality/tests/backend-gate-probes.sh` | H | REQ-OPS-023 probes (tests; test-designer writes it) |
| `../scripts/make/check.sh` | H | +1 line: run the probes after the backend check-fast step |
| `../.editorconfig` | H | `[*.java]` indent 2 (split from `kt,kts`) |

**SDLC prerequisite (separate `[SDLC]` commit, done before or alongside, not counted in the story):** `scripts/quality/coverage-gate.py` currently FAILs when `backend/build/reports/pitest/mutations.xml` is missing, but `make check` never runs PIT. So `make check` would be red the moment `backend/gradlew` exists, which violates REQ-OPS-023's "make check exits 0". Fix: add a `--require-mutation` flag; without it a missing PIT report is a `SKIP` row whose metric is still named "PIT report" (the existing test `test_coverage_gate_missing_report_for_existing_module_fails` keeps passing, because the JaCoCo FAIL still gives rc 1). `scripts/make/check-full.sh` re-runs `bash scripts/quality/coverage-gate.sh --require-mutation` after its `mutation (PIT)` step. Add two cases to `scripts/quality/tests/test_coverage_gate.py`: PIT missing without the flag -> SKIP, rc 0 when JaCoCo is good; with the flag -> FAIL. About 20 lines. Owner: devops-engineer or backend-engineer; it is recorded in DECISIONS.

### Pinned versions
Gradle 8.14.6; Spring Boot 3.5.16 (BOM: Flyway 11.7.2, PostgreSQL JDBC 42.7.11, Testcontainers 1.21.4); Error Prone 2.51.0 with `net.ltgt.errorprone` 5.1.1; NullAway 0.14.3; JSpecify 1.0.0; Spotless 8.10.4 with google-java-format 1.37.0; Checkstyle 14.3.0; SpotBugs plugin 6.5.13; JaCoCo 0.8.13; `info.solidsoft.pitest` 1.19.0 with pitest-junit5-plugin 1.2.3; ArchUnit 1.4.1; jqwik 1.10.2; JUnit BOM 5.14.4. All were checked against Maven Central, the Plugin Portal and services.gradle.org on 2026-10-23. **Verify at implementation:** that Checkstyle 14.3.0 runs under Gradle 8.14's Checkstyle task (fallback: the newest version that does, with that tag's google_checks.xml), and that Testcontainers 1.21.4 talks to the local Docker Engine 29.9 (API 1.56). If it fails with "client version ... too old", pin `testcontainers.version` higher through `platform("org.testcontainers:testcontainers-bom:<1.21.x fix>")` and record it in DECISIONS.

### build.gradle.kts (shape the implementer follows; about 110 lines)
```kotlin
import com.github.spotbugs.snom.Confidence
import net.ltgt.gradle.errorprone.errorprone
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
  java; jacoco; checkstyle
  id("org.springframework.boot") version "3.5.16"
  id("net.ltgt.errorprone") version "5.1.1"
  id("com.diffplug.spotless") version "8.10.4"
  id("com.github.spotbugs") version "6.5.13"
  id("info.solidsoft.pitest") version "1.19.0"
}
group = "eg.masroof"
version = "0.1.0"                                  // STORY-143 reads this for /health
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }

dependencies {
  implementation(platform(SpringBootPlugin.BOM_COORDINATES))
  testImplementation(platform("org.junit:junit-bom:5.14.4"))   // > Boot's 5.12.2; jqwik 1.10.2 needs platform 1.14
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.flywaydb:flyway-core")
  implementation("org.jspecify:jspecify:1.0.0")
  runtimeOnly("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")
  errorprone("com.google.errorprone:error_prone_core:2.51.0")
  errorprone("com.uber.nullaway:nullaway:0.14.3")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
  testImplementation("net.jqwik:jqwik:1.10.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.release = 21
  options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
  options.errorprone {
    disableWarningsInGeneratedCode = true
    error("NullAway", "RemoveUnusedImports")
    option("NullAway:AnnotatedPackages", "eg.masroof")
  }
}
spotless { java {
  target("src/*/java/**/*.java")
  googleJavaFormat("1.37.0"); removeUnusedImports(); trimTrailingWhitespace(); endWithNewline()
} }
checkstyle {
  toolVersion = "14.3.0"
  configFile = file("config/checkstyle/google_checks.xml")
  maxWarnings = 0
  configProperties["org.checkstyle.google.severity"] = "error"   // ERROR-level lines stay visible under -q
}
tasks.checkstyleTest { enabled = false }
spotbugs { reportLevel = Confidence.HIGH }
tasks.spotbugsTest { enabled = false }

tasks.test {
  useJUnitPlatform { excludeTags("integration", "arch") }
  finalizedBy(tasks.jacocoTestReport)
}
fun taggedTest(name: String, tag: String, what: String) = tasks.register<Test>(name) {
  description = what; group = LifecycleBasePlugin.VERIFICATION_GROUP
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags(tag) }
  shouldRunAfter(tasks.test)
}
val integrationTest = taggedTest("integrationTest", "integration", "Testcontainers tests (tag integration)")
integrationTest { finalizedBy(tasks.jacocoTestReport) }
taggedTest("archTest", "arch", "ArchUnit rules over main classes (tag arch)")
  .configure { extensions.configure<JacocoTaskExtension> { isEnabled = false } }
taggedTest("migrationTest", "migration", "Flyway from empty (previous-snapshot case arrives with STORY-004)")

jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport {
  executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("test.exec", "integrationTest.exec"))
  classDirectories.setFrom(sourceSets.main.get().output.classesDirs.asFileTree
      .matching { exclude("eg/masroof/MasroofApplication.class") })
  reports { xml.required = true; html.required = true }   // xml -> build/reports/jacoco/test/jacocoTestReport.xml
  mustRunAfter(integrationTest)
}
pitest {
  junit5PluginVersion = "1.2.3"
  targetClasses = setOf("eg.masroof.domain.*", "eg.masroof.*.money.*", "eg.masroof.*.parsing.*",
      "eg.masroof.*.dedup.*", "eg.masroof.*.analytics.*")
  excludedGroups = setOf("integration", "arch")
  outputFormats = setOf("XML", "HTML"); timestampedReports = false   // -> build/reports/pitest/mutations.xml
  failWhenNoMutations = false   // skeleton has no mutable domain code yet
}
tasks.register("checkFast") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "S8 fast gate: format, lint, -Werror compile with Error Prone + NullAway, SpotBugs HIGH, unit tests"
  dependsOn("spotlessCheck", "checkstyleMain", "compileJava", "compileTestJava", "spotbugsMain", "test")
}
```
Notes for the implementer:
- The Error Prone plugin forks javac with the JDK 16+ `--add-exports` automatically. Spotless opens the JDK internals that google-java-format needs. If `spotlessJavaCheck` throws `IllegalAccessError`, add google-java-format's documented `--add-exports` list to `org.gradle.jvmargs` and record that in DECISIONS.
- If a dependency's missing annotation class (for example `javax.annotation.meta.When`) causes a javac warning that breaks `-Werror`, add that annotation jar as `compileOnly`/`testCompileOnly`. Never relax `-Werror`.
- `JAVA_HOME` on the dev machine points at the Android Studio JBR, which is also JDK 21. The toolchain (21) accepts it, and CI uses Temurin 21. Do not add toolchain auto-download.
- Don't add the web starter, actuator, `springBoot { buildInfo() }` or JPA. They arrive with STORY-143 (web, actuator, build-info) and STORY-004 (JPA). This story has no web server: the application context is JDBC + Flyway only.
- `format-changed.sh` runs `spotlessApply` on files that agents edit, so the probes must plant their files from the script and never through an agent Write.

### Runtime config (`application.yml`, environment names from the root `.env.example`)
`spring.application.name: masroof-backend`; `spring.datasource.url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:masroof}`, `username: ${POSTGRES_USER:masroof}`, `password: ${POSTGRES_PASSWORD:}` (empty default, so no secret is committed); `spring.flyway.locations: classpath:db/migration`, `validate-migration-naming: true`, `clean-disabled: true`, `baseline-on-migrate: false`; `spring.main.banner-mode: off`. In tests, `@ServiceConnection` overrides the datasource.

### ArchUnit rules (exact; all in `ArchitectureRulesTest`)
The class carries `@AnalyzeClasses(packages = "eg.masroof", importOptions = ImportOption.DoNotIncludeTests.class)`, `@ArchTag("arch")` and `@ArchTag("REQ-OPS-024")`. ArchUnit's JUnit 5 engine maps `@ArchTag` to JUnit Platform tags, so `archTest` includes it and `test` excludes it. Every rule ends with `.allowEmptyShould(true)` (story: empty packages and slices pass). Each rule is a `@ArchTest public static final ArchRule` field, so the probe can reference it.
1. `DOMAIN_IS_FRAMEWORK_FREE = noClasses().that().resideInAPackage("eg.masroof.domain..").should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..", "com.fasterxml.jackson..", "java.net.http..")`. Moving to Spring Boot 4 (ADR-0003) adds `"tools.jackson.."`.
2. `ADAPTERS_IN_NOT_TO_OUT = noClasses().that().resideInAPackage("..adapters.in..").should().dependOnClassesThat().resideInAPackage("..adapters.out..")`, plus `ADAPTERS_OUT_NOT_TO_IN`, the mirror rule.
3. `MONEY_RULE`, a `CompositeArchRule` over a member predicate:
   - `FLOATING = DescribedPredicate.describe("double/float/Double/Float", (JavaClass c) -> Set.of("double", "float", "java.lang.Double", "java.lang.Float").contains(c.getName()))`
   - `MONEY_SCOPE = DescribedPredicate.describe("in money scope", (JavaMember m) -> PackageMatchers.of("..money..", "..parsing..", "..dedup..", "..analytics..").test(pkg(m)) || (PackageMatchers.of("..domain..", "..application..").test(pkg(m)) && NAME.matcher(m.getName()).matches()))`, with `NAME = Pattern.compile("(?i).*(amount|price|total|balance|fee|vat|tip|share).*")` and `pkg(m) = m.getOwner().getPackageName()`.
   - `MONEY_RULE = CompositeArchRule.of(fields().that(MONEY_SCOPE).should().notHaveRawType(FLOATING).allowEmptyShould(true)).and(methods().that(MONEY_SCOPE).should().notHaveRawReturnType(FLOATING).allowEmptyShould(true)).and(codeUnits().that(MONEY_SCOPE).should().notHaveRawParameterTypes(DescribedPredicate.anyElementThat(FLOATING)).allowEmptyShould(true)).as("rule 3 (money): no double/float/Double/Float in money scope")`.
   - Semantics test-designer can rely on: "member name" means the field name or the method name. ArchUnit does not model parameter names, so a parameter is caught when its method is in scope: by package, or because the method's name matches. Constructors (`<init>`) are caught by the package part only. For a record `record X(double amount)`, both the field and the accessor `amount()` are caught. Verify the exact ArchUnit generics while implementing; the API names above exist in 1.4.
4. `NO_STANDARD_STREAMS = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS`, which also bans `Throwable.printStackTrace()`. That is stricter than the AC and is intended.
5. `NO_FIELD_INJECTION = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION` (covers `@Autowired`, `@Value`, `@Inject` (javax and jakarta) and `@Resource` on fields).
6. `DOMAIN_IS_INNERMOST = noClasses().that().resideInAPackage("eg.masroof.domain..").should().dependOnClassesThat().resideInAnyPackage("eg.masroof.application..", "eg.masroof.adapters..")`, and `APPLICATION_NOT_TO_ADAPTERS = noClasses().that().resideInAPackage("eg.masroof.application..").should().dependOnClassesThat().resideInAPackage("eg.masroof.adapters..")`. These are plain `noClasses` rules, not `layeredArchitecture()`, so empty layers need no `withOptionalLayers`.
7. `ADAPTER_SLICES_INDEPENDENT = slices().matching("eg.masroof.adapters.(*).(*)..").should().notDependOnEachOther()`. The slices are `in.rest`, `out.persistence` and so on. Only `in` and `out` exist under `adapters`, and the overlap with rule 2 is intended. Classes directly in `eg.masroof.adapters.in` (only `package-info`) belong to no slice.

**Negative probe (S15 step 6, G4):** `MoneyRuleProbeTest` is tagged `@Tag("REQ-OPS-024")`, not `arch`, so it runs in the unit `test` task inside `checkFast` and every fast gate proves that the rule still bites. It runs `JavaClasses c = new ClassFileImporter().importClasses(BadMoney.class)`, then `assertThatThrownBy(() -> ArchitectureRulesTest.MONEY_RULE.check(c)).isInstanceOf(AssertionError.class).hasMessageContaining("BadMoney")`. `BadMoney` is in `eg.masroof.archfixture.domain` (package part "domain" + name "amount"), lives in test sources, and is therefore never seen by `archTest`, which uses `DoNotIncludeTests`. `make selftest` G4 plants `SelftestPlant` (`public double amount`) in `src/main/java/eg/masroof/domain/` and expects `./gradlew archTest` to fail. That needs the `domain` package directory to exist, hence its `package-info.java`.

### V1 migration and its test
`V1__baseline.sql` holds exactly the REQ-OPS-024 DDL, with a header comment (`-- STORY-002 baseline; immutable once on main`). `V1BaselineMigrationTest` is tagged `@Tag("integration") @Tag("migration") @Tag("REQ-OPS-024")`, carries `@SpringBootTest` and `@Testcontainers`, and declares `@Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")`. It injects `JdbcTemplate` through the constructor (`@Autowired` on the constructor parameter), because field injection would trip NullAway. Cases:
- `select version, success, script from flyway_schema_history` returns exactly one row (`'1'`, `true`, `V1__baseline.sql`).
- `information_schema.columns` for `household`: names, `data_type` (`uuid`, `text`, `character` with length 3, `text`, `integer`, `timestamp with time zone` x2), `is_nullable = 'NO'` for all, and `id` as the primary key.
- An insert of only `id` and `name` gets `EGP`, `Africa/Cairo`, `1`, and non-null `created_at`/`updated_at`.
- `month_start_day` of `0` and `29` (`@ParameterizedTest`) throws `DataIntegrityViolationException` whose `getMostSpecificCause()` is an `SQLException` with `getSQLState()` = `"23514"`. Bounds `1` and `28` are accepted (boundary pair).

The test needs Docker. With Docker absent it fails, and that is correct: it runs only in `integrationTest`/`migrationTest`, never in `checkFast`.

### Gate probes for the fast gate (`scripts/quality/tests/backend-gate-probes.sh`)
The file carries no `test-` prefix, so `run.sh` (check-fast sdlc) does not pick it up. `scripts/make/check.sh` calls it right after the check-fast step: `[ -x backend/gradlew ] && run "backend gate probes (REQ-OPS-023)" bash scripts/quality/tests/backend-gate-probes.sh`. It sources `env.sh` and runs from the repo root. It plants files in `backend/src/main/java/eg/masroof/`, uses `trap` to remove them on EXIT, and finally asserts that `git status --porcelain backend/src` is clean. It writes JUnit XML to `backend/build/test-results/gateProbes/TEST-BackendGateProbes.xml`, with classname `BackendGateProbes` and case names starting with `REQ-OPS-023`, which `make trace` reads through `backend/build/test-results/**/*.xml`. Cases:
1. `REQ-OPS-023 warm check-fast green < 3 min`: `make check-fast MODULE=backend` is run twice and the second run must exit 0 in under 180 s (`SECONDS`).
2. `REQ-OPS-023 tab indentation rejected`: `GateProbeTabs.java` (valid Java, tab-indented). `make check-fast MODULE=backend` must exit non-zero, and the output must contain `GateProbeTabs.java` and match `spotless|checkstyleMain|compileJava`. Expected task: `checkstyleMain` (`FileTabCharacter`) or `spotlessJavaCheck`, depending on Gradle's order.
3. `REQ-OPS-023 unused import rejected`: `GateProbeImport.java` with an unused `import java.util.List;`. Same command. The output must contain `GateProbeImport.java` and `compileJava`, because Error Prone `RemoveUnusedImports` runs in `compileJava`, which every other check task depends on and which therefore fails first.
4. `REQ-OPS-023 NullAway rejects nullable dereference`: `GateProbeNull.java` with `static int len(@Nullable String s) { return s.length(); }` (`org.jspecify.annotations.Nullable`). `cd backend && ./gradlew --console=plain -q checkFast` must exit non-zero, and the output must match `GateProbeNull\.java:[0-9]+` and contain `[NullAway]`.
5. `make check-fast` with no `MODULE` dispatching to `sdlc backend` comes from the Makefile's `MODULES_PRESENT`. It is asserted by grepping the case-1 log of a `make check-fast` run for `===== check-fast: backend =====`. Run that one without `MODULE` instead of case 1's first run.

### Invariants and where they are enforced
- **Money as minor units / no floating point:** ArchUnit rule 3 (`archTest`, in `make check`), the always-on negative probe (`test`, in `checkFast`), and the existing semgrep `masroof-money-not-floating-java`. No money types exist yet.
- **Cairo TZ / UTC storage:** V1 `timezone` defaults to `Africa/Cairo`, and timestamps are `timestamptz` with `now()`. Business-date computation arrives in a later story. Asserted by the V1 test.
- **Immutable migrations:** `protect-files.sh` blocks edits once V1 is on `main`; `spring.flyway.validate-migration-naming: true` plus Flyway's checksum validation at startup.
- **Log redaction:** no logging in this story. Rule 4 bans `System.out`/`System.err`/`printStackTrace` everywhere in main code.
- **No field injection:** rule 5 (main code). NullAway enforces constructor injection in tests in practice.
- **Generated code untouched:** the Gradle wrapper is generated and committed but never edited. No OpenAPI code generation yet (STORY-006). `protect-files.sh` already blocks `build/` and `generated/`.
- **Untrusted LLM input:** N/A (no LLM).
- **Secrets:** `POSTGRES_PASSWORD` defaults to empty, Testcontainers generates its own credentials, and nothing is committed.

### Failure modes (step, failure, resulting state)
- Format or style violation: `spotlessJavaCheck` or `checkstyleMain` fails and the build stops, naming the file and line. `make check-fast` is RED and the Stop hook blocks.
- Unused import: `compileJava` fails with `[RemoveUnusedImports]` (RED).
- `@Nullable` dereference: `compileJava`/`compileTestJava` fails with `[NullAway]` and `File.java:N` (RED).
- Any javac or Error Prone warning: `-Werror` turns it into a failure (RED).
- High-priority SpotBugs finding: `spotbugsMain` fails and writes an HTML report under `build/reports/spotbugs/` (RED).
- Docker unavailable: `integrationTest` fails with a Testcontainers "Could not find a valid Docker environment" error (RED in `make check`, while `checkFast` is unaffected).
- `V1__baseline.sql` edited after it reaches main: `protect-files.sh` blocks the edit; if done by hand, Flyway's checksum validation fails startup.
- A rule silently passing: the probe test fails `checkFast`.
- Missing JaCoCo XML: the coverage gate FAILs. It is produced by `test`'s finalizer, so it exists after `checkFast`.

### Test levels per REQ (for test-designer)
| REQ | Level | Test | Runs in |
|---|---|---|---|
| REQ-OPS-023 | gate probe (shell, JUnit XML) | `scripts/quality/tests/backend-gate-probes.sh`, cases 1-5 | `make check` |
| REQ-OPS-023 | CI | `make check-fast MODULE=backend` in `pr.yml`/`main.yml` (existing jobs) | CI |
| REQ-OPS-024 | integration (Testcontainers Postgres 16) | `V1BaselineMigrationTest` (tags `integration`, `migration`) | `integrationTest`, `migrationTest` |
| REQ-OPS-024 | architecture | `ArchitectureRulesTest`, rules 1-7 (`@ArchTag("arch")`) | `archTest` |
| REQ-OPS-024 | unit (negative probe) | `MoneyRuleProbeTest` + fixture `BadMoney` | `test` (checkFast) |
| REQ-OPS-024 | selftest | `make selftest` row G4 -> `PROVEN` | manual / phase gate |

No property, contract, e2e or eval tests apply: there is no logic, no endpoint and no parsing. jqwik is added as a dependency only, because S9 property tests start with Money in STORY-004/005.

### Threats
No runtime trust boundary is added or changed: no listener, no endpoint, no external input, no LLM. The only runtime connection is backend -> Postgres (B7), which uses the existing B7 mitigations. Flyway currently runs under the app user, and REQ-SEC-020 (a separate migration role) stays Planned. Build-time supply chain risk is mitigated by exact pinned versions, the wrapper's `distributionSha256Sum`, artifacts only from Maven Central / the Plugin Portal / services.gradle.org, and the existing osv-scanner/Dependabot. Spring Boot 3.5's OSS end of life is recorded in ADR-0003 for decision before STORY-143. `docs/security/THREAT_MODEL.md` is unchanged, and security-reviewer sign-off is not required.

### Size re-estimate (hand-written)
`build.gradle.kts` ≈ 110, settings + properties ≈ 7, `MasroofApplication` ≈ 14, 4 × package-info ≈ 12, `application.yml` ≈ 15, V1 ≈ 11, `.editorconfig` ≈ 3, check.sh 1. Production/config ≈ 173. Tests: `ArchitectureRulesTest` ≈ 70, probe test + fixture ≈ 25, `V1BaselineMigrationTest` ≈ 65, gate probes ≈ 55. Tests ≈ 215. **Total ≈ 388** (≤ 400). The SDLC coverage-gate fix (≈ 20) is a separate `[SDLC]` commit.

### Open items for the orchestrator
- ADR-0003: owner decision (Spring Boot 4.1.x recommended) before STORY-143 reaches implementation.
- Downloading the Gradle 8.14.6 distribution to generate the wrapper needs the owner's OK (one zip from services.gradle.org, checked against the published SHA-256).
- DECISIONS line 42 (varchar) was superseded by a new line (text). The story text did not change.

## Dependencies
STORY-001 (hooks proven first). Unblocks STORY-143 (`/health`), STORY-004 (V2/V3 on top of V1), STORY-008.

## Size estimate (hand-written lines incl. tests; generated scaffolding excluded)
Excluded: Gradle wrapper, Spring Initializr base files, vendored Checkstyle config. Production + build/config ≈ 165 (Gradle build with Error Prone/NullAway/SpotBugs/Spotless/Checkstyle/test-task split ≈ 100, version catalog ≈ 20, application class ≈ 10, `application.yml` ≈ 15, V1 ≈ 12, Checkstyle suppressions ≈ 5); tests ≈ 130 (ArchUnit rules 1-7 + probe + fixture ≈ 90, migration integration test ≈ 40). **Total ≈ 295.**

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 295; scaffolding generated by tools is excluded from the count)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 2 spec-review | spec-reviewer | CHANGES_REQUESTED x2 -> split 002/143/141/142; loop limit recorded (LOOP-001) |
| 2026-10-23 | 3 design | architect | DESIGN_READY (ADR-0002, ADR-0003) |
| 2026-10-23 | 4 tdd | test-designer | TESTS_FAILING_AS_EXPECTED (db2d8a2) |
| 2026-10-23 | 5 implement | backend-engineer | READY_FOR_VERIFY (d86e255) |
| 2026-10-24 | 6 verify | qa-verifier | PASS loop 1 (4 MINOR, 4 NIT) |
| 2026-10-24 | 7 review | code-reviewer / security-reviewer | APPROVE / CHANGES_REQUESTED (2 MAJOR: CVEs, no lockfile) |
| 2026-10-24 | 5 implement | backend-engineer | READY_FOR_VERIFY fix loop 1 (3f5ac58) |
| 2026-10-24 | 6 verify | qa-verifier | PASS loop 2 (3 MINOR, 5 NIT) |
| 2026-10-24 | 7 review | security-reviewer | APPROVE (476eacf) |
| 2026-10-24 | 8 integrate | orchestrator | DONE; follow-ups: money-rule constructor gap + V1 CHECKs -> STORY-004; guard/profile NITs -> STORY-143 |
| 2026-10-23 | 2 spec review | spec-reviewer | CHANGES_REQUESTED (split, rate limit, DB down, provenance, ArchUnit scope, V1 DDL) |
| 2026-10-23 | 1-2 spec fix | product-analyst | DRAFT — split into STORY-002/141/142, findings applied; size ≈ 615 lines > 400 |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — orchestrator accepted the size split: REQ-OPS-020 moved to STORY-143; ≈ 285 lines |
| 2026-10-23 | 2 spec review (loop 2) | spec-reviewer | CHANGES_REQUESTED |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — ArchUnit rules 6-7 (layering, adapter slices), unused-import probe wording; ≈ 295 lines |
| 2026-10-23 | 3 design | architect | DESIGN_READY — ADR-0002 (toolchain), ADR-0003 (Spring Boot 3.5 EOL, proposed); no contract change; V1 = REQ DDL; SDLC prerequisite: coverage gate PIT SKIP in make check; ≈ 388 lines |
