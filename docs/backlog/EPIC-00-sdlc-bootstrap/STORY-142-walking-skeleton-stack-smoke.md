---
id: STORY-142
epic: EPIC-00
title: "Walking skeleton (stack): make up with Caddy, MinIO, web; make smoke"
phase: 0
status: DONE
size: M
modules: [infra, sdlc, web, contracts]
depends_on: [STORY-143, STORY-141]
reqs: [REQ-OPS-022]
implements: ["PROMPT.md S15 item 7", "PROMPT.md §2 (compose: Postgres, MinIO, backend, web, Caddy)", "PROMPT.md §12 (actuator not public, non-root images)", "PROMPT.md S8 (make up, make smoke, make e2e)", "PROMPT.md S9 (image hardening)"]
risk_tier: low
owner_agent: devops-engineer
---

# STORY-142 — Walking skeleton (stack): make up with Caddy, MinIO, web; make smoke

## Goal
`make up` starts the whole local stack and `make smoke URL=http://localhost` proves the request path **browser → Caddy → (web bundle | backend → Postgres)** end to end, closing PROMPT S15 item 7 together with STORY-002 (backend module), STORY-143 (`/health` API) and STORY-141 (web page).

## Requirements and acceptance criteria
### REQ-OPS-022 — make up brings up the whole stack and smoke passes
**Given** Docker, a `.env` copied from `.env.example` with `DOMAIN` empty, the images already built (`docker compose build` done, with `GIT_SHA` set) and empty volumes **When** `make up` runs **Then** it exits 0 within 120 s, and `docker compose -f infra/docker-compose.yml ps` lists the services `postgres`, `minio`, `backend`, `web` and `caddy`, each with a `healthcheck` defined in the compose file and each reporting `healthy`. A cold build (no images) has no time budget.
**Given** `make up` runs **Then** it exports `GIT_SHA=$(git rev-parse HEAD | cut -c1-7)` to the build, so `GET http://localhost/health` returns that `gitSha` (STORY-143 REQ-OPS-020).
**Given** the stack is up **When** `make smoke URL=http://localhost` runs **Then** it exits 0, having asserted:
- `GET http://localhost/health` → `200` and the body contains `"status":"UP"`;
- `GET http://localhost/` → `200` and the body contains `<div id="root">`.
Both checks run concurrently within one `SMOKE_TIMEOUT` budget (default 10 s); total run <= budget + 5 s.
**Given** the stack is up and `DOMAIN` is empty **Then**:
- Caddy listens on plain HTTP port 80 and does not redirect to HTTPS (`GET http://localhost/health` is answered directly with `200`, never `301`/`308`);
- `GET http://localhost/health`, and `GET http://localhost/api/does-not-exist` sent with `Accept: */*`, are answered by the backend (`Content-Type` contains `json`), and every other path (`/`, `/some/client/route`, `/actuator/health`) is answered by the `web` container (`Content-Type: text/html`) — `/actuator/*` never reaches the backend;
- `docker compose ps --format json` shows published ports only on `caddy` (80); in particular the backend's port 8080 is not published to the host (`curl http://localhost:8080/health` fails to connect); the loopback publish used for development in STORY-143/STORY-141 lives in the dev-only overlay that `make up` does not load;
- the backend and web images run as non-root (`docker inspect` `Config.User` set and neither `root` nor `0`; postgres and minio use the official images' defaults in v1; hardening is STORY-121);
- the `web` image, built from `web/Dockerfile`, serves the Vite production bundle as static files with no Node.js runtime in the image (`docker run --rm --entrypoint node <web-image> --version` fails).
**Given** `make e2e` **Then** `infra/scripts/e2e.sh` exists and `make e2e` runs every `infra/tests/*.sh` compose check and `npm run e2e` (in `web/`) with base URL `http://localhost` against `make up`.
**Given** the Makefile **Then** the default `URL ?=` for `make smoke` stays `http://localhost`; `infra/README.md` lists the local URL as `http://localhost`, its overlay rows describe the base compose file and the dev-only overlay (backend on `127.0.0.1:8080`), and its image-hardening line says the web bundle is served by the non-root `web` static image (updating these rows is in scope).

Unhappy path (`.env` missing): **Given** no `.env` file **When** `make up` runs **Then** it prints a line containing `no .env found - copying .env.example`, creates `.env` from `.env.example` (current `scripts/make/compose.sh` behaviour, kept) and continues to bring the stack up.
Unhappy path (no seed script): **Given** `infra/scripts/seed-demo.sh` does not exist **When** `make up` runs and the stack becomes healthy **Then** `make up` exits 0 (today `compose.sh` chains `[ -f seed-demo.sh ] && …`, which exits 1 — fixing this is in scope).
Unhappy path (Postgres late at start-up): **Given** the backend container is started with Postgres stopped, and Postgres is started 30 s after the backend container **When** the backend boots **Then** the backend process exits non-zero while Postgres is unreachable, compose restarts it (`restart` policy), and the backend is healthy <= 90 s after Postgres reports healthy, with no manual action.
Unhappy path (backend down): **Given** the stack is up and then `docker compose stop backend` **When** `make smoke URL=http://localhost` runs **Then** it exits non-zero within budget + 5 s and prints `FAIL health http://localhost/health last-status=502` (the last HTTP status observed; `000` when no connection was made), while the web check prints `OK`. Check names are single tokens: `health`, `web-root`. Changing `infra/scripts/smoke.sh` output, concurrency and assertions accordingly is in scope.
Unhappy path (unreachable host): **Given** nothing listens on `http://localhost:9` **When** `make smoke URL=http://localhost:9` runs **Then** it exits `1` within 15 s printing `FAIL health http://localhost:9/health last-status=000` and `FAIL web-root http://localhost:9/ last-status=000`.
Unhappy path (missing healthcheck): **Given** a compose service without a `healthcheck` **When** the stack check inspects `docker compose -f infra/docker-compose.yml config --format json` **Then** it fails naming the service.

**Test location.** Compose checks live in `infra/tests/*.sh`, are tagged with the REQ id in the file name or a comment, and run via `make e2e`.

## UX states (UI stories only)
N/A (infrastructure; the page itself is STORY-141).

## Contract impact
Description-only edit of `servers[0]` to `http://localhost` (make up, via Caddy); oasdiff clean.

## Data / migration impact
None (MinIO starts with an empty volume; buckets arrive with the stories that use them).

## Security / privacy / LLM-safety impact
Trust boundary: **internet → Caddy → (web | backend)**. `docker compose ps --format json` shows published ports only on `caddy` (80; 443 when `DOMAIN` is set, HSTS/redirect behaviour owned by STORY-010 REQ-SEC-011). Actuator is never routed. Backend and web images run as non-root (postgres and minio use the official images' defaults in v1; hardening is STORY-121). MinIO and Postgres credentials come from `.env` (gitignored; `.env.example` holds placeholders only). No rate limiting in Caddy in this story (backend rate limits: STORY-010 REQ-SEC-051; Caddy limits on `/api/ingest/*` and `/webhooks/*` arrive with STORY-055/075). Routes for `/auth/*` and `/webhooks/*` are added by the stories that introduce those endpoints (STORY-009, STORY-075).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-143 (backend image + compose `postgres`/`backend` + dev overlay + `infra/scripts/e2e.sh`; it depends on STORY-002), STORY-141 (web bundle and Playwright specs). Unblocks STORY-003 (observability in `make up`), STORY-004 (demo seed on `make up`), STORY-016 (CI e2e against the compose stack).

## Size estimate (hand-written lines incl. tests; generated scaffolding excluded)
Caddyfile ≈ 20, `web/Dockerfile` + static-server config ≈ 30, compose additions (`minio`, `web`, `caddy`, healthchecks) ≈ 50, `compose.sh` ≈ 10, `smoke.sh` (names, `last-status`, concurrency) ≈ 35, `infra/scripts/e2e.sh` Playwright step ≈ 10, Makefile/`infra/README.md`/`servers[0]` ≈ 10; `infra/tests/*.sh` (timing, routing, ports, non-root, healthcheck presence, start-up resilience, smoke failure format) ≈ 130. **Total ≈ 295.**

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 295)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — split out of STORY-002 after spec review |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — depends_on re-pointed to STORY-143 + STORY-141 (size split of STORY-002) |
| 2026-10-23 | 2 spec review (loop 2) | spec-reviewer | CHANGES_REQUESTED |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — start-up resilience moved here, concurrent smoke, make e2e, published-port check, servers[0], web module; ≈ 295 lines |

## Follow-ups (verify+review loop 2, 2026-10-25)
- Caddy runs as root, and its image has 6 fixable HIGH findings with no upstream fix yet (STORY-121). MinIO default credentials appear in its WARN log (STORY-010/121). Security headers (STORY-010).
- `scripts/quality/oasdiff.sh` fails on Windows Git Bash (tmp mount); CI is unaffected.
- NITs: the e2e.sh pre-flight only probes HTTP on port 80; the web runtime still ships curl; the favicon gets the SPA fallback.
