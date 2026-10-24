# Verification report - STORY-142

- Verifier: qa-verifier (fresh context, combined low-tier verify + review). Date: 2026-10-24. Base: 45542e18e914d1abed12383ca96a783d66e331f1. Head: 9d42f73947963ff5c98bcf2839deebc6556cb627.
- Loop: 1 of 3. make is not on PATH in the sandbox, so the scripts it calls were run directly.

## Environment
- bash scripts/make/check-fast.sh (stdin /dev/null): check-fast GREEN [sdlc] in 58s (hook tests 624/624, quality tests 91/91, contract lint OK)
- web: npm ci, then npm run check-fast: tsc, eslint and prettier OK; vitest 31/31 passed

## Criteria
| REQ | Result | Evidence (command, observed) |
|---|---|---|
| REQ-OPS-022 test-designer checks | PASS | infra/tests/req-ops-022-smoke-format.sh ok (12 s); req-ops-022-stack.sh ok (21 s); req-ops-022-resilience.sh ok (83 s); req-ops-020-backend-compose.sh ok (22 s) |
| make up within 120 s, 5 healthy, gitSha | PASS | compose.sh up with no .env and images prebuilt: rc 0 in 10 s. Printed "no .env found - copying .env.example". DOMAIN empty. All 5 services healthy. /health gitSha 9d42f73 = short HEAD |
| no seed script, exit 0 | PASS | seed-demo.sh absent, up rc 0 |
| routing, no redirect | PASS | /health 200 json; /api/does-not-exist 404 json; /, /some/client/route and /actuator/health 200 text/html; no redirect |
| only caddy:80 published | PASS | ps json: only caddy 0.0.0.0:80; curl to 8080, 9000, 9001, 5432, 2019 and https://localhost all rc 7 |
| non-root, no node | PASS | backend 10001:10001, web 101:101 (inspect and exec id); node in web image rc 127 |
| smoke OK in budget + 5 s | PASS | smoke.sh http://localhost: OK health, OK web-root, rc 0, 0 s |
| backend down | PASS | after stop backend: FAIL health http://localhost/health last-status=502, plus OK web-root; rc 1 in 11 s |
| unreachable :9 | PASS | both FAIL lines with last-status=000, rc 1, 10 s |
| healthcheck detector | PASS | smoke-format test ok |
| Postgres late | PASS | resilience test ok (not re-run by hand) |
| Makefile URL default, README rows | PASS | Makefile:12; README diff read |
| make e2e against make up | FAIL | with make up running, scripts/make/e2e.sh rc 1: stack and resilience checks FAIL because port 80 is taken, Playwright 19 passed. With no stack, Playwright fails. Red in both states |
| Playwright via Caddy | PASS | E2E_BASE_URL=http://localhost npx playwright test: 19 passed, incl. the real-backend spec |
| contract-diff | PASS (by hand) | scripts/quality/oasdiff.sh errors on Git Bash (tmp mount, exit 102). oasdiff docker breaking, run by hand: no breaking changes, rc 0 |
| trivy | see findings | web 45 HIGH / 0 CRITICAL (all fixable); backend 1 HIGH; caddy 6 HIGH; minio 0. trivy config on infra and web/Dockerfile clean |

## Findings
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| MAJOR | infra/scripts/e2e.sh + infra/tests/req-ops-022-stack.sh, -resilience.sh | make e2e can't be green. The compose checks need host port 80 free, and Playwright needs make up on port 80. Fix: e2e.sh brings up its own stack after the compose checks, runs Playwright, and tears down in a trap | run compose.sh up, then scripts/make/e2e.sh: rc 1 |
| MAJOR | web/Dockerfile:10 (nginx-unprivileged 1.29.8 digest, about 5 months old) | 45 fixable HIGH CVEs (curl, expat, pcre2, openssl, libxml2, ...). The main.yml trivy web step (ignore-unfixed, exit-code 1) will fail. Re-pin to a current digest | trivy image --severity HIGH,CRITICAL masroof/web:dev |
| MINOR | contracts/openapi.yaml:30 (also contracts/README.md:47, redocly.yaml:6) | servers[0] url is still localhost:8080, but the description says via Caddy on port 80. 8080 is no longer published | curl localhost:8080 rc 7 |
| MINOR | scripts/quality/oasdiff.sh:55,75 (pre-existing) | contract-diff is broken on Windows Git Bash (mktemp tmp is not mountable) | bash scripts/quality/oasdiff.sh main |
| MINOR | infra/tests stack + resilience | not hermetic against an existing listener on port 80; observed false "smoke exited 0" against a foreign stack. Add a pre-flight free-port check | as in MAJOR 1 |
| MINOR | docker-compose caddy | runs as uid 0 (caps dropped); 6 fixable HIGH; STORY-121 | exec caddy id |
| MINOR | docker-compose postgres, minio, web | no restart policy | compose config json |
| MINOR | .env.example S3 keys (pre-existing) | minioadmin defaults; MinIO logs them at WARN ("Detected default credentials"), so compose logs in CI would print them; the fail-closed comment never triggers | compose logs minio |
| MINOR | web/nginx.conf | server_tokens on (Server: nginx/1.29.8); no security headers (deferred to STORY-010) | curl -sD- http://localhost/ |
| NIT | infra/scripts/smoke.sh:28 | SMOKE_TIMEOUT=abc gives an unbound-variable error and rc 1, not usage rc 2. SMOKE_TIMEOUT=0 reports 000 without trying | SMOKE_TIMEOUT=abc smoke.sh http://localhost |
| NIT | infra/scripts/smoke.sh:43 | printf piped to grep -q under pipefail can SIGPIPE on bodies over 64 KB (false FAIL); use a here-string | analysis |
| NIT | web/nginx.conf | /favicon.ico falls back to index.html with 200 | curl /favicon.ico |
| NIT | web/e2e real-backend spec title | still says "through the Vite proxy" | read |
| NIT | branch | carries commit 6960c7c [STORY-141] and 4322b02 [SDLC], out of scope | log 45542e1..HEAD |

