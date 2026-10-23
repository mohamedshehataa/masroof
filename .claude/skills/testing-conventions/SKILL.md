---
name: testing-conventions
description: Test conventions for every module (REQ tagging per framework so make trace works, jqwik property patterns for money/normalization/dedup, Testcontainers setup, Roborazzi and Playwright matrices with axe, Maestro flows, flaky-test quarantine limit of 5, test-integrity rules). Loads automatically when any test directory or test config is touched; test-designer, implementers and the verifier follow it.
user-invocable: false
paths:
  - "**/src/test/**"
  - "**/src/androidTest/**"
  - "**/src/testFixtures/**"
  - "web/src/**/*.test.*"
  - "web/src/**/*.spec.*"
  - "web/src/test/**"
  - "web/src/__tests__/**"
  - "web/e2e/**"
  - "web/playwright.config.*"
  - "web/vitest.config.*"
  - "web/vitest.setup.*"
  - "android/maestro/**"
  - "eval/datasets/**"
  - ".claude/hooks/tests/**"
  - "scripts/quality/tests/**"
  - "infra/tests/**"
---

# Testing conventions

## 1. REQ tagging (make trace fails on untagged tests and unknown ids)
| framework | how to tag | example |
|---|---|---|
| JUnit 5 (backend) | `@Tag("REQ-SMS-004")` on the method (several tags allowed) and the id in `@DisplayName` | `@Test @Tag("REQ-SMS-004") @DisplayName("REQ-SMS-004 CIB debit template yields EXPENSE")` |
| jqwik | `@Property @Tag("REQ-EXP-012")` | `@Property(tries = 1000) @Tag("REQ-EXP-012") void sharesSumToTotal(@ForAll ...)` |
| Vitest / Testing Library | `describe('REQ-UI-003 <name>', ...)` or `it('REQ-UI-003 ...')` | `describe('REQ-PWA-002 offline queue', () => { it('replays queued entries on reconnect', ...) })` |
| Playwright | `test.describe('REQ-UI-007 ...')`; tag `@REQ-UI-007` in the title for `--grep` | `test('REQ-UI-007 add expense appears in analytics @REQ-UI-007', ...)` |
| Kotlin JUnit (android unit/instrumented) | backticked test name starting with the id | fun `REQ-AND-002 OTP message is dropped before upload`() |
| Roborazzi screenshots | the test name as above; the image file inherits it | |
| Maestro | `name: REQ-AND-010 sms to entry` in the flow yaml + file name `REQ-AND-010-sms-to-entry.yaml` | |
| eval cases (JSONL) | `"tags": ["REQ-PARSE-003", "sms", "cib"]` | |
| hook / script tests (bash) | a comment line `# REQ: REQ-OPS-001` and the id in the test function name | |
Ids: `REQ-<AREA>-<NNN>`, areas WA, SMS, NTF, IOS, RCPT, PARSE, DEDUP, EXP, AN, UI, AND, PWA, SEC, OPS (S11).
One test may cover several REQs; every REQ in a DONE story must have at least one passing test.

## 2. What a good test looks like here
- Assert behavior through public interfaces; never on log text, private fields or call counts of internal collaborators.
- Name = the acceptance criterion (Given/When/Then in the display name is fine).
- Deterministic: fixed clock (`Clock.fixed(..., Africa/Cairo)`), seeded random (`@Property(seed=...)` only when
  reproducing), no sleeps (await with Awaitility / Playwright auto-wait), no network except Testcontainers.
- Test data: builders/fixtures in `src/testFixtures` or `fixtures/` (anonymized); Arabic and Arabic-Indic inputs in
  every parsing test class; amounts in minor units (`Money.ofMinor(125050, "EGP")`).
- Mocks only at the architecture boundary (LLM client, WhatsApp API, FCM); the DB is real (Testcontainers).

## 3. jqwik property patterns (required for money, normalization, dedup, month math)
```java
@Property(tries = 2000) @Tag("REQ-EXP-012")
void splitSharesSumExactly(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long totalMinor,
                           @ForAll @IntRange(min = 1, max = 10) int members) {
  var shares = Money.ofMinor(totalMinor, EGP).allocateEqually(members);
  assertThat(shares.stream().mapToLong(Money::minor).sum()).isEqualTo(totalMinor);
  assertThat(shares).allSatisfy(s -> assertThat(Math.abs(s.minor() - totalMinor / (double) members)).isLessThan(1.0));
}
```
Other properties to keep: normalization idempotent and digit-script invariant (`normalize(arabicIndic(s)) == normalize(s)`);
`parseAmount(format(m)) == m` for every locale/digit setting; FX round-trip within the documented tolerance;
dedup score symmetric `score(a,b) == score(b,a)`, no self-match side effects, monotonic in amount distance;
month-start-day periods tile the calendar with no gaps/overlaps for msd 1..28 across DST days; `Money` arithmetic
never throws for same-currency operands and always throws for mixed. Use `@Provide` arbitraries for
`Money`, `Arabic text`, `SMS template`; shrinkers come free.

