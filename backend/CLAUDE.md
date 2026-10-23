# backend/ - Spring Boot service conventions

Java 21, Spring Boot 3.x, Gradle Kotlin DSL, PostgreSQL 16 + Flyway, MinIO/S3, Anthropic Java SDK.
Module arrives in Phase 1 (walking skeleton first). Everything below is binding from the first commit.

## Layout (hexagonal; ArchUnit enforces)
```
src/main/java/eg/masroof/
  domain/        pure Java: records, value objects (Money, Period), domain services, no Spring/JPA/HTTP imports
  application/   use cases + ports (interfaces); transactions and orchestration live here
  adapters/in/   rest (implements generated OpenAPI interfaces), webhook, scheduler, jobs (SKIP LOCKED workers)
  adapters/out/  persistence (JPA entities + repositories, Flyway), storage (S3), ai (Anthropic), whatsapp, fx, stt, push
src/main/resources/db/migration/V<N>__<snake_name>.sql   immutable once on main
src/main/resources/prompts/<name>.v<N>.md                versioned prompts with header (purpose, model, schema, changelog)
src/test/java/...  unit (JUnit 5 + jqwik), arch (ArchUnit), integration (Testcontainers Postgres + MinIO, tag "integration")
```
Dependency rule: `adapters -> application -> domain`; adapters never import each other; domain imports nothing from Spring, JPA, Jackson or servlet APIs.

## Money and time
- `Money(long amountMinor, String currency)` in `domain.money`; arithmetic only through its methods; splits use largest-remainder so shares sum exactly. No `double`/`float` anywhere in money, parsing, dedup or analytics types (ArchUnit + semgrep).
- Clock is injected (`java.time.Clock`); business dates via `ZoneId.of("Africa/Cairo")`; DB columns `timestamptz`; periods honour `household.month_start_day`.

## Gradle entrypoints the SDLC calls (define them in build.gradle.kts)
- `checkFast` = spotlessCheck + checkstyleMain + compileJava/compileTestJava (`-Werror`, Error Prone + NullAway) + unit `test` (excludes tag integration) + SpotBugs (no high-priority findings).
- `integrationTest` (Testcontainers), `archTest` (ArchUnit), `pitest` (money/parsing/dedup/analytics packages), `migrationTest` (empty + previous snapshot), `openApiGenerate` (server interfaces from `../contracts/openapi.yaml` into `build/generated` - never edited), `cyclonedxBom`.
- JaCoCo thresholds come from `scripts/quality/quality-thresholds.yaml` (85/75 overall; 95/90 for money, parsing, dedup, analytics).

## Tests
- Tag every test with its REQ id: `@Tag("REQ-SMS-004")`; property tests with jqwik for normalization, money, FX, dedup, month boundaries; integration tests on real Postgres via Testcontainers; webhook tests with recorded Meta payloads from `fixtures/whatsapp/`.
- LLM calls are mocked in unit tests; real calls only through `make eval`. No test may assert on log text.

## Logging and privacy
- Structured JSON logs (trace id, household id). Never log `body`, `text`, `ocrText`, `payload`, account numbers or phone numbers above DEBUG; mask to last-4. Encrypt raw message text and OCR at rest (AES-GCM, key from `APP_ENCRYPTION_KEY`).

## API
- Contract-first: edit `contracts/openapi.yaml`, run `make contract-lint` + `make contract-diff BASE=main`, regenerate. Controllers implement generated interfaces; errors are RFC 9457 `application/problem+json`; everything is scoped to the caller's household (IDOR tests required).
