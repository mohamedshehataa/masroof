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

STATUS: FAIL
