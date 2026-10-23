# backend
Spring Boot 3.5 / Java 21 / Gradle 8.14 (wrapper). Conventions: `CLAUDE.md`; toolchain: `docs/adr/0002-backend-build-toolchain.md`.
Run from `backend/`; any JDK 21 works (no toolchain auto-download):
- `./gradlew checkFast` - fast gate: Spotless, Checkstyle, `-Werror` compile (Error Prone + NullAway), SpotBugs, unit tests.
- `./gradlew archTest` - ArchUnit rules over main classes. `./gradlew spotlessApply` - format with google-java-format.
- `./gradlew integrationTest` - Testcontainers tests (needs Docker); `migrationTest` is the Flyway-from-empty subset.
- `./gradlew pitest` - mutation tests (money/parsing/dedup/analytics); not part of `checkFast`.
- JaCoCo XML for the coverage gate: `build/reports/jacoco/test/jacocoTestReport.xml` (unit + integration runs merged).
- From the repo root: `make check-fast MODULE=backend`, `make check`.
- Do not hand-edit `gradle/wrapper/*`, `gradlew*` or `config/checkstyle/google_checks.xml` (generated/vendored).
- Do not run two builds in one tree at once, especially with `--rerun-tasks`: they race on `build/` and fail with spurious file-lock or missing-class errors.
- Dependencies are locked: after changing a version or plugin run `./gradlew dependencies buildEnvironment --write-locks` and commit `gradle.lockfile`, `buildscript-gradle.lockfile` and `gradle/verification-metadata.xml` (refresh with `./gradlew --write-verification-metadata sha256 checkFast archTest integrationTest pitest dependencies buildEnvironment`).
- Deployments run with `SPRING_PROFILES_ACTIVE=prod`, which requires `POSTGRES_PASSWORD` (no empty default).
