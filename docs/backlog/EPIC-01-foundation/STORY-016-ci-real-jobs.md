---
id: STORY-016
epic: EPIC-01
title: CI runs backend integration tests and builds and scans the real images
phase: 1
status: DONE
size: M
modules: [infra, sdlc]
depends_on: [STORY-002, STORY-141, STORY-142, STORY-143, STORY-145]
reqs: [REQ-OPS-060, REQ-OPS-061, REQ-OPS-172]
implements: ["PROMPT.md §14 Phase 1 (CI with real jobs)", "PROMPT.md S12 (pr.yml, main.yml)", "PROMPT.md S8 (make targets are the single command surface)"]
risk_tier: low
owner_agent: devops-engineer
---

# STORY-016 — CI runs backend integration tests and builds and scans the real images

## Goal
Close the gaps between what `pr.yml`/`main.yml` claim to check and what they actually run (PROMPT §14 Phase 1 "CI with real jobs", S12). The PR and main gates are already real and green (STORY-145, REQ-OPS-150: `PR gate` / `Main gate`, per-module `check-fast` legs, contract, test integrity, eval-fast, gitleaks/semgrep/osv, `make e2e` on main). Three gaps remain, all checked on main 56f9450:
1. CI runs only `make check-fast MODULE=backend`. The backend Testcontainers integration tests (`integrationTest`) and ArchUnit tests (`archTest`) run only inside `make check` (`scripts/make/check.sh`), which no workflow calls.
2. `make build-images` delegates to `infra/scripts/build-images.sh`, which does not exist. It prints `skip: infra/scripts/build-images.sh not present yet` and exits 0. The pr.yml `artifacts` job and the main.yml `images` job therefore build no image, and both main.yml Trivy image scans are skipped (the job only warns `No masroof/* image tagged ... found`).
3. main.yml uploads no web bundle or debug APK. Only pr.yml's `artifacts` job does.

## Scope trimmed on 2026-10-25 (already DONE elsewhere, not re-specified)
- Per-module PR legs, the aggregate `gate` job and its "detect must succeed" rule: STORY-145 REQ-OPS-150, `scripts/quality/tests/test_pr_gate.py`.
- `make e2e` on main against a throwaway compose stack: STORY-142 (`infra/scripts/e2e.sh`).
- Claude review job (skipped without `ANTHROPIC_API_KEY`): already in pr.yml.
- **REQ-OPS-062 (branch protection) is retired.** Its successor REQ-OPS-151 is in STORY-147, which is DESCOPED (owner decision 2026-10-25, OWNER-014 option c). The per-metric `gate/Mn` check names and `delivery-gates.yaml` it referred to were deferred with STORY-145's cut (REQ-OPS-152..167 retired).
- SBOMs (`make sbom` is still a stub): STORY-137 REQ-OPS-133 (Phase 10). Image push or signing to a registry (`IMAGE_PUSH`, `IMAGE_REGISTRY`): STORY-137.
- Android legs need `android/gradlew`, which STORY-008 adds. Until then they are skipped by `detect`. This story only makes sure they become real without further workflow edits.