## Exploratory
27 cases tried, 7 problems.
Inputs (curl path-as-is on http://localhost):
- /ACTUATOR/health web; /Health backend 404; /health/ web; /healthz web; /api web; /api/ backend 404
- /api/%2e%2e/actuator/health web; /api/%2E%2E%2Factuator/health web; /api/;/%2e%2e/actuator/health backend 404; /health;x web; /api/actuator/health backend 404; //actuator/health web
- actuator inside the backend container: 404; /assets/ 404 (no listing); /assets/nope.js 404; /index.html 200; /favicon.ico 200 html; /api/%00 400
- Host: other.example served; absolute-URI proxy to example.com returns the web index (no open proxy); POST /health 405; TRACE / 405
- closed ports 8080, 9000, 9001, 5432, 2019, 443
- smoke: trailing slash OK; SMOKE_TIMEOUT abc and 0; no scheme rc 2; no arg rc 2; SMOKE_HEALTH_PATH=/actuator/health gives FAIL with last-status=200 (4 s); blackhole 10.255.255.1 with budget 4: rc 1 in 6 s

## Test integrity
OK. scripts/quality/test-integrity-check.sh 45542e1 reports TEST_INTEGRITY: OK. Commit d62dcd9 touches no infra/tests, and infra/tests is unchanged since 32a25bf. Caveat: the tool counts 0 assertions in shell tests.

Teardown: down -v for project masroof-qa142; docker ps -a empty. The masroof_pgdata volume (created 16:02Z, before this run) was left in place.

Loop 1 result: FAIL (superseded by Loop 2 below)

---

# Loop 2 - re-verification of fix 392de9c (rebased on main 2c842f7, PR 9)

- Verifier: qa-verifier (fresh context). Date: 2026-10-24. Head: 392de9c066e0a5005d66ff8e9362a7e50ff7c17f (detached). 2c842f7 is an ancestor of the head. Loop: 2 of 3.

## Environment
- web: npm ci, then contract lint (scripts/make/contract.sh lint): "Your API description is valid"
- Docker was clean at the start (no containers); every project started here was torn down at the end (0 containers, 0 qa142/e2e/t022 volumes)

## Criteria (loop 2)
| Claim | Result | Evidence (command, observed) |
|---|---|---|
| make e2e green from clean, twice | PASS | bash scripts/make/e2e.sh: run 1 rc 0 (291 s), run 2 rc 0 (149 s). Each run: ok REQ-OPS-020, ok REQ-OPS-022 resilience, smoke-format and stack, then a throwaway stack masroof-e2e-PID with Playwright 19 passed; 0 containers left afterwards |
| make e2e with IMAGE_TAG = 40-char sha (main.yml) | PASS | IMAGE_TAG=392de9c066e0a5005d66ff8e9362a7e50ff7c17f bash scripts/make/e2e.sh: rc 0 (152 s), ok REQ-OPS-020 (non-root check), 19 passed |
| tests unchanged since test-designer | PASS | diff 7210dc0..392de9c over infra/tests, web/e2e and web/src test files is empty (7210dc0 = "test(infra): failing compose checks for stack smoke") |
| Linux screenshot baselines (NEW) | PASS | stack up (compose.sh up, project masroof-qa142l2). docker run mcr.microsoft.com/playwright:v1.64.1-noble (matches @playwright/test 1.64.1), web mounted read-only and copied in, npm ci, E2E_BASE_URL=http://host.docker.internal, CI=1, npx playwright test e2e/REQ-OPS-021-matrix.spec.ts: 4 passed (1.3 s). toHaveScreenshot (maxDiffPixelRatio 0.01) matched the Windows-made baselines on Linux chromium. The snapshot template has no platform suffix, so these are the same PNGs CI uses. The baselines were mounted read-only and not regenerated; the worktree is clean |
| trivy on web + backend | PASS | trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1: masroof/web:dev 0 HIGH/0 CRITICAL rc 0 (nginx 1.31.7); masroof/backend:dev 0/0 rc 0. caddy:2.11.7-alpine still 6 HIGH (rc 1), accepted and deferred to STORY-121 in the DECISIONS.md line dated 2026-10-24, "[STORY-142] Loop-1 fixes" (CI does not scan caddy) |
| servers[0] + non-breaking | PASS | openapi.yaml servers[0] url is http://localhost; contracts/README.md and redocly.yaml comments updated. oasdiff by docker (main vs head) breaking --fail-on ERR: "No breaking changes", rc 0; changelog: no changes |
| restart policies | PASS | compose config json: backend, caddy, minio, postgres and web all restart=unless-stopped |
| server_tokens off | PASS | curl -sD- http://localhost/ shows "Server: nginx" (no version) |
| SMOKE_TIMEOUT=abc exits 2 | PASS | "smoke: SMOKE_TIMEOUT must be a positive integer (seconds), got: abc", rc 2; smoke on the live stack still OK/OK rc 0 |
| main.yml e2e job for ubuntu | PASS (review) | new step in working-directory web: npm ci, then npx playwright install --with-deps chromium (installs the browser and apt deps; sudo is available on ubuntu-latest), then make e2e. e2e.sh unsets IMAGE_TAG, checks port 80 is free, runs the compose checks, then one throwaway stack for Playwright with teardown in an EXIT trap. Its correctness on Linux is shown by the Linux container run above plus the IMAGE_TAG run; the GitHub job itself was not run here |
| non-root / no node (regression) | PASS | web Config.User 101:101 and runtime id uid 101; node entrypoint rc 127; backend 10001:10001 |

## Loop-1 findings, resolution
| Loop-1 finding | Status |
|---|---|
| MAJOR make e2e red in both states | fixed (3 green runs above) |
| MAJOR web image 45 fixable HIGH | fixed (0 with ignore-unfixed) |
| MINOR servers[0] url | fixed |
| MINOR restart policies | fixed |
| MINOR server_tokens | fixed (security headers still deferred to STORY-010) |
| NIT SMOKE_TIMEOUT=abc | fixed |
| NIT printf-pipe SIGPIPE | fixed (here-string) |
| MINOR tests not hermetic on port 80 | mitigated: e2e.sh pre-flight refuses to start when port 80 answers (tests unchanged, by rule) |
| MINOR caddy root + 6 HIGH | deferred to STORY-121, recorded in DECISIONS |
| MINOR MinIO default credentials logged | deferred to STORY-010/121, recorded in DECISIONS; still open |
| MINOR oasdiff.sh broken on Windows Git Bash (pre-existing) | still open (oasdiff run by hand) |
| NIT favicon fallback, spec title, branch scope | still open (cosmetic) |

## New findings (loop 2)
| Severity | Location | Problem | Reproduce |
|---|---|---|---|
| NIT | infra/scripts/e2e.sh pre-flight | the port 80 check only detects an HTTP answer; a non-HTTP listener passes and fails later at compose up | listen on 80 with a raw TCP server, run e2e.sh |
| NIT | web/Dockerfile | the runtime image still ships curl (inherited from the base; 0 fixable HIGH now) | docker run --entrypoint sh masroof/web:dev -c "command -v curl" |
| NIT | branch | origin/main is now 9275b4e, beyond the rebase base 2c842f7; PR 9 may need a rebase before merging | rev-parse origin/main |

## Exploratory (loop 2)
8 regression cases, 0 new problems:
1. e2e run twice back to back (state reuse)
2. e2e with IMAGE_TAG = 40-char sha
3. SMOKE_TIMEOUT=abc
4. Server header on /
5. restart policy on all 5 services
6. Linux chromium screenshot diff for ltr/rtl x light/dark
7. node and curl in the web runtime
8. leftover containers/volumes after each e2e run (0)

## Test integrity (loop 2)
OK. The diff 7210dc0..392de9c over infra/tests, web/e2e and web/src test files is empty. No skips added, no assertions changed.

STATUS: PASS
