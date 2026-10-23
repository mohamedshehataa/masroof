---
id: STORY-142
epic: EPIC-00
title: "Walking skeleton (stack): make up with Caddy, MinIO, web; make smoke"
phase: 0
status: READY
size: M
modules: [infra, sdlc]
depends_on: [STORY-143, STORY-141]
reqs: [REQ-OPS-022]
implements: ["PROMPT.md S15 item 7", "PROMPT.md §2 (compose: Postgres, MinIO, backend, web, Caddy)", "PROMPT.md §12 (actuator not public, non-root images)", "PROMPT.md S8 (make up, make smoke)", "PROMPT.md S9 (image hardening)"]
owner_agent: devops-engineer
---

# STORY-142 — Walking skeleton (stack): make up with Caddy, MinIO, web; make smoke

## Goal
`make up` starts the whole local stack and `make smoke URL=http://localhost` proves the request path **browser → Caddy → (web bundle | backend → Postgres)** end to end, closing PROMPT S15 item 7 together with STORY-002 (backend module), STORY-143 (`/health` API) and STORY-141 (web page).

## Requirements and acceptance criteria
### REQ-OPS-022 — make up brings up the whole stack and smoke passes
**Given** Docker, a `.env` copied from `.env.example` with `DOMAIN` empty, the images already built (`docker compose build` done, with `GIT_SHA` set) and empty volumes **When** `make up` runs **Then** it exits 0 within 120 s, and `docker compose -f infra/docker-compose.yml ps` lists the services `postgres`, `minio`, `backend`, `web` and `caddy`, each with a `healthcheck` defined in the compose file and each reporting `healthy`. A cold build (no images) has no time budget.
**Given** `make up` runs **Then** it exports `GIT_SHA=$(git rev-parse --short=7 HEAD)` to the build, so `GET http://localhost/health` returns that `gitSha` (STORY-143 REQ-OPS-020).
**Given** the stack is up **When** `make smoke URL=http://localhost` runs **Then** it exits 0 within its 10 s budget, having asserted:
- `GET http://localhost/health` → `200` and the body contains `"status":"UP"`;
- `GET http://localhost/` → `200` and the body contains `<div id="root">`.
**Given** the stack is up and `DOMAIN` is empty **Then**:
- Caddy listens on plain HTTP port 80 and does not redirect to HTTPS (`GET http://localhost/health` is answered directly with `200`, never `301`/`308`);
- `GET http://localhost/health` and `GET http://localhost/api/does-not-exist` are answered by the backend (`Content-Type` contains `json`), and every other path (`/`, `/some/client/route`, `/actuator/health`) is answered by the `web` container (`Content-Type: text/html`) — `/actuator/*` never reaches the backend;
- the backend's port 8080 is not published to the host (`curl http://localhost:8080/health` fails to connect); the loopback publish used for development in STORY-143/STORY-141 lives in a dev-only overlay that `make up` does not load (architect names it);
- the `web` image runs as a non-root user (`docker inspect` `Config.User` is set and is neither `root` nor `0`) and serves the Vite production bundle as static files with no Node.js runtime in the image (`docker run --rm --entrypoint node <web-image> --version` fails).
**Given** `make` with no `URL` **When** `make smoke` runs **Then** the default URL is `http://localhost` (Makefile `URL ?=` changed from `http://localhost:8080`); `infra/README.md` lists the local URL as `http://localhost` and its image-hardening line says the web bundle is served by the non-root `web` static image.

Unhappy path (`.env` missing): **Given** no `.env` file **When** `make up` runs **Then** it prints a line containing `no .env found - copying .env.example`, creates `.env` from `.env.example` (current `scripts/make/compose.sh` behaviour, kept) and continues to bring the stack up.
Unhappy path (no seed script): **Given** `infra/scripts/seed-demo.sh` does not exist **When** `make up` runs and the stack becomes healthy **Then** `make up` exits 0 (today `compose.sh` chains `[ -f seed-demo.sh ] && …`, which exits 1 — fixing this is in scope).
Unhappy path (backend down): **Given** the stack is up and then `docker compose stop backend` **When** `make smoke URL=http://localhost` runs **Then** it exits non-zero after its budget and prints `FAIL health http://localhost/health last-status=502` (the last HTTP status observed; `000` when no connection was made), while the web check still prints `OK`. Check names are single tokens: `health`, `web-root`. Changing `infra/scripts/smoke.sh` output and assertions accordingly is in scope.
Unhappy path (unreachable host): **Given** nothing listens on `http://localhost:9` **When** `make smoke URL=http://localhost:9` runs **Then** it exits `1` within 15 s printing `FAIL health http://localhost:9/health last-status=000` and `FAIL web-root http://localhost:9/ last-status=000`.
Unhappy path (missing healthcheck): **Given** a compose service without a `healthcheck` **When** the stack test inspects `docker compose -f infra/docker-compose.yml config --format json` **Then** it fails naming the service.

## UX states (UI stories only)
N/A (infrastructure; the page itself is STORY-141).

## Contract impact
None.

## Data / migration impact
None (MinIO starts with an empty volume; buckets arrive with the stories that use them).

## Security / privacy / LLM-safety impact
Trust boundary: **internet → Caddy → (web | backend)**. Only Caddy publishes a host port (80; 443 when `DOMAIN` is set, HSTS/redirect behaviour owned by STORY-010 REQ-SEC-011). Actuator is never routed. Backend, web, Postgres and MinIO images run as non-root; MinIO and Postgres credentials come from `.env` (gitignored; `.env.example` holds placeholders only). No rate limiting in Caddy in this story (backend rate limits: STORY-010 REQ-SEC-051; Caddy limits on `/api/ingest/*` and `/webhooks/*` arrive with STORY-055/075). Routes for `/auth/*` and `/webhooks/*` are added by the stories that introduce those endpoints (STORY-009, STORY-075).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-143 (backend image + compose `postgres`/`backend`; it depends on STORY-002), STORY-141 (web bundle). Unblocks STORY-003 (observability in `make up`), STORY-004 (demo seed on `make up`), STORY-016 (CI e2e against the compose stack).

## Size estimate (orchestrator decision 13 — honest count)
Hand-written lines: Caddyfile ≈ 20, web Dockerfile + static-server config ≈ 30, compose additions (`minio`, `web`, `caddy`, healthchecks, dev overlay) ≈ 65, `compose.sh` ≈ 10, `smoke.sh` ≈ 25, Makefile + `infra/README.md` ≈ 6, stack test script (`make up` timing, routing, ports, non-root, healthcheck presence, smoke failure format) ≈ 90. **Total ≈ 245.**

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 245)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — split out of STORY-002 after spec review (orchestrator decisions 1, 7) |
| 2026-10-23 | 1-2 spec fix | product-analyst | READY — depends_on re-pointed to STORY-143 + STORY-141 (orchestrator size split of STORY-002) |