## Requirements and acceptance criteria
### REQ-OPS-060 — Backend integration and architecture tests run in the PR and main gates
**Amended 2026-10-25 (trimmed to what is missing; the per-metric check names from the 2026-10-24 amendment are void since STORY-145's cut).**
**Given** a pull request to `main` while `backend/gradlew` exists **When** `pr.yml` runs **Then**:
- a job runs the backend Testcontainers integration tests and the ArchUnit tests through a `make` target (the workflow calls no `./gradlew`, `npm` or `pytest` directly; S8);
- the job log shows at least one executed test for each of the two suites (the JUnit XML under `backend/build/test-results/` has `tests` > 0 for `integrationTest` and for `archTest`), and the results are uploaded as an artifact;
- the job finishes in < 15 min on `ubuntu-latest`;
- `PR gate` lists the job in `needs`, so `test_pr_gate.py` still passes, extended with this job.

The same job runs in `main.yml`, and `Main gate` needs it.
Unhappy path: **Given** a throwaway branch where one Testcontainers integration test asserts a wrong value **When** the PR runs **Then** this job concludes `failure`, `PR gate` concludes `failure` with `::error::At least one required job failed or was cancelled.`, and the uploaded test report names the failing test class.
Unhappy path: **Given** Docker or Testcontainers cannot start a container on the runner **When** the job runs **Then** it concludes `failure`. It never concludes `success` with 0 integration tests executed.
Unhappy path: **Given** `backend/gradlew` is absent (simulated in `test_pr_gate.py` with `detect` output `backend=false`) **Then** the job is skipped and `PR gate` still concludes `success`.
Unhappy path: **Given** an ArchUnit rule is violated (e.g. a `domain` class imports `org.springframework`) **Then** the job and `PR gate` conclude `failure`.

### REQ-OPS-061 — `make build-images` builds the real images and CI scans them with Trivy
**Amended 2026-10-25.** Image naming follows `infra/README.md`: `IMAGE_TAG` sets the tag, and an empty `IMAGE_REGISTRY` gives the local names `masroof/backend:<tag>` and `masroof/web:<tag>`.
**Given** Docker is running and `IMAGE_TAG=pr-12` **When** `make build-images` runs **Then**:
- it exits 0;
- `docker image inspect masroof/backend:pr-12` and `docker image inspect masroof/web:pr-12` both succeed;
- each image's `Config.User` is non-empty and is neither `root` nor `0`;
- every `FROM` line of `backend/Dockerfile` and `web/Dockerfile` carries an `@sha256:` digest;
- the output contains no `skip:` line.

**Given** `IMAGE_TAG` is unset **Then** the images are tagged `dev`, the tag compose and `infra/tests/*.sh` inspect. A later `make up` keeps working.
**Given** a pull request **When** the pr.yml `artifacts` job runs **Then** it builds both images and runs a Trivy image scan on each (severity `HIGH,CRITICAL`, ignore unfixed, exit code 1), and `PR gate` needs that job.
**Given** a push to `main` **When** the main.yml `images` job runs **Then** both Trivy image-scan steps run (they are not skipped), and the log has no `No masroof/* image tagged` warning.
Unhappy path: **Given** a throwaway branch whose `backend/Dockerfile` runtime base is re-pinned to an older digest with a fixable HIGH or CRITICAL CVE **When** the PR runs **Then** the Trivy step fails, its table names the package and CVE, and `PR gate` concludes `failure`.
Unhappy path: **Given** a Dockerfile syntax error **When** `make build-images` runs **Then** it exits non-zero and names the image that failed. CI then fails the job; it never exits 0.
Unhappy path: **Given** the Docker daemon is not reachable **When** `make build-images` runs **Then** it exits non-zero with one stderr line saying Docker is unavailable. It never silently skips.
Unhappy path: **Given** `web/package.json` is absent **Then** only the backend image is built, and it exits 0 with a line naming the skipped web image (it mirrors the module-presence rule of `build-web`).

### REQ-OPS-172 — main.yml publishes the web bundle and the debug APK
**Given** a push to `main` with `web/package.json` present **When** `main.yml` runs **Then** a job runs `make build-web` and uploads the artifact `web-bundle` (contents of `web/dist/`, including `index.html`, retention ≥ 7 days), and `Main gate` needs that job.
**Given** `android/gradlew` is present **When** the same run happens **Then** it also runs `make build-apk` and uploads `android-debug-apk` (`android/**/build/outputs/apk/debug/*.apk`, `if-no-files-found: error`). Until STORY-008 lands, this is proven by a workflow-structure test in the style of `test_pr_gate.py`: the step exists, it is gated on `detect.outputs.android == 'true'`, and the upload uses `if-no-files-found: error`.
Unhappy path: **Given** `make build-web` fails (e.g. a TypeScript error) **Then** the job and `Main gate` conclude `failure`.
Unhappy path: **Given** `android/gradlew` is absent **Then** the APK steps are skipped, and `Main gate` concludes `success` when everything else is green.
Unhappy path: **Given** the build produces no `index.html` in `web/dist/` **Then** the upload or a check step fails the job (an empty artifact is never published).

## UX states (UI stories only)
N/A (CI only).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
- Third-party actions stay pinned by commit SHA. Workflow and job `permissions` stay at `contents: read`, and no new secret is needed.
- Images are built and scanned locally on the runner and are never pushed (registry push is STORY-137).
- Trivy HIGH/CRITICAL with a fix becomes a real PR gate for the backend and web images. The Caddy and MinIO upstream images are not built here, so they are not scanned (Caddy: DECISIONS 2026-10-25 → STORY-121).
- Test reports must not contain fixture message bodies above DEBUG (logging-privacy rule). Integration-test logs are uploaded only as artifacts, not echoed in full.

## Eval impact
None (eval-fast already runs in both workflows).

## Design notes (architect)
Low tier: architect notes only if needed. Open points for the implementer:
- the make target name for "backend integration + ArchUnit" (a new target, or `make check` scoped to backend, without duplicating `check-fast`);
- whether `build-images.sh` wraps `docker build` or `docker compose build`;
- reusing the `IMAGE_TAG=pr-<n>` / `${{ github.sha }}` values already exported by the workflows.

## Dependencies
STORY-002 (backend Gradle `integrationTest`/`archTest` tasks), STORY-141 (web build), STORY-142 (compose stack, image tags `dev`), STORY-143 (backend Dockerfile), STORY-145 (`pr.yml` gate + `test_pr_gate.py`). All are DONE. STORY-006/007/008 were removed from `depends_on` on 2026-10-25: nothing in this scope needs codegen, and the APK leg is gated on `android/gradlew`.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths (REQ-OPS-060, -061, -172; REQ-OPS-062 retired)
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines. Estimate ≈ 250: `infra/scripts/build-images.sh` ~50, workflow edits ~50 (one backend-integration job in two workflows, Trivy steps in pr.yml `artifacts`, one main artifacts job), make target ~5, tests ~150 (workflow-structure tests extending `test_pr_gate.py`, a `build-images` shell test for tag/user/skip).
- [x] Dependencies listed (all DONE)

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — STORY-141/142 added to depends_on after the STORY-002 split |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — STORY-143 added to depends_on (backend image built by main.yml) |
| 2026-10-24 | 1-2 spec fix | product-analyst | DRAFT — STORY-145 decision D6: REQ-OPS-060 now feeds STORY-145's per-metric checks, REQ-OPS-062 applies branch protection with the yaml `required_checks` + push protection (D9), Claude review moved to STORY-145; depends_on += STORY-145; re-spec + spec-review at `/spec` |
| 2026-10-25 | 1 spec (low tier) | product-analyst | READY — trimmed to the remaining gaps: backend integration/ArchUnit not run in CI; `make build-images` was a skip stub, so no image was built or Trivy-scanned; main uploaded no web bundle/APK. REQ-OPS-062 retired (STORY-147 DESCOPED); REQ-OPS-172 added; depends_on cut to DONE stories; risk_tier low |

## Follow-ups (verify+review loop 1, 2026-10-25)
- Two weak tests in `test_ci_real_jobs.py` (test-designer): make the Gradle-failure fake write XML with tests > 0 and then exit 1, so a check that ignores the exit code is caught. Make the Docker-down fake fail only `docker info`, and assert the script's own one-line message.
- NITs: env.sh drive-letter regex also rewrites a single-letter relative PATH entry; `deploy-staging` should need `artifacts`; `Main gate` should require `detect` like the PR gate; HealthDatabaseOutageIT flaked once locally (Testcontainers port wait).
- Fixed here: main.yml's web Trivy step is gated on the web module, like pr.yml.
