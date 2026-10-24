# Review report - STORY-142

- Reviewers: code-reviewer + security-reviewer checklist, run by the combined low-tier qa-verifier (fresh context)
- Date: 2026-10-24. Diff: 45542e1..9d42f73 (story/STORY-142-walking-skeleton-stack-smoke)

## Findings
| # | Severity | Reviewer | Location | Why it matters | Suggested fix | Resolution |
|---|---|---|---|---|---|---|
| 1 | MAJOR | code-reviewer | infra/scripts/e2e.sh | make e2e is red whether make up runs or not: the compose checks need port 80 free, and Playwright needs a stack on port 80 | after the compose checks, run compose.sh up, Playwright, then down in a trap | open |
| 2 | MAJOR | security-reviewer | web/Dockerfile:10 | the nginx-unprivileged 1.29.8 digest carries 45 fixable HIGH CVEs, so the main.yml trivy web gate fails; the runtime also ships curl | re-pin to a current digest; consider removing curl | open |
| 3 | MINOR | code-reviewer | contracts/openapi.yaml:30, contracts/README.md:47, redocly.yaml:6 | servers[0] url localhost:8080 contradicts the new description; that port is not published | set the url to http://localhost (oasdiff: server changes are non-breaking) and update both comments | open |
| 4 | MINOR | code-reviewer | infra/tests stack + resilience | not hermetic against an existing port 80 listener, so false passes are possible | pre-flight check that port 80 is free | open |
| 5 | MINOR | security-reviewer | compose caddy | runs as root (caps dropped to NET_BIND_SERVICE); 6 fixable HIGH | STORY-121: non-root caddy with setcap, bump digest | open |
| 6 | MINOR | code-reviewer | compose postgres, minio, web | no restart policy, unlike backend and caddy | restart: unless-stopped | open |
| 7 | MINOR | security-reviewer | .env.example S3 keys | MinIO well-known defaults; MinIO echoes them in its WARN log, so CI log dumps would show them | placeholder plus TODO(owner); keep the fail-closed path real | open |
| 8 | MINOR | security-reviewer | web/nginx.conf, infra/Caddyfile | server_tokens on; no X-Content-Type-Options, Referrer-Policy or frame-ancestors | server_tokens off now; headers in STORY-010 | open |
| 9 | MINOR | code-reviewer | scripts/quality/oasdiff.sh (pre-existing) | contract-diff can't run on Windows Git Bash | mount a native path (cygpath -w) or use MSYS_NO_PATHCONV | open |
| 10 | NIT | code-reviewer | infra/scripts/smoke.sh:28 | a non-numeric SMOKE_TIMEOUT aborts with unbound variable, rc 1, not 2 | validate the value as an integer, exit 2 | open |
| 11 | NIT | code-reviewer | infra/scripts/smoke.sh:43 | printf piped to grep -q under pipefail can SIGPIPE on large bodies | here-string | open |
| 12 | NIT | code-reviewer | web/nginx.conf | missing root files (favicon) answer 200 html | serve them or 404 | open |
| 13 | NIT | code-reviewer | branch | carries commits tagged STORY-141 and SDLC | split or note in the PR | open |

