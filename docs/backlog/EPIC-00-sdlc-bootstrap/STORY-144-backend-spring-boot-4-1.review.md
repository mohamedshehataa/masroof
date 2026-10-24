# Review report — STORY-144 (loop 1)

- **Reviewers:** code-reviewer (APPROVE), security-reviewer (APPROVE; triggered by dependency files)
- **Date:** 2026-10-24  ·  **Diff:** `git diff b2eabfc...857eecc -- backend scripts/quality/tests/backend-boot41-checks.sh`
- **Verify report:** loop 1 PASS (STORY-144-backend-spring-boot-4-1.verify.md), from-scratch rerun

## Findings
| # | Severity | Reviewer | Location | Why it matters | Suggested fix | Resolution |
|---|---|---|---|---|---|---|
| 1 | MINOR | code | ProdDatabasePasswordGuard.java:27 | `contains("${")` branch reached by no test; a refactor could drop it silently | Unit case via MockEnvironment ignoring unresolvable placeholders or an escaped `ab\${cd}` | fix in progress (test-designer) |
| 2 | MINOR | code | ProdDatabasePasswordGuardTest.java:11,37 | Missing `@Tag("REQ-OPS-143")`; spec's `""` unit case missing | Tag class; add empty-string case | fix in progress (test-designer) |
| 3 | MINOR | code | backend/README.md:12 | Refresh command differs from the proven byte-identical regeneration command | Use the story's command; note CRLF normalisation | fix in progress (backend-engineer) |
| 4 | MINOR | security + verify | ProdDatabasePasswordGuard.java:23 (CWE-258 / ASVS-13.3.1) | Empty `SPRING_DATASOURCE_HIKARI_PASSWORD` overrides the guarded password after binding | Also guard `spring.datasource.hikari.password`, or compose test that no `SPRING_DATASOURCE_HIKARI_*` is passed | ticketed → STORY-143 |
| 5 | MINOR | verify | backend/gradle.lockfile | Gradle accepts a lock downgrade to a version already in the verification metadata (pre-existing Gradle behaviour) | Lockfile diff check in CI (delivery gates) | ticketed → STORY-145 (M13/M8 lockfile diff review) |
| 6 | MINOR | verify | make security (local) | semgrep not installed locally; money-no-float/no-log-body only run in CI | Docker fallback or explicit SKIPPED status | ticketed → STORY-145 (M8 must not report GREEN with a skipped scanner) |
| 7 | NIT | code | backend-boot41-checks.sh:29 | Quoted `"$PYTHON"` breaks the `py -3` fallback | Array split | fix in progress (backend-engineer) |
| 8 | NIT | code | build.gradle.kts:13 | Comment chain does not name commons-lang3 | Reword | fix in progress (backend-engineer) |
| 9 | NIT | code + verify | ProdDatabasePasswordGuard.java:30 | Message for a literal `${` says only "non-empty" | Mention `${` (never the value) | fix in progress (backend-engineer) |
| 10 | NIT | code + security | V1BaselineMigrationTest.java:32-36 | Digest-only pin; tag provenance only in a comment; no parity with compose pin | Record digest source; STORY-143 digest-parity check | ticketed → STORY-143 |
| 11 | NIT | verify | metadata regeneration on Windows | Gradle writes CRLF; raw `cmp` shows a false diff | Normalise line endings before `cmp` | covered by #3 README note |
| 12 | NIT | verify | guard-bash | verifier reported `osv-scanner scan source ...` blocked; orchestrator could not reproduce (exit 0 for all three forms) | none | not reproduced; no change |

## Security summary
osv-scanner (`--no-ignore`, 179 + 53 + 374 packages) 0 findings; trivy HIGH/CRITICAL 0 (incl. dev deps); gitleaks 35 commits, no leaks; semgrep via Docker 230 rules on 21 files, 0 findings. Remaining override: buildscript `commons-lang3 3.18.0` (GHSA-j288-q9x7-2f5v, lowest fix). Verification metadata: 599 sha256 entries, no trusted keys or wildcards; 4 jars matched Maven Central byte for byte. Image digest equals Docker Hub's `postgres:16.15-alpine3.24`. ADR-0003's §5 accepted risk can be closed (Boot 4.1.2 OSS-supported to 2027-07-31).

## Manual delivery-gate checks (docs/PROMPT_DELIVERY_GATES.md, applied by hand until STORY-145 lands)
| metric | result | evidence |
|---|---|---|
| M1 check-fast | PASS | `make check-fast MODULE=backend` GREEN (warm 1-2 s; cold `checkFast --rerun-tasks` 15 s) |
| M2 make check | PASS | verifier: `make check` GREEN in 2m21s |
| M3 coverage | N/A (no coverage-gated code; JaCoCo rows SKIP "no data") | `bash scripts/quality/coverage-gate.sh` |
| M4 mutation | N/A (pitest: 0 mutations, no money/parsing/dedup code yet) | verifier |
| M5 test integrity | PASS | verifier TEST_INTEGRITY: OK since b2eabfc |
| M6 traceability | PASS | `make trace`: 330 REQs, 0 unknown, gate PASS; REQ-OPS-142/143 covered |
| M7 contract | N/A (no contract change) | — |
| M8 security | PASS (semgrep via Docker by the security reviewer) | see Security summary |
| M9 eval | N/A (not AI-affecting) | — |
| M10 verification | PASS | verify.md STATUS: PASS, 0 BLOCKER/MAJOR, 20 exploratory cases, TEST_INTEGRITY OK, verifier ≠ implementer |
| M11 review | PASS once rows 1-3, 7-9 land | this report; every MINOR fixed or ticketed |
| M12 size | PASS | upgrade commit 26 hand-written lines; guard commit ~115; total < 400 |
| M13 commits | PASS for cherry-picked commits (hooks ran on cherry-pick in the main checkout); Signed-off-by trailer not yet enforced (arrives with STORY-145) | `git log b2eabfc..HEAD` |
| M14 docs | PASS | story READY→IN_PROGRESS; PROGRESS lines; DECISIONS lines for the lang3 constraint, image pin, junit-bom entry, webmvc deferral |
| M15 CI | NOT YET (no PR; CI per-metric checks arrive with STORY-145) | — |
| M16 loop budget | PASS | 1 implement loop + 1 minors pass; spend within ledger norms |

## Invariants checked
- [x] Money types (MoneyRuleProbeTest 6/6)
- [x] Cairo TZ (no date logic touched)
- [x] Log redaction (guard message carries no value; canary cases pass end to end)
- [x] Untrusted LLM input (N/A)
- [x] Migrations immutable (V1 untouched)
- [x] Generated code untouched (none yet)

STATUS: APPROVE
