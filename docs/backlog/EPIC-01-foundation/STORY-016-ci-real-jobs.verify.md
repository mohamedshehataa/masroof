# Verification report — STORY-016

- **Verifier:** qa-verifier (fresh context, combined verify + code-review, low tier)  ·  **Date:** 2026-10-25  ·  **Base:** `ee081232e4bf4843f66319cbe4b2395713441473` (origin/main)  ·  **Head:** `c5af347f8cdfaad219500eae6767590114525542`
- **Loop:** 1 of 3

## Environment
- Windows 11, Git Bash, Docker 29.9.2, trivy (winget), gh CLI. Modules present: backend, web (no android/gradlew).
- `make check` (scripts/env.sh loaded, stdin from /dev/null) → **check GREEN**, exit 0. It includes `check-fast [sdlc backend web]` OK, hook tests 624/624, sdlc-lint OK, shellcheck (66 scripts) OK, backend gate probes OK, backend integration + architecture tests OK (integrationTest 8, archTest 10), web coverage OK, contract diff OK, coverage gates PASS (backend line 88.5%, branch 77.8%), traceability gate PASS, test integrity vs main OK.
- Live CI: pr.yml run 40142922510 on head c5af347 → conclusion **success** (`gh run watch` exit 0).

## Criteria
| REQ | Result | Evidence (command → observed) |
|---|---|---|
| REQ-OPS-060 | PASS | `python -m unittest test_ci_real_jobs test_pr_gate` → Ran 25, OK. `make backend-integration` → exit 0, "integrationTest executed 8 test(s)", "archTest executed 10 test(s)". CI run 40142922510: job "Backend (integration + ArchUnit)" success in 64 s (timeout 15 min), log shows the same 8/10 counts, artifact `backend-integration-results` (13 KB) uploaded; PR gate `needs` include `detect` and `backend-integration` (pr.yml:456-467). main.yml has the same job (main.yml:195) and `Main gate` needs it (main.yml:509). Unhappy paths, run locally: archTest tag planted to a non-existent tag → exit 2, "ERROR: archTest executed no tests"; a domain class annotated `@org.springframework.stereotype.Component` → `ArchitectureRulesTest > DOMAIN_IS_FRAMEWORK_FREE FAILED`, "backend-integration: gradle failed", exit 2; a natural Testcontainers start timeout (HealthDatabaseOutageIT) → exit 2, and the XML file `TEST-...HealthDatabaseOutageIT.xml` names the failing class. The gate script returns non-zero for failure/cancelled and zero for skipped (unit test runs the real gate script). |
| REQ-OPS-061 | PASS | `IMAGE_TAG=x make build-images` → exit 0, 0 `skip:` lines; `masroof/backend:x` user `10001:10001`, `masroof/web:x` user `101:101`. `bash infra/tests/req-ops-061-build-images.sh` → `ok REQ-OPS-061 build-images`. All 4 FROM lines carry `@sha256:`. With no IMAGE_TAG → "done (tag dev)"; `make up` → all 4 containers Healthy, running `masroof/backend:dev` and `masroof/web:dev`; `make down` exit 0. `DOCKER_HOST=tcp://127.0.0.1:1 make build-images` → exit 2, stderr "ERROR: Docker is unavailable (daemon not reachable); cannot build images". A bare `FROM` appended to web/Dockerfile → exit 2, "ERROR: build of the web image (masroof/web:bad) failed". Local `trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1` → exit 0 on both images (alpine 0, app.jar 0). An old image (nginx:1.21.0-alpine) → exit 1 with a CVE table (shows the gate config can fail). CI: artifacts job logged "building masroof/backend:pr-17", "building masroof/web:pr-17", then two Trivy scans with severity HIGH,CRITICAL, ignore-unfixed true, exit-code 1, both reporting 0 vulnerabilities; PR gate needs `artifacts`. main.yml `images`: both Trivy steps have no `if`, and the `steps.img` warning step is removed (main.yml:417-436). |
| REQ-OPS-172 | PASS | Unit tests `test_req_ops_172_*` → ok. main.yml:224-268, read directly: the `artifacts` job runs `make build-web` then `test -f web/dist/index.html`, and uploads `web-bundle` (path `web/dist/`, if-no-files-found error, retention 7). The APK build and upload `android-debug-apk` (path `android/**/build/outputs/apk/debug/*.apk`, if-no-files-found error, retention 7) are both gated on `needs.detect.outputs.android == "true"`. `Main gate` needs `artifacts`. main.yml cannot run before merge, so this was checked by structure only, as the spec allows. |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MINOR | scripts/quality/tests/test_ci_real_jobs.py:109-123,214 | `target_fails_when_gradle_fails` does not isolate the Gradle-exit check. The fake gradlew exits before it writes any XML, so the zero-tests check catches it anyway. Mutant "drop the exit 1 after ./gradlew" survives all 25 tests. In real runs a failing test writes XML with tests>0, so that regression would let red integration tests pass the job. | In a scratch copy, replace the "gradle failed ... exit 1" handler in scripts/make/backend-integration.sh with `true`, then run the unittest modules → OK (it should FAIL). Fix: the fake writes tests="2" XML and then exits 1. |
| MINOR | scripts/quality/tests/test_ci_real_jobs.py:126-131,266-273 | The docker-down test passes on the fake docker own "Cannot connect to the Docker daemon" stderr. Mutant "remove the `docker info` pre-check" in build-images.sh survives. "One stderr line" is not asserted. | In a scratch copy, make the `docker info` pre-check always pass, then run the tests → OK. Fix: the fake fails only on `info` when down (or assert the script own message). |
| MINOR | .github/workflows/main.yml:428-436 | The main `images` Trivy (web) step is ungated. When web/package.json is absent, build-images skips the web image by design, so this step would fail on a missing image. pr.yml gates the same step on `detect.outputs.web` (pr.yml:420). Latent today because web exists. | Read main.yml:428 vs pr.yml:420. |
| MINOR | docs/backlog/EPIC-01-foundation/STORY-004-core-schema-and-seeds.spec-review.md | Out-of-scope file: the STORY-004 spec review was committed in the STORY-016 start commit 81b5072. The sdlc-lint `.spec-review.md` exclusion was then added so lint accepts it. The exclusion itself is sound. | `git show --stat 81b5072` |
| NIT | scripts/env.sh:7-16 | The drive-path rewrite merges a single-letter relative PATH entry with the next absolute entry: `/usr/bin:x:/y` → `/usr/bin:/x/y` (the `/y` entry is lost). Very unlikely in practice. | Set PATH to `/usr/bin:x:/y` with OSTYPE=msys, load env.sh, print PATH. |
| NIT | .github/workflows/main.yml:458 | `deploy-staging` needs do not include `artifacts`, so a failed web-bundle build does not block a staging deploy. Main gate still fails. Staging is disabled today. | Read main.yml:458 |
| NIT | .github/workflows/main.yml:509 (pre-existing) | `Main gate` neither needs `detect` nor checks that it succeeded (the STORY-145 rule exists only in pr.yml). If detect fails, every job skips and Main gate is green. Not introduced here. | Read main.yml:504-520 |
| NIT | backend HealthDatabaseOutageIT (pre-existing) | Flaked once locally: Testcontainers port-mapping wait timed out after 5 s. Green on re-run and in CI. This test now gates CI, so watch for flakes. | `make backend-integration` (first local run) |

