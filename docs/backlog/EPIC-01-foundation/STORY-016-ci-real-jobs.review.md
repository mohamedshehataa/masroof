# Review report — STORY-016

- **Reviewers:** code-reviewer checklist, run by qa-verifier (combined low-tier verify + review, fresh context). Security-relevant paths (`.github/workflows`, dependency-free) were checked for pins, permissions and secrets.
- **Date:** 2026-10-25  ·  **Diff:** `git diff ee08123...c5af347`

## Findings
| # | Severity (BLOCKER/MAJOR/MINOR/NIT) | Reviewer | Location | Why it matters | Suggested fix | Resolution |
|---|---|---|---|---|---|---|
| 1 | MINOR | code-reviewer | scripts/quality/tests/test_ci_real_jobs.py:109-123,214 | The Gradle-failure test is satisfied by the zero-tests check. Mutant M2 (ignore the gradlew exit code) survives, so a later edit could let failing integration tests pass CI unnoticed. | The fake gradlew writes tests="2" XML, then exits 1 when FAKE_GRADLE_FAIL=1. Done by test-designer (a test strengthening, not a weakening). | open (follow-up) |
| 2 | MINOR | code-reviewer | scripts/quality/tests/test_ci_real_jobs.py:126-131,266-273 | The docker-down test passes on the fake docker own stderr. Mutant M3 (drop the `docker info` pre-check) survives, and the one-line contract is not asserted. | The fake docker fails only `info` when down; assert the script message and a single non-make stderr line. | open (follow-up) |
| 3 | MINOR | code-reviewer | .github/workflows/main.yml:428-436 | The main `images` Trivy (web) step is not gated on web presence. pr.yml is (pr.yml:420), so the two workflows are inconsistent, and main would fail if web/ ever goes away. | Add `if: needs.detect.outputs.web == "true"` (single-quoted in YAML) to the web scan step. | open |
| 4 | MINOR | code-reviewer | docs/backlog/EPIC-01-foundation/STORY-004-core-schema-and-seeds.spec-review.md | A STORY-004 artifact rode in on the STORY-016 start commit (81b5072). It blurs story scope in the PR history. | Accept with a note in the PR body, or move it to the STORY-004 branch. | open |
| 5 | NIT | code-reviewer | scripts/env.sh:7-16 | The regex `(^|:)([A-Za-z]):/` also matches a single-letter relative entry followed by an absolute one (`x:/y` → `/x/y`). | Anchor to the entry boundary: require the next char after `:/` to sit in the same entry, or split PATH on `:` and rejoin (still fork-free). | open |
| 6 | NIT | code-reviewer | .github/workflows/main.yml:458 | `deploy-staging` needs omit `artifacts`. | Add `artifacts` to its needs when staging is switched on. | open |
| 7 | NIT | code-reviewer | .github/workflows/main.yml:504-520 (pre-existing) | Main gate does not require `detect` success, unlike PR gate. | Port the pr.yml detect check in a later SDLC story. | out of scope |

Checked and fine:
- `backend-integration.sh`: clears stale results, fails on a Gradle error, a missing XML or 0 tests. It sums `tests=` across files; shellcheck is clean.
- `build-images.sh`: `set -euo pipefail`, Docker probe first, names the failing image, GIT_SHA has a hex fallback, and a missing web module is skipped by name with no `skip:` line.
- `build.sh images` hard-fails if the script is missing (no silent delegate).
- Makefile `IMAGE_TAG ?= dev` honours the workflow env (pr-17 seen in CI).
- `check.sh` reuses the new target, so there is no duplicated Gradle invocation.
- The sdlc-lint exclusion matches the existing `.verify.md`/`.review.md` pattern.
- Every `uses:` in both workflows is pinned to a 40-hex SHA. Workflow `permissions: contents: read`, with no new job-level permissions and no new secrets. Images are never pushed.
- No `./gradlew`, `npm` or `pytest` is called directly in the new jobs (S8).

## Invariants checked
- [x] Money types (n/a, no money code touched)
- [x] Cairo TZ (n/a)
- [x] Log redaction: integration job output is quiet (290 log lines, 6 INFO, no bodies); full reports go only to artifacts
- [x] Untrusted LLM input (n/a)
- [x] Migrations immutable (none touched)
- [x] Generated code untouched

STATUS: APPROVE
