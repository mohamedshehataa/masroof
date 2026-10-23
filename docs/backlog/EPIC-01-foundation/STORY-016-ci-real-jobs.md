---
id: STORY-016
epic: EPIC-01
title: CI workflows run the real gates per module and build artifacts
phase: 1
status: READY
size: M
modules: [infra, sdlc]
depends_on: [STORY-002, STORY-143, STORY-141, STORY-142, STORY-006, STORY-007, STORY-008]
reqs: [REQ-OPS-060, REQ-OPS-061, REQ-OPS-062]
implements: ["PROMPT.md §14 Phase 1 (CI with real jobs)", "PROMPT.md S12 (pr.yml, main.yml, nightly.yml)", "PROMPT.md §2 (CI)"]
owner_agent: devops-engineer
---

# STORY-016 — CI workflows run the real gates per module and build artifacts

## Goal
Pull requests and `main` run the same `make` gates the agents run locally, split per module, and produce the backend image, web bundle and debug APK (PROMPT S12).

## Requirements and acceptance criteria
### REQ-OPS-060 — pr.yml runs the gates per module in parallel
**Given** a pull request **When** `pr.yml` runs **Then** parallel jobs `sdlc` (`make sdlc-lint hooks-test`), `backend` (`make check-fast MODULE=backend` + integration tests with Testcontainers), `web` (`make check-fast MODULE=web`), `android` (`make check-fast MODULE=android`), `security` (gitleaks full history, semgrep incl. `.semgrep/`, osv-scanner) and `contract` (`make contract-lint`, `make contract-diff BASE=origin/main`) all report, each job finishes in < 15 min, and the workflow passes only if every job passes; jobs for modules untouched by the diff still run (no path filters on gates).
Unhappy path: **Given** a PR whose `web/` change fails ESLint **Then** only the `web` job is red and the PR check is red; the failure annotation points at the file and line.

### REQ-OPS-061 — main.yml builds and publishes artifacts
**Given** a push to `main` **When** `main.yml` runs **Then** after the PR gates it builds the backend container image (tag `sha-<short>`, non-root, pinned base digest), the web bundle (`make build-web`) and the debug APK (`make build-apk`), uploads them as workflow artifacts, and runs `make e2e` against a compose stack started from the built images (Playwright matrix placeholder may be the health page until Phase 2); the Trivy scan of the image reports no HIGH/CRITICAL with a fix.
Unhappy path: **Given** Trivy finds a fixable CRITICAL **Then** the workflow fails and the image is not retained.

### REQ-OPS-062 — Traceability and test-integrity gates block the merge
**Given** `pr.yml` **Then** jobs `trace` (`make trace`, fails on uncovered DONE REQs or unknown REQ ids in tests) and `test-integrity` (`make test-integrity BASE=origin/main`, fails on net assertion loss or new skip/disable) are required status checks for `main` (branch protection configured when `gh` is authenticated; otherwise `TODO(owner)` recorded in `docs/DEPLOY.md`).
Unhappy path: **Given** a PR that deletes a test file without a `STORY-NNN` reference in the commit message **Then** `test-integrity` fails naming the file.

## UX states (UI stories only)
N/A.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Secrets come only from GitHub Actions secrets; workflows pin third-party actions by SHA; `GITHUB_TOKEN` permissions are `contents: read` by default. The Claude review action (S12) is added here with `pull-requests: write` only.

## Eval impact
`eval` fast subset job is added as a placeholder calling `make eval-fast` with the reference adapter (already present in Phase 0).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-002 (backend gate), STORY-143 (backend image), STORY-141 (web gate), STORY-142 (compose stack for `make e2e`), STORY-006, STORY-007, STORY-008.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — STORY-141/142 added to depends_on after the STORY-002 split |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — STORY-143 added to depends_on (backend image built by main.yml) |