## 4. Testcontainers (backend integration)
Single shared Postgres (and MinIO) per JVM: `@Testcontainers` + `static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")`
in an abstract `AbstractIntegrationTest` with `@ServiceConnection` (Spring Boot 3.1+) or `@DynamicPropertySource`;
reuse enabled via `~/.testcontainers.properties` locally (`testcontainers.reuse.enable=true`), off in CI.
Real Flyway migrations run on the container (never `ddl-auto`); tests start from a clean schema per class
(`@Sql` cleanup or TRUNCATE of app tables via a `DbCleaner`). Migration tests: empty -> head, and the previous
release's schema snapshot + data -> head (`backend/src/test/resources/schema-snapshots/<tag>.sql`). N+1 guard:
Hibernate `Statistics` assertion (`assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(n)`) on list
endpoints; EXPLAIN assertions for analytics queries on >10k seeded rows (`EXPLAIN (FORMAT JSON)` has no `Seq Scan`
on `expense`). Webhook tests replay recorded Meta payloads from `fixtures/whatsapp/` with a computed signature;
bad signature -> 401 and no row.

## 5. Android
- Unit (JVM, Robolectric where a Context is needed): SMS sender filter, OTP/promo detection (an OTP classified
  as a transaction is a HARD failure), outbox/WorkManager logic (`TestListenableWorkerBuilder`), ViewModels with
  `StandardTestDispatcher` + Turbine.
- Compose UI tests for key flows; **Roborazzi** screenshot matrix for every screen: LTR (en) / RTL (ar) x light / dark
  x font scale 1.0 / 1.5 = 8 images per screen, parameterized (`@RunWith(ParameterizedRobolectricTestRunner)`,
  `qualifiers = "+ar-rEG"`, `fontScale`), recorded with `./gradlew recordRoborazziDebug`, verified in CI with
  `verifyRoborazziDebug`; diffs over the threshold fail. Golden images live under `android/app/src/test/snapshots/`.
- **Maestro** flows in `android/maestro/`: onboarding, quick add, SMS-to-entry (`adb emu sms send <sender> "<text>"`
  then assert the entry appears), review inbox; run by `make e2e` on the emulator.
- Kover >= 80% on ingestion/filtering/outbox/view-model packages.

## 6. Web
- Vitest + Testing Library (jsdom) for hooks/utils/components >= 85%; i18n tests render both `en` and `ar`.
- **Playwright** matrix in `web/playwright.config.ts`: projects for `en-LTR-light`, `ar-RTL-light`, `en-LTR-dark`,
  `ar-RTL-dark` x `mobile` (Pixel 7 / iPhone 14 viewport) and `desktop` (1280x800) = 8 projects; screenshots
  via `toHaveScreenshot` with per-project snapshots; **axe-core** (`@axe-core/playwright`) on every page object
  with zero `serious`/`critical` violations (`expect(results.violations.filter(v => ['serious','critical'].includes(v.impact))).toEqual([])`).
- Flows: login, add expense -> appears in analytics, review inbox, RTL rendering, offline queue (route abort then
  reconnect), install prompt, cross-device SSE update within 5 s.
- Mock the API with MSW in unit tests; e2e runs against `make up` with seeded data.

## 7. Flaky tests
A test that fails then passes on retry is flaky: CI quarantines it automatically (tag `@Tag("quarantine")` /
`test.fixme` / `@Ignore("QUARANTINE STORY-NNN")` is added by the flaky-report job with a linked story id), and a
story to fix it is created. Quarantine can hold **at most 5 tests**; a 6th fails the build. Fixing means removing
the quarantine tag in the same PR as the fix; retries (`retries: 2`) are allowed only in e2e projects and never
hide a quarantine.

## 8. Test integrity (hook + CI `scripts/quality/test-integrity-check.sh <base>`)
- No net loss of assertions in a changed test file; no new `@Disabled`/`@Ignore`/`.skip`/`.only`/`xit`/`test.fixme`
  (except the quarantine job); no loosened tolerances (`within`, `toBeCloseTo`, `isCloseTo`) without a reason in the
  report; no test deleted without a linked story id in the commit body.
- Only `test-designer` writes tests in stage 4. Implementers may ADD tests; a change to a test-designer test is
  logged and must be explained under `KNOWN_LIMITATIONS`; the qa-verifier reads `.claude/state/test-integrity.log`.
- Tests must fail for the right reason before implementation (assertion/404/not-implemented), never compile errors.
