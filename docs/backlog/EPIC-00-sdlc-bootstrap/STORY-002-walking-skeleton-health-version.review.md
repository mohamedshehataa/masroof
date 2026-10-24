# Review report — STORY-002 (loop 1)

- **Reviewers:** code-reviewer (APPROVE), security-reviewer (CHANGES_REQUESTED; triggered by dependency files)
- **Date:** 2026-10-24  ·  **Diff:** `git diff 68008e6...d86e255 -- backend scripts/make/check.sh scripts/quality/tests/backend-gate-probes.sh .editorconfig`
- **Verify report:** loop 1 PASS (STORY-002-walking-skeleton-health-version.verify.md)

## Findings
| # | Severity | Reviewer | Location | Why it matters | Suggested fix | Resolution |
|---|---|---|---|---|---|---|
| 1 | MAJOR | security | backend/build.gradle.kts:25,28,31 (CWE-1395 / ASVS-15.2.1) | Runtime classpath ships pgjdbc 42.7.11 (CVE-2026-54291, 8.2, channel-binding downgrade), jackson 2.21.4 (5 x 7.5 DoS), log4j-api 2.24.3 — all with fixes; the EOL Boot 3.5.16 BOM will never pull them | Override above the BOM (jackson-bom 2.21.x, pgjdbc 42.7.12+, log4j-api 2.25.x) with reasons; rescan | fix loop 1 (backend-engineer) |
| 2 | MAJOR | security | backend (no dependencyLocking), scripts/make/security.sh:18, pr.yml:292, main.yml:305 (CWE-1104 / ASVS-15.1.2) | No gradle.lockfile → osv-scanner step skipped, trivy misses nested build dirs, `make security` RED on a removed osv flag; loop 2 found the CI steps still passed `--skip-git`, which would turn the gate permanently red once a lockfile exists | `dependencyLocking { lockAllConfigurations() }` + committed lockfiles; fix security.sh and workflow flags | security.sh + both workflows fixed by orchestrator; locking landed in 3f5ac58 |
| 3 | MINOR | security | settings/build.gradle.kts (CWE-494 / ASVS-15.2.4) | No `verification-metadata.xml`, no wrapper-jar validation in CI | `--write-verification-metadata sha256`; `gradle/actions/wrapper-validation` step | fix loop 1 |
| 4 | MINOR | security | build/test classpath (httpcore5 5.3.6, commons-compress 1.24.0) | Build/test-only CVEs; not shipped | Track via lockfile; bump when plugins allow | tracked by #2 |
| 5 | MINOR | security | application.yml:9 (CWE-1188 / ASVS-13.3.1) | `${POSTGRES_PASSWORD:}` empty default could connect unauthenticated against a permissive pg_hba in prod | No default outside dev/test or fail-fast in prod profile | fix loop 1 |
| 6 | MINOR | code | build.gradle.kts:75-87 | No `testLogging` → under `-q` failures never name the class or rule | `testLogging.quiet { events(FAILED); exceptionFormat = FULL }` | fix loop 1 |
| 7 | MINOR | code | ArchitectureRulesTest:122,127 | `allowEmptyShould(true)` on rules whose subject set is never empty hides a broken import (all rules pass silently) | Drop it from NO_STANDARD_STREAMS and NO_FIELD_INJECTION (canaries) | fix loop 1 (test strengthening) |
| 8 | MINOR | code | MoneyRuleProbeTest:29 | Probe proves only the field sub-rule; a mis-wired parameter/return sub-rule would stay green | Add double-parameter and Double-return fixtures | fix loop 1 (test strengthening) |
| 9 | MINOR | code + verify | ArchitectureRulesTest:81-94 | Money rule misses constructor parameters and non-money-named methods; name substrings (`tip` in multiplier, `vat` in activate) false-positive | Architect: camelCase-boundary regex shared with semgrep; extend code-unit predicate before STORY-004 adds Money | ticketed → STORY-004 design (architect) |
| 10 | MINOR | code | backend-gate-probes.sh:57, check.sh:14 | Probe case 5 re-runs the whole sdlc gate (~40% of `make check`) | Prove dispatch via `make -n`; fix INT/TERM trap; require RemoveUnusedImports in case 3 | fix loop 1 |
| 11 | MINOR | code + verify | commits db2d8a2+d86e255 | ~812 hand-written lines vs ~400 budget (tests 607: gjf-expanded chains) — review still tractable | Record estimate miss; budget tests at ~2x for fluent chains | LESSONS.md entry added; accepted |
| 12 | MINOR | verify | scripts/quality/traceability.py | `out` exclusion skipped adapters/out tests | Exclude only at module root | fixed (commit 2a56125) |
| 13 | NIT | code/security | V1BaselineMigrationTest:33 | Floating `postgres:16-alpine` tag | Pin minor/digest | fix loop 1 |
| 14 | NIT | code + verify | V1__baseline.sql | Accepts `egp`, `Mars/Phobos`, empty name; no updated_at trigger (spec DDL; V1 immutable) | V2+ CHECKs and trigger in STORY-004 | ticketed → STORY-004 |
| 15 | NIT | code | gradle.properties:1, migrationTest JaCoCo exec, README race note, probe regex | Hygiene | As listed | fix loop 1 |
| 16 | NIT | security (loop 2) | ProdDatabasePasswordGuard.java:17 | Reads POSTGRES_PASSWORD, not the effective spring.datasource.password | Also check `spring.datasource.password` | ticketed → STORY-143 (compose sets SPRING_PROFILES_ACTIVE=prod) |
| 17 | NIT | security (loop 2) | ProdDatabasePasswordGuardTest.java:14 | No test proves @Profile("prod") wiring | ApplicationContextRunner test | ticketed → STORY-143 |
| 18 | NIT | security (loop 2) | gradle.lockfile log4j-to-slf4j 2.24.3 vs log4j-api 2.25.5 | Version drift between API and bridge | `platform(log4j-bom:2.25.5)` | ticketed → STORY-143 |

## Loop 2 (after 3f5ac58)
security-reviewer: loop-1 #1, #3, #4, #5 resolved (osv 0 findings on both lockfiles; checksums spot-checked against Maven Central; prod guard fails closed without logging the value); #2 resolved locally, CI osv args fixed by the orchestrator in this loop. New risk from the fix: none.

## Invariants checked
- [x] Money types (ArchUnit money rule present; gaps ticketed to STORY-004)
- [x] Cairo TZ (N/A, no business dates yet)
- [x] Log redaction (no logging code)
- [x] Untrusted LLM input (N/A)
- [x] Migrations immutable (V1 header; protect-files)
- [x] Generated code untouched (none generated yet)

## Threat model delta (from security-reviewer, to apply in fix loop 1)
B7 backend→Postgres: channel-binding downgrade row (pgjdbc ≥ 42.7.12); Flyway shares the runtime DB user (split before prod). New "Build supply chain" rows: distribution checksum (done), wrapper validation + verification-metadata (planned), lockfile + SCA (planned). §5: Boot 3.5.16 past OSS EOL accepted for STORY-002 only; CVE fixes overridden above the BOM until the 4.1 move (ADR-0003).

## Final verdict (loop 2)
code-reviewer: APPROVE (loop 1). security-reviewer: APPROVE after 476eacf (CI osv args fixed); NITs 16-18 ticketed to STORY-143. Threat-model delta applied in 3f5ac58 plus the §5/B7/supply-chain wording below is for the architect to merge in STORY-143.

STATUS: APPROVE