## Exploratory
20 cases tried, 3 problems.
1. DOCKER_HOST=tcp://127.0.0.1:1 with `make build-images` → exit 2, a single ERROR line (ok)
2. web/Dockerfile with a trailing bare `FROM` line, IMAGE_TAG=bad → exit 2, names the web image (ok)
3. backend/build.gradle.kts:128 archTest tag "arch" changed to "nosuchtag" → exit 2, "archTest executed no tests" (ok)
4. backend/src/main/java/eg/masroof/domain/QaPlantViolation.java annotated `@org.springframework.stereotype.Component` → ArchUnit failure, exit 2 (ok)
5. Natural Testcontainers startup timeout → exit 2 (ok; flake noted)
6. IMAGE_TAG unset → tag dev, then `make up` healthy (ok)
7. IMAGE_TAG=x → non-root users 10001:10001 and 101:101 (ok)
8. trivy with exit-code 1 on nginx:1.21.0-alpine → exit 1 with CVE table (ok)
9. PATH `C:/x/bin:/usr/bin` → `/c/x/bin:/usr/bin` (ok)
10. PATH `/usr/bin:C:/Program Files/Git/bin:D:/tools` → `/c/Program Files/Git/bin`, `/d/tools` (ok)
11. PATH `c:/lower:/usr/bin` → `/c/lower` (ok)
12. PATH `/usr/bin:x:/y` → `/usr/bin:/x/y` (**problem**, NIT)
13. PATH `/usr/bin:/opt/C:/weird` → unchanged (ok)
14. PATH `/usr/bin:C:/a:C:/a` → both rewritten (ok)
15. PATH `/c/already:/usr/bin` → unchanged (ok)
16. PATH `/usr/bin:C:` → unchanged (ok)
17. Mutation run (scratch copy, 15 mutants): M1 zero-tests rc, M4 build failure not fatal, M5 build.sh silent skip, M6 PR gate drops job, M7 main Trivy re-gated on steps.img, M8 empty web bundle allowed, M9 Main gate drops artifacts, M10 Trivy exit-code 0, M11 tag ignores IMAGE_TAG, M12 workflow calls gradlew directly, M13 APK if-no-files-found ignore, M14 web scan removed, M15 Main gate drops backend-integration → all killed
18. Mutant M2 (Gradle failure ignored) → **survived** (MINOR)
19. Mutant M3 (`docker info` pre-check removed) → **survived** (MINOR)
20. Workflow SHA-pin scan: no `uses:` line lacks a 40-hex SHA; no new `secrets.` reference in the diff; workflow permissions stay `contents: read` (no new job-level permissions)

## Test integrity
OK. `bash scripts/quality/test-integrity-check.sh origin/main` → `TEST_INTEGRITY: OK`, assertions 0 → 62, 0 skips. Implementation commit c5af347 touches no test-designer file: `git show --stat c5af347` lists no file under `scripts/quality/tests/` or `infra/tests/`. The only test files (test_ci_real_jobs.py, req-ops-061-build-images.sh) came from b9f3227 and were not modified after it.

STATUS: PASS
