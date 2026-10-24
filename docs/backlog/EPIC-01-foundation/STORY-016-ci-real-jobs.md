---
id: STORY-016
epic: EPIC-01
title: CI workflows run the real gates per module and build artifacts
phase: 1
status: DRAFT
size: M
modules: [infra, sdlc]
depends_on: [STORY-002, STORY-143, STORY-141, STORY-142, STORY-006, STORY-007, STORY-008, STORY-145]
reqs: [REQ-OPS-060, REQ-OPS-061, REQ-OPS-062]
implements: ["PROMPT.md §14 Phase 1 (CI with real jobs)", "PROMPT.md S12 (pr.yml, main.yml, nightly.yml)", "PROMPT.md §2 (CI)"]
owner_agent: devops-engineer
---

# STORY-016 — CI workflows run the real gates per module and build artifacts

## Goal
Pull requests and `main` run the same `make` gates the agents run locally, split per module, and produce the backend image, web bundle and debug APK (PROMPT S12).

## Requirements and acceptance criteria
### REQ-OPS-060 — pr.yml runs the gates per module in parallel
**Amended 2026-10-24 (STORY-145 decision D6; re-spec at this story's `/spec`).** The check runs and their names are owned by STORY-145 REQ-OPS-161: the `required_checks` list in `scripts/quality/delivery-gates.yaml` (14 names, `gate/M1 check-fast` … `pr-body-lint`). There is no aggregate check. This story makes the module legs real inside those checks: backend with Testcontainers integration tests, web, android, and the full-history security scans.
**Given** a pull request **When** `pr.yml` runs **Then** the module work of `gate/M1 check-fast` and `gate/M2 check` runs in parallel per present module, for backend (`make check-fast MODULE=backend` plus integration tests with Testcontainers), web (`make check-fast MODULE=web`) and android (`make check-fast MODULE=android`). Each leg finishes in < 15 min. The check is green only if every leg passes. Legs for modules the diff does not touch still run, because gates have no path filters.
Unhappy path: **Given** a PR whose `web/` change fails ESLint **Then** `gate/M1 check-fast` is red, its summary names the web leg, and the failure annotation points at the file and line. The other module legs report green.

### REQ-OPS-061 — main.yml builds and publishes artifacts
**Given** a push to `main` **When** `main.yml` runs **Then** after the PR gates it builds the backend container image (tag `sha-<short>`, non-root, pinned base digest), the web bundle (`make build-web`) and the debug APK (`make build-apk`), uploads them as workflow artifacts, and runs `make e2e` against a compose stack started from the built images (Playwright matrix placeholder may be the health page until Phase 2); the Trivy scan of the image reports no HIGH/CRITICAL with a fix.
Unhappy path: **Given** Trivy finds a fixable CRITICAL **Then** the workflow fails and the image is not retained.

### REQ-OPS-062 — Branch protection on main uses the delivery-gates required checks
**Amended 2026-10-24 (STORY-145 decision D6; re-spec at this story's `/spec`).**
**Given** `gh` is authenticated as an admin of the repository **When** this story applies branch protection to `main` **Then** `gh api repos/<owner>/<repo>/branches/main/protection` shows all of the following:
- required status checks exactly equal to `required_checks` in `scripts/quality/delivery-gates.yaml` (the 14 names, read from the file, not copied);
- 1 required approving review;
- dismiss stale approvals on new pushes;
- required linear history;
- force pushes and deletions disabled;
- `enforce_admins` true.

The repository also has secret scanning **and push protection** enabled (STORY-145 decision D9). Without admin access, the commands to run are recorded as `TODO(owner)` in `docs/DEPLOY.md`.
Unhappy path: **Given** a PR whose `gate/M5 test-integrity` is red **Then** GitHub refuses the merge, and `gh pr merge` reports the failing required check. **Given** a PR approved only by its author **Then** the merge is refused, because GitHub does not count an author's own review.

## UX states (UI stories only)
N/A.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Secrets come only from GitHub Actions secrets. Workflows pin third-party actions by SHA. `GITHUB_TOKEN` permissions are `contents: read` by default. The Claude review action (S12) **moved to STORY-145** (REQ-OPS-161, check `gate/M11 ai-review`) by decision D6. Branch protection and push protection are applied here (REQ-OPS-062).

## Eval impact
`eval` fast subset job is added as a placeholder calling `make eval-fast` with the reference adapter (already present in Phase 0).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-002 (backend gate), STORY-143 (backend image), STORY-141 (web gate), STORY-142 (compose stack for `make e2e`), STORY-006, STORY-007, STORY-008, and STORY-145 (the check names in `delivery-gates.yaml`, `approvers`, and the per-metric `pr.yml`).

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (REQ-OPS-060/062 amended 2026-10-24; spec-review pending at `/spec`)
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
| 2026-10-24 | 1-2 spec fix | product-analyst | DRAFT — STORY-145 decision D6: REQ-OPS-060 now feeds STORY-145's per-metric checks, REQ-OPS-062 applies branch protection with the yaml `required_checks` + push protection (D9), Claude review moved to STORY-145; depends_on += STORY-145; re-spec + spec-review at `/spec` |