## Checklist notes
- Shell: smoke.sh has set -euo pipefail, a mktemp dir with an EXIT trap, quoted expansions, and two background checks joined by wait, each writing one result file. The last-status logic keeps the last non-000 code, matches the spec (502 when the backend is down, 000 with no connection) and was observed. No GNU-only flags; ran on Git Bash, and shellcheck in check-fast is green.
- Compose: every service has a healthcheck; caddy depends on backend and web being healthy; backend restart policy; read_only, tmpfs, cap_drop ALL and no-new-privileges on backend and web; minio console bound to 127.0.0.1 inside the container; only caddy:80 published. Verified live.
- Caddyfile: /health and /api/* go to the backend, everything else to web, so actuator is never routed (also 404 inside the backend). Upstreams are fixed (absolute-URI attempt answered by web, no open proxy). Plain HTTP when DOMAIN is empty, no redirect.
- nginx.conf: autoindex off by default (/assets/ 404), SPA fallback only through try_files, immutable cache on /assets.
- Images pinned by digest: postgres, caddy, node (build stage), nginx-unprivileged, minio. Local images masroof/backend and masroof/web are built.
- MinIO (cgr.dev/chainguard/minio, digest 73297936...): OCI labels point to Chainguard (source github.com/chainguard-images/images, built 2026-10-23 from MinIO RELEASE.2026-10-23T20-06-51Z); runs as uid 65532 non-root; 0 HIGH/CRITICAL; healthcheck is a bash /dev/tcp probe of /minio/health/live and reported healthy. Recommendation: accept for v1 with the digest pin and the DECISIONS.md entry. The free tier only publishes latest, so add a cosign verify of the Chainguard signature in CI, and a scheduled digest bump, under STORY-121; revisit if the free tier stops serving old digests.
- Invariants: no secrets added; .env.example holds placeholders only; nothing logs message bodies. Money, Cairo TZ, LLM input, migrations and generated code are not touched.
- Size: about 150 production lines and about 200 test lines (diff stat 362 insertions incl. docs), under the 400 cap.

## Invariants checked
- [x] Money types (not touched)
- [x] Cairo TZ (not touched)
- [x] Log redaction (no new logging; MinIO default-credential WARN noted, item 7)
- [x] Untrusted LLM input (not touched)
- [x] Migrations immutable (none)
- [x] Generated code untouched

Loop 1 result: CHANGES_REQUESTED (superseded by Loop 2 below)

---

# Loop 2 - review of fix 392de9c

- Date: 2026-10-24. Diff reviewed: the fix commit 392de9c (main.yml, backend/Dockerfile, web/Dockerfile, web/nginx.conf, compose, e2e.sh, smoke.sh, contracts, DECISIONS) on top of the rebased story commits.

## Resolution of loop-1 items
| # | Loop-1 item | Resolution |
|---|---|---|
| 1 | MAJOR make e2e can never be green | fixed: e2e.sh runs the compose checks first, then one throwaway stack (temp copy, temp .env, unique project, EXIT-trap down -v) for Playwright; unsets IMAGE_TAG; pre-flight refuses when port 80 answers. 3 green runs incl. IMAGE_TAG=sha |
| 2 | MAJOR web image fixable HIGH | fixed: nginx-unprivileged 1.31.7-alpine digest plus apk upgrade brotli-libs (also in backend); trivy ignore-unfixed exit-code 1 clean on both |
| 3 | MINOR servers[0] | fixed: http://localhost; README and redocly comments aligned; oasdiff non-breaking |
| 4 | MINOR tests not hermetic | mitigated by the e2e.sh pre-flight (tests may not be changed) |
| 5 | MINOR caddy root + 6 HIGH | deferred to STORY-121 (DECISIONS 2026-10-24 loop-1 fixes line) |
| 6 | MINOR restart policies | fixed: all 5 unless-stopped |
| 7 | MINOR MinIO default credentials | deferred (DECISIONS); open |
| 8 | MINOR server_tokens / headers | server_tokens fixed; headers deferred to STORY-010 |
| 9 | MINOR oasdiff.sh on Windows | open (pre-existing, outside the story) |
| 10 | NIT SMOKE_TIMEOUT validation | fixed (rc 2) |
| 11 | NIT SIGPIPE | fixed (here-string) |
| 12 | NIT favicon fallback | open |
| 13 | NIT branch scope | open |

## New findings (loop 2)
| # | Severity | Reviewer | Location | Why it matters | Suggested fix | Resolution |
|---|---|---|---|---|---|---|
| 14 | NIT | code-reviewer | infra/scripts/e2e.sh pre-flight | the curl probe misses a non-HTTP listener on port 80 | probe the TCP connect (bash /dev/tcp) instead | open |
| 15 | NIT | security-reviewer | web/Dockerfile | the runtime still contains curl from the base image | apk del curl in the runtime stage (healthcheck uses wget) | open |
| 16 | NIT | code-reviewer | PR 9 | origin/main has moved to 9275b4e | rebase before merge | open |

## Checklist notes (loop 2)
- e2e.sh: set -uo pipefail (no -e, on purpose, to collect rc), nullglob, quoted paths, the trap cleans up on every exit path, shellcheck disable carries a reason, the real .env is never touched. OK on Git Bash (3 runs).
- Dockerfiles: USER root only for the apk upgrade, then back to USER 101:101 (verified at runtime, uid 101). The backend still runs as 10001.
- main.yml e2e: npm ci and playwright install --with-deps chromium in web, before make e2e. Correct for ubuntu-latest. The Linux Playwright 1.64.1 noble container passed the 4 screenshot baselines within maxDiffPixelRatio 0.01.
- Invariants: no secrets added, no new logging, no migrations, generated code untouched.

STATUS: APPROVE
